"""The dark switch for the factory board snapshot. Every route in `board.urls` answers only when
`LEGION_BOARD=on` is in the engine's environment; unset (the default) each one is a 404 before
authentication, so nothing is read, written or queried, and the board's tools (`board_status`,
`add_backlog_idea`) are left out of every tool list (`engine_mcp.tools.dark_tools`).

Passed through by `deploy/cloudrun/_common.py` (`OPTIONAL_PLAIN_ENV_VARS`) and pinned unset in
`deploy/cloudrun/flags.yaml` (backlog intake 1122: Kevin's ruling, 2026-11-22, is to switch it on
once the laptop's push works). Switching it on is its own reviewed change: `"on"` in flags.yaml for
`deploy/ship.py`, or `LEGION_BOARD=on` in the deploy `.env` with the ship script no longer listing
it among the switches that must stay absent. On, it also opens backlog intake
(`add_backlog_idea`, `/api/board/ideas`) and its one scoring model call per idea.
"""

from __future__ import annotations

import os

BOARD_ENV = "LEGION_BOARD"


def board_enabled() -> bool:
    return os.environ.get(BOARD_ENV, "").strip().lower() == "on"
