"""deploy/ship_checks.py: the engine ship's credential-free rules (cloud-builds step 2).
Invented paths and blobs, except one real migration read from the tree as the state-only
precedent."""

from __future__ import annotations

import importlib.util
import subprocess
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
# The two the ship must keep refusing, as whole migrations rather than fragments: these DO run DDL.
DELETE_MODEL = '''"""Drops the gadgets table."""

from django.db import migrations


class Migration(migrations.Migration):

    dependencies = [("widgets", "0002_gadgets")]

    operations = [migrations.DeleteModel(name="Gadget")]
'''
# The shape of the tracker cut's second half: no Django operation names an object, the drop is SQL.
# This one DOES reach the database, and the redesign-s1 RED review found it passing clean while the
# state-only migration above was refused.
RUN_SQL_DROP = '''"""Drops the four retired tables. THIS DELETES DATA."""

from django.db import migrations

_DROPS = ("user_records", "user_field_defs", "user_record_types", "user_aspects")


class Migration(migrations.Migration):

    dependencies = [("homes", "0003_retire_user_records_state")]

    operations = [
        migrations.RunSQL(
            sql=f"DROP TABLE IF EXISTS public.{table};",
            reverse_sql=migrations.RunSQL.noop,
        )
        for table in _DROPS
    ]
'''
# A correctly written additive migration: the drop is the UNDO, which no ship runs.
REVERSE_ONLY = '''"""Creates the schema Django's own tables live in."""

from django.db import migrations


class Migration(migrations.Migration):

    operations = [
        migrations.RunSQL(
            sql="CREATE SCHEMA IF NOT EXISTS django;",
            reverse_sql="DROP SCHEMA IF EXISTS django CASCADE;",
        ),
    ]
'''
# How 26 of the engine's migrations stay idempotent. It destroys no row and no column.
TRIGGER_RECREATE = '''"""Re-installs the visibility guard trigger."""

from django.db import migrations


class Migration(migrations.Migration):

    operations = [
        migrations.RunSQL(
            sql="drop trigger if exists guard on public.widgets; create trigger guard "
                "before insert on public.widgets execute function public.guard();",
            reverse_sql=migrations.RunSQL.noop,
        ),
    ]
'''
REMOVE_FIELD = DELETE_MODEL.replace(
    'migrations.DeleteModel(name="Gadget")',
    'migrations.RemoveField(model_name="widget", name="gadget")',
).replace("Drops the gadgets table.", "Drops the widget.gadget column.")


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


def test_a_deleted_image_file_ships_only_on_a_deletion_pin():
    """redesign-s1 deletes five server files, and `changed[path]` is None for a deletion while
    `parse_pins` only takes a 40-hex blob, so every one of them STOPped as `changed but not
    pinned` and the ship could not be expressed at all."""
    D = sc.DELETED_PIN
    gone = "server/homes/records.py"
    # The deletion the ship named is the deletion it gets.
    assert sc.image_problems({gone: None}, {gone: D}) == []
    # A deletion pin on a file that is still there is a lie about the image: STOP.
    assert sc.image_problems({"server/x.py": A}, {"server/x.py": D}) == [
        f"server/x.py is blob {A}, pinned deleted"
    ]
    # An unpinned deletion is still unpinned.
    assert sc.image_problems({"server/x.py": None}, {}) == ["changed but not pinned: server/x.py"]
    # A deletion pin on a path this ship does not touch is still pinned-but-not-changed.
    assert sc.image_problems({}, {"server/x.py": D}) == ["pinned but not changed: server/x.py"]
    # The pin is a blob to `parse_pins`, so it survives the workflow's `path:blob` input unchanged.
    assert sc.parse_pins(f"server/homes/records.py {D}\n") == {"server/homes/records.py": D}


def test_a_deleted_migration_is_not_added_whatever_it_is_pinned_to():
    """`parse_pins` is shared with --migrations, so the sentinel must stay a plain blob there.
    This is the half `migration_problems` owns; `migration_pin_problems` owns the refusal."""
    path = "server/widgets/migrations/0002_more.py"
    assert sc.migration_problems([("D", path)], {}, set(), {}, {}) == [
        f"schema file {path} is D, not added"
    ]
    # It only checks the path is pinned, never the blob, so the sentinel passes HERE and the
    # refusal has to come from migration_pin_problems against the file's real blob at FIX.
    assert sc.migration_problems(
        [("A", path)], {path: sc.DELETED_PIN}, set(), {path: "ops = []\n"}, {path: A}
    ) == []


