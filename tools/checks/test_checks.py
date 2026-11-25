"""Tests for the review-finding checks (tools/checks). Pure: each builds a Change by hand, no git,
except the parse and end-to-end tests at the bottom, which use a throwaway repo in tmp_path."""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))

import clock_times  # noqa: E402
import detekt_baseline  # noqa: E402
import em_dashes  # noqa: E402
import file_size  # noqa: E402
import migrations  # noqa: E402
import run_all  # noqa: E402
from common import FAIL, WARN, split_comment  # noqa: E402
from gitdiff import Change, parse_added  # noqa: E402

DASH = "—"


def change(files: dict[str, tuple[str, str]], base: dict[str, str] | None = None,
           head_files: list[str] | None = None) -> Change:
    """files: path -> (letter, head text). Every head line counts as added for A; for M, only lines not in base."""
    base = base or {}
    status, added, head = {}, {}, {}
    for path, (letter, text) in files.items():
        status[path] = letter
        if letter == "D":
            continue
        head[path] = text
        old = set((base.get(path) or "").splitlines())
        added[path] = [(i, line) for i, line in enumerate(text.splitlines(), 1) if letter == "A" or line not in old]
    hf = head_files if head_files is not None else [p for p, (letter, _) in files.items() if letter != "D"]
    return Change(status=status, added=added, head_text=head, base_text=dict(base), head_files=hf)


def levels(findings):
    return sorted(f.level for f in findings)


# ------------------------------------------------------------------------------------------ comments
@pytest.mark.parametrize(
    "path,line,comment",
    [
        ("a.py", "x = 1  # note", "# note"),
        ("a.py", "x = '#not' # yes", "# yes"),
        ("a.kt", "val u = \"http://x\" // c", "// c"),
        ("a.kt", " * block line", " * block line"),
        ("a.kt", "val x = 1", ""),
        ("a.xml", "<x/>", ""),
    ],
)
def test_split_comment(path, line, comment):
    assert split_comment(path, line)[1] == comment


# ------------------------------------------------------------------------------------------ (a) clock
@pytest.mark.parametrize(
    "path,line,expected",
    [
        ("notes.md", "Kevin said ok at 14:42.", [FAIL]),
        ("notes.md", "Built 2026-11-21T07:13, shipped later.", [FAIL]),
        ("notes.md", "The brief runs between 04:00 and 11:00.", [WARN, WARN]),
        ("notes.md", "Format is \"21:41\" or `11:59 PM`.", []),
        ("notes.md", "Offset UTC-06:00 and +00:00.", []),
        ("notes.md", "Port 5432:5432, ratio 16:9, slice 1:30.", []),
        ("notes.md", "Dated 2026-11-22, no time.", []),
        ("notes.md", "Done at 14:42 clock-ok", []),
        ("a.py", "# ran at 14:42", [WARN]),
        ("a.py", "# 20:18 CST, the dongle connected", [FAIL]),
        ("a.py", "# run 3, 11:47:13", [FAIL]),
        ("a.py", "QUIET = \"22:00\"", []),
        ("a.kt", "val t = LocalTime.of(14, 42) // 14:42 local", [FAIL]),
        ("a.kt", "val t = \"14:42\"", []),
        ("a.json", "{\"t\": \"14:42\"}", []),
    ],
)
def test_clock_levels(path, line, expected):
    assert sorted(level for level, _ in clock_times.times_in(path, line)) == sorted(expected)


def test_clock_only_added_lines_and_allowlist():
    base = {"docs/a.md": "old line at 14:42\n"}
    ch = change({"docs/a.md": ("M", "old line at 14:42\nnew line at 15:10\n"),
                 "server/tests/test_x.py": ("A", "# fake 09:30:00 data\n")}, base=base)
    found = clock_times.check(ch, allow=["server/tests/*"])
    assert [(f.path, f.line, f.level) for f in found] == [("docs/a.md", 2, FAIL)]


# ------------------------------------------------------------------------------------------ (b) size
def lines(n: int) -> str:
    return "x = 1\n" * n


def test_new_file_over_limit_fails():
    found = file_size.check(change({"server/x.py": ("A", lines(601))}), allow=[])
    assert levels(found) == [FAIL]


def test_new_file_at_limit_passes():
    assert file_size.check(change({"server/x.py": ("A", lines(600))}), allow=[]) == []


