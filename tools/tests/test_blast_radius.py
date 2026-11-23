"""Tests for tools/blast_radius.py: the tier of a change from its file list."""

from __future__ import annotations

import io
import subprocess
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import blast_radius as br  # noqa: E402


@pytest.mark.parametrize(
    "path,tier",
    [
        ("docs/adr/0110-something.md", "GREEN"),
        ("README.md", "GREEN"),
        ("server/tests/test_ledger.py", "GREEN"),
        # round 2: S1 subjects, S2 gate code
        ("server/tests/test_agents_relay_api.py", "RED"),
        ("server/tests/test_claude_worker_scope.py", "RED"),
        ("server/tests/test_claude_session_api.py", "RED"),
        ("server/tests/test_ingest_runs.py", "RED"),
        ("server/tests/test_headunit_signin.py", "RED"),
        ("server/tests/test_connect_session.py", "RED"),
        ("server/tests/test_add_household_member.py", "RED"),
        ("server/tests/finance_synthetic.py", "RED"),
        ("server/tests/bofa_fixtures/bofa_happy_path.txt", "RED"),
        ("server/tests/test_bofa_activity_ingest.py", "RED"),
        ("server/tests/test_plaid_sync.py", "RED"),
        ("server/tests/test_ledger_dedup.py", "RED"),
        ("server/tests/test_merchant_aliases.py", "RED"),
        ("server/tests/test_drive_statements.py", "RED"),
        ("server/tests/test_ledger_categorize.py", "RED"),
        ("server/tests/test_spend_api.py", "RED"),
        ("server/tests/test_tenant_isolation.py", "RED"),
        ("server/tests/test_visibility.py", "RED"),
        ("app/src/test/java/com/kevin/legion/service/ReadThroughWriteGuardTest.kt", "RED"),
        ("server/tests/test_agents_spoken.py", "RED"),
        ("app/src/test/java/com/kevin/legion/voiceeval/VoiceEvalRun.kt", "RED"),
        ("server/tests/test_recruiter_content.py", "RED"),
        ("server/tests/test_assistant.py", "RED"),
        ("config/detekt/detekt.yml", "RED"),
        ("AGENTS.md", "RED"),
        ("TEAM.md", "RED"),
        ("app/src/test/java/com/kevin/legion/FooTest.kt", "GREEN"),
        ("server/frontend/src/lib/foo.test.ts", "GREEN"),
        ("server/frontend/e2e/try-shots.spec.ts", "GREEN"),
        ("app/src/main/res/values/strings.xml", "GREEN"),
        ("server/frontend/src/index.css", "GREEN"),
        ("tools/screen_map.py", "RED"),
        ("app/src/main/java/com/kevin/legion/ui/CalendarScreen.kt", "YELLOW"),
        ("server/assistant/tour.py", "YELLOW"),
        ("server/frontend/src/screens/try.tsx", "YELLOW"),
        ("server/openapi.yaml", "YELLOW"),
        ("some/new/unknown.thing", "YELLOW"),
        ("tools/claude_session/asklist_hook.py", "RED"),
        ("tools/claude_session/test_asklist_hook.py", "RED"),
        ("tools/claude_session/README.md", "RED"),
        ("server/claude_session/views.py", "RED"),
        ("server/board/authentication.py", "RED"),
        ("app/src/main/java/com/kevin/legion/backend/engine/EngineAuth.kt", "RED"),
        ("server/household/tenancy.py", "RED"),
        ("server/assistant/spoken_figures.py", "RED"),
        ("server/ingest/statements.py", "RED"),
        ("server/finance/migrations/0031_add_thing.py", "RED"),
        ("app/schemas/com.kevin.legion.data.local.CarDatabase/81.json", "RED"),
        ("app/src/main/java/com/kevin/legion/data/local/Migrations.kt", "RED"),
        ("deploy/ship.py", "RED"),
        (".github/workflows/server.yml", "RED"),
        (".github/known-failures/server.txt", "RED"),
        ("tools/voice_eval/gate_cases.txt", "RED"),
        ("tools/voice_eval.py", "RED"),
        ("server/demo/shop/guards.py", "RED"),
        ("server/tests/test_recruiter_delivery_frozen.py", "RED"),
        ("server/requirements.txt", "RED"),
        ("CLAUDE.md", "RED"),
        ("tools/blast_radius.py", "RED"),
        ("tools/checks/clock_times.py", "RED"),
        ("config/detekt/baseline.xml", "YELLOW"),
        ("./docs/x.md", "GREEN"),
        ("server/procedures/builtin/README.md", "GREEN"),
        ("server/agents/specs/generalist.md", "YELLOW"),
        ("server/assistant/shared_clauses.txt", "YELLOW"),
        ("app/src/main/assets/wake-kws/tokens.txt", "YELLOW"),
        ("tools\\claude_session\\worker.py", "RED"),
        # B2: no tools/* GREEN catch-all
        ("tools/render_diagrams.py", "YELLOW"),
        ("tools/laptop_voice/service.py", "YELLOW"),
        ("tools/obsidian_sync.py", "RED"),
        ("tools/pending_wiki.py", "RED"),
        ("tools/voice_guide_copy.py", "RED"),
        ("tools/connect_session.py", "RED"),
        ("tools/mcp/engine_server.py", "RED"),
        (".mcp.json", "RED"),
        ("tools/phone/rails.py", "RED"),
        ("tools/finance_privacy_check.py", "RED"),
        # B3: tests and data of RED subjects
        ("app/src/test/java/com/kevin/legion/engine/ReconciliationGateTest.kt", "RED"),
        ("app/src/test/java/com/kevin/legion/gmail/EmailSendGateTest.kt", "RED"),
        ("app/src/test/java/com/kevin/legion/AllFeaturesReadThroughGuardTest.kt", "RED"),
        ("app/src/test/java/com/kevin/legion/finance/SpokenMoneyTest.kt", "RED"),
        ("app/src/test/java/com/kevin/legion/backend/engine/EngineAuthTest.kt", "RED"),
        ("app/src/test/java/com/kevin/legion/service/MoneySpecialistEvalCasesTest.kt", "RED"),
        ("app/src/test/resources/gate-corpus.json", "RED"),
        ("tools/tests/test_ship.py", "RED"),
        ("tools/tests/test_release_apk.py", "RED"),
        ("tools/tests/test_voice_eval.py", "RED"),
        ("tools/tests/test_blast_radius.py", "RED"),
        ("server/tests/test_finance_privacy.py", "RED"),
        ("server/tests/conftest.py", "RED"),
        ("server/tests/realrun_money.py", "RED"),
        ("server/tests/fixtures/recurrence_vectors.json", "YELLOW"),
        # B6: case
        ("claude.md", "RED"),
        ("Claude.md", "RED"),
        ("Server/Ingest/gate.py", "RED"),
        # B7: rules and hooks at any depth, by file name only
        ("CLAUDE.local.md", "RED"),
        ("docs/CLAUDE.md", "RED"),
        ("server/CLAUDE.md", "RED"),
        ("docs/.claude/settings.json", "RED"),
        ("vault/.claude/settings.json", "RED"),
        ("headunit/.claude/agents/x.md", "RED"),
        ("headunit/.mcp.json", "RED"),
        ("docs/adr/0080-claude-files-to-phone.md", "GREEN"),
        ("docs/process/how-claude-works.md", "GREEN"),
        ("tools/claude_session/main_session_prompt.md", "RED"),
        # gates.yml runs the Gradle build with the key present
        ("app/build.gradle.kts", "RED"),
        ("gradle/libs.versions.toml", "RED"),
        ("gradle/wrapper/gradle-wrapper.properties", "RED"),
        # B5 / N2: doc roots hold docs only; test globs anchored to test roots
        ("docs/x.py", "YELLOW"),
        ("vault/x.py", "YELLOW"),
        ("memory/run.sh", "YELLOW"),
        ("app/src/main/java/com/x/__tests__/Prod.kt", "YELLOW"),
        ("server/assistant/test_mode.py", "YELLOW"),
        ("server/tests/test_ledger.py", "GREEN"),
        # round 2: S1 subjects, S2 gate code
        ("server/tests/test_agents_relay_api.py", "RED"),
        ("server/tests/test_claude_worker_scope.py", "RED"),
        ("server/tests/test_claude_session_api.py", "RED"),
        ("server/tests/test_ingest_runs.py", "RED"),
        ("server/tests/test_headunit_signin.py", "RED"),
        ("server/tests/test_connect_session.py", "RED"),
        ("server/tests/test_add_household_member.py", "RED"),
        ("server/tests/finance_synthetic.py", "RED"),
        ("server/tests/bofa_fixtures/bofa_happy_path.txt", "RED"),
        ("server/tests/test_bofa_activity_ingest.py", "RED"),
        ("server/tests/test_plaid_sync.py", "RED"),
        ("server/tests/test_ledger_dedup.py", "RED"),
        ("server/tests/test_merchant_aliases.py", "RED"),
        ("server/tests/test_drive_statements.py", "RED"),
        ("server/tests/test_ledger_categorize.py", "RED"),
        ("server/tests/test_spend_api.py", "RED"),
        ("server/tests/test_tenant_isolation.py", "RED"),
        ("server/tests/test_visibility.py", "RED"),
        ("app/src/test/java/com/kevin/legion/service/ReadThroughWriteGuardTest.kt", "RED"),
        ("server/tests/test_agents_spoken.py", "RED"),
        ("app/src/test/java/com/kevin/legion/voiceeval/VoiceEvalRun.kt", "RED"),
        ("server/tests/test_recruiter_content.py", "RED"),
        ("server/tests/test_assistant.py", "RED"),
        ("config/detekt/detekt.yml", "RED"),
        ("AGENTS.md", "RED"),
        ("TEAM.md", "RED"),
    ],
)
def test_classify(path, tier):
    assert br.classify(path)[0] == tier


