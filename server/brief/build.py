"""Building and serving the brief (ai-upgrade ticket 02).

`build_for_household` is the one place a brief is made, by the 05:00 job and by
Refresh alike: one News call for the household, then each member's own
Canvas, Money and Car, stored as one `briefs` row per member, then the rows
older than `KEEP_DAYS` household-local days pruned.

**The household's clock, not the server's.** "Today", "yesterday" and the
05:00 gate all read `Household.timezone`. With none set, the UTC date is used
and every brief says so in words; the zone name itself is server-side date
math only and never reaches a prompt (CLAUDE.md section 1).
"""

from __future__ import annotations

import datetime as dt
from collections.abc import Callable, Iterable

from django.utils import timezone

from brief.compose import Viewer, canvas_section, car_section, money_section
from brief.models import KEEP_DAYS, Brief, Trigger
from brief.news import NewsResult, build_news
from household.timezones import zone_named

BUILD_HOUR = 5

NO_TIMEZONE = (
    "This household has no timezone set, so today and yesterday are UTC dates and the brief "
    "is built at 05:00 UTC. An owner can set one in Settings, Household."
)


def household_clock(household, now: dt.datetime) -> tuple[dt.tzinfo, dt.datetime, list[str]]:
    """(zone, local now, notes)."""
    zone = zone_named(household.timezone)
    notes: list[str] = []
    if zone is None:
        zone = dt.UTC
        notes.append(NO_TIMEZONE)
    return zone, now.astimezone(zone), notes


def is_due(household, now: dt.datetime) -> bool:
    """The 05:00 gate: local time is 05:00 or later, and no scheduled brief
    exists yet for the household's local date. Run hourly, this builds once a
    day at the first run on or after 05:00 local, through DST changes and for
    any household's zone."""
    _zone, local, _notes = household_clock(household, now)
    if local.hour < BUILD_HOUR:
        return False
    return not Brief.objects.filter(
        household=household, trigger=Trigger.SCHEDULED, brief_date=local.date()
    ).exists()


def members_of(household) -> list:
    from household.models import HouseholdMember

    return [
        member.user
        for member in HouseholdMember.objects.filter(household=household)
        .select_related("user")
        .order_by("user__email")
        if member.user.is_active
    ]


def compose(member, now: dt.datetime, zone, today: dt.date, news: NewsResult) -> list[dict]:
    viewer = Viewer(member)
    return [
        canvas_section(viewer, now, zone),
        money_section(viewer, today),
        car_section(viewer, zone, today),
        news.section,
    ]


def build_for_household(
    household,
    trigger: str,
    *,
    members: Iterable | None = None,
    now: dt.datetime | None = None,
    news_builder: Callable[[dt.date], NewsResult] | None = None,
    save: bool = True,
) -> list[Brief]:
    """One brief per member (all active members, or `members`). With
    `save=False` the rows are built and returned unsaved and nothing is
    pruned: the dry run's path."""
    now = now or timezone.now()
    zone, local, notes = household_clock(household, now)
    today = local.date()
    news = (news_builder or build_news)(today)
    briefs = []
    for member in members if members is not None else members_of(household):
        brief = Brief(
            household=household,
            user=member,
            brief_date=today,
            built_at=now,
            trigger=trigger,
            sections=compose(member, now, zone, today, news),
            notes=list(notes),
            **news.usage_fields(),
        )
        if save:
            brief.save()
        briefs.append(brief)
    if save:
        Brief.objects.filter(
            household=household, brief_date__lt=today - dt.timedelta(days=KEEP_DAYS - 1)
        ).delete()
    return briefs


def age_sentence(brief: Brief, now: dt.datetime) -> str:
    from ingest.freshness import ago

    how = "by the 05:00 build" if brief.trigger == Trigger.SCHEDULED else "by a refresh"
    return f"Built {ago(brief.built_at, now)}, {how}."


def payload(brief: Brief, now: dt.datetime) -> dict:
    """The served body. Never the member's or the household's id."""
    return {
        "id": brief.id,
        "brief_date": brief.brief_date,
        "built_at": brief.built_at,
        "trigger": brief.trigger,
        "age_sentence": age_sentence(brief, now),
        "news_ok": brief.news_ok,
        "notes": brief.notes or [],
        "sections": brief.sections,
    }


def describe(brief: Brief) -> str:
    """Plain text, for the dry run and the operator's terminal."""
    out = [f"Brief for {brief.user.email} on {brief.brief_date} ({brief.trigger})"]
    for note in brief.notes or []:
        out.append(f"  note: {note}")
    for sec in brief.sections:
        out.append(f"  [{sec['title']}] {sec['status']}")
        for item in sec["lines"]:
            flag = " (UNVERIFIED)" if item.get("unverified") else ""
            out.append(f"    - {item['text']}{flag}")
            for link in item.get("links") or []:
                out.append(f"        {link['title']}: {link['url']}")
        if sec.get("note"):
            out.append(f"    ({sec['note']})")
    if brief.cost_estimate_micro_usd is not None:
        out.append(
            f"  news call: {brief.total_tokens} tokens, {brief.search_queries} searches, "
            f"estimated ${brief.cost_estimate_micro_usd / 1_000_000:.4f} (estimate)"
        )
    return "\n".join(out)