def test_existing_file_crossing_warns():
    ch = change({"server/x.py": ("M", lines(650))}, base={"server/x.py": lines(590)})
    found = file_size.check(ch, allow=[])
    assert levels(found) == [WARN] and "590 -> 650" in found[0].message


def test_existing_debt_growing_warns_shrinking_is_silent():
    grow = change({"a/B.kt": ("M", lines(900))}, base={"a/B.kt": lines(800)})
    shrink = change({"a/B.kt": ("M", lines(700))}, base={"a/B.kt": lines(800)})
    assert levels(file_size.check(grow, allow=[])) == [WARN]
    assert file_size.check(shrink, allow=[]) == []


def test_size_skips_non_code_and_allowlisted():
    ch = change({"docs/a.md": ("A", lines(900)), "server/api/schema.d.ts": ("A", lines(900))})
    assert file_size.check(ch, allow=["*.d.ts"]) == []


# ------------------------------------------------------------------------------------------ (c) migrations
def test_modified_django_migration_fails():
    ch = change({"server/ledger/migrations/0003_x.py": ("M", "ops = []\n")})
    assert levels(migrations.check(ch)) == [FAIL]


def test_deleted_django_migration_fails():
    ch = change({"server/ledger/migrations/0003_x.py": ("D", "")})
    assert "deleted" in migrations.check(ch)[0].message


def test_django_number_clash_fails():
    ch = change({"server/ledger/migrations/0005_b.py": ("A", "ops = []\n")},
                head_files=["server/ledger/migrations/0005_a.py", "server/ledger/migrations/0005_b.py"])
    found = migrations.check(ch)
    assert levels(found) == [FAIL] and "0005" in found[0].message


def test_django_new_additive_passes_destructive_warns():
    ok = change({"server/ledger/migrations/0006_a.py": ("A", "migrations.AddField(...)\n")})
    bad = change({"server/ledger/migrations/0006_a.py": ("A", "migrations.RemoveField(...)\n")})
    assert migrations.check(ok) == []
    assert levels(migrations.check(bad)) == [WARN]


# A migration that only tells Django what the database already holds: the shape of
# checklists/0002_household.py and of the 2026-11-24 tracker cut. Its docstring names the operation
# too, because a text match refused the file for that alone.
STATE_ONLY = '''"""State only: Django forgets a model whose table stays. DeleteModel, no DDL."""

from django.db import migrations


class Migration(migrations.Migration):

    dependencies = [("ledger", "0005_a")]

    operations = [
        migrations.SeparateDatabaseAndState(
            database_operations=[],
            state_operations=[migrations.DeleteModel(name="Gadget")],
        ),
    ]
'''


def _judge(source: str) -> list[str]:
    """The levels check (c) gives one added migration holding `source`."""
    return levels(migrations.check(change({"server/ledger/migrations/0006_a.py": ("A", source)})))


def test_django_state_only_migration_is_not_destructive():
    assert _judge(STATE_ONLY) == []


def test_django_state_only_keeps_the_exemption_with_the_usual_django_imports():
    """38 of the 154 server migrations bind one name twice by plain import
    (`import django.db.models.deletion` beside `import django.utils.timezone`), measured with `ast`.
    Two imports of one name from the SAME source are not ambiguous, so the file's imports still read
    and a state-only migration still ships."""
    usual = STATE_ONLY.replace(
        "from django.db import migrations",
        "import django.db.models.deletion\nimport django.utils.timezone\n\n"
        "from django.db import migrations",
    )
    assert usual != STATE_ONLY
    assert _judge(usual) == []