def test_highest_tier_wins():
    tier, rows = br.tier_of(["docs/a.md", "server/assistant/tour.py"])
    assert tier == "YELLOW"
    tier, rows = br.tier_of(["docs/a.md", "server/assistant/tour.py", "deploy/ship.py"])
    assert tier == "RED"
    assert rows[0] == ("RED", "deploy, ship and CI tooling", "deploy/ship.py")


def test_all_green():
    tier, _ = br.tier_of(["docs/a.md", "server/tests/test_x.py"])
    assert tier == "GREEN"


def test_empty_list_is_an_error_not_green():
    with pytest.raises(ValueError):
        br.tier_of([])
    with pytest.raises(ValueError):
        br.tier_of(["", "  "])


def test_summary_line_names_the_reasons_of_the_top_tier_only():
    tier, rows = br.tier_of(["docs/a.md", "deploy/ship.py", "tools/claude_session/worker.py", "x.kt"])
    line = br.summary_line(tier, rows)
    assert line.startswith("RED (")
    assert "deploy, ship and CI tooling" in line and "worker hook and containment" in line
    assert "app, engine or tool logic" not in line
    assert line.endswith(": 4 files")


def test_cli_reads_stdin(monkeypatch, capsys):
    monkeypatch.setattr(sys, "stdin", io.StringIO("vault/a.md\nserver/assistant/tour.py\n"))
    assert br.main(["--files", "-", "--short"]) == 0
    assert capsys.readouterr().out.strip() == "YELLOW (app, engine or tool logic): 2 files"


