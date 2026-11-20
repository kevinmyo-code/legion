"""The dark switch for head-unit sign-in (T3b). Every route in `headunit_signin.urls` answers only
when `LEGION_HEADUNIT_SIGNIN=on` is in the engine's environment; unset (the default) each one is a
404 before authentication, so nothing is read or written, and a head-unit token is refused on every
route as an unknown token without a database query (`headunit_signin.authentication`).

Not in `deploy/cloudrun/_common.py`'s pass-through list, so no deploy can switch it on by a `.env`
line; turning it on is its own step.
"""

from __future__ import annotations

import os

SIGNIN_ENV = "LEGION_HEADUNIT_SIGNIN"


def signin_enabled() -> bool:
    return os.environ.get(SIGNIN_ENV, "").strip().lower() == "on"
