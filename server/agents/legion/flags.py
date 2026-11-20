"""The dark switch (engine-brain ticket 02). The turn endpoint answers only when
`LEGION_AGENTS_TURN=on` is in the engine's environment; unset (the default, and every deploy that
does not name it) it refuses a member with 404 right after authentication, so nothing reaches
the model. A recruiter is
refused whatever the switch says (`household.recruiter_routes`, and the route in `turn.py`).

Deliberately NOT yet in `deploy/cloudrun/_common.py`'s pass-through list, so no deploy of this
stage can switch it on: the deploy scripts stay frozen, and a `.env` line would reach nothing.
Turning it on is the switch's stage (ticket 04), which adds the name there with the per-member flag.
"""

from __future__ import annotations

import os

TURN_ENV = "LEGION_AGENTS_TURN"


def turn_enabled() -> bool:
    return os.environ.get(TURN_ENV, "").strip().lower() == "on"


# The speech-to-text hedge on the agents door (`agents/voice/hedge.py`): a second identical request
# beside a slow first. Off unless `on`: it can pay for one utterance's audio twice.
STT_HEDGE_ENV = "LEGION_AGENTS_STT_HEDGE"


def stt_hedge_enabled() -> bool:
    return os.environ.get(STT_HEDGE_ENV, "").strip().lower() == "on"

# Streaming speech-to-text on the agents door (`agents/voice/streaming.py`): one long request per
# utterance, its audio streamed in while he speaks. Off unless `on`; on top of LEGION_AGENTS_TURN.
STREAM_STT_ENV = "LEGION_AGENTS_STREAM_STT"


def stream_stt_enabled() -> bool:
    return os.environ.get(STREAM_STT_ENV, "").strip().lower() == "on"
