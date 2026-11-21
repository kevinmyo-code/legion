"""The lines code says in a voice turn, never the model (engine-brain switch plan, slice E0).

Lifted unchanged from the web door (`assistant/cascade.py`, ADR 0102), which imports them from
here, so both doors say the same words: the miss, the failure leads, the spoken failures, and the
closing lines decided from what happened (ADR 0090's wait, a claimed change that did or did not go
through). The rules that choose between them are `agents/legion/speech_rules.py` and the web door's
own turn.
"""

from __future__ import annotations

# The phone's failure leads (`TextChatEngine.TextFailure`, spec 31), less "Check it in Setup".
NOT_ANSWERED_MODEL = "Not answered: Gemini returned an error for that message."
NOT_ANSWERED_TIMEOUT = "Not answered: that took too long and was stopped."
NOT_ANSWERED_BUDGET = "Not answered: this turn reached its spending limit."
NO_TEXT = "No written reply came back for that."
NOTHING_CHANGED = " Nothing was changed."
NOTHING_HEARD = "Nothing heard, so nothing was asked."
STT_FAILED = "Not answered: the words could not be made out just now."
MISSED_LINE = "Didn't catch that, say it again?"
# A turn that broke is said too, from memory, when its line is in hand (round 2 turn review 7):
# the voice cannot be asked for new speech once the turn has failed.
FAILED_SPOKEN = "Sorry, something broke on my end. Nothing was changed."
FAILED_AFTER_WRITE_SPOKEN = (
    "Sorry, something broke on my end, but the change you asked for went through."
)
SPOKEN_FAILURES = (MISSED_LINE, FAILED_SPOKEN, FAILED_AFTER_WRITE_SPOKEN)

# ADR 0090's reason in code, spoken whenever a turn that read outside text does not make a change
# it was asked for (measured 2026-11-17: the model gave a false reason, "that only works in the
# phone app", or claimed the change). The person's next message may make it.
READ_THROUGH_WAIT = (
    "I haven't changed anything: this turn read text from the web, so changes wait for your next "
    "message. Ask me again and I'll do it."
)
# The carry (the owner's ruling, 2026-11-21, ADR 0090 amended): a change refused because the turn
# BEFORE this one read outside text. True about which turn read it; the same promise.
READ_THROUGH_WAIT_CARRIED = (
    "I haven't changed anything: I read text from the web just before this, so changes wait for "
    "your next message. Ask me again and I'll do it."
)
# Said after a claimed change that no write backed and none went through by the end of the turn.
CHANGE_NOT_MADE = "Nothing was changed: that change did not go through, so ask me again for it."
# The same, when a write did go through in this turn (a claim made before its own call).
CHANGE_MADE = "That change went through."
# ADR 0090 after a write in this turn was refused because the turn read outside text, but an
# earlier write in the same turn went through (finding 3): what happened, not "nothing".
READ_THROUGH_WAIT_AFTER_WRITE = (
    "The first change went through. The next one waits for your next message, because this turn "
    "read text from the web. Ask me again and I'll do it."
)
TOO_MANY_TOOLS = (
    "Nothing was read or written: this turn already ran as many tool calls as one turn may."
)
