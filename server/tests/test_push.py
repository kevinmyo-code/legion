"""Web Push (web-revamp ticket 15, server half; spec D7).

`push_dispatch` is driven as a function of (database, clock) with a fake
sender: every message is asserted word for word, and the dedupe, the quiet
window, recurrence and skips, privacy, the morning rule and the delivery
failure rules each have a test. Then the routes, the command and the copy.
"""

from __future__ import annotations

import datetime as dt
import inspect
import io
import uuid

import pytest
from django.core.management import call_command
from rest_framework.test import APIClient

from checklists.models import ChecklistItem
from ingest.models import IngestRun, Source
from push import copy
from push.dispatch import dispatch_household
from push.models import PushPreference, PushSent, PushSubscription
from tests.conftest import _client_for, _member

pytestmark = pytest.mark.django_db

NOW = dt.datetime(2026, 10, 5, 15, 0, tzinfo=dt.UTC)  # 10:00 am in Chicago
VAPID = {
    "VAPID_PUBLIC_KEY": "BAi5o31dKI-test-public",
    "VAPID_PRIVATE_KEY": "test-private",
    "VAPID_SUBJECT": "mailto:kevin@example.com",
}


class FakeSender:
    """Records every send; answers 201 unless told otherwise per endpoint."""

    def __init__(self):
        self.sent: list[tuple[object, str, dict]] = []
        self.status: dict[str, int] = {}

    def __call__(self, subscription, payload):
        self.sent.append((subscription.user_id, subscription.endpoint, payload))
        return self.status.get(subscription.endpoint, 201)

    def bodies(self, user):
        return [p["body"] for uid, _, p in self.sent if uid == user.pk]

    def clear(self):
        self.sent.clear()


@pytest.fixture
def kevin(household_user):
    household_user.first_name = "Kevin"
    household_user.save()
    return household_user


@pytest.fixture
def mia(household_a):
    user = _member(household_a, "mia@example.com")
    user.first_name = "Mia"
    user.save()
    return user


@pytest.fixture
def kevin_client(auth_client, kevin):
    return auth_client


@pytest.fixture
def mia_client(mia):
    return _client_for(mia)


@pytest.fixture
def send():
    return FakeSender()


def subscribe(user, household, *, tz="America/Chicago", since=NOW - dt.timedelta(days=1)):
    sub = PushSubscription.objects.create(
        household=household,
        user=user,
        endpoint=f"https://push.example.com/{uuid.uuid4()}",
        p256dh="p",
        auth="a",
        tz=tz,
    )
    PushSubscription.objects.filter(pk=sub.pk).update(created_at=since)
    sub.refresh_from_db()
    return sub


def run(household, at, send):
    return dispatch_household(household, at, send)


def add_item(client, checklist_id, text, at):
    made = client.post(f"/api/checklists/{checklist_id}/items", {"text": text}, format="json")
    assert made.status_code == 201, made.data
    ChecklistItem.objects.filter(pk=made.data["id"]).update(created_at=at)
    return made.data["id"]


def minutes(n):
    return dt.timedelta(minutes=n)


# =============================================================================
# List changes
# =============================================================================


def test_a_list_batch_goes_after_two_quiet_minutes_to_everyone_but_its_creator(
    kevin_client, kevin, mia, household_a, send
):
    subscribe(kevin, household_a)
    subscribe(mia, household_a)
    groceries = kevin_client.post("/api/checklists/", {"name": "Groceries"}, format="json").data
    for n, text in enumerate(("oat milk", "eggs", "bread", "butter")):
        add_item(kevin_client, groceries["id"], text, NOW - minutes(6 - n))
    add_item(kevin_client, groceries["id"], "jam", NOW - minutes(1))

    run(household_a, NOW, send)
    assert send.sent == []  # Kevin added jam a minute ago: still adding

    run(household_a, NOW + minutes(2), send)
    assert send.bodies(mia) == ["Kevin added oat milk, eggs and 3 more to Groceries."]
    assert send.bodies(kevin) == []
    payload = send.sent[0][2]
    assert payload["title"] == "Groceries"
    assert payload["off"] == {"kind": "list_changes", "label": "Turn these off"}

    send.clear()
    run(household_a, NOW + minutes(10), send)
    assert send.sent == []  # said once

    add_item(kevin_client, groceries["id"], "honey", NOW + minutes(11))
    run(household_a, NOW + minutes(14), send)
    assert send.bodies(mia) == ["Kevin added honey to Groceries."]


