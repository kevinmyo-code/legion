"""The demo's one-minute tour (Kevin, 2026-11-17: "the voice the center of attention"): a few beats
the assistant drives, each one visual picked by code and one or two sentences spoken.

Every beat runs its tool through the web tool door (`assistant.cascade.WebDoor`): the recruiter's
fence, the audit row and the conversation record are the door's own, as for any tool call. The
words are written by code, never by a model:
- facts about Kevin come from the content the engine already holds for the visit's code (the
  `hero` lead in `demo.models.RecruiterContent`), loaded from his private folder; none is written
  here, in a public repo;
- a diagram beat says the first sentence of the diagram's own caption (`diagrams/catalogue.json`);
- the rest are fixed sentences about what is on the screen, which claim nothing about Kevin.

A beat with nothing to show (no content loaded, a diagram not available to this version) is
skipped, so the tour never says "here is" over an empty screen. The tour is interruptible at any
beat: the page stops playback and stops asking for beats; "skip" asks for the next one and "stop"
ends it (`is_skip`, `is_stop`).
"""

from __future__ import annotations

import re
from dataclasses import dataclass

from agents.runtime.tools import ToolCall

# Each line claims only what the demo as built shows (round 2 speech review T): the card is a live
# read of the made-up household's records, the resume is the one loaded for this visit, and the
# contact links are the stage's own (`components/demo/voice-stage.tsx`, from the loaded content).
LIVE_LINE = "That card is a live lookup on this made-up household's car records."
RESUME_LINE = "And that's his one-page resume."
# The tour's lighter lines carry an expression tag (`assistant/speech_tags.py`): spoken as a sound,
# never shown, stored or checked.
END_LINE = (
    "That's the tour. <short pause> Ask me anything else, or use the links below to get in touch."
)
STOPPED_LINE = "Okay, tour stopped. Ask me anything."
# The tour's own code lines: the only tour sentences that may carry a tag.
CODE_LINES = frozenset({LIVE_LINE, RESUME_LINE, END_LINE, STOPPED_LINE})

_SKIP = re.compile(
    r"^\W*(skip|next|go on|keep going|carry on|continue)(\s+(it|that|please))*\W*$", re.I
)
_STOP = re.compile(
    r"^\W*(stop|stop the tour|end the tour|enough|that's enough|no more)(\s+please)?\W*$", re.I
)


def is_skip(words: str) -> bool:
    return bool(_SKIP.match(words or ""))


def is_stop(words: str) -> bool:
    return bool(_STOP.match(words or ""))


def _first_sentences(text: str, n: int = 1) -> str:
    parts = re.split(r"(?<=[.!?])\s+", (text or "").strip())
    return " ".join(p for p in parts[:n] if p).strip()


@dataclass
class Beat:
    key: str
    narration: str
    tool: str | None = None
    args: dict | None = None
    # The tool's own text (a diagram's caption) may give the narration instead.
    from_caption: bool = False


def _content(user) -> dict:
    from demo.models import RecruiterContent

    row = RecruiterContent.for_user(user)
    return dict(row.content or {}) if row is not None else {}


def _diagram(user, project: str, prefer: tuple[str, ...]) -> tuple[str, str] | None:
    from diagrams.catalogue import available

    views = available(user, project)
    for view in prefer:
        if view in views:
            return project, view
    return (project, views[0]) if views else None


def beats_for(user) -> list[Beat]:
    """The tour for this visitor, in order, with every beat that has nothing to show left out."""
    content = _content(user)
    beats: list[Beat] = []
    lead = _first_sentences((content.get("hero") or {}).get("lead", ""), 2)
    if lead:
        beats.append(Beat("who", lead))
    found = _diagram(user, "legion", ("components", "context", "containers"))
    if found:
        beats.append(
            Beat("legion", "", "show_diagram", {"project": found[0], "view": found[1]}, True)
        )
    beats.append(Beat("live", LIVE_LINE, "next_service", {}))
    found = _diagram(user, "workflow", ("loop", "roles"))
    if found:
        beats.append(
            Beat("workflow", "", "show_diagram", {"project": found[0], "view": found[1]}, True)
        )
    beats.append(Beat("resume", RESUME_LINE, "show_resume", {}))
    return beats


def run_beat(door, beat: Beat) -> tuple[str, bool]:
    """Runs the beat's tool through the door and returns (what to say, whether the tool ran). A
    beat whose tool did not run says nothing over an empty screen: it returns ("", False)."""
    if beat.tool is None:
        return beat.narration, True
    door.call(None, None, ToolCall(f"tour-{beat.key}", beat.tool, dict(beat.args or {})), 8.0)
    done = door.calls[-1]
    if not done.ok:
        return "", False
    if beat.from_caption:
        caption = (done.card or {}).get("caption", "")
        return _first_sentences(caption, 1), True
    return beat.narration, True
