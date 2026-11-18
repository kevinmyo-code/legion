# ruff: noqa: F811 - fixtures imported from the cascade tests
"""Round 3 design change D2 (`review-demo-voice-first-1117-turn-r3.md` N3): the 2.5 s wait for the
caller's own earlier turn must not pin request threads. At most ONE waiter per user and per
address; any other concurrent post is answered `busy` at once. Real threads, each with its own
database connection, in autocommit as production runs."""

from __future__ import annotations

import threading
import time

import pytest
from django.db import connection, connections

from assistant import turn_slots
from tests.test_assistant_cascade import visitor_user  # noqa: F401 - fixture

pytestmark = pytest.mark.django_db(transaction=True)

ADDRESS = "203.0.113.7"


def _hold_user_lock(user, ready: threading.Event, release: threading.Event):
    """A turn already running for `user`: its user lock, on another connection."""
    try:
        with connection.cursor() as cur:
            key = turn_slots._key(f"user:{user.pk}")
            cur.execute("SELECT pg_advisory_lock(%s, %s)", [turn_slots._NS_USER, key])
            ready.set()
            release.wait(15)
            cur.execute("SELECT pg_advisory_unlock(%s, %s)", [turn_slots._NS_USER, key])
    finally:
        connections.close_all()


def _attempt(user, address, out: list, start: threading.Event):
    try:
        start.wait(5)
        began = time.monotonic()
        held, busy, status = turn_slots.acquire(user, address)
        out.append((time.monotonic() - began, busy, status))
        if held is not None:
            held.release()
    finally:
        connections.close_all()


def test_twenty_posts_at_once_from_one_visitor_pin_at_most_one_waiting_thread(
    visitor_user, monkeypatch
):
    monkeypatch.setattr(turn_slots, "OWN_TURN_WAIT_S", 1.5)
    ready, release, start = threading.Event(), threading.Event(), threading.Event()
    holder = threading.Thread(target=_hold_user_lock, args=(visitor_user, ready, release))
    holder.start()
    assert ready.wait(5)
    out: list = []
    threads = [
        threading.Thread(target=_attempt, args=(visitor_user, ADDRESS, out, start))
        for _ in range(20)
    ]
    for t in threads:
        t.start()
    start.set()
    for t in threads:
        t.join(10)
    release.set()
    holder.join(10)
    assert len(out) == 20
    waited = [o for o in out if o[0] >= 0.5]
    at_once = [o for o in out if o[0] < 0.5]
    # One running (the holder) plus at most one asleep waiting for it.
    assert len(waited) <= 1, out
    assert len(at_once) >= 19
    assert all(busy == turn_slots.BUSY_SELF for _s, busy, _st in at_once)


def test_a_waiter_counts_against_its_address(visitor_user, django_user_model, monkeypatch):
    """Another visit from the same network while one visitor's re-send is waiting: answered at
    once, never a second sleeping thread for that address."""
    from household.models import Household, HouseholdMember

    monkeypatch.setattr(turn_slots, "OWN_TURN_WAIT_S", 1.5)
    demo = Household.objects.create(name="Demo visit two", is_demo_visit=True)
    other = django_user_model.objects.create_user(email="visitor-two@demo.invalid")
    HouseholdMember.objects.create(user=other, household=demo, role=HouseholdMember.RECRUITER)

    ready, release, start = threading.Event(), threading.Event(), threading.Event()
    holder = threading.Thread(target=_hold_user_lock, args=(visitor_user, ready, release))
    holder.start()
    assert ready.wait(5)
    first: list = []
    waiter = threading.Thread(target=_attempt, args=(visitor_user, ADDRESS, first, start))
    waiter.start()
    start.set()
    time.sleep(0.3)  # the waiter is asleep now
    second: list = []
    _attempt(other, ADDRESS, second, start)
    release.set()
    waiter.join(10)
    holder.join(10)
    assert second[0][0] < 0.5 and second[0][1] is not None, second
