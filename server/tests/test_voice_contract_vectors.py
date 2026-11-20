"""The voice contract's shared test vectors are well formed (ADR 0104).

Shape only: every client loads `server/contracts/voice/v1/*.json` in its own tests, and a malformed
file would fail there, far from here. No database and no Django model is touched.
"""

from __future__ import annotations

import json
import re
from pathlib import Path

import pytest

VECTORS = Path(__file__).resolve().parent.parent / "contracts" / "voice" / "v1"
FILES = ("eot.json", "backchannel.json", "owed.json")

EOT_OPS = {"windows", "quiet_ms", "result", "check_failed", "force_start", "quiet_answering"}
EOT_EXPECT = {
    "types", "contains", "excludes", "count", "check_silence_ms", "start_onset", "end",
    "in_activity",
}
EOT_END_KEYS = {
    "reason", "silence_ms", "silence_ms_min", "silence_ms_lt", "silence_ms_gt", "scores",
    "longest_silence_ms", "longest_silence_ms_min",
}
CLIENTS = {"phone", "headunit", "web"}
SERVER = Path(__file__).resolve().parent.parent
EOT_POLICY_TS = SERVER / "frontend" / "src" / "assistant" / "turn" / "eot-policy.ts"
# eot.json constant -> the web policy's exported name (the phone's twin is not on this branch).
WEB_CONSTANTS = {
    "window_ms": "WINDOW_MS", "start_prob": "START_PROB", "end_prob": "END_PROB",
    "confirm_ms": "CONFIRM_MS", "gate_ms": "GATE_MS", "base_ms": "BASE_MS",
    "finished_ms": "WEB_FINISHED_MS", "cap_ms": "CAP_MS", "threshold": "THRESHOLD",
    "early_ms": "EARLY_MS",
}
EOT_REASONS = {"base", "finished", "extended", "cap", "fallback"}
EOT_ACTIONS = {"start", "check", "early", "end", "resumed"}
SKIPS = ["not_his", "quiet", "half_duplex", "over_reply", "answer_to_ask", "short", "tic"]
OWED_OPS = {"owe", "due", "pay_due", "pay", "write_down"}


def _load(name: str) -> dict:
    return json.loads((VECTORS / name).read_text(encoding="utf-8"))


@pytest.mark.parametrize("name", FILES)
def test_every_file_names_its_version_kind_and_sources(name):
    data = _load(name)
    assert data["contract_version"] == 1
    assert data["kind"] == name.removesuffix(".json")
    assert data["sources"] and all(isinstance(s, str) for s in data["sources"])


def test_eot_cases_use_only_known_ops_and_expectations():
    data = _load("eot.json")
    assert data["cases"]
    for case in data["cases"]:
        assert case["name"] and case["source"]
        assert isinstance(case["policy"]["smart"], bool)
        assert case["policy"]["finished_ms"] in (None, data["constants"]["finished_ms"])
        assert case["setup"] in ("speaking", "none")
        assert set(case.get("clients", CLIENTS)) <= CLIENTS, case["name"]
        for step in case["steps"]:
            assert step["op"] in EOT_OPS, (case["name"], step)
            expect = step.get("expect", {})
            assert set(expect) <= EOT_EXPECT, (case["name"], expect)
            named = expect.get("types", []) + expect.get("excludes", [])
            named += [expect["contains"]] if "contains" in expect else []
            for t in named + list(expect.get("count", {})):
                assert t in EOT_ACTIONS, (case["name"], t)
            if "end" in expect:
                assert set(expect["end"]) <= EOT_END_KEYS, (case["name"], expect["end"])
                assert expect["end"].get("reason", "base") in EOT_REASONS


def test_eot_constants_match_the_web_policy():
    source = EOT_POLICY_TS.read_text(encoding="utf-8")
    constants = _load("eot.json")["constants"]
    for key, name in WEB_CONSTANTS.items():
        m = re.search(rf"^export const {name} = ([0-9_.]+)$", source, re.MULTILINE)
        assert m, name
        assert float(m.group(1).replace("_", "")) == constants[key], (key, m.group(1))


def test_backchannel_cases_expect_play_or_a_known_skip():
    data = _load("backchannel.json")
    assert data["skip_order"] == SKIPS
    defaults = data["decide"]["defaults"]
    for case in data["decide"]["cases"]:
        assert set(case["input"]) <= set(defaults), case["name"]
        assert case["expect"] == "play" or case["expect"] in SKIPS, case["name"]
    for section in ("clip_fits", "still_due", "spacing_wait_ms", "pick"):
        assert data[section]["cases"], section


def test_owed_cases_name_only_facts_they_declare():
    data = _load("owed.json")
    for case in data["cases"]:
        facts = case["facts"]
        for fact in facts.values():
            assert {"tool", "action", "result", "ok", "confirmed"} <= set(fact)
        for step in case["steps"]:
            assert step["op"] in OWED_OPS, (case["name"], step)
            for key in ("turn", "played", "ids"):
                assert set(step.get(key, [])) <= set(facts), (case["name"], step)
            if step["op"] == "write_down":
                assert "expect" in step, (case["name"], step)
            if "expect" in step:
                assert step["expect"] is None or isinstance(step["expect"], str)
