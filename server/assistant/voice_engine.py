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

Neither reaches Cloud Run until its name is added to `deploy/cloudrun/_common.py`'s pass-through
list; until then the code default is what runs. Any other value is ignored (the default holds),
so a typo never switches a role to an engine nobody chose.

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


def text_model() -> str:
    return os.environ.get(TEXT_MODEL_ENV, "").strip() or TEXT_MODEL_DEFAULT