def test_list_batches_are_per_list_per_creator(
    kevin_client, mia_client, kevin, mia, household_a, send
):
    subscribe(kevin, household_a)
    subscribe(mia, household_a)
    groceries = kevin_client.post("/api/checklists/", {"name": "Groceries"}, format="json").data
    party = kevin_client.post("/api/checklists/", {"name": "Party"}, format="json").data
    add_item(kevin_client, groceries["id"], "eggs", NOW - minutes(5))
    add_item(kevin_client, party["id"], "balloons", NOW - minutes(5))
    add_item(mia_client, groceries["id"], "oat milk", NOW - minutes(5))
    add_item(mia_client, groceries["id"], "bread", NOW - minutes(4))

    run(household_a, NOW, send)
    assert sorted(send.bodies(mia)) == [
        "Kevin added balloons to Party.",
        "Kevin added eggs to Groceries.",
    ]
    assert send.bodies(kevin) == ["Mia added oat milk and bread to Groceries."]


def test_a_private_list_and_items_from_before_subscribing_are_never_announced(
    kevin_client, kevin, mia, household_a, send
):
    subscribe(mia, household_a, since=NOW - minutes(30))
    secret = kevin_client.post(
        "/api/checklists/", {"name": "Gifts", "visibility": "private"}, format="json"
    ).data
    add_item(kevin_client, secret["id"], "scarf", NOW - minutes(10))
    groceries = kevin_client.post("/api/checklists/", {"name": "Groceries"}, format="json").data
    add_item(kevin_client, groceries["id"], "old", NOW - minutes(60))
    run(household_a, NOW, send)
    assert send.sent == []


# =============================================================================
# Event reminders
# =============================================================================


def make_event(client, **fields):
    made = client.post("/api/events", fields, format="json")
    assert made.status_code == 201, made.data
    return made.data["id"]


def test_a_reminder_fires_once_at_its_lead_time(kevin_client, kevin, mia, household_a, send):
    subscribe(kevin, household_a)
    subscribe(mia, household_a)
    make_event(
        kevin_client, title="Dentist", starts_at="2026-10-05T15:30:00Z", remind_minutes_before=30
    )
    run(household_a, NOW - minutes(1), send)
    assert send.sent == []
    run(household_a, NOW, send)
    assert send.bodies(mia) == ["Dentist at 10:30 am, in 30 minutes."]
    assert send.bodies(kevin) == ["Dentist at 10:30 am, in 30 minutes."]
    send.clear()
    run(household_a, NOW + minutes(3), send)
    assert send.sent == []


def test_a_repeating_reminder_fires_per_occurrence_and_honours_skips(
    kevin_client, kevin, mia, household_a, send
):
    subscribe(mia, household_a)
    pk = make_event(
        kevin_client,
        title="Swim",
        starts_at="2026-10-05T16:00:00Z",
        remind_minutes_before=60,
        repeat_kind="DAILY",
        repeat_every=1,
        repeat_end_kind="NEVER",
    )
    assert (
        kevin_client.post(
            f"/api/events/{pk}/skips", {"skip_date": "2026-10-06"}, format="json"
        ).status_code
        == 201
    )
    day = dt.timedelta(days=1)
    for offset in range(3):
        run(household_a, NOW + offset * day, send)
    assert send.bodies(mia) == ["Swim at 11:00 am, in 1 hour.", "Swim at 11:00 am, in 1 hour."]
    sent = PushSent.objects.filter(user=mia, kind="event_reminders")
    keys = sorted(sent.values_list("key", flat=True))
    assert keys == [f"event:{pk}:2026-10-05", f"event:{pk}:2026-10-07"]


def test_a_private_event_reminds_only_its_owner(kevin_client, kevin, mia, household_a, send):
    subscribe(kevin, household_a)
    subscribe(mia, household_a)
    make_event(
        kevin_client,
        title="Therapy",
        starts_at="2026-10-06T15:00:00Z",
        remind_minutes_before=1440,
        visibility="private",
    )
    run(household_a, NOW, send)
    assert send.bodies(kevin) == ["Therapy tomorrow at 10:00 am."]
    assert send.bodies(mia) == []


def test_a_done_or_deleted_event_does_not_remind(kevin_client, kevin, household_a, send):
    subscribe(kevin, household_a)
    done = make_event(
        kevin_client,
        title="Done",
        starts_at="2026-10-05T15:05:00Z",
        remind_minutes_before=5,
        kind="task",
    )
    kevin_client.patch(f"/api/events/{done}", {"done": True}, format="json")
    gone = make_event(
        kevin_client, title="Gone", starts_at="2026-10-05T15:05:00Z", remind_minutes_before=5
    )
    kevin_client.delete(f"/api/events/{gone}")
    run(household_a, NOW, send)
    assert send.sent == []


