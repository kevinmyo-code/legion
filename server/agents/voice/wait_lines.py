"""Chained holding lines on a long wait, the phone's rule for every voice door (Kevin, 2026-11-24:
Alfred and the guide behave the same in every voice behavior, only the personality differs, and the
phone is the default to match).

The phone's own copy is `app/.../service/cascade/FillerTiming.kt` and `WaitFillers.kt`; this module
is the same rule in Python and `test_assistant_wait_lines.py` reads those two files so the numbers
and the words cannot drift apart.

What a turn says, measured from the moment the door has the person's words (the phone measures from
its own end-of-turn decision, which it owns; a browser door's first moment is after upload and
speech-to-text, so the acknowledgement is later there by that much and parity stops at the order,
not the clock):

| What | When |
|---|---|
| the acknowledgement (`fillers.ACK`) | at once |
| the task line (`fillers.FILLERS`) | its tool's rule, never inside `SPACING_MS` of the last clip |
| the first wait line | `FIRST_GAP_MS` after the last filler of any kind ended |
| each further wait line | `NEXT_GAP_MS` after the one before ended |
| cap, all kinds | `MAX_PER_TURN` a turn |

Neutral holding lines only: each promises nothing and claims nothing ("Still on it.", never "Almost
done."), so none of them can state an outcome the turn did not observe (CLAUDE.md section 7). No
line twice in one turn, and the task line's own words count as said.

Pure: no clock, no audio, no request. A door passes the clock in and keeps the audio itself.
"""

from __future__ import annotations

from dataclasses import dataclass, field

# ---- the phone's `FillerTiming.kt`, value for value -------------------------------------------

# The first wait line: this long after the last filler of any kind ended.
FIRST_GAP_MS = 1_500
# Each further wait line: this long after the one before it ended.
NEXT_GAP_MS = 2_500
# Never two clips back to back: a task line waits this long after the acknowledgement's end.
SPACING_MS = 400
# Fillers of every kind in one turn (acknowledgement, task line, wait lines): a hard cap.
MAX_PER_TURN = 6

# ---- the phone's `WaitFillers.kt` --------------------------------------------------------------

# "Working on it." is also `fillers.FALLBACK`'s first line, so it is warmed already and the set
# costs four new renders per voice, not five.
LINES: tuple[str, ...] = (
    "Still on it.",
    "Still checking.",
    "One moment more.",
    "Bear with me.",
    "Working on it.",
)

# A wait line's measured clip must be inside this, or another line is tried. The door cannot recall
# a clip it has pushed, so a line longer than the ceiling could still be playing when the answer is
# ready; the phone's `WaitFillers.MAX_CLIP_MS` is the same number for the same reason.
MIN_CLIP_MS = 200
MAX_CLIP_MS = 1_300


@dataclass
class TurnFillers:
    """One turn's filler state: what has been said, and when the last clip would have finished.

    `last_end` is on the door's own monotonic clock, in seconds. A turn with no filler yet sets it
    to the moment the door had the words, so the first wait line falls `FIRST_GAP_MS` after that.
    """

    last_end: float
    said: int = 0
    wait_said: int = 0
    words: set[str] = field(default_factory=set)

    def note(self, line: str, clip_ms: int, now: float, *, wait: bool) -> None:
        """A line was pushed now: it counts, and the next gap runs from the end of its audio."""
        self.said += 1
        if wait:
            self.wait_said += 1
        self.words.add(line)
        self.last_end = now + clip_ms / 1000

    @property
    def capped(self) -> bool:
        return self.said >= MAX_PER_TURN

    def lead_ms(self, now: float) -> int:
        """Silence to put in front of the next clip so it is never back to back with the last one
        (the phone's `SPACING_MS`, 400 ms after the acknowledgement's end).

        The phone holds the next line back on a timer. A door that streams audio has no timer at
        every moment it may speak (a tool's start has none), so it leads the clip with that much
        silence instead: the same distance between the two clips, and nothing waits on a clock.
        """
        if self.said == 0:
            return 0
        return max(0, round((self.last_end + SPACING_MS / 1000 - now) * 1000))

    def due_at(self) -> float | None:
        """When the next wait line is due, or None once the cap is reached or every line was said.

        The phone's `WaitFillers.nextAtMs`: `FIRST_GAP_MS` after `last_end` for the first wait
        line, `NEXT_GAP_MS` for any later one.
        """
        if self.capped or self.wait_said >= len(LINES):
            return None
        gap = FIRST_GAP_MS if self.wait_said == 0 else NEXT_GAP_MS
        return self.last_end + gap / 1000

    def due_now(self, now: float) -> bool:
        """The phone's `WaitFillers.dueNow`, without its play lock: the door holds one clip at a
        time by construction (it pushes one and returns to the loop)."""
        due = self.due_at()
        return due is not None and now >= due

    def ready(self, in_hand) -> tuple[str, ...]:
        """The wait lines that may be said now: none said this turn (the task line's words
        included), and audio in hand. `in_hand(line)` answers for this turn's voice."""
        return tuple(line for line in LINES if line not in self.words and in_hand(line))


def clip_fits(ms: int) -> bool:
    return MIN_CLIP_MS <= ms <= MAX_CLIP_MS


def timeline(last_end: float, said: int, answer_at: float, clip_s: float) -> list[float]:
    """When each wait line would start in a turn whose answer is ready at `answer_at`, each line
    `clip_s` long, after `said` fillers that ended at `last_end`. The same rule the door runs, as a
    timeline a test and a reader can check (the phone's `WaitFillers.timeline`)."""
    state = TurnFillers(last_end=last_end, said=said)
    starts: list[float] = []
    while True:
        due = state.due_at()
        if due is None or due >= answer_at:
            return starts
        starts.append(due)
        state.note(LINES[state.wait_said], int(clip_s * 1000), due, wait=True)
