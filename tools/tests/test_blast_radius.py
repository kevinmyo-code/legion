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
        ("TEAM.md", "GREEN"),
        ("server/procedures/builtin/README.md", "GREEN"),
        ("server/agents/specs/generalist.md", "YELLOW"),
        ("server/assistant/shared_clauses.txt", "YELLOW"),
        ("app/src/main/assets/wake-kws/tokens.txt", "YELLOW"),
        ("tools\\claude_session\\worker.py", "RED"),
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
    assert ": 4 files" in line and line.endswith("needs Kevin's word)")


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


def test_docs_change_is_flagged_publish():
    tier, rows = br.tier_of(["docs/a.md"])
    assert tier == "GREEN"
    assert "PUBLISH" in br.summary_line(tier, rows)
    tier, rows = br.tier_of(["vault/a.md"])
    assert "PUBLISH" not in br.summary_line(tier, rows)


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
