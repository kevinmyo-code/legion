"""What `manage.py push_dispatch` decides to say, as a function of the database
and the clock (web-revamp ticket 15, spec D7).

`dispatch_household(household, now, send)` is the whole job for one
household. `now` is passed in and `send` is passed in, so a test freezes the
one and fakes the other (`tests/test_push.py`).

Three kinds, each checked against `PushSent` before anything is sent, so a
thing is said once however often the job runs:

- **List changes.** Items a member added to a SHARED list, grouped per list
  per creator, sent to every other member who wants them, once the creator
  has been quiet on that list for two minutes - so adding six things is one
  message, not six. Never sent to the person who added them. A private list
  is never announced: only its owner can see it.
- **Event reminders.** For every event the recipient can see (shared, or
  private to them: `visible()`'s rule) with `remind_minutes_before` set, each
  occurrence - repeats expanded and skips honoured by `api/recurrence.py` -
  whose start minus the lead time fell in the last five minutes.
- **The morning list.** At the first run at or after the person's
  `morning_time` in their device's zone, the tasks they can see that are due
  that local day and not done. Nothing due sends nothing, and the day is
  still marked decided, so a task added at 2pm never produces a "morning"
  message.

Delivery: a 2xx is a success; 404 or 410 means the browser is gone and the
subscription is deleted; anything else counts a failure, and five in a row
delete it. A thing is marked said only when it reached at least one device,
so a run where every device failed is retried by the next one.
"""

from __future__ import annotations

import datetime as dt
from dataclasses import dataclass, field
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from django.db.models import Max, Q

from push import copy
from push.models import (
    DEFAULT_MORNING_TIME,
    EVENT_REMINDERS,
    LIST_CHANGES,
    TASK_DUE_MORNING,
    PushPreference,
    PushSent,
    PushSubscription,
)

LIST_QUIET = dt.timedelta(minutes=2)
REMINDER_WINDOW = dt.timedelta(minutes=5)
MAX_FAILURES = 5
GONE = frozenset({404, 410})


@dataclass
class Message:
    user_id: object
    kind: str
    key: str
    body: str


@dataclass
class Report:
    delivered: list[Message] = field(default_factory=list)
    subscriptions_removed: int = 0


@dataclass
class _Prefs:
    list_changes: bool = True
    event_reminders: bool = True
    task_due_morning: bool = True
    morning_time: dt.time = DEFAULT_MORNING_TIME


def _zone(name: str | None) -> ZoneInfo:
    try:
        return ZoneInfo(name or "UTC")
    except (ZoneInfoNotFoundError, ValueError):
        return ZoneInfo("UTC")


def _display_name(user) -> str:
    return (user.first_name or "").strip() or user.email.split("@")[0]


