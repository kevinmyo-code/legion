"""The demo's one-minute tour (Kevin, 2026-11-17: "the voice the center of attention"): a few beats
the assistant drives, each one visual picked by code and one short sentence spoken. Lighter since
2026-11-22 (Kevin: "its too dense and verbose and too much"): four beats, one sentence each, and
the diagram the one whose caption opens with a short sentence.

Every beat runs its tool through the web tool door (`assistant.cascade.WebDoor`): the recruiter's
fence, the audit row and the conversation record are the door's own, as for any tool call. The
words are written by code, never by a model:
- facts about Kevin are his profile's first section in his own order (`demo.profile`, the same
  third-person sentences `about_kevin` reads aloud), from the content the engine holds for the
  visit's code, loaded from his private folder; none is written here, in a public repo. Not the
  page's `hero` lead: that is written in the first person for the page, and spoken by the tour it
  said "I" for Kevin (Kevin, 2026-11-18). No pronoun is ever swapped;
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
# read of the made-up household's records, and the resume is the one loaded for this visit.
LIVE_LINE = "That's a live lookup on this made-up household's car."
RESUME_LINE = "And that's his one-page resume."
# The tour's lighter lines carry an expression tag (`assistant/speech_tags.py`): spoken as a sound,
# never shown, stored or checked.
END_LINE = "That's the tour. <short pause> Ask me anything."
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
    # The narration states facts about him (his own lead): history replays a marker for it.
    about_him: bool = False


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


def _who(content: dict) -> str:
    """His profile's first section's first sentence, as written; "" without his order (then nothing
    leads by accident, as `demo.profile.speak` asks which one)."""
    from demo.profile import PROFILE_ORDER, sections_of

    order = content.get(PROFILE_ORDER)
    sections = sections_of(content.get("profile"), order) if order else []
    return sections[0].sentences[0] if sections and sections[0].sentences else ""


def beats_for(user) -> list[Beat]:
    """The tour for this visitor, in order, with every beat that has nothing to show left out."""
    content = _content(user)
    beats: list[Beat] = []
    who = _who(content)
    if who:
        beats.append(Beat("who", who, about_him=True))
    # The context view first: its caption opens with one short sentence ("Three kinds of people reach
    # LEGION through four clients."); the components view's runs to forty words.
    found = _diagram(user, "legion", ("context", "components", "containers"))
    if found:
        beats.append(
            Beat("legion", "", "show_diagram", {"project": found[0], "view": found[1]}, True)
        )
    beats.append(Beat("live", LIVE_LINE, "next_service", {}))
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