def test_cli_empty_stdin_exits_2(monkeypatch, capsys):
    monkeypatch.setattr(sys, "stdin", io.StringIO(""))
    assert br.main(["--files", "-"]) == 2


def test_cli_full_output_hides_green_rows_under_a_higher_tier(monkeypatch, capsys):
    monkeypatch.setattr(sys, "stdin", io.StringIO("docs/a.md\ndeploy/ship.py\n"))
    assert br.main(["--files", "-"]) == 0
    out = capsys.readouterr().out
    assert "tier: RED" in out and "Kevin's word" in out
    assert "docs/a.md" not in out and "1 GREEN files" in out


def test_case_collision_is_red():
    tier, rows = br.tier_of(["readme.md"], tracked=["README.md", "readme.md"])
    assert tier == "RED" and rows[0][1] == "case-collides with another path"
    tier, _ = br.tier_of(["docs/a.md"], tracked=["docs/a.md", "docs/b.md"])
    assert tier == "GREEN"


@pytest.mark.parametrize(
    "path,flag",
    [
        ("docs/index.html", True),
        ("docs/privacy.html", True),
        ("docs/board.json", True),
        ("docs/site.js", True),
        ("docs/style.css", True),
        ("docs/CNAME", True),
        ("docs/.nojekyll", True),
        ("docs/_config.yml", True),
        ("docs/logo.png", True),
        ("docs/design/canvas/Main.dc.html", True),
        ("docs/adr/0110-x.md", False),
        ("docs/process/blast-radius.md", False),
        ("docs/architecture/views/c1.svg", False),
        ("docs/notes.txt", False),
        ("vault/index.html", False),
    ],
)
def test_publish_flag_only_for_rendered_site_files(path, flag):
    tier, rows = br.tier_of([path])
    assert ("PUBLISH" in br.summary_line(tier, rows)) is flag


