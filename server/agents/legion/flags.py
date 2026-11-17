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