def test_django_real_ddl_warns_however_it_is_dressed():
    """Every one of these runs a real drop, so every one keeps the old rule. The four after the
    first two try to carry an operation out of the exempt list or to look like Django and not be."""
    cases = {
        "a plain DeleteModel": STATE_ONLY.replace(
            "        migrations.SeparateDatabaseAndState(\n"
            "            database_operations=[],\n"
            "            state_operations=[migrations.DeleteModel(name=\"Gadget\")],\n"
            "        ),\n",
            '        migrations.DeleteModel(name="Gadget"),\n',
        ),
        "a plain RemoveField": STATE_ONLY.replace(
            'state_operations=[migrations.DeleteModel(name="Gadget")],',
            'state_operations=[],\n        ),\n        migrations.RemoveField(\n'
            '            model_name="widget", name="gadget",',
        ),
        "SQL beside the state operations": STATE_ONLY.replace(
            "database_operations=[],",
            'database_operations=[migrations.RunSQL("DROP TABLE gadgets")],',
        ),
        "database_operations it cannot read": STATE_ONLY.replace(
            "database_operations=[],", "database_operations=OPS,"
        ),
        "an operation bound out with :=": STATE_ONLY.replace(
            'state_operations=[migrations.DeleteModel(name="Gadget")],',
            'state_operations=[(d := migrations.DeleteModel(name="Gadget"))][:0],',
        ).replace("        ),\n    ]\n", "        ),\n        d,\n    ]\n"),
        "the list read back off the call": """from django.db import migrations


class Migration(migrations.Migration):

    s = migrations.SeparateDatabaseAndState(
        database_operations=[],
        state_operations=[migrations.DeleteModel(name="Gadget")],
    )
    operations = [s.state_operations[0]]
""",
        "the name imported from elsewhere": STATE_ONLY.replace(
            "from django.db import migrations",
            "from django.db import migrations\nfrom widgets.helpers import SeparateDatabaseAndState",
        ).replace("migrations.SeparateDatabaseAndState(", "SeparateDatabaseAndState("),
        "a star import that could bind it": STATE_ONLY.replace(
            "from django.db import migrations",
            "from django.db import migrations\nfrom widgets.helpers import *",
        ),
        "a callee that is not Django's": '''from django.db import migrations


def SeparateDatabaseAndState(database_operations, state_operations):
    """Not Django's: it hands the state operation straight to the database."""
    return state_operations[0]


class Migration(migrations.Migration):

    operations = [
        SeparateDatabaseAndState(
            database_operations=[],
            state_operations=[migrations.DeleteModel(name="Gadget")],
        ),
    ]
''',
    }
    for what, source in cases.items():
        assert source != STATE_ONLY, f"{what}: the fixture did not change, so it proves nothing"
        assert _judge(source) == [WARN], what


def test_django_the_exemption_is_the_plain_shape_and_nothing_else():
    """The eleven shapes the smig-red RED review flipped or found (2026-11-24). Each one builds a
    real `DeleteModel` inside the exempt list and gets it back out, or dresses a non-Django call as
    Django's; each one reaches `operations`, so each one really runs. The exemption is an allow-list
    over the whole file, so all eleven lose it."""
    sdas = (
        "migrations.SeparateDatabaseAndState(database_operations=[],"
        " state_operations=[migrations.DeleteModel('X')])"
    )
    cases = {
        # Carried back out of the exempt list, five ways a deny rule per route has to chase.
        "getattr": f"S = {sdas}\nclass Migration(migrations.Migration):\n"
        "    operations = [getattr(S, 'state_operations')[0]]\n",
        "deconstruct": f"S = {sdas}\nclass Migration(migrations.Migration):\n"
        "    operations = [S.deconstruct()[2]['state_operations'][0]]\n",
        "vars": f"S = {sdas}\nclass Migration(migrations.Migration):\n"
        "    operations = [vars(S)['state_operations'][0]]\n",
        "a second assignment to operations": "class Migration(migrations.Migration):\n"
        f"    operations = [{sdas}]\n"
        "    operations = [getattr(operations[0], 'state_operations')[0]]\n",
        "a rewrite after the class": "class Migration(migrations.Migration):\n"
        f"    operations = [{sdas}]\n\n\n"
        "Migration.operations = [getattr(Migration.operations[0], 'state_operations')[0]]\n",
        # Built inside it and stashed, so what `operations` holds is not what the list shows.
        "an append inside the list": "BAG = []\nclass Migration(migrations.Migration):\n"
        "    operations = [migrations.SeparateDatabaseAndState(database_operations=[],"
        " state_operations=[BAG.append(migrations.DeleteModel('X'))"
        " or migrations.CreateModel('Y', [])]), *BAG]\n",
        "an append inside an operation's argument": "BAG = []\n"
        "class Migration(migrations.Migration):\n"
        "    operations = [migrations.SeparateDatabaseAndState(database_operations=[],"
        " state_operations=[migrations.CreateModel("
        "BAG.append(migrations.DeleteModel('X')) or 'Y', [])]), *BAG]\n",
        "a helper that keeps what it is given": "KEEP = []\n"
        "def keep(op):\n    KEEP.append(op)\n    return migrations.CreateModel('Y', [])\n"
        "class Migration(migrations.Migration):\n"
        "    operations = [migrations.SeparateDatabaseAndState(database_operations=[],"
        " state_operations=[keep(migrations.DeleteModel('X'))]), *KEEP]\n",
        "a mutation by another class attribute": "class Migration(migrations.Migration):\n"
        f"    operations = [{sdas}]\n"
        "    atomic = operations.append(getattr(operations[0], 'state_operations')[0])\n",
        # Dressed as Django's and not Django's.
        "a receiver that is not django.db": "from mystuff import shim\n"
        "class Migration(migrations.Migration):\n"
        "    operations = [shim.SeparateDatabaseAndState(database_operations=[],"
        " state_operations=[migrations.DeleteModel('X')])]\n",
        "a name rebound in the class body": "from mystuff import shim\n"
        "class Migration(migrations.Migration):\n"
        "    migrations = shim\n"
        f"    operations = [{sdas}]\n",
        # The class header and the code beside the exempt call: both run around the list the shape
        # rules read, so both are part of the shape.
        "a decorator that rewrites operations": "@(lambda c: setattr("
        "c, 'operations', [getattr(c.operations[0], 'state_operations')[0]]) or c)\n"
        "class Migration(migrations.Migration):\n"
        f"    operations = [{sdas}]\n",
        "a metaclass that rewrites operations": "class Migration(migrations.Migration,"
        " metaclass=(lambda n, b, ns: type(n, b, {**ns,"
        " 'operations': [getattr(ns['operations'][0], 'state_operations')[0]]}))):\n"
        f"    operations = [{sdas}]\n",
        "a base of its own": "from mystuff import Base\nclass Migration(Base):\n"
        f"    operations = [{sdas}]\n",
        "a base built with type()": "Base = type('Base', (migrations.Migration,), {})\n"
        "class Migration(Base):\n"
        f"    operations = [{sdas}]\n",
        "a sibling element patching django first": "class Migration(migrations.Migration):\n"
        "    operations = [\n"
        "        (setattr(migrations, 'SeparateDatabaseAndState',"
        " lambda database_operations, state_operations: state_operations[0]),"
        " migrations.RunPython(migrations.RunPython.noop))[1],\n"
        f"        {sdas},\n"
        "    ]\n",
    }
    for what, body in cases.items():
        assert _judge("from django.db import migrations\n" + body) == [WARN], what


