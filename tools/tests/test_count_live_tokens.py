"""`tools/count_live_tokens.py` with no network and no key. Run from the repo root:

    python -m pytest tools/tests/test_count_live_tokens.py -p no:cacheprovider
"""
from __future__ import annotations

import importlib.util
import json
import sys
from pathlib import Path

import pytest

TOOLS = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("count_live_tokens_under_test", TOOLS / "count_live_tokens.py")
clt = importlib.util.module_from_spec(spec)
sys.modules["count_live_tokens_under_test"] = clt
spec.loader.exec_module(clt)

SETUP = {
    "systemInstruction": "You are a made-up assistant.",
    "tools": [
        {"googleSearch": {}},
        {"functionDeclarations": [{"name": "get_time", "description": "The time.", "parameters": {"type": "OBJECT"}}]},
    ],
}


@pytest.fixture
def setup_file(tmp_path):
    path = tmp_path / "setup.json"
    path.write_text(json.dumps(SETUP), encoding="utf-8")
    return path


def test_the_request_carries_the_instruction_and_the_exact_tools():
    body = clt.request_body(SETUP, "gemini-x")["generateContentRequest"]
    assert body["model"] == "models/gemini-x"
    assert body["systemInstruction"]["parts"][0]["text"] == SETUP["systemInstruction"]
    assert body["tools"] == SETUP["tools"]
    assert body["contents"]


def test_main_prints_the_count_minus_the_turn_and_never_the_key(setup_file, monkeypatch, capsys):
    calls = []

    def fake_count(body, model, key):
        calls.append((body, key))
        return 120 if "tools" in body["generateContentRequest"] else 3

    monkeypatch.setattr(clt, "count", fake_count)
    monkeypatch.setenv("GEMINI_API_KEY", "made-up-key")
    assert clt.main(["--setup", str(setup_file), "--model", "gemini-x"]) == 0
    out = capsys.readouterr().out
    assert "countTokens      117" in out
    assert "tool decls       1" in out
    assert "made-up-key" not in out
    assert all(key == "made-up-key" for _, key in calls)


def test_no_key_and_no_file_are_refused_in_words(setup_file, tmp_path, monkeypatch):
    monkeypatch.delenv("GEMINI_API_KEY", raising=False)
    with pytest.raises(SystemExit, match="No key"):
        clt.main(["--setup", str(setup_file)])
    with pytest.raises(SystemExit, match="is missing"):
        clt.main(["--setup", str(tmp_path / "absent.json")])
