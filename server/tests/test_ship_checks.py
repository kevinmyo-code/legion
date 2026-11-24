"""deploy/ship_checks.py: the engine ship's credential-free rules (cloud-builds step 2).
Invented paths and blobs, except one real migration read from the tree as the state-only precedent."""

from __future__ import annotations

import importlib.util
from pathlib import Path

import pytest

_PATH = Path(__file__).resolve().parents[2] / "deploy" / "ship_checks.py"
_spec = importlib.util.spec_from_file_location("ship_checks", _PATH)
sc = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(sc)

A = "a" * 40
B = "b" * 40
FIX = "f" * 40

# A real state-only migration, the shape ADR 0045's tenancy work and the 2026-11-24 tracker cut both
# need: no `database_operations`, so no SQL, so nothing for the ship to refuse. Its docstring names
# the operation on purpose, because a text match used to refuse the file for that alone.
STATE_ONLY = '''"""State only: Django forgets four models whose tables stay. DeleteModel, no DDL."""

from django.db import migrations


class Migration(migrations.Migration):

    dependencies = [("widgets", "0002_gadgets")]

    operations = [
        migrations.SeparateDatabaseAndState(
            database_operations=[],
            state_operations=[
                migrations.DeleteModel(name="Gadget"),
                migrations.RemoveField(model_name="Widget", name="gadget"),
            ],
        ),
    ]
'''
# The same file with real DDL beside the state operations: a drop is a drop.
WITH_DDL = STATE_ONLY.replace(
    "database_operations=[],",
    'database_operations=[migrations.RunSQL("DROP TABLE gadgets")],',
)
# `database_operations` by name but not a literal: what it holds cannot be read here.
VARIABLE_DB_OPS = STATE_ONLY.replace("database_operations=[],", "database_operations=OPS,")


def test_pins_parse_and_refuse_bad_lines():
    assert sc.parse_pins(f"# note\nserver/x.py {A}\n\nserver/y.py {B}\n") == {
        "server/x.py": A,
        "server/y.py": B,
    }
    with pytest.raises(ValueError):
        sc.parse_pins("server/x.py notablob")
    with pytest.raises(ValueError):
        sc.parse_pins(f"server/x.py {A}\nserver/x.py {B}")


def test_image_change_must_equal_the_pins_file_for_file_and_blob_for_blob():
    assert sc.image_problems({"server/x.py": A}, {"server/x.py": A}) == []
    assert sc.image_problems({"server/x.py": A, "server/z.py": B}, {"server/x.py": A}) == [
        "changed but not pinned: server/z.py"
    ]
    assert sc.image_problems({}, {"server/x.py": A}) == ["pinned but not changed: server/x.py"]
    assert sc.image_problems({"server/x.py": B}, {"server/x.py": A}) == [
        f"server/x.py is blob {B}, pinned {A}"
    ]
    assert sc.image_problems({"server/x.py": None}, {"server/x.py": A}) == [
        f"server/x.py is blob deleted, pinned {A}"
    ]


def test_frozen_files_never_change():
    assert sc.frozen_problems(["server/agents/x.py"]) == []
    assert sc.frozen_problems(["server/requirements.txt", "server/Dockerfile"]) == [
        "frozen file changed: server/requirements.txt",
        "frozen file changed: server/Dockerfile",
    ]


def test_schema_files_added_pinned_and_never_destructive():
    m = "server/agents/migrations/0004_notes.py"
    assert sc.migration_problems([("A", m)], {m: A}, set(), {m: "AddField(...)"}) == []
    assert sc.migration_problems([("M", m)], {m: A}, set(), {}) == [
        f"schema file {m} is M, not added",
        f"pinned schema file not added by this ship: {m}",
    ]
    assert sc.migration_problems([("A", m)], {}, set(), {m: ""}) == [
        f"schema file added but not pinned: {m}"
    ]
    assert sc.migration_problems([("A", m)], {m: A}, set(), {m: "migrations.RemoveField('x')"}) == [
        f"{m} holds a destructive database operation (RemoveField)"
    ]
    init = "server/newapp/migrations/__init__.py"
    assert sc.migration_problems([("A", init)], {}, {"newapp"}, {}) == []
    assert sc.migration_problems([("A", init)], {}, set(), {}) == [
        f"unexpected file under migrations/: {init}"
    ]
    assert sc.migration_problems([], {m: A}, set(), {}) == [
        f"pinned schema file not added by this ship: {m}"
    ]


def _one_migration(source: str) -> list[str]:
    m = "server/widgets/migrations/0003_state_only.py"
    return sc.migration_problems([("A", m)], {m: A}, set(), {m: source})


def test_a_state_only_migration_ships():
    """No `database_operations` means no SQL, so there is nothing destructive to refuse - however
    many DeleteModels the state operations and the docstring name."""
    assert _one_migration(STATE_ONLY) == []


def test_a_drop_beside_the_state_operations_is_still_refused():
    for source, what in ((WITH_DDL, "real DDL"), (VARIABLE_DB_OPS, "unreadable database_operations")):
        problems = _one_migration(source)
        assert len(problems) == 1, (what, problems)
        assert "holds a destructive database operation" in problems[0], what