def test_django_migration_that_does_not_parse_warns():
    assert _judge("operations = [  # truncated\n") == [WARN]


RUN_SQL = '''"""Runs SQL the Django operations never name."""

from django.db import migrations


class Migration(migrations.Migration):

    operations = [
        migrations.RunSQL(sql="SQL_HERE", reverse_sql=migrations.RunSQL.noop),
    ]
'''


def test_django_sql_is_judged_by_what_it_destroys():
    """The engine's own migrations drop and recreate triggers and functions to stay idempotent, so
    only SQL that destroys rows or a column counts (redesign-s1 RED review, the inversion in F3)."""
    destroys = (
        "DROP TABLE gadgets",
        "ALTER TABLE w DROP COLUMN g",
        "ALTER TABLE w DROP g",  # Postgres lets the word COLUMN be left out
        "TRUNCATE w",
        "DELETE FROM w",
    )
    keeps = (
        "drop trigger if exists guard on w; create trigger guard before insert on w;",
        "drop function if exists public.upsert(uuid);",
        "CREATE INDEX w_idx ON w (name)",
        "ALTER TABLE w ALTER COLUMN g DROP NOT NULL",
        "ALTER TABLE w ALTER COLUMN g DROP DEFAULT",
        # One statement's ALTER must not reach into the next statement's trigger drop.
        "alter table w add column g text; drop trigger if exists t on w; create trigger t;",
    )
    for sql in destroys:
        assert _judge(RUN_SQL.replace("SQL_HERE", sql)) == [WARN], sql
    for sql in keeps:
        assert _judge(RUN_SQL.replace("SQL_HERE", sql)) == [], sql


def test_django_a_drop_in_reverse_sql_only_is_not_destructive():
    undo = RUN_SQL.replace('sql="SQL_HERE", reverse_sql=migrations.RunSQL.noop', (
        'sql="CREATE SCHEMA IF NOT EXISTS django;",\n'
        '            reverse_sql="DROP SCHEMA IF EXISTS django CASCADE;"'
    ))
    assert undo != RUN_SQL
    assert _judge(undo) == []


