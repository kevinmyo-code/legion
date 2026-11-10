"""Laptop health (2026-11-10): the heartbeat's snapshot, its history, the machines API, and the
push alerts (offline from the tick, sustained thresholds, cooldown, recovery).

The rule engine is pure and driven with a fake clock; the API tests post real heartbeats and
fake only the Web Push sender. Readings are made up.
"""

from __future__ import annotations

import datetime as dt
import uuid

import pytest
from django.utils import timezone

from claude_session import health as hl
from claude_session.models import ClaudeMachine
from push.models import PushSent, PushSubscription

pytestmark = pytest.mark.django_db
API = "/api/claude/"
T0 = dt.datetime(2026, 11, 12, 3, 0, tzinfo=dt.UTC)

SNAP = {
    "cpu_c": 61.0, "gpu_c": 44.0, "fans_rpm": {"cpu": 3100, "gpu": 2900}, "cpu_load_pct": 12.5,
    "ram_used_pct": 70.1, "disk_free_gb": 180.2, "uptime_s": 7200,
    "boot_at": "2026-11-12T01:00:00+00:00", "laptopctl_agent": True, "on_mains": True,
    "reboot_pending": False,
}


def at(minutes: float) -> dt.datetime:
    return T0 + dt.timedelta(minutes=minutes)


class FakeSender:
    def __init__(self):
        self.sent: list[dict] = []

    def __call__(self, subscription, payload):
        self.sent.append(payload)
        return 201


@pytest.fixture
def sender(monkeypatch):
    s = FakeSender()
    monkeypatch.setattr(hl, "default_sender", lambda: s)
    return s


@pytest.fixture
def subscribed(household_user, household_a):
    return PushSubscription.objects.create(
        household=household_a, user=household_user,
        endpoint=f"https://push.example.com/{uuid.uuid4()}", p256dh="p", auth="a", tz="UTC",
    )


def _beat(client, name="Alienware", scope="full", **extra):
    body = {"name": name, "hostname": "h", "scope": scope, "state": "idle", "running": [],
            "claude_version": "2.1", **extra}
    r = client.post(f"{API}machines/heartbeat/", body, format="json")
    assert r.status_code == 200, r.data
    return r.data


# --- cleaning ------------------------------------------------------------------------

def test_clean_keeps_known_fields_and_nulls_bad_ones():
    got = hl.clean({**SNAP, "cpu_c": "hot", "gpu_c": float("nan"), "ram_used_pct": True,
                    "evil": "x", "fans_rpm": {"cpu": -5, "gpu": 2000}, "boot_at": "nope"})
    assert set(got) == set(hl.FIELDS)
    assert got["cpu_c"] is None and got["gpu_c"] is None and got["ram_used_pct"] is None
    assert got["fans_rpm"] == {"gpu": 2000}
    assert got["boot_at"] is None
    assert "evil" not in got
    assert hl.clean("x") is None


def test_history_one_point_per_ten_minutes_kept_seven_days():
    h: list = []
    for m in range(0, 30, 1):
        h = hl.add_history(h, SNAP, at(m))
    assert [r[0] for r in h] == [int(at(0).timestamp()), int(at(10).timestamp()),
                                int(at(20).timestamp())]
    h = hl.add_history(h, SNAP, at(7 * 24 * 60 + 15))
    assert h[0][0] == int(at(20).timestamp())
    shown = hl.shown_history(h, at(7 * 24 * 60 + 15))
    assert len(shown) == 1 and shown[0][1:] == [61.0, 44.0]


# --- the rules (pure) ----------------------------------------------------------------

def _run(seq):
    """seq: [(minute, conds)] -> list of (minute, kind, recovered) fired."""
    state, out = {}, []
    for minute, conds in seq:
        state, fires = hl.step(state, conds, at(minute))
        out += [(minute, f.kind, f.recovered) for f in fires]
    return state, out


def test_hot_fires_only_after_three_minutes_sustained():
    hot = {hl.HOT: True}
    _, out = _run([(0, hot), (1, hot), (2, hot)])
    assert out == []
    _, out = _run([(0, hot), (1, hot), (2, hot), (3, hot), (4, hot)])
    assert out == [(3, hl.HOT, False)]


def test_a_dip_restarts_the_sustain_clock():
    _, out = _run([(0, {hl.HOT: True}), (2, {hl.HOT: False}), (3, {hl.HOT: True}),
                   (5, {hl.HOT: True})])
    assert out == []


def test_ram_needs_ten_minutes():
    seq = [(m, {hl.RAM_HIGH: True}) for m in range(0, 11)]
    _, out = _run(seq)
    assert out == [(10, hl.RAM_HIGH, False)]


def test_disk_low_fires_at_once_and_once_per_episode():
    _, out = _run([(0, {hl.DISK_LOW: True}), (1, {hl.DISK_LOW: True}), (90, {hl.DISK_LOW: True})])
    assert out == [(0, hl.DISK_LOW, False)]


def test_cooldown_blocks_a_quick_second_episode_then_allows_it():
    d = hl.DISK_LOW
    _, out = _run([(0, {d: True}), (5, {d: False}), (10, {d: True}), (30, {d: True}),
                   (61, {d: True})])
    assert out == [(0, d, False), (61, d, False)]