def test_a_destructive_operation_outside_the_state_block_is_refused():
    mixed = STATE_ONLY.replace(
        "    ]\n", '        migrations.AlterField(model_name="widget", name="name", field=None),\n    ]\n', 1
    )
    assert _one_migration(mixed) == [
        "server/widgets/migrations/0003_state_only.py holds a destructive database operation (AlterField)"
    ]


def test_a_migration_that_does_not_parse_is_refused():
    problems = _one_migration("operations = [  # truncated\n")
    assert len(problems) == 1
    assert "does not parse" in problems[0]


def test_the_checklists_household_migration_is_judged_state_only():
    """The precedent in the repo: `SeparateDatabaseAndState` with an empty `database_operations`,
    holding AddField, AlterField and AddConstraint. It changed no schema, so it is not destructive."""
    path = Path(__file__).resolve().parents[1] / "checklists" / "migrations" / "0002_household.py"
    source = path.read_text(encoding="utf-8")
    assert "SeparateDatabaseAndState" in source and "AlterField" in source
    assert _one_migration(source) == []


def test_switches_can_only_grow():
    names = sc.switch_names("")
    assert names == list(sc.SWITCHES)
    assert sc.switch_names("LEGION_NEW_THING, LEGION_BOARD") == [*sc.SWITCHES, "LEGION_NEW_THING"]
    with pytest.raises(ValueError):
        sc.switch_names("not-a-name")


def test_add_checks_parse_and_only_add():
    add = sc.parse_add_checks(
        "unset:LEGION_NEW frozen:server/board/ probe:POST:/api/board/x:401,403,404"
    )
    assert add == {
        "unset": ["LEGION_NEW"],
        "frozen": ["server/board"],
        "probes": [("POST", "/api/board/x", ["401", "403", "404"])],
    }
    for bad in (
        "probe:GET:/api/x:200",
        "probe:GET:/health:404",
        "probe:GET:/api/../x:404",
        "frozen:app/x.kt",
        "unset:lower",
        "remove:server/demo",
    ):
        with pytest.raises(ValueError):
            sc.parse_add_checks(bad)


def test_unfreeze_needs_exact_files_and_reasons():
    assert sc.parse_unfreeze("server/demo/a.py=the demo delivery text; ") == {
        "server/demo/a.py": "the demo delivery text"
    }
    with pytest.raises(ValueError):
        sc.parse_unfreeze("server/demo/a.py=")
    with pytest.raises(ValueError):
        sc.parse_unfreeze("server/demo/a.py")
    with pytest.raises(ValueError):
        sc.parse_unfreeze("server/demo/a.py=reason one here;server/demo/a.py=reason two here")


def test_frozen_live_paths_hold_unless_named_with_a_reason():
    frozen = list(sc.FROZEN_LIVE)
    assert "server/demo" in frozen and "server/frontend" in frozen and "server/legion" in frozen
    assert sc.frozen_live_problems(["server/agents/legion/turn.py"], frozen, {}) == []
    assert sc.frozen_live_problems(["server/demo/x.py"], frozen, {}) == [
        "frozen live path changed: server/demo/x.py"
        " (name it in unfreeze with a reason, or leave it)"
    ]
    assert sc.frozen_live_problems(["server/demo/x.py"], frozen, {"server/demo/x.py": "why"}) == []
    assert sc.frozen_live_problems([], frozen, {"server/demo/x.py": "why"}) == [
        "unfreeze names server/demo/x.py, which this ship does not change"
    ]
    assert sc.frozen_live_problems(["server/agents/a.py"], frozen, {"server/agents/a.py": "w"}) == [
        "unfreeze names server/agents/a.py, which is not frozen"
    ]
    # server/demos is not under server/demo
    assert sc.frozen_live_problems(["server/demos/x.py"], frozen, {}) == []


def _card(*extra: str) -> str:
    return "\n".join(
        ["gate: PASS first run (spent about $0.04)", "chip turn: PASS", *extra, "RESULT: PASS"]
    )


def test_gate_run_must_be_the_engine_for_this_sha_on_its_first_attempt():
    assert sc.gate_problems(_card(), f"Gates engine {FIX}", 1, FIX, []) == []
    assert sc.gate_problems(_card(), f"Gates all {FIX}", 1, FIX, []) == []
    assert sc.gate_problems(_card(), f"Gates phone {FIX}", 1, FIX, []) == [
        f"the gates run is titled 'Gates phone {FIX}', not the engine for {FIX}"
    ]
    assert sc.gate_problems(_card(), f"Gates engine {FIX}", 2, FIX, []) == [
        "the gates run passed on attempt 2; only a first attempt counts"
    ]
    assert "the engine scorecard has no 'RESULT: PASS'" in sc.gate_problems(
        "gate: PASS\nchip turn: PASS\nRESULT: FAIL (x)", f"Gates engine {FIX}", 1, FIX, []
    )


def test_gate_extras_must_be_exactly_the_ones_the_ship_names():
    card = _card("extra realrun_web_carry_checks: PASS (spent about $0.08, cap $0.12)")
    title = f"Gates engine {FIX}"
    assert sc.gate_problems(card, title, 1, FIX, ["realrun_web_carry_checks"]) == []
    assert sc.gate_problems(_card(), title, 1, FIX, ["realrun_web_carry_checks"]) == [
        "expected extra check realrun_web_carry_checks did not PASS in that run"
    ]
    assert sc.gate_problems(card, title, 1, FIX, []) == [
        "extra check realrun_web_carry_checks ran but this ship did not name it"
    ]