def test_frozen_files_never_change():
    assert sc.frozen_problems(["server/agents/x.py"]) == []
    assert sc.frozen_problems(["server/requirements.txt", "server/Dockerfile"]) == [
        "frozen file changed: server/requirements.txt",
        "frozen file changed: server/Dockerfile",
    ]


def test_schema_files_added_pinned_and_never_destructive():
    m = "server/agents/migrations/0004_notes.py"
    assert sc.migration_problems([("A", m)], {m: A}, set(), {m: "AddField(...)"}, {m: A}) == []
    assert sc.migration_problems([("M", m)], {m: A}, set(), {}, {}) == [
        f"schema file {m} is M, not added",
        f"pinned schema file not added by this ship: {m}",
    ]
    assert sc.migration_problems([("A", m)], {}, set(), {m: ""}, {m: A}) == [
        f"schema file added but not pinned: {m}"
    ]
    destructive = sc.migration_problems(
        [("A", m)], {m: A}, set(), {m: "migrations.RemoveField('x')"}, {m: A}
    )
    assert len(destructive) == 1
    assert destructive[0].startswith(f"{m} holds a destructive database operation (RemoveField;")
    init = "server/newapp/migrations/__init__.py"
    assert sc.migration_problems([("A", init)], {}, {"newapp"}, {}, {}) == []
    assert sc.migration_problems([("A", init)], {}, set(), {}, {}) == [
        f"unexpected file under migrations/: {init}"
    ]
    assert sc.migration_problems([], {m: A}, set(), {}, {}) == [
        f"pinned schema file not added by this ship: {m}"
    ]


# The two reviewed state-only migrations, read from the checked-in list rather than retyped here, so
# this file cannot drift from tools/checks/state_only_migrations.txt.
LISTED = sorted(sc.migration_ops.STATE_ONLY.items())


def _one_migration(
    source: str, path: str = "server/widgets/migrations/0003_state_only.py", blob: str = A
) -> list[str]:
    return sc.migration_problems([("A", path)], {path: A}, set(), {path: source}, {path: blob})


def test_the_state_only_list_holds_the_two_reviewed_migrations():
    """The exemption is a reviewed file content, so the list is data a test can read. Both entries
    are Django migration paths at a full 40-hex blob."""
    assert len(LISTED) == 2
    assert [p for p, _ in LISTED] == [
        "server/checklists/migrations/0002_household.py",
        "server/homes/migrations/0003_retire_user_records_state.py",
    ]
    for path, blob in LISTED:
        assert sc.MIGRATION_PATH.match(path), path
        assert sc.migration_ops.BLOB.match(blob), (path, blob)


def test_a_state_only_migration_needs_a_reviewed_listing():
    """Rounds 1 to 3 tried to recognise this shape from the source and were beaten each time, so the
    shape earns nothing on its own: an unlisted file is judged by the old text match, and the STOP
    says how a reviewer grants the exemption."""
    problems = _one_migration(STATE_ONLY)
    assert len(problems) == 1
    assert "not listed in tools/checks/state_only_migrations.txt" in problems[0]
    # The STOP hands the operator the exact line to have reviewed, path and blob together.
    assert "a RED review adds the line" in problems[0]
    assert "'server/widgets/migrations/0003_state_only.py " + A + "'" in problems[0]


def test_a_listed_state_only_migration_ships():
    """The whole point of the change: redesign step 1's migration reaches the engine."""
    for path, blob in LISTED:
        assert _one_migration(STATE_ONLY, path, blob) == [], path


def test_one_changed_byte_voids_the_listing():
    """The exemption is bound to the content, not the path: a different blob is a different file,
    and the STOP prints both shas so an operator can see which."""
    path, blob = LISTED[0]
    edited = blob[:-1] + ("0" if blob[-1] != "0" else "1")
    problems = _one_migration(STATE_ONLY, path, edited)
    assert len(problems) == 1
    assert f"lists this path at blob {blob}" in problems[0]
    assert f"not the {edited} being shipped" in problems[0]


