"""`GET /api/freshness` - how current each scheduled feed is, for the caller's
household, in words (backend-etl ticket 01; map ruling 6: "a stale or failing
feed is said in words on the web and phone surfaces that use it").

**Surfaces render `sentence` and never compose their own.** Two surfaces
composing "last synced" from `last_ok_at` would be two phrasings of one fact,
free to drift, and one of them would eventually say "synced 0 minutes ago" for
a feed that failed. The words live here, once, next to the rule that decides
them.

What each field means:

- `last_ok_at`: when the newest `ok` run FINISHED. Null if there never was one.
- `last_outcome` / `last_error`: the newest finished run, ignoring
  `skipped_locked` (that only says another run was already going, which is not
  news about the feed). A run still in progress is ignored too.
- `stale`: no `ok` inside the source's threshold. False for a source this
  household has not set up (newest outcome `skipped`): a feed nobody asked for
  is not late.
"""
from __future__ import annotations

import datetime
from dataclasses import dataclass

from django.utils import timezone
from drf_spectacular.utils import OpenApiResponse, extend_schema
from rest_framework import serializers
from rest_framework.response import Response
from rest_framework.views import APIView

from household.tenancy import household_of, scoped
from ingest.models import LOGIN_SCRIPT, SESSION_FOR_SOURCE, IngestRun, Outcome, Source
from ingest.statements import quarantined_files

# Ticket 01's thresholds, in code as the ticket says. `heartbeat` is not in the
# ticket's list: it runs every 30 minutes, so two missed beats is the line.
STALE_AFTER: dict[str, datetime.timedelta] = {
    Source.CANVAS: datetime.timedelta(hours=2),
    Source.WEBASSIGN: datetime.timedelta(hours=36),
    Source.DRIVE_STATEMENTS: datetime.timedelta(hours=36),
    Source.BACKUP: datetime.timedelta(hours=36),
    Source.OBD_ROLLUP: datetime.timedelta(hours=36),
    Source.HEARTBEAT: datetime.timedelta(hours=1),
    # Every five minutes, so three missed runs is the line.
    Source.PUSH: datetime.timedelta(minutes=15),
}


# ADR 0041 gap: photos and recordings have no durable store, so the nightly
# backup cannot include them (backend-etl ticket 03).
BACKUP_MEDIA_GAP = "Photos and recordings are not in it yet."


@dataclass(frozen=True)
class _Words:
    subject: str
    done: str  # "last synced", completed with "3 hours ago"
    never: str  # the whole predicate for "no ok run, ever"


WORDS: dict[str, _Words] = {
    Source.CANVAS: _Words("Canvas", "last synced", "has never synced"),
    Source.WEBASSIGN: _Words("WebAssign", "last synced", "has never synced"),
    Source.DRIVE_STATEMENTS: _Words(
        "The statements folder", "was last checked", "has never been checked"
    ),
    Source.BACKUP: _Words("The backup", "last ran", "has never run"),
    Source.OBD_ROLLUP: _Words("The drive roll-up", "last ran", "has never run"),
    Source.HEARTBEAT: _Words("The scheduler", "last ran", "has never run"),
    Source.PUSH: _Words("Notifications", "were last checked", "have never been checked"),
}


def ago(then: datetime.datetime, now: datetime.datetime) -> str:
    seconds = max(0, int((now - then).total_seconds()))
    if seconds < 60:
        return "just now"
    minutes = seconds // 60
    if minutes < 60:
        return f"{minutes} minute{'s' if minutes != 1 else ''} ago"
    hours = minutes // 60
    if hours < 48:
        return f"{hours} hour{'s' if hours != 1 else ''} ago"
    days = hours // 24
    return f"{days} days ago"


# Ticket 06: how long a quarantined statement file keeps being said on the
# freshness line. It is never re-read (its content hash is on record), so the
# line is the prompt to act on it; the permanent record is `ingested_files`.
QUARANTINE_WINDOW = datetime.timedelta(days=30)
QUARANTINE_NAMES_SHOWN = 3


def quarantine_sentence(files) -> str:
    """One sentence naming the statement files quarantined (by the gate, or
    before it: an unrecognised CSV, a missing anchor), with the newest one's
    reason. Empty when there are none."""
    files = list(files)
    if not files:
        return ""
    shown = files[:QUARANTINE_NAMES_SHOWN]
    names = ", ".join(f.display_name or f.content_sha256[:12] for f in shown)
    if len(files) > QUARANTINE_NAMES_SHOWN:
        names += f" and {len(files) - QUARANTINE_NAMES_SHOWN} more"
    count = len(files)
    noun = "file was" if count == 1 else "files were"
    reason = (files[0].quarantine_reason or "").strip()
    return (
        f"{count} statement {noun} quarantined and nothing from "
        f"{'it' if count == 1 else 'them'} was written: {names}."
        + (f" {'Reason' if count == 1 else 'Latest reason'}: {reason}" if reason else "")
    )


