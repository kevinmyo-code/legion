"""Laptop health: the snapshot a worker sends, its 10-minute history, and the push alerts
(laptop-health 2026-11-10).

Kevin leaves the Alienware on for days and watches it from the phone. The worker
(`tools/claude_session/health.py`) adds a compact `health` dict to its heartbeat about once a
minute. This module:

- **cleans** it (`clean`): a whitelist of fields, numbers coerced, anything else dropped. A bad
  snapshot is never a reason to refuse a heartbeat;
- **stores** it on `ClaudeMachine` (`store`): the latest in `health`, one point every
  `HISTORY_EVERY` in `health_history` (kept `HISTORY_KEEP`) for the phone's sparkline;
- **alerts** by Web Push (kind `laptop_health`), each kind once per episode and at most once per
  `COOLDOWN`:

  | kind | when |
  |---|---|
  | `offline` | no heartbeat for `OFFLINE_AFTER` (the push tick checks: a dead laptop cannot |
  |  | report itself), then "back online" on the next heartbeat |
  | `hot` | CPU or GPU >= `HOT_C` for `HOT_FOR` |
  | `disk_low` | C: free < `DISK_LOW_GB` |
  | `ram_high` | RAM >= `RAM_HIGH_PCT` for `RAM_FOR` |
  | `agent_down` | laptopctl-agent not answering for `AGENT_FOR` |

  Only a machine that sent a snapshot within `WATCHED_FOR` is watched, so the work laptop
  (which sends none) never pages Kevin for being switched off.

The rule engine (`step`) is pure: state in, state and pushes out, `now` passed in.
Alert state lives in `ClaudeMachine.health_alerts`: `{kind: {"since": epoch, "open": bool,
"sent": epoch}}`.
"""

from __future__ import annotations

import datetime as dt
import logging
from dataclasses import dataclass
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from django.db import transaction

logger = logging.getLogger(__name__)

HISTORY_EVERY = dt.timedelta(minutes=10)
HISTORY_KEEP = dt.timedelta(days=7)
HISTORY_SHOWN = dt.timedelta(hours=24)
WATCHED_FOR = dt.timedelta(hours=24)
OFFLINE_AFTER = dt.timedelta(minutes=10)
COOLDOWN = dt.timedelta(minutes=60)

HOT_C = 90.0
HOT_FOR = dt.timedelta(minutes=3)
DISK_LOW_GB = 15.0
RAM_HIGH_PCT = 95.0
RAM_FOR = dt.timedelta(minutes=10)
# One missed reading is a busy agent, not a dead one; two in a row (snapshots are a minute apart).
AGENT_FOR = dt.timedelta(minutes=2)

OFFLINE = "offline"
HOT = "hot"
DISK_LOW = "disk_low"
RAM_HIGH = "ram_high"
AGENT_DOWN = "agent_down"
SUSTAIN = {
    OFFLINE: dt.timedelta(0),
    HOT: HOT_FOR,
    DISK_LOW: dt.timedelta(0),
    RAM_HIGH: RAM_FOR,
    AGENT_DOWN: AGENT_FOR,
}
# Only going offline says when it is over; the others are said once per episode.
SAYS_RECOVERY = frozenset({OFFLINE})

NUMBERS = ("cpu_c", "gpu_c", "cpu_load_pct", "ram_used_pct", "disk_free_gb")
BOOLS = ("laptopctl_agent", "on_mains", "reboot_pending")
FIELDS = (*NUMBERS, "fans_rpm", "uptime_s", "boot_at", *BOOLS)


# ---------------------------------------------------------------- cleaning

def _num(v, lo=-1e6, hi=1e9):
    if isinstance(v, bool) or not isinstance(v, (int, float)):
        return None
    f = float(v)
    if f != f or not lo <= f <= hi:
        return None
    return round(f, 1)


def clean(raw) -> dict | None:
    """The worker's dict to the stored one, every field present (null when unknown)."""
    if not isinstance(raw, dict):
        return None
    out: dict = {k: _num(raw.get(k)) for k in NUMBERS}
    for k in BOOLS:
        v = raw.get(k)
        out[k] = v if isinstance(v, bool) else None
    fans = raw.get("fans_rpm")
    rpm = {}
    if isinstance(fans, dict):
        for name, v in list(fans.items())[:8]:
            n = _num(v, 0, 100_000)
            if n is not None:
                rpm[str(name)[:16]] = int(n)
    out["fans_rpm"] = rpm or None
    up = _num(raw.get("uptime_s"), 0, 10 * 365 * 86400)
    out["uptime_s"] = int(up) if up is not None else None
    boot = raw.get("boot_at")
    out["boot_at"] = None
    if isinstance(boot, str) and len(boot) <= 40:
        try:
            out["boot_at"] = dt.datetime.fromisoformat(boot).isoformat()
        except ValueError:
            pass
    return out