def test_a_listing_is_bound_to_its_own_path():
    """A reviewed blob excuses that path and no other."""
    other = "server/widgets/migrations/0003_state_only.py"
    assert _one_migration(STATE_ONLY, other, LISTED[0][1]) != []


def test_a_listed_migration_is_still_read_for_sql():
    """Listing says the OPERATIONS are state-only. The gate reads the strings itself, so no line in
    the list can ever excuse a DROP."""
    path, blob = LISTED[0]
    problems = _one_migration(WITH_DDL, path, blob)
    assert len(problems) == 1
    assert "SQL running DROP TABLE" in problems[0]
    assert "the SQL it runs still counts" in problems[0]


def test_a_blob_is_required_not_just_a_listed_path():
    """A caller with no blob (a hand-built Change, a probe harness) earns no exemption."""
    path, _ = LISTED[0]
    assert _one_migration(STATE_ONLY, path, "") != []


def test_real_ddl_is_still_refused():
    cases = {
        "DeleteModel with SQL": DELETE_MODEL,
        "RemoveField with SQL": REMOVE_FIELD,
        "a drop beside the state operations": WITH_DDL,
        "database_operations it cannot read": VARIABLE_DB_OPS,
        "RunSQL dropping the tables": RUN_SQL_DROP,
        "a column drop with the word COLUMN left out": TRIGGER_RECREATE.replace(
            "drop trigger if exists guard on public.widgets;", "alter table public.widgets drop g;"
        ),
        "TRUNCATE": TRIGGER_RECREATE.replace("drop trigger if exists guard", "truncate"),
        "DELETE FROM": TRIGGER_RECREATE.replace("drop trigger if exists guard", "delete from"),
        # Postgres takes every dependent column with the type or the domain, CASCADE or not, and a
        # materialized view holds a stored copy (smig-red F2, 2026-11-24).
        "DROP TYPE": TRIGGER_RECREATE.replace("drop trigger if exists guard", "drop type mood"),
        "DROP DOMAIN": TRIGGER_RECREATE.replace(
            "drop trigger if exists guard", "drop domain money_amt cascade"
        ),
        "DROP MATERIALIZED VIEW": TRIGGER_RECREATE.replace(
            "drop trigger if exists guard", "drop materialized view mv_spend"
        ),
    }
    for what, source in cases.items():
        problems = _one_migration(source)
        assert len(problems) == 1, (what, problems)
        assert "holds a destructive database operation" in problems[0], what


def test_sql_that_destroys_nothing_ships():
    """Measured against the tree, not guessed: 26 engine migrations drop and recreate a trigger or a
    function to stay idempotent, and a `CREATE TABLE` whose `reverse_sql` drops it is correct."""
    for what, source in (("the undo only", REVERSE_ONLY), ("a trigger recreate", TRIGGER_RECREATE)):
        assert _one_migration(source) == [], what


def test_the_name_match_runs_on_the_raw_source_of_an_unlisted_file():
    """What rounds 1 to 3 kept losing. The name match is the old regex over the source string, with
    no parse in front of it, so none of the regions an AST walk skipped can hide a name: a
    docstring, a `bytes` constant, a dropped `reverse_sql` subtree, a file that does not parse."""
    cases = {
        "a name in the module docstring only": '"""DeleteModel"""\nx = 1\n',
        "a name in a bytes constant": "x = b'DeleteModel'\n",
        "a name parked in reverse_sql": (
            "from django.db import migrations\n"
            "S = migrations.RunSQL('select 1', reverse_sql=migrations.DeleteModel)\n"
        ),
        "a name in a file that does not parse": "class Migration(\nDeleteModel\n",
        "a name in a comment": "# DeleteModel\n",
    }
    for what, source in cases.items():
        problems = _one_migration(source)
        assert len(problems) == 1, (what, problems)
        assert "destructive database operation (DeleteModel" in problems[0], what