def sentence(
    source: str,
    last_ok_at: datetime.datetime | None,
    last_outcome: str | None,
    now: datetime.datetime,
    *,
    detail: str | None = None,
    quarantined=(),
) -> str:
    words = WORDS[source]
    refused = quarantine_sentence(quarantined)
    tail = f" {refused}" if refused else ""
    if last_outcome == Outcome.SKIPPED:
        if detail:
            # A job that returned `skipped` and said why (ticket 06: no key).
            return f"{words.subject} is not being read. {detail}{tail}"
        return f"{words.subject} is not set up."
    if last_outcome == Outcome.NEEDS_LOGIN:
        # Ticket 02: say what to run, since the fix is a person at a laptop.
        session = SESSION_FOR_SOURCE.get(source)
        if session is None:
            return f"{words.subject} needs you to log in again."
        return f"{words.subject} needs you to log in again: run {LOGIN_SCRIPT} {session}"
    if last_ok_at is None:
        base = f"{words.subject} {words.never}."
    else:
        base = f"{words.subject} {words.done} {ago(last_ok_at, now)}."
        if source == Source.BACKUP:
            # Ticket 03: a backup that ran is not everything saved. Said here,
            # where every surface reads it, until media has a durable store.
            base += f" {BACKUP_MEDIA_GAP}"
    if last_outcome == Outcome.FAILED:
        return f"{base} The latest attempt failed.{tail}"
    return f"{base}{tail}"


def freshness_for(runs, source: str, now: datetime.datetime, *, quarantined=()) -> dict:
    """One source's entry. `runs` is ALREADY scoped to one household - this
    function never widens it, and neither may the caller's `quarantined`."""
    of_source = runs.filter(source=source)
    last_ok = (
        of_source.filter(outcome=Outcome.OK, finished_at__isnull=False)
        .order_by("-finished_at")
        .first()
    )
    latest = (
        of_source.filter(outcome__isnull=False)
        .exclude(outcome=Outcome.SKIPPED_LOCKED)
        .order_by("-started_at")
        .first()
    )
    last_ok_at = last_ok.finished_at if last_ok else None
    last_outcome = latest.outcome if latest else None
    if last_outcome == Outcome.SKIPPED:
        stale = False
    else:
        stale = last_ok_at is None or now - last_ok_at > STALE_AFTER[source]
    return {
        "source": source,
        "last_ok_at": last_ok_at,
        "last_outcome": last_outcome,
        "last_error": latest.error if latest else None,
        "stale": stale,
        "sentence": sentence(
            source,
            last_ok_at,
            last_outcome,
            now,
            detail=latest.error if latest and last_outcome == Outcome.SKIPPED else None,
            quarantined=quarantined,
        ),
    }


class FreshnessSourceSerializer(serializers.Serializer):
    source = serializers.ChoiceField(choices=Source.choices)
    last_ok_at = serializers.DateTimeField(allow_null=True)
    last_outcome = serializers.ChoiceField(choices=Outcome.choices, allow_null=True)
    last_error = serializers.CharField(allow_null=True)
    stale = serializers.BooleanField()
    sentence = serializers.CharField()


class FreshnessSerializer(serializers.Serializer):
    sources = FreshnessSourceSerializer(many=True)


class FreshnessView(APIView):
    @extend_schema(
        operation_id="api_freshness_retrieve",
        tags=["freshness"],
        responses={
            200: OpenApiResponse(
                response=FreshnessSerializer,
                description=(
                    "One entry per scheduled source, always all of them, for the caller's "
                    "household only. Render `sentence` as it is; do not compose a phrase "
                    "from `last_ok_at`. `stale` is true when no run succeeded inside the "
                    "source's threshold (canvas 2h, webassign/drive_statements/backup/"
                    "obd_rollup 36h, heartbeat 1h), and false for a source the household "
                    "has not set up."
                ),
            ),
        },
    )
    def get(self, request):
        now = timezone.now()
        runs = scoped(IngestRun, request)
        refused = list(
            quarantined_files(household_of(request), since=now - QUARANTINE_WINDOW)
        )
        body = {
            "sources": [
                freshness_for(
                    runs,
                    source,
                    now,
                    quarantined=refused if source == Source.DRIVE_STATEMENTS else (),
                )
                for source in Source.values
            ]
        }
        return Response(FreshnessSerializer(body).data)