class _Dispatcher:
    def __init__(self, household, now: dt.datetime, send):
        self.household = household
        self.now = now
        self.send = send
        self.report = Report()

    # ------------------------------------------------------------------ who

    def recipients(self):
        """Members of this household with at least one subscription, each with
        their preferences and the zone of their first subscribed device."""
        from household.models import HouseholdMember

        members = {
            m.user_id: m.user
            for m in HouseholdMember.objects.filter(household=self.household).select_related("user")
        }
        subs: dict[object, list[PushSubscription]] = {}
        for sub in PushSubscription.objects.filter(household=self.household).order_by(
            "created_at", "id"
        ):
            if sub.user_id in members:
                subs.setdefault(sub.user_id, []).append(sub)
        prefs = {p.user_id: p for p in PushPreference.objects.filter(household=self.household)}
        for user_id, user_subs in subs.items():
            stored = prefs.get(user_id)
            pref = (
                _Prefs(
                    stored.list_changes,
                    stored.event_reminders,
                    stored.task_due_morning,
                    stored.morning_time,
                )
                if stored
                else _Prefs()
            )
            yield members[user_id], pref, user_subs

    # --------------------------------------------------------------- deliver

    def already(self, user, kind: str, key: str) -> bool:
        return PushSent.objects.filter(
            household=self.household, user=user, kind=kind, key=key
        ).exists()

    def deliver(self, user, subs, kind, key, title, body, url, watermark=None) -> bool:
        if self.already(user, kind, key):
            return False
        payload = {
            "kind": kind,
            "title": title,
            "body": body,
            "url": url,
            "tag": key,
            "off": {"kind": kind, "label": copy.TURN_OFF_ACTION},
        }
        reached = False
        for sub in subs:
            if sub.pk is None:
                continue
            status = self.send(sub, payload)
            if 200 <= status < 300:
                reached = True
                PushSubscription.objects.filter(pk=sub.pk).update(
                    failure_count=0, last_ok_at=self.now
                )
            elif status in GONE:
                PushSubscription.objects.filter(pk=sub.pk).delete()
                sub.pk = None
                self.report.subscriptions_removed += 1
            else:
                sub.failure_count += 1
                if sub.failure_count >= MAX_FAILURES:
                    PushSubscription.objects.filter(pk=sub.pk).delete()
                    sub.pk = None
                    self.report.subscriptions_removed += 1
                else:
                    PushSubscription.objects.filter(pk=sub.pk).update(
                        failure_count=sub.failure_count
                    )
        if reached:
            PushSent.objects.create(
                household=self.household,
                user=user,
                kind=kind,
                key=key,
                watermark=watermark,
                sent_at=self.now,
            )
            self.report.delivered.append(Message(user.pk, kind, key, body))
        return reached

    # ------------------------------------------------------------------ lists

    def list_changes(self, user, subs):
        from checklists.models import Checklist, ChecklistItem

        first_subscribed = subs[0].created_at
        shared = Checklist.objects.filter(
            household=self.household, owner_user__isnull=True, deleted_at__isnull=True
        )
        for checklist in shared:
            creators = (
                ChecklistItem.objects.filter(
                    checklist=checklist, deleted_at__isnull=True, created_by__isnull=False
                )
                .exclude(created_by=user)
                .values_list("created_by", flat=True)
                .distinct()
            )
            for creator_id in creators:
                prefix = f"list:{checklist.pk}:{creator_id}:"
                last = PushSent.objects.filter(
                    household=self.household,
                    user=user,
                    kind=LIST_CHANGES,
                    key__startswith=prefix,
                ).aggregate(m=Max("watermark"))["m"]
                since = max(filter(None, [last, first_subscribed]))
                batch = list(
                    ChecklistItem.objects.filter(
                        checklist=checklist,
                        created_by_id=creator_id,
                        deleted_at__isnull=True,
                        created_at__gt=since,
                        created_at__lte=self.now,
                    )
                    .select_related("created_by")
                    .order_by("created_at", "id")
                )
                if not batch:
                    continue
                newest = batch[-1].created_at
                if self.now - newest < LIST_QUIET:
                    continue  # the creator is still adding; wait for the quiet
                body = copy.list_added(
                    _display_name(batch[0].created_by), [i.text for i in batch], checklist.name
                )
                self.deliver(
                    user,
                    subs,
                    LIST_CHANGES,
                    prefix + newest.isoformat(),
                    checklist.name,
                    body,
                    "/lists",
                    watermark=newest,
                )

    # -------------------------------------------------------------- reminders

    def _visible_events(self, user):
        from legacy.models.dates import Event

        return Event.objects.filter(household=self.household, deleted_at__isnull=True).filter(
            Q(owner_user__isnull=True) | Q(owner_user=user)
        )

    def _skips(self, event_ids):
        from legacy.models.dates import EventSkip

        out: dict[object, set[dt.date]] = {}
        for event_id, day in EventSkip.objects.filter(
            event_id__in=event_ids, deleted_at__isnull=True
        ).values_list("event_id", "skip_date"):
            out.setdefault(event_id, set()).add(day)
        return out

    def event_reminders(self, user, subs):
        from api.event_columns import KIND_SUGGESTION
        from api.recurrence import series_occurrences

        zone = _zone(subs[0].tz)
        # Never a suggestion: a suggestion is not a plan, so it never
        # rings (the CHECK in `api/event_columns.py` already keeps its reminder
        # null; this is the reader saying so too, in case that ever moves).
        events = list(
            self._visible_events(user)
            .filter(remind_minutes_before__isnull=False, done=False, starts_at__isnull=False)
            .exclude(kind=KIND_SUGGESTION)
        )
        skips = self._skips([e.pk for e in events])
        for event in events:
            lead = dt.timedelta(minutes=event.remind_minutes_before)
            start = self.now - REMINDER_WINDOW + lead + dt.timedelta(microseconds=1)
            end = self.now + lead
            for occurrence in series_occurrences(event, skips.get(event.pk, ()), start, end, zone):
                local = occurrence.astimezone(zone)
                if event.all_day:
                    body = copy.all_day_reminder(event.title, event.remind_minutes_before)
                else:
                    body = copy.event_reminder(
                        event.title,
                        copy.clock(local.hour, local.minute),
                        event.remind_minutes_before,
                    )
                self.deliver(
                    user,
                    subs,
                    EVENT_REMINDERS,
                    f"event:{event.pk}:{local.date().isoformat()}",
                    event.title,
                    body,
                    "/calendar",
                )

    # ---------------------------------------------------------------- morning

    def morning(self, user, subs, morning_time: dt.time):
        from api.recurrence import series_occurrences

        zone = _zone(subs[0].tz)
        local_now = self.now.astimezone(zone)
        if local_now.time() < morning_time:
            return
        today = local_now.date()
        key = f"morning:{today.isoformat()}"
        if self.already(user, TASK_DUE_MORNING, key):
            return
        day_start = dt.datetime.combine(today, dt.time(0), tzinfo=zone)
        day_end = dt.datetime.combine(today + dt.timedelta(days=1), dt.time(0), tzinfo=zone)
        tasks = list(
            self._visible_events(user).filter(kind="task", done=False, starts_at__isnull=False)
        )
        skips = self._skips([t.pk for t in tasks])
        due = []
        for task in tasks:
            occurrences = series_occurrences(
                task,
                skips.get(task.pk, ()),
                day_start,
                day_end - dt.timedelta(microseconds=1),
                zone,
            )
            if occurrences:
                due.append((occurrences[0], task.title))
        if not due:
            PushSent.objects.create(
                household=self.household,
                user=user,
                kind=TASK_DUE_MORNING,
                key=key,
                delivered=False,
                sent_at=self.now,
            )
            return
        due.sort(key=lambda pair: (pair[0], pair[1]))
        self.deliver(
            user,
            subs,
            TASK_DUE_MORNING,
            key,
            copy.TITLE_MORNING,
            copy.morning([title for _, title in due]),
            "/",
        )

    # -------------------------------------------------------------------- run

    def run(self) -> Report:
        for user, prefs, subs in list(self.recipients()):
            if prefs.list_changes:
                self.list_changes(user, subs)
            subs = [s for s in subs if s.pk is not None]
            if subs and prefs.event_reminders:
                self.event_reminders(user, subs)
            subs = [s for s in subs if s.pk is not None]
            if subs and prefs.task_due_morning:
                self.morning(user, subs, prefs.morning_time)
        return self.report


def dispatch_household(household, now: dt.datetime, send) -> Report:
    return _Dispatcher(household, now, send).run()