# =============================================================================
# The morning list
# =============================================================================


def test_the_morning_list_is_sent_once_per_local_day(kevin_client, kevin, mia, household_a, send):
    subscribe(mia, household_a)
    make_event(kevin_client, title="HW 4", kind="task", starts_at="2026-10-05T23:00:00Z")
    make_event(kevin_client, title="rent", kind="task", starts_at="2026-10-05T17:00:00Z")
    make_event(
        kevin_client,
        title="Kevin only",
        kind="task",
        starts_at="2026-10-05T17:00:00Z",
        visibility="private",
    )
    make_event(kevin_client, title="tomorrow", kind="task", starts_at="2026-10-06T17:00:00Z")
    make_event(kevin_client, title="an event", kind="event", starts_at="2026-10-05T17:00:00Z")

    run(household_a, dt.datetime(2026, 10, 5, 11, 0, tzinfo=dt.UTC), send)  # 6:00 am local
    assert send.sent == []
    run(household_a, NOW, send)  # 10:00 am local, after 7:30
    assert send.bodies(mia) == ["2 things due today: rent, HW 4."]
    assert send.sent[0][2]["title"] == "Due today"
    send.clear()
    run(household_a, NOW + minutes(5), send)
    assert send.sent == []


def test_nothing_due_sends_nothing_and_the_day_stays_decided(
    kevin_client, kevin, household_a, send
):
    subscribe(kevin, household_a)
    run(household_a, NOW, send)
    assert send.sent == []
    marker = PushSent.objects.get(user=kevin, kind="task_due_morning")
    assert (marker.key, marker.delivered) == ("morning:2026-10-05", False)
    make_event(kevin_client, title="late", kind="task", starts_at="2026-10-05T22:00:00Z")
    run(household_a, NOW + dt.timedelta(hours=4), send)
    assert send.sent == []


def test_the_morning_time_and_zone_are_the_persons(kevin, household_a, kevin_client, send):
    subscribe(kevin, household_a, tz="Asia/Tokyo")  # NOW is 00:00 on the 6th in Tokyo
    PushPreference.objects.create(household=household_a, user=kevin, morning_time=dt.time(0, 0))
    make_event(kevin_client, title="tokyo task", kind="task", starts_at="2026-10-06T01:00:00Z")
    run(household_a, NOW, send)
    assert send.bodies(kevin) == ["1 thing due today: tokyo task."]


# =============================================================================
# Delivery failures and the one-tap silence
# =============================================================================


def test_a_404_or_410_deletes_the_subscription(kevin_client, kevin, household_a, send):
    gone = subscribe(kevin, household_a)
    expired = subscribe(kevin, household_a)
    send.status[gone.endpoint] = 410
    send.status[expired.endpoint] = 404
    make_event(
        kevin_client, title="Dentist", starts_at="2026-10-05T15:30:00Z", remind_minutes_before=30
    )
    report = run(household_a, NOW, send)
    assert report.subscriptions_removed == 2
    assert not PushSubscription.objects.filter(user=kevin).exists()
    assert not PushSent.objects.filter(user=kevin, kind="event_reminders").exists()


def test_five_failures_in_a_row_delete_the_subscription_and_a_success_resets(
    kevin_client, kevin, household_a, send
):
    flaky = subscribe(kevin, household_a)
    make_event(
        kevin_client,
        title="Swim",
        starts_at="2026-10-05T15:30:00Z",
        remind_minutes_before=30,
        repeat_kind="DAILY",
        repeat_every=1,
        repeat_end_kind="NEVER",
    )
    day = dt.timedelta(days=1)
    send.status[flaky.endpoint] = 500
    run(household_a, NOW, send)
    run(household_a, NOW + day, send)
    assert PushSubscription.objects.get(pk=flaky.pk).failure_count == 2
    send.status[flaky.endpoint] = 201
    run(household_a, NOW + 2 * day, send)
    refreshed = PushSubscription.objects.get(pk=flaky.pk)
    assert refreshed.failure_count == 0 and refreshed.last_ok_at == NOW + 2 * day
    send.status[flaky.endpoint] = 503
    for offset in range(3, 8):
        run(household_a, NOW + offset * day, send)
    assert not PushSubscription.objects.filter(pk=flaky.pk).exists()