def test_a_file_that_does_not_parse_is_refused_even_when_listed():
    """Fail closed: the SQL half still has to read a listed file."""
    path, blob = LISTED[0]
    problems = _one_migration("class Migration(\n", path, blob)
    assert len(problems) == 1
    assert "does not parse" in problems[0]


def test_an_operation_carried_out_of_the_state_block_is_refused():
    """The shape of the smig-red F1 flips. It is refused for a duller reason now: the file is not a
    reviewed (path, blob) pair, so the raw-text name match reads its `DeleteModel` and that is the
    end of it. Nothing has to recognise the carry-out."""
    carried = '''"""State only, it says."""

from django.db import migrations


S = migrations.SeparateDatabaseAndState(
    database_operations=[],
    state_operations=[migrations.DeleteModel(name="Gadget")],
)


class Migration(migrations.Migration):

    operations = [getattr(S, "state_operations")[0]]
'''
    problems = _one_migration(carried)
    assert len(problems) == 1, problems
    assert "destructive database operation (DeleteModel;" in problems[0], problems


def test_files_outside_the_image_are_named_not_counted():
    """A supabase schema change is collected by neither the pins nor the migration check, so the
    ship log's own line is the only place its name appears."""
    line = sc.outside_image_line(["supabase/migrations/0041_x.sql", "docs/process/x.md"])
    assert "2 files" in line and "supabase/migrations/0041_x.sql" in line
    assert sc.outside_image_line([]) == "outside the image (not deployed by this ship): 0 files"


def test_a_migration_that_does_not_parse_is_refused():
    problems = _one_migration("operations = [  # truncated\n")
    assert len(problems) == 1
    assert "does not parse" in problems[0]


def test_the_listed_blob_is_the_checklists_migration_in_this_tree():
    """The listing binds an exemption to a file's CONTENT, so it holds only while the listed sha
    really is that file's blob. This is the check that reddens if either side moves: editing the
    migration without relisting it, or listing a sha that is not its content.

    The sha comes from git, not from hashing the working file: a checkout may translate line
    endings, so the bytes on disk are not always the bytes git stored - and it is the git blob that
    the ship and check (c) hand to the gate."""
    root = Path(__file__).resolve().parents[2]
    path = "server/checklists/migrations/0002_household.py"
    blob = subprocess.run(
        ["git", "-C", str(root), "rev-parse", f"HEAD:{path}"],
        check=True, capture_output=True, text=True,
    ).stdout.strip()
    source = (root / path).read_text(encoding="utf-8")
    assert "SeparateDatabaseAndState" in source and "AlterField" in source
    assert blob == sc.migration_ops.STATE_ONLY[path]
    # And so the real file, at its real blob, ships: redesign step 1's precedent in the repo.
    assert _one_migration(source, path, blob) == []


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


def test_schema_status_collects_django_migrations_only(tmp_path, monkeypatch):
    """`*/migrations/*` crossed `/` in a git pathspec, so `supabase/migrations/*.sql` - the legacy
    schema, applied by hand and not in the deployed image - was collected and then refused as an
    unexpected file under migrations/ (redesign-s1 RED review, finding F3b)."""
    def run(*args: str) -> str:
        return subprocess.run(
            ["git", *args], cwd=tmp_path, check=True, capture_output=True, text=True
        ).stdout

    run("init", "-q", "-b", "main")
    run("config", "user.email", "t@example.invalid")
    run("config", "user.name", "t")
    nested = tmp_path / "server" / "pkg" / "inner" / "migrations"
    for folder in (tmp_path / "server" / "widgets" / "migrations", nested,
                   tmp_path / "supabase" / "migrations"):
        folder.mkdir(parents=True)
    (tmp_path / "server" / "widgets" / "migrations" / "0001_initial.py").write_text("ops = []\n")
    run("add", "-A")
    run("commit", "-qm", "base")
    live = run("rev-parse", "HEAD").strip()
    (tmp_path / "server" / "widgets" / "migrations" / "0002_more.py").write_text("ops = []\n")
    (nested / "0002_deep.py").write_text("ops = []\n")
    (tmp_path / "supabase" / "migrations" / "20261124_drop.sql").write_text("drop table x;\n")
    run("add", "-A")
    run("commit", "-qm", "fix")
    fix = run("rev-parse", "HEAD").strip()

    monkeypatch.chdir(tmp_path)
    # The nested one is collected too: narrowing to one folder deep would fail open
    # inside the deployed image.
    assert sorted(sc.schema_status(live, fix)) == [
        ("A", "server/pkg/inner/migrations/0002_deep.py"),
        ("A", "server/widgets/migrations/0002_more.py"),
    ]