def test_django_reverse_sql_run_forward_by_a_function_of_its_own_warns():
    """`reverse_sql` is the undo on Django's RunSQL. A helper taking an argument of that name and
    running it forward gets no exemption."""
    forward = '''from django.db import migrations


def later(reverse_sql):
    """Not Django's: it runs what it is given."""
    return migrations.RunSQL(sql=reverse_sql)


class Migration(migrations.Migration):

    operations = [later(reverse_sql="DROP TABLE gadgets")]
'''
    assert _judge(forward) == [WARN]


DB = "app/src/main/java/com/kevin/legion/data/local/CarDatabase.kt"
SCHEMA = "app/schemas/com.kevin.legion.data.local.CarDatabase/"


def test_room_new_version_registered_passes():
    ch = change({SCHEMA + "81.json": ("A", "{}\n"), DB: ("M", ".addMigrations(MIGRATION_79_80, MIGRATION_80_81)\n")},
                head_files=[SCHEMA + "80.json", SCHEMA + "81.json", DB])
    assert migrations.check(ch) == []


def test_room_new_version_unregistered_fails():
    ch = change({SCHEMA + "81.json": ("A", "{}\n")},
                head_files=[SCHEMA + "80.json", SCHEMA + "81.json", DB])
    ch.head_text[DB] = ".addMigrations(MIGRATION_79_80)\n"
    found = migrations.check(ch)
    assert levels(found) == [FAIL] and "MIGRATION_80_81" in found[0].message


def test_room_version_named_only_in_a_comment_fails():
    ch = change({SCHEMA + "81.json": ("A", "{}\n")},
                head_files=[SCHEMA + "80.json", SCHEMA + "81.json", DB])
    ch.head_text[DB] = (
        "// the diag table: see [MIGRATION_80_81]\n"
        ".addMigrations(\n    MIGRATION_78_79, // MIGRATION_80_81 next\n    MIGRATION_79_80,\n)\n"
    )
    assert levels(migrations.check(ch)) == [FAIL]


def test_room_registration_read_across_lines():
    src = ".addMigrations(\n    MIGRATION_1_2,\n    foo(MIGRATION_2_3),\n    MIGRATION_80_81,\n)\n.build()"
    assert "MIGRATION_80_81" in migrations.registered(src)
    assert "build" not in migrations.registered(src)


def test_room_oldest_exported_version_needs_no_migration():
    hu = "headunit/app/schemas/com.kevin.midnightai.data.local.CarDatabase/"
    ch = change({hu + "5.json": ("A", "{}\n")}, head_files=[hu + "5.json", hu + "6.json"])
    assert migrations.check(ch) == []


def test_room_shipped_schema_modified_fails():
    ch = change({SCHEMA + "80.json": ("M", "{changed}\n")}, base={SCHEMA + "80.json": "{}\n"})
    assert levels(migrations.check(ch)) == [FAIL]


# ------------------------------------------------------------------------------------------ (d) em dashes
@pytest.mark.parametrize(
    "path,line,expected",
    [
        ("app/src/main/res/values/strings.xml", f"<string name=\"a\">One {DASH} two</string>", [FAIL]),
        ("app/src/main/res/values/strings.xml", "<string name=\"a\">One &#8212; two</string>", [FAIL]),
        ("app/src/main/res/values/strings.xml", "<string name=\"a\">One &mdash; two</string>", [FAIL]),
        ("app/src/main/res/values/strings.xml", f"<!-- note {DASH} here -->", []),
        ("headunit/app/src/main/res/values/strings.xml", f"<string>a {DASH} b</string>", [FAIL]),
        ("server/push/copy.py", f"TITLE = \"Due {DASH} now\"", [FAIL]),
        ("server/push/copy.py", f"# a comment {DASH} is fine", []),
        ("server/frontend/src/screens/x.tsx", f"<p>Hi {DASH} there</p>", [WARN]),
        ("server/frontend/src/screens/x.tsx", "const s = \"a \\u2014 b\"", [WARN]),
        ("server/frontend/src/screens/x.test.tsx", f"expect('{DASH}')", []),
        ("app/src/main/java/com/kevin/legion/ui/A.kt", f"Text(\"a {DASH} b\")", [WARN]),
        ("app/src/main/java/com/kevin/legion/ui/A.kt", f"// why {DASH} because", []),
        ("server/demo/content.py", f"LINE = \"a {DASH} b\"", [WARN]),
        ("docs/a.md", f"a {DASH} b", []),
    ],
)
def test_em_dash_areas(path, line, expected):
    assert levels(em_dashes.check(change({path: ("A", line + "\n")}))) == expected


