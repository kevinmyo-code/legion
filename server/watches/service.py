"""Watches for one member: list, set, change, remove, and the firings the phone says (ADR 0083).
The REST views (`watches/views.py`) call these, and the phone's voice tool and Watches screen
both call those views (ADR 0035: one implementation, two doors).

**Who may use watches.** A member of a real household, on the phone or a browser. Refused in
words: a recruiter visit (ADR 0068, default deny), and any Claude worker token (ADR 0067): a
watch is a durable write that later causes unprompted speech, and the Claude worker may read mail,
so third-party content could become a watch through it. The voice door has a second fence on the
phone: a turn that read mail or the web cannot set or change a watch (round 3's per-turn
read-through flag, the same one `remember` uses).
"""

from __future__ import annotations

import datetime as dt

from django.db import IntegrityError, transaction
from django.utils import timezone

from claude_session.tokens import is_claude_token
from household.models import is_recruiter
from household.tenancy import household_of, visible
from watches.models import (
    OUTCOMES,
    SOURCE_BUILTIN,
    SOURCE_SCREEN,
    SOURCES,
    Watch,
    WatchFiring,
)
from watches.registry import (
    BUILTINS,
    REGISTRY,
    WatchRefused,
    clean_label,
    clean_params,
    label_for,
)

RECRUITER_REFUSAL = "Nothing was read or written. Watches are not part of the demo."
CLAUDE_REFUSAL = (
    "Nothing was read or written. A Claude session cannot set or read watches: a watch makes "
    "Alfred speak later, and only the member, by voice or on the phone's screen, sets one."
)
DEMO_REFUSAL = "Nothing was read or written. A demo visit has no watches."
NOT_FOUND = "No such watch. Nothing was changed. List watches for their ids."
PENDING_LIMIT = 20
RECENT_LIMIT = 30


def refusal(request) -> str | None:
    user = getattr(request, "user", None)
    if is_recruiter(user):
        return RECRUITER_REFUSAL
    if is_claude_token(request):
        return CLAUDE_REFUSAL
    household = getattr(user, "household", None)
    if household is not None and getattr(household, "is_demo_visit", False):
        return DEMO_REFUSAL
    return None


def mine(request):
    """The caller's own watches, never another member's (`visible()`; `owner_user` is never
    null)."""
    return visible(Watch, request).filter(deleted_at__isnull=True)


def ensure_defaults(request) -> int:
    """Seeds the built-in watches (the engine signals) for this member, once each. A built-in the
    member removed is never seeded again: its row stays, removed. Returns how many were added."""
    household = household_of(request)
    added = 0
    for builtin in BUILTINS:
        if Watch.objects.filter(
            household=household, owner_user=request.user, builtin=builtin.key
        ).exists():
            continue
        try:
            with transaction.atomic():
                Watch.objects.create(
                    household=household,
                    owner_user=request.user,
                    kind=builtin.kind,
                    params=dict(builtin.params),
                    label=label_for(builtin.kind, builtin.params),
                    builtin=builtin.key,
                    source=SOURCE_BUILTIN,
                )
            added += 1
        except IntegrityError:
            continue
    return added


def listing(request) -> list[Watch]:
    ensure_defaults(request)
    return list(mine(request).order_by("builtin", "created_at", "id"))


def _source_note(request, kind: str) -> str | None:
    """What the watch cannot see yet, in words (unreadable is not empty). None when its source
    is there, or for a phone-evaluated kind (the phone says whether it can see mail)."""
    from ingest.models import PlaidItem, SourceCredential

    household = household_of(request)
    spec = REGISTRY[kind]
    if spec.evaluated_on != "engine":
        return None
    if spec.needs == "a linked bank":
        from legacy.models.ledger import LedgerTransaction

        if (
            PlaidItem.objects.filter(household=household).exists()
            or LedgerTransaction.objects.filter(household=household).exists()
        ):
            return None
    elif spec.needs == "Canvas connected":
        if SourceCredential.objects.filter(household=household, source="canvas").exists():
            return None
    else:
        from legacy.models.fleet import MaintenanceSchedule

        if MaintenanceSchedule.objects.filter(
            household=household, deleted_at__isnull=True
        ).exists():
            return None
    return f"It cannot fire yet: it needs {spec.needs}, and the engine has none."


def _expiry(raw) -> dt.datetime | None:
    if raw in (None, ""):
        return None
    if isinstance(raw, dt.datetime):
        value = raw
    else:
        try:
            value = dt.datetime.fromisoformat(str(raw).replace("Z", "+00:00"))
        except ValueError as exc:
            raise WatchRefused(
                f"No watch was set. expires_at must be a date and time; got {raw!r}."
            ) from exc
    if value.tzinfo is None:
        value = value.replace(tzinfo=dt.UTC)
    if value <= timezone.now():
        raise WatchRefused("No watch was set. expires_at is already past.")
    return value


def create(
    request, *, kind, params=None, label=None, repeat=True, expires_at=None, source=SOURCE_SCREEN
) -> tuple[Watch, str | None]:
    """Sets a watch. Returns it and the note on what it cannot see yet (or None)."""
    if not isinstance(kind, str) or kind not in REGISTRY:
        from watches.registry import unknown_kind_refusal

        raise WatchRefused(unknown_kind_refusal(str(kind), str(label or "")))
    if source not in SOURCES or source == SOURCE_BUILTIN:
        raise WatchRefused("No watch was set. source is voice or screen.")
    clean = clean_params(kind, params)
    watch = Watch.objects.create(
        household=household_of(request),
        owner_user=request.user,
        kind=kind,
        params=clean,
        label=clean_label(label, kind, clean),
        repeat=bool(repeat),
        expires_at=_expiry(expires_at),
        source=source,
    )
    return watch, _source_note(request, kind)