def _git(repo, *args):
    return subprocess.run(["git", "-C", str(repo), *args], capture_output=True, text=True, check=True).stdout


def test_base_copy_wins_over_a_head_copy_that_lowers_itself(tmp_path, monkeypatch, capsys):
    """B1: a change that edits the script to call itself GREEN still gets the base copy's RED."""
    src = Path(br.__file__).read_text(encoding="utf-8")
    _git(tmp_path, "init", "-q", "-b", "dev")
    _git(tmp_path, "config", "user.email", "t@example.com")
    _git(tmp_path, "config", "user.name", "t")
    (tmp_path / "tools").mkdir()
    (tmp_path / "tools" / "blast_radius.py").write_text(src, encoding="utf-8")
    _git(tmp_path, "add", ".")
    _git(tmp_path, "commit", "-q", "-m", "base")
    _git(tmp_path, "checkout", "-q", "-b", "feat")
    gutted = src.replace("        SELF,\n", "")
    gutted = gutted.replace('"deploy/*",', '')
    assert gutted != src
    (tmp_path / "tools" / "blast_radius.py").write_text(gutted, encoding="utf-8")
    (tmp_path / "deploy").mkdir()
    (tmp_path / "deploy" / "x.sh").write_text("echo\n", encoding="utf-8")
    _git(tmp_path, "add", ".")
    _git(tmp_path, "commit", "-q", "-m", "lower myself")
    monkeypatch.chdir(tmp_path)
    assert br.base_copy_tier("dev", "HEAD", ["tools/blast_radius.py", "deploy/x.sh"]) == "RED"
    assert br.main(["--base", "dev", "--head", "HEAD", "--short"]) == 0
    assert capsys.readouterr().out.startswith("RED")


REPO = Path(__file__).resolve().parents[2]


def test_everything_realrun_imports_is_red():
    """S2: the keyed engine gate runs server/tests/realrun_*; every tests module they import, transitively,
    must be RED, or a merged change could alter what runs with the key under a lighter review."""
    import re

    tests = REPO / "server" / "tests"
    todo = sorted(tests.glob("realrun_*.py"))
    assert todo, "no realrun_*.py found: the test is not looking at the repo"
    seen: set[Path] = set()
    while todo:
        f = todo.pop()
        if f in seen:
            continue
        seen.add(f)
        for m in re.finditer(r"^\s*(?:from|import)\s+tests\.([A-Za-z0-9_]+)", f.read_text(encoding="utf-8"), re.M):
            dep = tests / f"{m.group(1)}.py"
            if dep.exists():
                todo.append(dep)
    for f in sorted(seen):
        rel = f.relative_to(REPO).as_posix()
        assert br.classify(rel)[0] == "RED", f"{rel} runs in the keyed gate but tiers {br.classify(rel)}"


def test_base_copy_read_from_the_base_tip_for_a_stale_branch(tmp_path, monkeypatch):
    """A branch forked before the script existed still meets the copy on the base tip."""
    src = Path(br.__file__).read_text(encoding="utf-8")
    _git(tmp_path, "init", "-q", "-b", "dev")
    _git(tmp_path, "config", "user.email", "t@example.com")
    _git(tmp_path, "config", "user.name", "t")
    (tmp_path / "a.txt").write_text("a\n", encoding="utf-8")
    _git(tmp_path, "add", ".")
    _git(tmp_path, "commit", "-q", "-m", "fork point")
    _git(tmp_path, "checkout", "-q", "-b", "feat")
    (tmp_path / "deploy").mkdir()
    (tmp_path / "deploy" / "x.sh").write_text("echo\n", encoding="utf-8")
    _git(tmp_path, "add", ".")
    _git(tmp_path, "commit", "-q", "-m", "stale branch")
    _git(tmp_path, "checkout", "-q", "dev")
    (tmp_path / "tools").mkdir()
    (tmp_path / "tools" / "blast_radius.py").write_text(src, encoding="utf-8")
    _git(tmp_path, "add", ".")
    _git(tmp_path, "commit", "-q", "-m", "script lands on dev")
    monkeypatch.chdir(tmp_path)
    assert br.base_copy_tier("dev", "feat", ["deploy/x.sh"]) == "RED"
