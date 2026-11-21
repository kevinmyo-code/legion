"""The dark switch for the factory board snapshot. Every route in `board.urls` answers only when
`LEGION_BOARD=on` is in the engine's environment; unset (the default) each one is a 404 before
authentication, so nothing is read, written or queried, and the `board_status` tool is left out of
every tool list (`engine_mcp.tools.dark_tools`).

Not in `deploy/cloudrun/_common.py`'s pass-through list, so no deploy can switch it on by a `.env`
line; turning it on is its own step.
"""

from __future__ import annotations

import os

BOARD_ENV = "LEGION_BOARD"


def board_enabled() -> bool:
    return os.environ.get(BOARD_ENV, "").strip().lower() == "on"