# ---------------------------------------------------------------- history

def add_history(history: list, snap: dict, now: dt.datetime) -> list:
    """One `[epoch, cpu_c, gpu_c, ram_used_pct, disk_free_gb]` per `HISTORY_EVERY`; older than
    `HISTORY_KEEP` dropped."""
    t = int(now.timestamp())
    rows = [r for r in (history or []) if isinstance(r, list) and r
            and t - r[0] <= HISTORY_KEEP.total_seconds()]
    if not rows or t - rows[-1][0] >= HISTORY_EVERY.total_seconds():
        rows.append([t, snap.get("cpu_c"), snap.get("gpu_c"), snap.get("ram_used_pct"),
                     snap.get("disk_free_gb")])
    return rows


def shown_history(history: list, now: dt.datetime) -> list:
    """The last 24 h as `[iso time, cpu_c, gpu_c]`, oldest first, for the phone's sparkline."""
    since = (now - HISTORY_SHOWN).timestamp()
    return [
        [dt.datetime.fromtimestamp(r[0], dt.UTC).isoformat(), r[1], r[2]]
        for r in (history or []) if isinstance(r, list) and len(r) >= 3 and r[0] >= since
    ]


# ---------------------------------------------------------------- rules

def conditions(snap: dict) -> dict[str, bool]:
    """Each snapshot rule, true when it holds. An unknown reading never holds."""
    temps = [v for v in (snap.get("cpu_c"), snap.get("gpu_c")) if v is not None]
    disk = snap.get("disk_free_gb")
    ram = snap.get("ram_used_pct")
    return {
        HOT: bool(temps) and max(temps) >= HOT_C,
        DISK_LOW: disk is not None and disk < DISK_LOW_GB,
        RAM_HIGH: ram is not None and ram >= RAM_HIGH_PCT,
        AGENT_DOWN: snap.get("laptopctl_agent") is False,
    }


@dataclass(frozen=True)
class Fire:
    kind: str
    recovered: bool
    since: float


def step(state: dict, conds: dict[str, bool], now: dt.datetime) -> tuple[dict, list[Fire]]:
    """Pure. A condition that starts holding records `since`; once it has held for its
    `SUSTAIN` and none of its kind went out within `COOLDOWN`, it fires once and stays `open`
    until it clears. Clearing an open `offline` fires its recovery. Kinds not in [conds] are
    left as they are."""
    t = now.timestamp()
    new = {k: dict(v) for k, v in (state or {}).items() if isinstance(v, dict)}
    fires: list[Fire] = []
    for kind, holds in conds.items():
        st = new.get(kind, {})
        if holds:
            since = st.get("since") or t
            st["since"] = since
            sent = st.get("sent")
            if (not st.get("open") and t - since >= SUSTAIN[kind].total_seconds()
                    and (sent is None or t - sent >= COOLDOWN.total_seconds())):
                st["open"] = True
                st["sent"] = t
                fires.append(Fire(kind, False, since))
        else:
            if st.get("open") and kind in SAYS_RECOVERY:
                fires.append(Fire(kind, True, st.get("since") or t))
            st = {"sent": st["sent"]} if st.get("sent") is not None else {}
        if st:
            new[kind] = st
        else:
            new.pop(kind, None)
    return new, fires


def open_kinds(state: dict) -> list[str]:
    return sorted(k for k, v in (state or {}).items() if isinstance(v, dict) and v.get("open"))


# ---------------------------------------------------------------- words

def _zone(machine) -> ZoneInfo:
    try:
        return ZoneInfo(getattr(machine.household, "timezone", None) or "UTC")
    except (ZoneInfoNotFoundError, ValueError):
        return ZoneInfo("UTC")


def _clock(machine, when: dt.datetime | None) -> str:
    if when is None:
        return "an unknown time"
    local = when.astimezone(_zone(machine))
    return local.strftime("%a %I:%M %p").replace(" 0", " ")


