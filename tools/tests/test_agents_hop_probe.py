"""`tools/agents_hop_probe.py --turn-off` with a member's token: once the agent turn is switched on
that post would run a real, paid turn (review 2026-11-19, C1). It is refused without `--paid`,
and the estimate is printed either way. No network: `ttfb` is a fake."""

from __future__ import annotations

import importlib.util
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location(
    "hop_probe_under_test", TOOLS / "agents_hop_probe.py"
)
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


def _token(tmp_path):
    path = tmp_path / "token.txt"
    path.write_text("not-a-real-token", encoding="utf-8")
    return str(path)


def test_a_member_turn_is_not_posted_without_paid(tmp_path, monkeypatch, capsys):
    posted = []
    monkeypatch.setattr(
        probe, "ttfb", lambda *a, **k: posted.append(a) or (1.0, 404, b"switched off")
    )
    code = probe.main(
        [
            "--base",
            "http://engine.invalid",
            "--turn-off",
            "--token-file",
            _token(tmp_path),
        ]
    )
    out = capsys.readouterr().out
    assert code == 2 and posted == []
    assert "--paid" in out and "$" in out


def test_with_paid_it_posts_once_and_prints_the_estimate_first(
    tmp_path, monkeypatch, capsys
):
    posted = []
    monkeypatch.setattr(
        probe, "ttfb", lambda *a, **k: posted.append(a) or (1.0, 404, b"switched off")
    )
    code = probe.main(
        [
            "--base",
            "http://engine.invalid",
            "--turn-off",
            "--paid",
            "--token-file",
            _token(tmp_path),
        ]
    )
    assert code == 0 and len(posted) == 1
    assert "$" in capsys.readouterr().out


def test_the_anonymous_check_needs_no_paid(monkeypatch):
    monkeypatch.setattr(probe, "ttfb", lambda *a, **k: (1.0, 401, b""))
    assert probe.main(["--base", "http://engine.invalid", "--turn-off"]) == 0
