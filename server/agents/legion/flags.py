"""The dark switch (engine-brain ticket 02). The turn endpoint answers only when
`LEGION_AGENTS_TURN=on` is in the engine's environment; unset (the default, and every deploy that
does not name it) it refuses before authentication, so nothing reaches the model. A recruiter is
refused whatever the switch says (`household.recruiter_routes`, and the route in `turn.py`).

Passed through by `deploy/cloudrun/_common.py` (`OPTIONAL_PLAIN_ENV_VARS`) only when set in
`deploy/cloudrun/.env`; the ship script refuses if it is set there in this stage.
"""

from __future__ import annotations

import os

TURN_ENV = "LEGION_AGENTS_TURN"


def turn_enabled() -> bool:
    return os.environ.get(TURN_ENV, "").strip().lower() == "on"
