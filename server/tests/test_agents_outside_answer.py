"""What the next turn replays of a turn that read outside text (ADR 0090): a note that it was
answered and is done, never the search's words, its query or a bare placeholder. A bare
placeholder left the question looking unanswered, and the model kept going back to it. Made-up
place and figures."""

from __future__ import annotations

import json
import re
from pathlib import Path

from agents.legion import redact

ROOT = Path(__file__).resolve().parents[2]
ANSWERED = (
    "(Answered from a web search or other outside text. That answer was not kept. "
    "Done unless he asks again.)"
)
INSTRUCTION = (
    "A reply marked as answered from a web search or other outside text is finished: never go "
    "back to it on your own. If he asks about that topic again, look it up again; never answer "
    "it from memory. If he asks what you said there or where you read it, say answers read from "
    "outside (the web, a mail, the news brief) are not kept, and offer to read it or look it up "
    "again."
)


def _search_turn(*model_texts: str) -> list[dict]:
    return [
        {"role": "user", "parts": [{"text": "how much is a flat in Invented City"}]},
        {
            "role": "model",
            "parts": [
                {
                    "functionCall": {
                        "name": "web_search",
                        "args": {"query": "flat rent Invented City"},
                    }
                }
            ],
        },
        {
            "role": "user",
            "parts": [
                {
                    "functionResponse": {
                        "name": "web_search",
                        "response": {"message": "Invented City flats rent for 1,900 a month."},
                    }
                }
            ],
        },
        {"role": "model", "parts": [{"text": t} for t in model_texts]},
    ]


def test_the_reply_after_a_web_search_is_replayed_as_the_answered_note():
    stored = redact.history_for_storage(
        _search_turn("A flat there is about 1,900 a month."), outside={"web_search"}
    )
    assert stored[-1]["parts"] == [{"text": ANSWERED}]
    assert "1,900" not in json.dumps(stored)


def test_the_search_query_is_not_replayed():
    stored = redact.history_for_storage(_search_turn("About 1,900."), outside={"web_search"})
    assert "Invented City" not in json.dumps(stored[1:])
    call = stored[1]["parts"][0]["functionCall"]
    assert call["name"] == "web_search" and call["args"] == redact.OUTSIDE_ARGS


def test_a_reply_in_several_parts_leaves_one_note():
    stored = redact.history_for_storage(
        _search_turn("A flat there is about 1,900.", " Prices rose last year."),
        outside={"web_search"},
    )
    assert stored[-1]["parts"] == [{"text": ANSWERED}]


def test_the_phone_and_the_engine_use_the_same_note():
    rules = ROOT / "app/src/main/java/com/kevin/legion/service/cascade/CascadeRules.kt"
    block = rules.read_text(encoding="utf-8").split("const val OUTSIDE_ANSWER_NOTE =", 1)[1]
    block = block.split("\n\n", 1)[0]
    phone = "".join(re.findall(r'"([^"]*)"', block))
    assert phone == ANSWERED == redact.OUTSIDE_ANSWER_NOTE


def test_the_engine_prompt_carries_the_instruction_line():
    prompt = " ".join((ROOT / "server/agents/specs/generalist.md").read_text("utf-8").split())
    assert INSTRUCTION in prompt
