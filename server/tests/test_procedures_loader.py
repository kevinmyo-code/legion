"""procedures ticket 11: the built-in loader and the tables' own integrity rules.

Verify (ticket 11): the loader is idempotent (a second run writes nothing); a definition that
violates a load-time check is refused with a sentence naming it; versions are immutable; at most
one active version, and only an approved one.
"""

from __future__ import annotations

from pathlib import Path

import pytest
from django.db import IntegrityError, transaction

from procedures import apps as procedures_apps
from procedures import loader
from procedures.definition import DefinitionError, validate
from procedures.loader import BUILTIN_DIR, load_builtins, read_definition
from procedures.models import Procedure, ProcedureVersion, Source

pytestmark = pytest.mark.django_db

GOOD = """\
name: {name}
description: What is due on the car.
steps:
  - id: services
    kind: tool
    tool: next_service
    next: answer
  - id: answer
    kind: say
    template: "{{$steps.services}}"
"""


def write(directory: Path, name: str, body: str | None = None) -> Path:
    path = directory / f"{name}.yaml"
    path.write_text(body if body is not None else GOOD.format(name=name), encoding="utf-8")
    return path


def rows() -> tuple[int, int]:
    return Procedure.objects.count(), ProcedureVersion.objects.count()


def test_the_repos_built_ins_all_pass_their_load_time_checks():
    for path in sorted(BUILTIN_DIR.glob("*.yaml")):
        definition, _ = read_definition(path)
        validate(definition)


def test_loading_writes_an_active_approved_built_in(tmp_path):
    write(tmp_path, "car_check_test")
    report = load_builtins(tmp_path)
    assert report.created == ["car_check_test"]
    procedure = Procedure.objects.get(name="car_check_test")
    assert procedure.household_id is None
    version = procedure.active_version()
    assert version.version == 1
    assert version.source == Source.BUILTIN
    assert version.writes is False
    assert version.approved_at is not None and version.approved_by is None
    assert version.household_id is None


def test_a_second_load_writes_nothing(tmp_path):
    write(tmp_path, "car_check_test")
    load_builtins(tmp_path)
    before = rows()
    stamp = ProcedureVersion.objects.get().created_at
    report = load_builtins(tmp_path)
    assert report.unchanged == ["car_check_test"]
    assert report.created == report.updated == report.deactivated == []
    assert rows() == before
    assert ProcedureVersion.objects.get().created_at == stamp


def test_a_changed_file_is_a_new_version_and_the_old_one_stays(tmp_path):
    write(tmp_path, "car_check_test")
    load_builtins(tmp_path)
    write(tmp_path, "car_check_test", GOOD.format(name="car_check_test").replace(
        "What is due on the car.", "What is due on the car, and when."))
    report = load_builtins(tmp_path)
    assert report.updated == ["car_check_test"]
    versions = list(ProcedureVersion.objects.order_by("version"))
    assert [(v.version, v.is_active) for v in versions] == [(1, False), (2, True)]
    assert versions[0].definition["description"] == "What is due on the car."


def test_a_broken_file_writes_nothing_at_all(tmp_path):
    write(tmp_path, "a_good_one")
    write(tmp_path, "b_broken", GOOD.format(name="b_broken").replace("next_service", "nope"))
    with pytest.raises(DefinitionError) as caught:
        load_builtins(tmp_path)
    assert "In 'b_broken', step 'services' calls 'nope'" in str(caught.value)
    assert rows() == (0, 0)


def test_a_file_must_carry_its_own_name(tmp_path):
    write(tmp_path, "car_check_test", GOOD.format(name="money_check"))
    with pytest.raises(DefinitionError, match="a built-in's name is its file's"):
        load_builtins(tmp_path)


def test_a_file_that_is_not_a_mapping_is_refused(tmp_path):
    write(tmp_path, "car_check_test", "- just\n- a list\n")
    with pytest.raises(DefinitionError, match="must hold one mapping"):
        load_builtins(tmp_path)


def test_a_removed_file_deactivates_and_its_return_reactivates(tmp_path):
    path = write(tmp_path, "car_check_test")
    load_builtins(tmp_path)
    path.unlink()
    assert load_builtins(tmp_path).deactivated == ["car_check_test"]
    assert Procedure.objects.get().active_version() is None
    assert ProcedureVersion.objects.count() == 1
    write(tmp_path, "car_check_test")
    assert load_builtins(tmp_path).updated == ["car_check_test"]
    assert Procedure.objects.get().active_version().version == 1


def test_migrate_runs_the_loader(tmp_path, monkeypatch):
    write(tmp_path, "car_check_test")
    monkeypatch.setattr(loader, "BUILTIN_DIR", tmp_path)
    procedures_apps._load_builtins_after_migrate(sender=None, using="default")
    assert Procedure.objects.filter(name="car_check_test").exists()
    procedures_apps._load_builtins_after_migrate(sender=None, using="readonly")
    assert ProcedureVersion.objects.count() == 1


def test_the_loader_is_wired_to_post_migrate():
    from django.apps import apps
    from django.db.models.signals import post_migrate

    config = apps.get_app_config("procedures")
    assert post_migrate.has_listeners(config)


def _version(tmp_path) -> ProcedureVersion:
    write(tmp_path, "car_check_test")
    load_builtins(tmp_path)
    return ProcedureVersion.objects.get()


@pytest.mark.parametrize(
    "column, value",
    [("definition", {"name": "x"}), ("version", 9), ("source", "hand"),
     ("content_sha256", "0" * 64), ("writes", True)],
)
def test_a_version_is_immutable(tmp_path, column, value):
    version = _version(tmp_path)
    with pytest.raises(IntegrityError, match="immutable"), transaction.atomic():
        ProcedureVersion.objects.filter(pk=version.pk).update(**{column: value})


def test_activation_and_approval_may_change(tmp_path):
    version = _version(tmp_path)
    ProcedureVersion.objects.filter(pk=version.pk).update(is_active=False)
    ProcedureVersion.objects.filter(pk=version.pk).update(is_active=True)


def test_an_unapproved_version_cannot_be_active(tmp_path):
    procedure = Procedure.objects.create(name="hand_made")
    with pytest.raises(IntegrityError), transaction.atomic():
        ProcedureVersion.objects.create(
            procedure=procedure, version=1, definition={}, source=Source.DISTILLED,
            content_sha256="0" * 64, writes=False, is_active=True,
        )


def test_only_one_version_is_active(tmp_path):
    version = _version(tmp_path)
    with pytest.raises(IntegrityError), transaction.atomic():
        ProcedureVersion.objects.create(
            procedure=version.procedure, version=2, definition={}, source=Source.BUILTIN,
            content_sha256="1" * 64, writes=False, is_active=True, approved_at=version.approved_at,
        )


def test_two_built_ins_cannot_share_a_name():
    Procedure.objects.create(name="car_check")
    with pytest.raises(IntegrityError), transaction.atomic():
        Procedure.objects.create(name="car_check")


def test_a_name_has_the_tool_name_shape():
    with pytest.raises(IntegrityError), transaction.atomic():
        Procedure.objects.create(name="Car Check")