def test_em_dash_message_never_holds_the_character():
    found = em_dashes.check(change({"app/src/main/res/values/strings.xml": ("A", f"<string>a {DASH} b</string>\n")}))
    assert DASH not in found[0].message


# ------------------------------------------------------------------------------------------ git plumbing
def test_parse_added_line_numbers():
    diff = (
        "diff --git a/x.md b/x.md\n--- a/x.md\n+++ b/x.md\n"
        "@@ -1,0 +2,2 @@\n+two\n+three\n"
        "diff --git a/gone.md b/gone.md\n--- a/gone.md\n+++ /dev/null\n@@ -1 +0,0 @@\n-x\n"
    )
    assert parse_added(diff) == {"x.md": [(2, "two"), (3, "three")]}


def test_parse_added_path_with_a_space():
    diff = "diff --git a/a b.md b/a b.md\n--- a/a b.md\t\n+++ b/a b.md\t\n@@ -0,0 +1 @@\n+one\n"
    assert parse_added(diff) == {"a b.md": [(1, "one")]}


def _git(repo: Path, *args: str) -> str:
    return subprocess.run(["git", "-C", str(repo), *args], capture_output=True, text=True, check=True).stdout


@pytest.fixture
def repo(tmp_path, monkeypatch):
    _git(tmp_path, "init", "-q", "-b", "dev")
    _git(tmp_path, "config", "user.email", "t@example.com")
    _git(tmp_path, "config", "user.name", "t")
    (tmp_path / "docs").mkdir()
    (tmp_path / "docs" / "a.md").write_text("plain\n", encoding="utf-8")
    _git(tmp_path, "add", ".")
    _git(tmp_path, "commit", "-q", "-m", "base")
    _git(tmp_path, "checkout", "-q", "-b", "feat")
    monkeypatch.chdir(tmp_path)
    return tmp_path


def test_gate_end_to_end_fails_on_a_new_log_time(repo, capsys):
    (repo / "docs" / "a.md").write_text("plain\nshipped 2026-11-21 14:42\n", encoding="utf-8")
    _git(repo, "commit", "-q", "-am", "log time")
    assert run_all.main(["--base", "dev"]) == 1
    out = capsys.readouterr().out
    assert "FAIL clock-times docs/a.md:2" in out and "Blast radius: GREEN" in out


def test_gate_end_to_end_passes_clean_change(repo, capsys):
    (repo / "docs" / "a.md").write_text("plain\nmore words\n", encoding="utf-8")
    _git(repo, "commit", "-q", "-am", "words")
    assert run_all.main(["--base", "dev"]) == 0


def test_gate_unknown_base_exits_2_not_pass(repo, capsys):
    assert run_all.main(["--base", "no-such-branch"]) == 2


# ------------------------------------------------------------------------------------------ review round 1
def test_new_test_file_over_limit_only_warns():
    found = file_size.check(change({"server/tests/test_big.py": ("A", lines(700))}), allow=[])
    assert levels(found) == [WARN]


def test_pure_move_is_not_a_new_file():
    ch = change({"server/old_big.py": ("D", ""), "server/garage/big.py": ("A", lines(700))},
                base={"server/old_big.py": lines(700)})
    ch.moved_from["server/garage/big.py"] = "server/old_big.py"
    assert file_size.check(ch, allow=[]) == []
    ch.head_text["server/garage/big.py"] = lines(720)
    assert levels(file_size.check(ch, allow=[])) == [WARN]


def test_migration_edit_marker_turns_fail_into_named_warn():
    path = "server/ledger/migrations/0003_x.py"
    plain = change({path: ("M", "ops = []\n")})
    marked = change({path: ("M", "# migration-edit-ok: server/ledger/migrations/0003_x.py never applied anywhere, fixes a typo\nops = []\n")})
    assert levels(migrations.check(plain)) == [FAIL]
    found = migrations.check(marked)
    assert levels(found) == [WARN] and "never applied anywhere" in found[0].message