def _repo(tmp_path):
    """A throwaway git repo with `server/`, and a commit(msg) that stages all, returning its sha."""
    def run(*args: str) -> str:
        return subprocess.run(
            ["git", *args], cwd=tmp_path, check=True, capture_output=True, text=True
        ).stdout

    run("init", "-q", "-b", "main")
    run("config", "user.email", "t@example.invalid")
    run("config", "user.name", "t")
    run("config", "core.quotePath", "false")
    (tmp_path / "server").mkdir()

    def commit(msg: str) -> str:
        run("add", "-A")
        run("commit", "-qm", msg)
        return run("rev-parse", "HEAD").strip()

    return commit


def test_a_path_with_a_space_arrives_whole_so_no_fragment_can_be_pinned(tmp_path, monkeypatch):
    """`.split()` on `--name-only` cut `server/a b.py` into `server/a` and `b.py`, whose blob at
    FIX is None for each. That was fail-closed while no pin matched None; with a deletion pin each
    fragment became satisfiable and the real ADDED file was pinned by nothing, so scope passed a
    file nobody reviewed (delpin RED review, finding 1, probe 1)."""
    commit = _repo(tmp_path)
    (tmp_path / "server" / "x.py").write_text("x = 1\n")
    live = commit("base")
    (tmp_path / "server" / "a b.py").write_text("unreviewed = 1\n")
    fix = commit("fix")

    monkeypatch.chdir(tmp_path)
    changed = sc.image_blobs(live, fix)
    assert list(changed) == ["server/a b.py"], "the path must not fragment"
    assert changed["server/a b.py"] is not None, "an added file is not a deletion"
    # So the two fragments a generator would emit are pinned-but-not-changed and the real file is
    # named unpinned. The pins file cannot express the real path: its format is space-separated.
    assert sc.image_problems(changed, {"server/a": sc.DELETED_PIN, "b.py": sc.DELETED_PIN}) == [
        "changed but not pinned: server/a b.py",
        "pinned but not changed: b.py",
        "pinned but not changed: server/a",
    ]


def test_a_spaced_path_whose_fragments_are_real_deletions_is_still_named(tmp_path, monkeypatch):
    """The counterexample that sank 'a deletion pin's path must also exist at LIVE_CODE': here the
    fragments ARE files that really were deleted, so that guard passes them and the added file
    still ships (delpin RED review, finding 1, probe 2). Only `-z` on the names closes it."""
    commit = _repo(tmp_path)
    (tmp_path / "server" / "x").write_text("x\n")
    (tmp_path / "server" / "y.py").write_text("y = 1\n")
    live = commit("base")
    (tmp_path / "server" / "x").unlink()
    (tmp_path / "server" / "y.py").unlink()
    # The added path is `server/x server/y.py`: a folder whose name holds a space, so
    # `--name-only` prints a row that `.split()` cuts into the two deleted paths above.
    (tmp_path / "server" / "x server").mkdir()
    (tmp_path / "server" / "x server" / "y.py").write_text("unreviewed = 1\n")
    fix = commit("fix")

    monkeypatch.chdir(tmp_path)
    changed = sc.image_blobs(live, fix)
    assert sorted(changed) == ["server/x", "server/x server/y.py", "server/y.py"]
    assert changed["server/x"] is None and changed["server/y.py"] is None
    assert changed["server/x server/y.py"] is not None
    # Pinning both genuine deletions leaves the added file named, not silently deployed.
    assert sc.image_problems(
        changed, {"server/x": sc.DELETED_PIN, "server/y.py": sc.DELETED_PIN}
    ) == ["changed but not pinned: server/x server/y.py"]