def words(machine, fire: Fire, snap: dict | None) -> tuple[str, str]:
    """(title, body) for a push. Facts only (CLAUDE.md compulsion test (a)): what was read, when."""
    name = machine.name
    snap = snap or {}
    if fire.kind == OFFLINE and fire.recovered:
        return (f"{name} back online", f"{name} is sending heartbeats again.")
    if fire.kind == OFFLINE:
        return (
            f"{name} offline",
            f"No heartbeat since {_clock(machine, machine.last_seen_at)}. A power cut, a restart "
            "with nobody logged in, or a crash.",
        )
    if fire.kind == HOT:
        parts = [f"{label} {snap[k]:.0f} C" for k, label in (("cpu_c", "CPU"), ("gpu_c", "GPU"))
                 if snap.get(k) is not None]
        mins = int(HOT_FOR.total_seconds() // 60)
        return (f"{name} running hot", f"{', '.join(parts)} for over {mins} minutes.")
    if fire.kind == DISK_LOW:
        return (f"{name} low on disk", f"C: has {snap.get('disk_free_gb')} GB free.")
    if fire.kind == RAM_HIGH:
        return (
            f"{name} out of memory",
            f"RAM at {snap.get('ram_used_pct')}% for over "
            f"{int(RAM_FOR.total_seconds() // 60)} minutes.",
        )
    return (
        f"{name}: laptopctl stopped",
        "The fan agent is not answering; the 95 C fan watchdog is off.",
    )


# ---------------------------------------------------------------- delivery

def recipients(machine) -> list:
    """The member whose token speaks for the machine; else the household's owners."""
    from household.models import HouseholdMember

    token = getattr(machine, "token", None)
    if token is not None and getattr(token, "user", None) is not None:
        return [token.user]
    return [
        m.user for m in HouseholdMember.objects.filter(
            household_id=machine.household_id, role=HouseholdMember.OWNER
        ).select_related("user")
    ]


def default_sender():
    from push.sender import webpush_sender
    from push.vapid import vapid_config

    config = vapid_config()
    return webpush_sender(config) if config is not None else None


def deliver(
    machine, fires: list[Fire], snap: dict | None, now: dt.datetime, send=None
) -> list[str]:
    """Push each fire to the machine's person. Never raises; returns what happened per push."""
    if not fires:
        return []
    from push.dispatch import send_now
    from push.models import LAPTOP_HEALTH

    out = []
    try:
        send = send or default_sender()
        if send is None:
            logger.info("laptop health: push is off, %s alert(s) not sent", len(fires))
            return ["push_off"] * len(fires)
        for fire in fires:
            title, body = words(machine, fire, snap)
            phase = "ok" if fire.recovered else "on"
            key = f"laptop:{machine.id}:{fire.kind}:{phase}:{int(fire.since)}"
            for user in recipients(machine):
                out.append(send_now(
                    machine.household, user, kind=LAPTOP_HEALTH, key=key, title=title,
                    body=body, url="/", now=now, send=send,
                ))
    except Exception:  # noqa: BLE001 - an alert must never fail a heartbeat or the tick
        logger.exception("laptop health: push failed")
    return out


# ---------------------------------------------------------------- the two entry points

def _locked(machine_id):
    from claude_session.models import ClaudeMachine

    return ClaudeMachine.objects.select_for_update(of=("self",)).select_related(
        "household", "token__user"
    ).get(pk=machine_id)


def on_heartbeat(machine, raw, now: dt.datetime, send=None) -> list[str]:
    """Called by every heartbeat. Stores a snapshot when one came, steps the snapshot rules on
    it, and closes an open `offline` (the "back online" push). Writes only what changed."""
    snap = clean(raw) if raw is not None else None
    if snap is None and not (machine.health_alerts or {}).get(OFFLINE, {}).get("open"):
        return []
    with transaction.atomic():
        m = _locked(machine.pk)
        conds = {OFFLINE: False}
        fields = ["health_alerts"]
        if snap is not None:
            conds.update(conditions(snap))
            m.health = snap
            m.health_at = now
            m.health_history = add_history(m.health_history, snap, now)
            fields += ["health", "health_at", "health_history"]
        m.health_alerts, fires = step(m.health_alerts, conds, now)
        m.save(update_fields=fields)
    machine.health, machine.health_at = m.health, m.health_at
    machine.health_alerts = m.health_alerts
    return deliver(m, fires, snap or m.health, now, send)


def check_offline(now: dt.datetime, send=None) -> int:
    """The push tick's half: every watched machine silent for `OFFLINE_AFTER` fires `offline`
    once. Returns how many fired. Never raises."""
    from claude_session.models import ClaudeMachine

    fired = 0
    try:
        watched = ClaudeMachine.objects.filter(
            health_at__gte=now - WATCHED_FOR - OFFLINE_AFTER,
            last_seen_at__lt=now - OFFLINE_AFTER,
        ).values_list("pk", flat=True)
        for pk in list(watched):
            with transaction.atomic():
                m = _locked(pk)
                m.health_alerts, fires = step(m.health_alerts, {OFFLINE: True}, now)
                if not fires:
                    continue
                m.save(update_fields=["health_alerts"])
            deliver(m, fires, m.health, now, send)
            fired += len(fires)
    except Exception:  # noqa: BLE001
        logger.exception("laptop health: offline check failed")
    return fired