def test_migration_marker_needs_a_reason_and_the_right_file():
    path = "server/ledger/migrations/0003_x.py"
    no_reason = change({path: ("M", f"# migration-edit-ok: {path} ok\n")})
    wrong_file = change({path: ("M", "# migration-edit-ok: server/ledger/migrations/0004_y.py a long enough reason\n")})
    name_only = change({path: ("M", "# migration-edit-ok: 0003_x.py a long enough reason here\n")})
    assert levels(migrations.check(no_reason)) == [FAIL]
    assert levels(migrations.check(wrong_file)) == [FAIL]
    assert levels(migrations.check(name_only)) == [FAIL]


BASELINE = "config/detekt/baseline.xml"


def baseline(*entries: str) -> str:
    return "<SmellBaseline><CurrentIssues>\n" + "".join(f"<ID>{e}</ID>\n" for e in entries) + "</CurrentIssues></SmellBaseline>\n"


def test_detekt_baseline_shrinking_passes_growing_fails():
    old = baseline("LongMethod:A.kt$A$fun a()", "MagicNumber:B.kt$B$3")
    shrink = change({BASELINE: ("M", baseline("LongMethod:A.kt$A$fun a()"))}, base={BASELINE: old})
    grow = change({BASELINE: ("M", baseline("LongMethod:A.kt$A$fun a()", "MagicNumber:B.kt$B$3",
                                            "LongMethod:C.kt$C$fun c()"))}, base={BASELINE: old})
    assert detekt_baseline.check(shrink) == []
    assert levels(detekt_baseline.check(grow)) == [FAIL]


KT = "app/src/main/java/com/x/"


def test_detekt_plain_rekey_to_a_new_file_and_object_passes():
    old = baseline("CyclomaticComplexMethod:LiveToolbox.kt:LiveToolbox$private fun getMpg(): JSONObject")
    new = baseline("CyclomaticComplexMethod:VehicleHealthToolbox.kt:VehicleHealthToolbox$private fun getMpg(): JSONObject")
    ch = change({BASELINE: ("M", new), KT + "LiveToolbox.kt": ("M", "x\n"), KT + "VehicleHealthToolbox.kt": ("A", "x\n")},
                base={BASELINE: old})
    assert detekt_baseline.check(ch) == []


def test_detekt_split_key_passes_when_a_changed_file_split_into_new_ones():
    # LiveToolbox still has a 5; two new files split out of it now each list a 5 too.
    old = baseline("MagicNumber:LiveToolbox.kt$LiveToolbox$5")
    new = baseline("MagicNumber:LiveToolbox.kt$LiveToolbox$5", "MagicNumber:Places.kt$Places$5",
                   "MagicNumber:Music.kt$Music$5")
    ch = change({BASELINE: ("M", new), KT + "LiveToolbox.kt": ("M", "x\n"), KT + "Places.kt": ("A", "x\n"),
                 KT + "Music.kt": ("A", "x\n")}, base={BASELINE: old})
    assert detekt_baseline.check(ch) == []


def test_detekt_truly_new_finding_fails():
    old = baseline("MagicNumber:A.kt$A$5")
    new = baseline("MagicNumber:A.kt$A$5", "LongMethod:New.kt$New$fun brandNew()")
    ch = change({BASELINE: ("M", new), KT + "New.kt": ("A", "x\n")}, base={BASELINE: old})
    found = detekt_baseline.check(ch)
    assert levels(found) == [FAIL] and "new finding" in found[0].message


def test_detekt_count_growing_without_a_split_fails():
    old = baseline("MagicNumber:A.kt$A$5")
    # Same text under another EXISTING file (not added by this change): new debt, not a split.
    in_old_file = change({BASELINE: ("M", baseline("MagicNumber:A.kt$A$5", "MagicNumber:B.kt$B$5")),
                          KT + "A.kt": ("M", "x\n"), KT + "B.kt": ("M", "x\n")}, base={BASELINE: old})
    # A new file, but the file the base listed it under was not touched: nothing moved out of it.
    untouched_source = change({BASELINE: ("M", baseline("MagicNumber:A.kt$A$5", "MagicNumber:C.kt$C$5")),
                               KT + "C.kt": ("A", "x\n")}, base={BASELINE: old})
    assert levels(detekt_baseline.check(in_old_file)) == [FAIL]
    assert levels(detekt_baseline.check(untouched_source)) == [FAIL]


