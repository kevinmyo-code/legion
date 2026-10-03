"""Whether this engine can send Web Push at all.

Push needs three settings from the environment (`deploy/.env.example`):
`VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY` and `VAPID_SUBJECT`. **Absent, push
is off and says so in words** (spec D7): a stranger's clone works without
them, the notifications page shows `PUSH_OFF`, subscribing is refused with
it, and `push_dispatch` prints it and sends nothing. Never a crash, never a
silent no-op.
"""

from __future__ import annotations

import os
from dataclasses import dataclass

from push.copy import PUSH_OFF

ENV_PUBLIC = "VAPID_PUBLIC_KEY"
ENV_PRIVATE = "VAPID_PRIVATE_KEY"
ENV_SUBJECT = "VAPID_SUBJECT"

__all__ = ["PUSH_OFF", "VapidConfig", "vapid_config"]


@dataclass(frozen=True)
class VapidConfig:
    public_key: str
    private_key: str
    subject: str


def vapid_config() -> VapidConfig | None:
    """The three keys, or None when any is missing or blank."""
    values = [os.environ.get(name, "").strip() for name in (ENV_PUBLIC, ENV_PRIVATE, ENV_SUBJECT)]
    if not all(values):
        return None
    return VapidConfig(*values)
