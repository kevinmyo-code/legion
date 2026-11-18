"""A real-model replay of "Tell me about Kevin." (2026-11-18, facts about Kevin are never reworded).

NOT part of the suite (pytest collects only `test_*.py`); run by name, with a key:

    LEGION_GEMINI_KEY_FILE=<path> REALRUN_OUT=<dir> [REALRUN_CONTENT_DIR=<folder>] \\
        pt.sh <worktree> <port> <db> tests/realrun_profile_verbatim.py -s

A recruiter visitor (through `/api/demo/enter`) asks, as typed turns each in a new conversation,
"Tell me about Kevin." `REALRUN_KEVIN_RUNS` times (default 10) and "Is Kevin authorised to work
in the US?" `REALRUN_STATUS_RUNS` times (default 5). For every reply it sorts each shown sentence
into: a sentence of the profile, one of code's fixed lines (not shared, the offers, "There's more
on ..."), or a sentence the MODEL wrote. The target is no model-written sentence in any turn
where `about_kevin` ran, and none about Kevin in any turn. One JSON line per turn goes to
`REALRUN_OUT/profile-turns.jsonl`.

The profile is the invented one in `tests/test_profile_verbatim.py` unless `REALRUN_CONTENT_DIR`
names a content folder to load (Kevin's private one, on his machine only: its sentences then
appear in the output file, which stays outside the repo). Costs are ESTIMATES (`_cost`); the run
skips once `REALRUN_BUDGET_USD` (default 0.30) is passed.
"""

from __future__ import annotations

import json
import os
import re
from io import StringIO

import pytest
from django.core.management import call_command

from demo import profile
from tests.realrun_web_cascade import OUT, _visitor, real  # noqa: F401 - fixture

pytestmark = [pytest.mark.django_db, pytest.mark.live_model]

KEVIN_RUNS = int(os.environ.get("REALRUN_KEVIN_RUNS", "10"))
STATUS_RUNS = int(os.environ.get("REALRUN_STATUS_RUNS", "5"))
CONTENT_DIR = os.environ.get("REALRUN_CONTENT_DIR", "")
MORE = re.compile(
    r"^There's more on .+(; just ask\.| than I can read out here; ask him directly\.)$"
)


def _load(tmp_path) -> dict:
    from demo.models import RecruiterContent
    from tests.test_profile_verbatim import _content
    from tests.test_recruiter_content import write_folder

    folder = CONTENT_DIR or str(write_folder(tmp_path / "c", content=_content()))
    call_command("load_recruiter_content", folder, stdout=StringIO())
    return RecruiterContent.current().content.get("profile") or {}


def _kind(sentence: str, profile_sentences: set[str]) -> str:
    if sentence in profile_sentences:
        return "profile"
    if sentence == profile.NOT_SHARED_LINE or sentence in profile.OFFERS.values():
        return "fixed"
    if MORE.match(sentence):
        return "fixed"
    return "model"


def test_realrun_profile_verbatim(real, tmp_path):  # noqa: F811
    raw = _load(tmp_path)
    sentences = {s for section in profile.sections_of(raw) for s in section.sentences}
    client = _visitor()
    asks = [("kevin", "Tell me about Kevin.")] * KEVIN_RUNS + [
        ("status", "Is Kevin authorised to work in the US?")
    ] * STATUS_RUNS
    summary = {
        "turns": 0,
        "model_sentences_in_about_kevin_turns": 0,
        "turns_with_model_sentences": 0,
    }
    with (OUT / "profile-turns.jsonl").open("a", encoding="utf-8") as out:
        for run, (case, words) in enumerate(asks):
            row, _cid = real.turn(client, "recruiter", case, run, 0, text=words)
            said = re.split(r"(?<=[.!?])\s+(?=\S)", row.get("said") or "")
            # The shown sentences one by one, as the page got them.
            shown = row.get("sentences") or [s for s in said if s]
            kinds = [(_kind(s, sentences), s) for s in shown]
            ran = any(name == "about_kevin" for name, _ok, _text in row.get("tools") or [])
            model = [s for kind, s in kinds if kind == "model"]
            summary["turns"] += 1
            if ran:
                summary["model_sentences_in_about_kevin_turns"] += len(model)
            if model:
                summary["turns_with_model_sentences"] += 1
            out.write(
                json.dumps(
                    {
                        "case": case,
                        "run": run,
                        "about_kevin": ran,
                        "kinds": [kind for kind, _ in kinds],
                        "model_sentences": model,
                        "cost_usd": row.get("cost_usd"),
                        "spent_usd": row.get("spent_usd"),
                    },
                    ensure_ascii=False,
                )
                + "\n"
            )
    print("PROFILE SUMMARY " + json.dumps(summary) + f" spent ${real.spent:.4f}")