def test_a_migration_pinned_to_the_deletion_sentinel_is_refused(tmp_path, monkeypatch):
    """The rule the mutant survived: `migration_pin_problems` has no sentinel, so the deletion pin
    buys a schema file nothing (delpin RED review, Debt)."""
    commit = _repo(tmp_path)
    folder = tmp_path / "server" / "widgets" / "migrations"
    folder.mkdir(parents=True)
    (folder / "0002_more.py").write_text("ops = []\n")
    fix = commit("fix")
    path = "server/widgets/migrations/0002_more.py"

    monkeypatch.chdir(tmp_path)
    real = sc.blob_at(fix, path)
    assert sc.migration_pin_problems(fix, {path: real}) == []
    assert sc.migration_pin_problems(fix, {path: sc.DELETED_PIN}) == [
        f"{path} at FIX is not the pinned blob 00000000"
    ]


def _rerooted(tmp_path):
    """live -> bridge on the old history; dev restarts at an orphan root carrying the bridge's tree,
    and fix sits on that root. Returns (run, live, bridge, root, fix)."""
    commit = _repo(tmp_path)

    def run(*args: str) -> str:
        return subprocess.run(
            ["git", *args], cwd=tmp_path, check=True, capture_output=True, text=True
        ).stdout

    (tmp_path / "server" / "app.py").write_text("v = 1\n")
    (tmp_path / "deploy").mkdir()
    (tmp_path / "deploy" / "run.sh").write_text("echo run\n")
    live = commit("live")
    (tmp_path / "server" / "app.py").write_text("v = 2\n")
    bridge = commit("bridge")
    run("checkout", "-q", "--orphan", "dev")
    root = commit("root, same tree as the bridge")
    (tmp_path / "server" / "app.py").write_text("v = 3\n")
    fix = commit("fix")
    return run, live, bridge, root, fix


def test_a_rerooted_dev_ships_only_through_a_bridge_that_holds(tmp_path, monkeypatch):
    """Dev was re-rooted on 2026-11-25, so the live code is no ancestor of any dev commit. The
    bridge restores what the ancestor check proved: the live code is under the bridge, and the
    bridge's image trees are a root of FIX's history."""
    run, live, bridge, root, fix = _rerooted(tmp_path)
    monkeypatch.chdir(tmp_path)
    assert subprocess.run(
        ["git", "merge-base", "--is-ancestor", live, fix], cwd=tmp_path
    ).returncode == 1
    assert sc.bridge_problems(live, fix, bridge) == []
    # The live code itself is no bridge to a root it is not equal to.
    assert "match no root" in sc.bridge_problems(live, fix, live)[0]
    # A bridge the live code is not under proves nothing.
    assert "not an ancestor of the bridge" in sc.bridge_problems(bridge, fix, root)[0]
    assert sc.bridge_problems(live, fix, "nope") == ["not a full sha: nope"]


def test_a_bridge_whose_image_tree_differs_from_the_root_is_refused(tmp_path, monkeypatch):
    run, live, bridge, root, fix = _rerooted(tmp_path)
    run("checkout", "-q", bridge)
    (tmp_path / "deploy" / "run.sh").write_text("echo changed\n")
    run("commit", "-qam", "bridge drifts in deploy/")
    drifted = run("rev-parse", "HEAD").strip()
    monkeypatch.chdir(tmp_path)
    assert "match no root" in sc.bridge_problems(live, fix, drifted)[0]


def test_scope_without_a_bridge_still_stops_on_a_rerooted_dev(tmp_path, monkeypatch, capsys):
    run, live, bridge, root, fix = _rerooted(tmp_path)
    monkeypatch.chdir(tmp_path)
    pins = tmp_path.parent / f"{tmp_path.name}-pins.txt"
    pins.write_text(f"server/app.py {sc.blob_at(fix, 'server/app.py')}\n")
    args = ["scope", "--fix", fix, "--live-code", live, "--image-files", str(pins)]
    assert sc.main(args) == 1
    assert "is not an ancestor of" in capsys.readouterr().out
    assert sc.main([*args, "--live-bridge", bridge]) == 0
    assert sc.main(["bridge", "--live-code", live, "--fix", fix, "--live-bridge", bridge]) == 0
    assert sc.main(["bridge", "--live-code", live, "--fix", fix, "--live-bridge", root]) == 1
