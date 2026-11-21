"""Ask-again (the phone's `FillerTurn`, spec 23; engine-brain switch plan, slice E0): a turn whose
tools ran but whose answer was only filler, or nothing, is asked once more, tool-free, with
`FILLER_NOTE`; whatever it says is the answer.

Lifted unchanged from the web door (`assistant/cascade.py`, ADR 0102), which calls it; the agent
turn picks it up in slice E3. The door passes what is its own (its declarations, its round
timeout, its list of metered rounds), so nothing here reads a request or a database. Which of the
round's sentences are said is `SentenceGate.in_ask_again` (`agents/legion/sentence_gate.py`).
"""

from __future__ import annotations

from agents.legion.speech_rules import FILLER_NOTE
from agents.runtime.loop import SentenceSplitter
from agents.runtime.model import ModelFailure, ModelRequest, TextDelta, Usage


def asks_again(
    *,
    failed: bool,
    status: str | None,
    sentences: int,
    filler_only: bool,
    tools_ran: bool,
    ending: bool,
) -> bool:
    """Whether the turn is asked once more: it ended `done` with no failure line, said no sentence,
    dropped filler or got no words at all (`filler_only`), ran a tool, and is not closing the
    conversation (measured 2026-11-17: a write refused after a web search left the last round
    empty, and the person heard neither the search's answer nor the refusal)."""
    return (
        not failed
        and status == "done"
        and sentences == 0
        and filler_only
        and tools_ran
        and not ending
    )


def ask_again(model, model_name, system, messages, declarations, timeout_s, rounds) -> list[str]:
    """One tool-free round after a filler-only answer (spec 23). [] on any failure."""
    req = ModelRequest(
        model=model_name,
        system=system,
        contents=[*messages, {"role": "user", "parts": [{"text": FILLER_NOTE}]}],
        declarations=declarations,
        allow_tools=False,
        timeout_s=timeout_s,
    )
    splitter = SentenceSplitter()
    out: list[str] = []
    usage = Usage()
    try:
        for item in model.stream(req):
            if isinstance(item, TextDelta):
                out.extend(splitter.feed(item.text))
            elif isinstance(item, Usage):
                usage = item
    except ModelFailure:
        return []
    finally:
        rounds.append(
            {
                "prompt_tokens": usage.prompt_tokens,
                "cached_tokens": usage.cached_tokens,
                "output_tokens": usage.output_tokens,
            }
        )
    out.extend(splitter.flush())
    return out