def test_the_one_tap_off_stops_that_kind_only(
    kevin_client, mia_client, kevin, mia, household_a, send
):
    subscribe(kevin, household_a)
    subscribe(mia, household_a)
    silenced = mia_client.post(
        "/api/push/preferences/off", {"kind": "event_reminders"}, format="json"
    )
    assert silenced.status_code == 200, silenced.data
    assert silenced.data["event_reminders"] is False
    assert silenced.data["list_changes"] is True
    assert "will not get event reminders" in silenced.data["detail"]
    make_event(
        kevin_client, title="Dentist", starts_at="2026-10-05T15:30:00Z", remind_minutes_before=30
    )
    run(household_a, NOW, send)
    assert send.bodies(mia) == []
    assert send.bodies(kevin) == ["Dentist at 10:30 am, in 30 minutes."]


# =============================================================================
# Routes
# =============================================================================


def test_with_no_keys_push_is_off_in_words(monkeypatch, kevin_client):
    for name in VAPID:
        monkeypatch.delenv(name, raising=False)
    key = kevin_client.get("/api/push/vapid-public-key").data
    assert key == {"enabled": False, "public_key": None, "detail": copy.PUSH_OFF}
    refused = kevin_client.post(
        "/api/push/subscriptions",
        {"endpoint": "https://push.example.com/x", "keys": {"p256dh": "p", "auth": "a"}},
        format="json",
    )
    assert refused.status_code == 503
    assert (
        refused.data["detail"] == "Notifications are not set up on this server. Nothing was saved."
    )
    assert not PushSubscription.objects.exists()


@pytest.fixture
def vapid(monkeypatch):
    for name, value in VAPID.items():
        monkeypatch.setenv(name, value)


def test_subscribe_resubscribe_and_unsubscribe(vapid, kevin_client, mia_client, kevin, mia):
    key = kevin_client.get("/api/push/vapid-public-key").data
    assert key["enabled"] is True and key["public_key"] == VAPID["VAPID_PUBLIC_KEY"]
    body = {
        "endpoint": "https://push.example.com/abc",
        "expirationTime": None,
        "keys": {"p256dh": "p1", "auth": "a1"},
        "tz": "America/Chicago",
        "user_agent": "iPhone",
    }
    made = kevin_client.post("/api/push/subscriptions", body, format="json")
    assert made.status_code == 201, made.data
    assert set(made.data) == {"id", "user_agent", "tz", "created_at", "last_ok_at"}
    assert PushPreference.objects.filter(user=kevin).exists()
    again = kevin_client.post(
        "/api/push/subscriptions", {**body, "keys": {"p256dh": "p2", "auth": "a2"}}, format="json"
    )
    assert again.status_code == 200 and again.data["id"] == made.data["id"]
    assert PushSubscription.objects.get().p256dh == "p2"

    assert mia_client.delete(f"/api/push/subscriptions/{made.data['id']}").status_code == 404
    assert kevin_client.delete(f"/api/push/subscriptions/{made.data['id']}").status_code == 204
    assert not PushSubscription.objects.exists()


def test_a_subscription_with_a_bad_zone_or_endpoint_is_refused(vapid, kevin_client):
    for body in (
        {"endpoint": "http://push.example.com/x", "keys": {"p256dh": "p", "auth": "a"}},
        {
            "endpoint": "https://push.example.com/x",
            "keys": {"p256dh": "p", "auth": "a"},
            "tz": "Mars/Olympus",
        },
    ):
        response = kevin_client.post("/api/push/subscriptions", body, format="json")
        assert response.status_code == 400, response.data
        assert "Nothing was saved" in str(response.data)


def test_preferences_round_trip_and_default_on(kevin_client):
    assert kevin_client.get("/api/push/preferences").data == {
        "list_changes": True,
        "event_reminders": True,
        "task_due_morning": True,
        "morning_time": "07:30",
    }
    put = kevin_client.put(
        "/api/push/preferences",
        {
            "list_changes": False,
            "event_reminders": True,
            "task_due_morning": True,
            "morning_time": "06:45",
        },
        format="json",
    )
    assert put.status_code == 200, put.data
    assert kevin_client.get("/api/push/preferences").data["morning_time"] == "06:45"
    bad = kevin_client.post("/api/push/preferences/off", {"kind": "streaks"}, format="json")
    assert bad.status_code == 400 and "Nothing was changed" in bad.data["detail"]


