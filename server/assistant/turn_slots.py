"""How many cascade turns may run at once (blind review 2026-11-17, finding 1).

A cascade turn holds one server thread for its whole stream (speech-to-text, model rounds, speech),
and the engine has a small pool of threads across all its instances. So:

- **One turn in flight per caller.** A second turn from the same user (a visitor is its own user)
  while one runs is refused (409, `BUSY_SELF`); the page stops the first before it starts another.
- **One demo turn at a time per address** (round 2 finding 1): two visits from one address cannot
  hold every demo slot (429, `BUSY_ADDRESS`). The address is `legion.client_ip`'s.
- **A small cap on demo turns across the whole engine** (`LEGION_DEMO_TURN_SLOTS`; default half the
  threads of one instance, `LEGION_GUNICORN_WORKERS` x `LEGION_GUNICORN_THREADS` / 2, so 2 with the
  2 x 2 default), so members' turns are never starved by visitors (429, `BUSY_DEMO`).

Both are Postgres advisory locks held by the turn's own database connection: they hold across
gunicorn workers and Cloud Run instances (one database), and a crashed process releases them when
its connection drops. They are released when the stream closes (`release`, wired to the response's
close), whichever way it ends. Members' turns are not capped beyond one each.
"""

from __future__ import annotations

import hashlib
import logging
import os
from dataclasses import dataclass, field

from django.db import connection

from household.models import is_recruiter

log = logging.getLogger(__name__)

SLOTS_ENV = "LEGION_DEMO_TURN_SLOTS"
# Lock keys live in their own space: (namespace, key) pairs, the two-int form of advisory locks.
_NS_USER = 0x4C45  # "LE"
_NS_DEMO = 0x4744  # "GD"
_NS_ADDRESS = 0x4950  # "IP"

BUSY_SELF = (
    "Nothing was answered: your last question is still being answered. Wait for it or stop it, "
    "then ask again."
)
BUSY_DEMO = "The demo is busy right now. Nothing was asked; try again in a few seconds."
BUSY_ADDRESS = (
    "Another demo conversation from this network is still being answered. Nothing was asked; "
    "try again when it is done."
)


def _threads_per_instance() -> int:
    try:
        workers = int(os.environ.get("LEGION_GUNICORN_WORKERS", "").strip() or "2")
        threads = int(os.environ.get("LEGION_GUNICORN_THREADS", "").strip() or "2")
    except ValueError:
        return 4
    return max(workers * threads, 1)


def demo_slots() -> int:
    """Demo turns at once across the engine: `LEGION_DEMO_TURN_SLOTS`, or half the threads of ONE
    instance (Cloud Run may run just one), never all of them."""
    raw = os.environ.get(SLOTS_ENV, "").strip()
    default = max(1, _threads_per_instance() // 2)
    try:
        value = int(raw) if raw else default
    except ValueError:
        value = default
    return max(1, min(value, 16))


def _key(text: str) -> int:
    return int.from_bytes(hashlib.sha256(text.encode()).digest()[:4], "big", signed=True)


def _try(ns: int, key: int) -> bool:
    with connection.cursor() as cur:
        cur.execute("SELECT pg_try_advisory_lock(%s, %s)", [ns, key])
        return bool(cur.fetchone()[0])


def _unlock(ns: int, key: int) -> None:
    with connection.cursor() as cur:
        cur.execute("SELECT pg_advisory_unlock(%s, %s)", [ns, key])


@dataclass
class Held:
    locks: list[tuple[int, int]] = field(default_factory=list)
    released: bool = False

    def release(self) -> None:
        """Idempotent: the stream's end and the response's close may both call it."""
        if self.released:
            return
        self.released = True
        for ns, key in reversed(self.locks):
            try:
                _unlock(ns, key)
            except Exception:  # noqa: BLE001 - a dropped connection already released it
                log.warning("turn slots: unlock failed", exc_info=True)


def acquire(user, address: str = "") -> tuple[Held | None, str | None, int]:
    """(the held locks, or None with the refusal words and its HTTP status). `address` is the
    caller's (`legion.client_ip`), counted for demo turns only."""
    if connection.vendor != "postgresql":
        # No advisory locks (a local SQLite run): nothing is limited. Production is Postgres.
        return Held(), None, 0
    held = Held()
    user_key = _key(f"user:{user.pk}")
    if not _try(_NS_USER, user_key):
        return None, BUSY_SELF, 409
    held.locks.append((_NS_USER, user_key))
    if is_recruiter(user):
        address_key = _key(f"ip:{address or 'unknown'}")
        if not _try(_NS_ADDRESS, address_key):
            held.release()
            return None, BUSY_ADDRESS, 429
        held.locks.append((_NS_ADDRESS, address_key))
        for slot in range(demo_slots()):
            if _try(_NS_DEMO, slot):
                held.locks.append((_NS_DEMO, slot))
                return held, None, 0
        held.release()
        return None, BUSY_DEMO, 429
    return held, None, 0