def test_offline_recovers_with_a_push_others_silently():
    state, out = _run([(0, {hl.OFFLINE: True}), (20, {hl.OFFLINE: False})])
    assert out == [(0, hl.OFFLINE, False), (20, hl.OFFLINE, True)]
    assert hl.open_kinds(state) == []
    _, out = _run([(0, {hl.DISK_LOW: True}), (5, {hl.DISK_LOW: False})])
    assert out == [(0, hl.DISK_LOW, False)]


def test_conditions_from_a_snapshot():
    assert hl.conditions({**SNAP, "gpu_c": 90.0}) == {
        hl.HOT: True, hl.DISK_LOW: False, hl.RAM_HIGH: False, hl.AGENT_DOWN: False}
    c = hl.conditions({**SNAP, "disk_free_gb": 14.9, "ram_used_pct": 95.0,
                       "laptopctl_agent": False})
    assert c[hl.DISK_LOW] and c[hl.RAM_HIGH] and c[hl.AGENT_DOWN]
    unknown = hl.conditions({k: None for k in hl.FIELDS})
    assert not any(unknown.values())


# --- heartbeat, API, push ------------------------------------------------------------

def test_heartbeat_stores_snapshot_and_status_shows_it(token_a):
    _beat(token_a, health=SNAP)
    m = ClaudeMachine.objects.get(name="Alienware")
    assert m.health["cpu_c"] == 61.0 and len(m.health_history) == 1
    row = token_a.get(f"{API}status/").data["machines"][0]
    assert row["health"]["ram_used_pct"] == 70.1
    assert row["health"]["fans_rpm"] == {"cpu": 3100, "gpu": 2900}
    assert row["health"]["alerts"] == []
    assert len(row["health"]["temps_24h"]) == 1


def test_a_beat_without_health_keeps_the_snapshot_and_a_machine_without_one_shows_null(token_a):
    _beat(token_a, health=SNAP)
    _beat(token_a)
    _beat(token_a, name="work laptop", scope="listed")
    rows = {r["name"]: r for r in token_a.get(f"{API}status/").data["machines"]}
    assert rows["Alienware"]["health"]["cpu_c"] == 61.0
    assert rows["work laptop"]["health"] is None


def test_a_garbage_health_never_refuses_the_heartbeat(token_a):
    _beat(token_a, health="not a dict")
    _beat(token_a, health={"cpu_c": [1, 2]})
    assert ClaudeMachine.objects.get(name="Alienware").health["cpu_c"] is None


def test_disk_low_pushes_once(token_a, subscribed, sender):
    _beat(token_a, health={**SNAP, "disk_free_gb": 9.5})
    _beat(token_a, health={**SNAP, "disk_free_gb": 9.4})
    assert len(sender.sent) == 1
    assert sender.sent[0]["kind"] == "laptop_health"
    assert "9.5 GB" in sender.sent[0]["body"]
    assert token_a.get(f"{API}status/").data["machines"][0]["health"]["alerts"] == ["disk_low"]


def test_offline_from_the_tick_then_back_online(token_a, subscribed, sender):
    _beat(token_a, health=SNAP)
    now = timezone.now()
    assert hl.check_offline(now) == 0  # just seen
    ClaudeMachine.objects.update(last_seen_at=now - dt.timedelta(minutes=11))
    assert hl.check_offline(now) == 1
    assert hl.check_offline(now + dt.timedelta(minutes=5)) == 0  # once per episode
    assert sender.sent[-1]["title"] == "Alienware offline"
    _beat(token_a)  # no snapshot needed to come back
    assert sender.sent[-1]["title"] == "Alienware back online"
    assert len(sender.sent) == 2
    assert ClaudeMachine.objects.get().health_alerts.get("offline", {}).get("open") is None


def test_a_machine_that_never_sent_health_is_not_watched(token_a, subscribed, sender):
    _beat(token_a, name="work laptop", scope="listed")
    ClaudeMachine.objects.update(last_seen_at=timezone.now() - dt.timedelta(hours=3))
    assert hl.check_offline(timezone.now()) == 0
    assert sender.sent == []


def test_push_dispatch_runs_the_offline_check(token_a, subscribed, sender, monkeypatch):
    from django.core.management import call_command

    import push.management.commands.push_dispatch as cmd
    from push import vapid

    _beat(token_a, health=SNAP)
    ClaudeMachine.objects.update(last_seen_at=timezone.now() - dt.timedelta(minutes=30))
    monkeypatch.setattr(cmd, "vapid_config", lambda: object())
    monkeypatch.setattr(cmd, "webpush_sender", lambda config: sender)
    assert vapid  # imported for clarity: the real config is never read here
    call_command("push_dispatch")
    assert [p["title"] for p in sender.sent] == ["Alienware offline"]
    assert PushSent.objects.filter(kind="laptop_health").count() == 1


def test_laptop_health_respects_the_off_switch(token_a, subscribed, sender):
    r = token_a.post("/api/push/preferences/off", {"kind": "laptop_health"}, format="json")
    assert r.status_code == 200, r.data
    _beat(token_a, health={**SNAP, "disk_free_gb": 2.0})
    assert sender.sent == []
