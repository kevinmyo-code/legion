"""The dark switch for the garage assistant (option B, slice 1).

`show_part` is declared, run, and the torque guard checks spoken sentences, only when
`LEGION_JEEP_ASSISTANT=on` is in the engine's environment. Unset (the default, and every deploy
that does not name it) every door behaves exactly as before: the tool is in no list, no
declaration and no prompt, a call to it is refused as unknown, and no sentence is checked.

Read at call time, never at import, so turning it on or off needs no code change. Deliberately NOT
in `deploy/cloudrun/flags.yaml` or `_common.py`'s pass-through list, the `LEGION_AGENTS_TURN`
precedent: no deploy of this slice can switch it on. Turning it on is a reviewed commit that adds
the name there, on Kevin's word.
"""

from __future__ import annotations

import os

ENV = "LEGION_JEEP_ASSISTANT"


def enabled() -> bool:
    return os.environ.get(ENV, "").strip().lower() == "on"