def get(request, watch_id) -> Watch:
    watch = mine(request).filter(pk=watch_id).first()
    if watch is None:
        raise WatchRefused(NOT_FOUND, status=404)
    return watch


def change(request, watch_id, fields: dict) -> Watch:
    """Pause, resume, rename, re-param, or change repeat/expiry. Resuming re-arms: facts from
    while it was paused never fire (no backfill)."""
    watch = get(request, watch_id)
    now = timezone.now()
    if "params" in fields:
        watch.params = clean_params(watch.kind, fields["params"])
        watch.armed_at = now
        watch.baselined_at = None
        watch.state = {}
        if "label" not in fields:
            watch.label = label_for(watch.kind, watch.params)
    if "label" in fields:
        watch.label = clean_label(fields["label"], watch.kind, watch.params)
    if "repeat" in fields:
        watch.repeat = bool(fields["repeat"])
        if watch.repeat:
            watch.spent_at = None
    if "expires_at" in fields:
        watch.expires_at = _expiry(fields["expires_at"])
    if "paused" in fields:
        paused = bool(fields["paused"])
        if watch.paused and not paused:
            watch.armed_at = now
            watch.baselined_at = None
            watch.state = {}
            watch.spent_at = None
        watch.paused = paused
    watch.updated_at = now
    watch.save()
    return watch


def remove(request, watch_id) -> Watch:
    """ "Stop watching X". The row stays, removed, so a built-in is never seeded back and its
    firings keep their anchor; it is never evaluated or listed again."""
    watch = get(request, watch_id)
    now = timezone.now()
    Watch.objects.filter(pk=watch.pk).update(deleted_at=now, updated_at=now)
    watch.deleted_at = now
    return watch


def pending(request) -> list[WatchFiring]:
    """Firings the phone has not said yet: not acked, not baseline, not expired, from a watch
    that is still live and not paused ("stop watching X" silences what it already found)."""
    ensure_defaults(request)
    now = timezone.now()
    return list(
        visible(WatchFiring, request)
        .filter(
            acked_at__isnull=True,
            baseline=False,
            expires_at__gt=now,
            watch__deleted_at__isnull=True,
            watch__paused=False,
        )
        .select_related("watch")
        .order_by("fired_at", "id")[:PENDING_LIMIT]
    )


def recent(request) -> list[WatchFiring]:
    return list(
        visible(WatchFiring, request)
        .filter(baseline=False)
        .select_related("watch")
        .order_by("-fired_at", "-id")[:RECENT_LIMIT]
    )


def ack(request, ids, outcome: str) -> int:
    """Marks the caller's firings said (spoken, notified or silenced). Another member's id is
    simply not matched. Returns how many were marked."""
    if outcome not in OUTCOMES:
        raise WatchRefused(f"Nothing was marked. outcome is one of {', '.join(OUTCOMES)}.")
    if not isinstance(ids, list) or not ids:
        raise WatchRefused("Nothing was marked. ids must be a list of firing ids.")
    return (
        visible(WatchFiring, request)
        .filter(pk__in=[str(i) for i in ids], acked_at__isnull=True)
        .update(acked_at=timezone.now(), outcome=outcome)
    )


def watch_dict(watch: Watch) -> dict:
    spec = REGISTRY[watch.kind]
    now = timezone.now()
    if watch.paused:
        status = "paused"
    elif watch.spent_at is not None:
        status = "done"
    elif watch.expires_at is not None and watch.expires_at <= now:
        status = "expired"
    else:
        status = "on"
    return {
        "id": str(watch.id),
        "kind": watch.kind,
        "title": spec.title,
        "label": watch.label,
        "params": watch.params,
        "repeat": watch.repeat,
        "paused": watch.paused,
        "status": status,
        "builtin": watch.builtin,
        "evaluated_on": spec.evaluated_on,
        "expires_at": watch.expires_at.isoformat() if watch.expires_at else None,
        "last_fired_at": watch.last_fired_at.isoformat() if watch.last_fired_at else None,
        "created_at": watch.created_at.isoformat() if watch.created_at else None,
    }


def firing_dict(firing: WatchFiring) -> dict:
    return {
        "id": str(firing.id),
        "watch_id": str(firing.watch_id),
        "watch_label": firing.watch.label,
        "kind": firing.kind,
        "facts": firing.facts,
        "detail": firing.detail,
        "sources": firing.sources,
        "fired_at": firing.fired_at.isoformat(),
        "expires_at": firing.expires_at.isoformat(),
        "acked_at": firing.acked_at.isoformat() if firing.acked_at else None,
        "outcome": firing.outcome,
    }


def kinds_list() -> list[dict]:
    return [
        {
            "kind": k.key,
            "title": k.title,
            "says": k.says,
            "needs": k.needs,
            "evaluated_on": k.evaluated_on,
            "params": [
                {
                    "name": p.name,
                    "type": {int: "integer", str: "string", list: "list"}[p.type],
                    "required": p.required,
                    "default": p.default,
                    "minimum": p.minimum,
                    "maximum": p.maximum,
                    "choices": list(p.choices) if p.choices else None,
                    "words": p.words,
                }
                for p in k.params
            ],
        }
        for k in REGISTRY.values()
    ]
