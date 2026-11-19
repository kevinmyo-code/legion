"""Which voice engine a web assistant conversation runs on, decided by the server per role.

Kevin, 2026-11-17 ("recruiter demo > can it [go] to our current cloud pipeline too"), ADR 0102:
the web assistant has two engines.

- `live`: the browser talks to Gemini Live with a locked ephemeral token the engine mints
  (`assistant/live.py`, ADR 0056). Unchanged.
- `cascade`: the phone's cloud voice shape on the engine. The browser records one utterance and
  posts it to `POST /api/assistant/turn`; the engine transcribes it, runs the text model's tool
  loop over the web assistant's own tool door, speaks the reply and streams it back
  (`assistant/cascade.py`). The browser never holds a token.

**Defaults are code, on purpose** (the deploy scripts pass only names they know, and this stage
adds none): `cascade` for every role (Kevin, 2026-11-17: *"same voice behavior across
everything"*), with Live kept whole behind the switch as the fallback for any role. An
environment override, for the day one is wanted, is one variable per role:

- `LEGION_WEB_VOICE` = `live` | `cascade`: household members.
- `LEGION_WEB_VOICE_RECRUITER` = `live` | `cascade`: recruiter demo visits.

Both are in `deploy/cloudrun/_common.py`'s optional pass-through list: set in `.env`, they reach
Cloud Run on the next deploy; unset, the code default runs. Any other value is ignored (the default
holds), so a typo never switches a role to an engine nobody chose.

The choice is enforced, not advised: `POST /api/assistant/session` mints no Live token for a
caller whose engine is `cascade` (409), and `POST /api/assistant/turn` runs no turn for a caller
whose engine is `live` (409). Rollback is one variable.
"""

from __future__ import annotations

import os

from household.models import is_recruiter

LIVE = "live"
CASCADE = "cascade"
ENGINES = (LIVE, CASCADE)

MEMBER_ENV = "LEGION_WEB_VOICE"
RECRUITER_ENV = "LEGION_WEB_VOICE_RECRUITER"

MEMBER_DEFAULT = CASCADE
RECRUITER_DEFAULT = CASCADE

# The text model the cascade runs its tool loop on, and the model that transcribes (the phone's
# `CloudSpeechWire.STT_MODEL` is its cascade text model too). `LEGION_WEB_TEXT_MODEL` overrides.
TEXT_MODEL_ENV = "LEGION_WEB_TEXT_MODEL"
TEXT_MODEL_DEFAULT = "gemini-3.5-flash-lite"


def _read(name: str, default: str) -> str:
    raw = os.environ.get(name, "").strip().lower()
    return raw if raw in ENGINES else default


def engine_for(user) -> str:
    """`live` or `cascade` for this signed-in caller."""
    if is_recruiter(user):
        return _read(RECRUITER_ENV, RECRUITER_DEFAULT)
    return _read(MEMBER_ENV, MEMBER_DEFAULT)


# The cascade's voice, per role, next to the engine choice (Kevin, 2026-11-17, after auditioning:
# then again: "i dont like that southern voice anymore. can we go back to puck? lively energetic
# and fast?" for the demo; then for Alfred on LEGION: "this is my choice Authoritative Advisor 3 /
# en-gb-advisor-3"). Members: en-gb-advisor-3; the demo and its tour: Puck. Plain: no style. The
# companion's stored voice is Live's (the fallback engine keeps it); the cascade speaks in these.
# `LEGION_WEB_TTS_VOICE` / `LEGION_WEB_TTS_VOICE_RECRUITER` override. A name Google refuses is
# spoken in `speech.FALLBACK_VOICE` (Puck) instead of going silent.
VOICE_ENV = "LEGION_WEB_TTS_VOICE"
RECRUITER_VOICE_ENV = "LEGION_WEB_TTS_VOICE_RECRUITER"
MEMBER_VOICE_DEFAULT = "en-gb-advisor-3"
RECRUITER_VOICE_DEFAULT = "Puck"


def voice_for(user) -> str:
    """The cascade's text-to-speech voice for this caller's role."""
    if is_recruiter(user):
        return os.environ.get(RECRUITER_VOICE_ENV, "").strip() or RECRUITER_VOICE_DEFAULT
    return os.environ.get(VOICE_ENV, "").strip() or MEMBER_VOICE_DEFAULT


# Early transcripts (`POST /api/assistant/transcribe`): OFF unless this is "on" (2026-11-17 review
# round 2). What must exist before it is switched on is in ADR 0103 ("Early transcripts").
EARLY_TRANSCRIPTS_ENV = "LEGION_WEB_EARLY_TRANSCRIPTS"


def early_transcripts_on() -> bool:
    return os.environ.get(EARLY_TRANSCRIPTS_ENV, "").strip().lower() == "on"


def text_model() -> str:
    return os.environ.get(TEXT_MODEL_ENV, "").strip() or TEXT_MODEL_DEFAULT


# PROTOTYPE (perf/try-faster-first-word fix 5), OFF unless this is "on": the utterance's audio goes
# straight into the model's first round, asked while speech-to-text still runs for the caption,
# the record and every check (`assistant/audio_round.py`). Ships only after its own review.
AUDIO_ROUND_ENV = "LEGION_WEB_AUDIO_FIRST_ROUND"


def audio_first_round_on() -> bool:
    return os.environ.get(AUDIO_ROUND_ENV, "").strip().lower() == "on"
