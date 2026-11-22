"""Which of the model's sentences are said, held or dropped (engine-brain switch plan, slice E0).

Lifted unchanged from the web door's turn (`assistant/cascade.py`, `_answer`, ADR 0102), which
asks it once per sentence and keeps every effect (the counters, speech, the events) itself; the
agent turn picks it up in slice E3. Three checks in a fixed order, as the web door has them:

- **in the answer** (`in_answer`): the runtime's no-answer line is held back; a first sentence that
  is only filler is dropped (`speech_rules.is_filler`); money after code spoke a money line
  (`has_money`) or a decimal figure no money tool returned (`states_unbacked_figure`) is dropped;
  so is an unbacked torque figure (`states_unbacked_torque`); past the door's cap nothing more is
  said; a claim of a change no write backs yet (`guards.claims_unbacked_outcome`) is HELD until the
  turn ends.
- **in the ask-again round** (`in_ask_again`, `agents/legion/ask_again.py`): the same, less the
  no-answer line and the cap; filler is dropped wherever it stands, and money after a code money
  line is dropped without being counted.
- **for a held claim at the end of the turn** (`held`): its figures are checked now, like every
  sentence's (a claim may be held before the money tool runs); then it is said if a write backs it
  by now, else it is unbacked and the closing line speaks for it (`agents/legion/closing.py`).

Every input that grows while the turn runs (the calls, so the backed figures and the actions) is a
callable the door hands in, asked at the moment the web door asked it, so nothing is snapshotted.
"""

from __future__ import annotations

from collections.abc import Callable

from agents.legion.guards import claims_unbacked_outcome
from agents.legion.speech_rules import (
    TorqueGuard,
    has_money,
    is_filler,
    states_unbacked_figure,
    states_unbacked_torque,
)
from agents.runtime.loop import NO_ANSWER_WORDS

# What to do with a sentence. The door counts, holds or says; `SKIP` is dropped uncounted.
SAY = "say"
SKIP = "skip"
NO_ANSWER = "no_answer"
FILLER = "filler"
MONEY = "money"
TORQUE = "torque"
OVER_CAP = "over_cap"
HOLD = "hold"
UNBACKED = "unbacked"


class SentenceGate:
    """One turn's sentence checks. `backed` answers the money figures this turn's tools returned
    (`speech_rules.backed_cents`, None when no money tool ran), `torque` the torque guard
    (`speech_rules.backed_torque`, None when it is off), `actions` the turn's calls as the
    honesty check reads them (name, writes, ok)."""

    def __init__(
        self,
        backed: Callable[[], set[int] | None],
        torque: Callable[[], TorqueGuard | None],
        actions: Callable[[], list[dict]],
    ):
        self._backed = backed
        self._torque = torque
        self._actions = actions

    def torque_unbacked(self, text: str) -> bool:
        return states_unbacked_torque(text, self._torque())

    def honest(self, text: str) -> bool:
        """False for a sentence that claims a change when no write has gone through in this turn
        yet (`agents.legion.guards.claims_unbacked_outcome`, the runtime's own check; CLAUDE.md
        section 7). Such a sentence is held and decided at the end of the turn. Nothing else is
        rewritten from the model's words (findings 4 and 10): when in doubt its sentence stays."""
        return not claims_unbacked_outcome(text, self._actions())

    def in_answer(self, text: str, *, sentences: int, verbatim_said: bool, max_spoken: int) -> str:
        """A sentence of the answer, untagged; `sentences` said so far this turn."""
        if not text:
            return SKIP
        if text == NO_ANSWER_WORDS:
            # The runtime's line for a turn with no words. Held: after tools, the turn is
            # asked once more; else the phone's own line is said (spec 31).
            return NO_ANSWER
        if sentences == 0 and is_filler(text):
            return FILLER
        if (verbatim_said and has_money(text)) or states_unbacked_figure(text, self._backed()):
            return MONEY
        if self.torque_unbacked(text):
            return TORQUE
        if sentences >= max_spoken:
            return OVER_CAP
        if not self.honest(text):
            return HOLD
        return SAY

    def in_ask_again(self, text: str, *, verbatim_said: bool) -> str:
        """A sentence of the ask-again round, untagged."""
        if not text or is_filler(text) or (verbatim_said and has_money(text)):
            return SKIP
        if states_unbacked_figure(text, self._backed()):
            return MONEY
        if self.torque_unbacked(text):
            return TORQUE
        if not self.honest(text):
            return HOLD
        return SAY

    def held(self, text: str) -> str:
        """A claim held earlier in the turn, decided at its end: `MONEY` and `TORQUE` are unbacked
        too (the closing line about the change is said in its place, round 2, N1)."""
        if states_unbacked_figure(text, self._backed()):
            return MONEY
        if self.torque_unbacked(text):
            return TORQUE
        if self.honest(text):
            return SAY
        return UNBACKED