@pytest.mark.parametrize(
    "entry,expected",
    [
        ("LongMethod:A.kt$A$fun a()", ("LongMethod", "A.kt", "fun a()")),
        ("MaxLineLength:A.kt:A$val x = 1", ("MaxLineLength", "A.kt", "val x = 1")),
        ("ComplexCondition:FleetScreen.kt:a == b || c", ("ComplexCondition", "FleetScreen.kt", "a == b || c")),
        ("MagicNumber:B.kt$B.Companion$3", ("MagicNumber", "B.kt", "3")),
    ],
)
def test_detekt_parse(entry, expected):
    assert detekt_baseline.parse(entry) == expected


def test_gate_end_to_end_non_ascii_path_is_read(repo, capsys):
    (repo / "docs" / "café.md").write_text("shipped 2026-11-21 14:42\n", encoding="utf-8")
    _git(repo, "add", ".")
    _git(repo, "commit", "-q", "-m", "accent")
    assert run_all.main(["--base", "dev"]) == 1


def test_gate_end_to_end_pure_move_of_big_file_passes(repo, capsys):
    _git(repo, "checkout", "-q", "dev")
    (repo / "server").mkdir()
    (repo / "server" / "big.py").write_text(lines(700), encoding="utf-8")
    _git(repo, "add", ".")
    _git(repo, "commit", "-q", "-m", "big")
    _git(repo, "checkout", "-q", "-B", "feat")
    _git(repo, "mv", "server/big.py", "server/garage_big.py")
    _git(repo, "commit", "-q", "-m", "move")
    assert run_all.main(["--base", "dev"]) == 0


def test_detekt_rekey_survives_visibility_change_and_renamed_object_reference():
    old = baseline("ReturnCount:LiveToolbox.kt:LiveToolbox$private suspend fun queue(q: String): JSONObject",
                   "MaxLineLength:SomeTest.kt:SomeTest$LiveToolbox.LocationReadout.NoPermission")
    new = baseline("ReturnCount:MusicToolbox.kt:MusicToolbox$internal suspend fun queue(q: String): JSONObject",
                   "MaxLineLength:SomeTest.kt:SomeTest$PlacesToolbox.LocationReadout.NoPermission")
    ch = change({BASELINE: ("M", new), KT + "LiveToolbox.kt": ("M", "x\n"), KT + "MusicToolbox.kt": ("A", "x\n"),
                 KT + "PlacesToolbox.kt": ("A", "x\n")}, base={BASELINE: old})
    assert detekt_baseline.check(ch) == []


def test_detekt_renamed_reference_to_an_untouched_object_is_still_new():
    old = baseline("MaxLineLength:SomeTest.kt:SomeTest$Alpha.X")
    new = baseline("MaxLineLength:SomeTest.kt:SomeTest$Beta.X")
    ch = change({BASELINE: ("M", new)}, base={BASELINE: old})
    assert levels(detekt_baseline.check(ch)) == [FAIL]


def test_detekt_new_large_class_in_a_new_file_fails_while_another_file_is_modified():
    old = baseline("LargeClass:LiveToolbox.kt:LiveToolbox")
    new = baseline("LargeClass:LiveToolbox.kt:LiveToolbox", "LargeClass:Brand.kt:Brand")
    ch = change({BASELINE: ("M", new), KT + "LiveToolbox.kt": ("M", "x\n"), KT + "Brand.kt": ("A", "x\n")},
                base={BASELINE: old})
    assert levels(detekt_baseline.check(ch)) == [FAIL]


def test_detekt_rename_gaining_large_class_fails():
    old = baseline("TooManyFunctions:Old.kt:Old", "LongMethod:Old.kt$Old$fun a()")
    new = baseline("TooManyFunctions:Renamed.kt:Renamed", "LargeClass:Renamed.kt:Renamed",
                   "LongMethod:Renamed.kt$Renamed$fun a()")
    ch = change({BASELINE: ("M", new), KT + "Old.kt": ("D", ""), KT + "Renamed.kt": ("A", "x\n")},
                base={BASELINE: old})
    assert levels(detekt_baseline.check(ch)) == [FAIL]


def test_detekt_class_level_finding_still_rekeys_one_for_one():
    old = baseline("TooManyFunctions:Old.kt:Old")
    new = baseline("TooManyFunctions:Renamed.kt:Renamed")
    ch = change({BASELINE: ("M", new), KT + "Old.kt": ("D", ""), KT + "Renamed.kt": ("A", "x\n")},
                base={BASELINE: old})
    assert detekt_baseline.check(ch) == []