def test_the_off_route_works_from_a_session_without_a_csrf_token(household_user):
    from django.core.cache import cache

    cache.clear()  # the `login` scope's budget is shared with other test files
    client = APIClient(enforce_csrf_checks=True)
    signed_in = client.post(
        "/api/auth/session/login",
        {"email": household_user.email, "password": "correct horse battery"},
        format="json",
    )
    assert signed_in.status_code == 200
    assert (
        client.post(
            "/api/push/preferences/off", {"kind": "list_changes"}, format="json"
        ).status_code
        == 200
    )  # noqa: E501
    # Every other push write still needs one.
    assert (
        client.put(
            "/api/push/preferences",
            {
                "list_changes": True,
                "event_reminders": True,
                "task_due_morning": True,
                "morning_time": "07:30",
            },
            format="json",
        ).status_code
        == 403
    )


def test_removing_a_member_drops_their_subscriptions(kevin_client, mia, household_a):
    subscribe(mia, household_a)
    assert kevin_client.delete(f"/api/households/me/members/{mia.pk}").status_code == 204
    assert not PushSubscription.objects.filter(user=mia).exists()


# =============================================================================
# The command
# =============================================================================


def test_the_command_with_no_keys_says_so_and_records_nothing(monkeypatch, kevin, household_a):
    for name in VAPID:
        monkeypatch.delenv(name, raising=False)
    subscribe(kevin, household_a)
    out = io.StringIO()
    call_command("push_dispatch", stdout=out)
    assert (
        out.getvalue().strip() == "Notifications are not set up on this server. Nothing was sent."
    )
    assert not IngestRun.objects.filter(source=Source.PUSH).exists()


def test_the_command_sends_through_the_sender_and_records_a_run(
    vapid, monkeypatch, kevin_client, kevin, household_a
):
    import push.management.commands.push_dispatch as command

    fake = FakeSender()
    monkeypatch.setattr(command, "webpush_sender", lambda config: fake)
    subscribe(kevin, household_a, since=dt.datetime(2020, 1, 1, tzinfo=dt.UTC))
    make_event(
        kevin_client,
        title="Now",
        starts_at=dt.datetime.now(dt.UTC).isoformat(),
        remind_minutes_before=0,
    )
    call_command("push_dispatch", stdout=io.StringIO())
    assert [p["body"].split(" at ")[0] for _, _, p in fake.sent] == ["Now"]
    run_row = IngestRun.objects.get(source=Source.PUSH, household=household_a)
    assert run_row.outcome == "ok" and run_row.rows_written >= 1


# =============================================================================
# The copy: the compulsion test, made checkable
# =============================================================================

BANNED = ("haven't", "miss", "streak", "days since", "come back")


def test_no_notification_string_references_absence_or_streaks():
    strings = [v for k, v in vars(copy).items() if isinstance(v, str) and not k.startswith("__")]
    strings += list(copy.KIND_NAMES.values())
    strings += [
        copy.list_added("Kevin", ["a", "b", "c", "d"], "Groceries"),
        copy.list_added("Kevin", ["a"], "Groceries"),
        copy.event_reminder("Dentist", "3:00 pm", 30),
        copy.event_reminder("Dentist", "3:00 pm", 0),
        copy.event_reminder("Dentist", "3:00 pm", 120),
        copy.event_reminder("Dentist", "3:00 pm", 1440),
        copy.all_day_reminder("Birthday", 60),
        copy.all_day_reminder("Birthday", 1440),
        copy.morning(["HW 4", "rent", "a", "b", "c", "d"]),
        copy.morning(["HW 4"]),
    ]
    # Every function in the module is exercised above, so a new template is
    # caught by this count before it can ship ungrepped.
    functions = {
        n for n, f in vars(copy).items() if inspect.isfunction(f) and not n.startswith("_")
    }
    assert functions == {"list_added", "event_reminder", "all_day_reminder", "morning", "clock"}
    offenders = [(s, word) for s in strings for word in BANNED if word in s.lower()]
    assert not offenders, offenders


def test_the_copy_reads_the_way_the_spec_writes_it():
    assert copy.list_added("Kevin", ["oat milk", "eggs", "x", "y"], "Groceries") == (
        "Kevin added oat milk, eggs and 2 more to Groceries."
    )
    assert (
        copy.event_reminder("Dentist", copy.clock(15, 0), 30)
        == "Dentist at 3:00 pm, in 30 minutes."
    )
    assert copy.morning(["HW 4", "rent", "return library books"]) == (
        "3 things due today: HW 4, rent, return library books."
    )
    assert copy.clock(0, 5) == "12:05 am" and copy.clock(12, 0) == "12:00 pm"
