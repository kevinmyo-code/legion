"""Action records: deploy, publish, flag and spend, asked by the laptop and allowed by his tap
(control room C3), DARK behind `LEGION_BOARD_ACTIONS` (`board.flags.actions_enabled`).

Kevin, 2026-11-22: a tap counts as his word for deploys and spending; deploy, publish, flag and
spend need a biometric or PIN confirm on the phone; a spend tap is capped at $25.

**What the engine guarantees.**
- A record is made ONLY by the laptop's writer token (`board.sh request`, after the tier script,
  CI green and "Confirmed: SHIP at <sha>"), from TYPED fields only: an unknown field, a bad shape,
  a spend over the cap or a review that is not of the same sha is refused, and nothing is stored.
- `record_sha256` is the engine's hash of exactly the typed fields (`record_hash`). His tap sends
  it back; a different hash is 409 ("changed"), so he allows only what he saw.
- One tap, once: the row is locked (`select_for_update`); a second tap on a decided record is 409
  ("replay"); a record past `expires_at` (30 minutes on the ENGINE's clock, never the laptop's) is
  410. The cap is checked again at the tap.
- Every tap, whatever it came to, is one row in the append-only tap log (`BoardActionTap`).
- Allowed by a member's DEVICE token only: never a browser session, the writer token, a head-unit
  token, a recruiter, a tool or a voice path.

**What it does not guarantee.** The engine cannot see the fingerprint or PIN: the confirm runs on
the phone (`ui/controlroom/ActionConfirm.kt`), as the ID vault's does (ADR 0076). And an approval
runs nothing by itself: the laptop pulls approved records, and the orchestrator re-checks the hash
and the head before running the pinned ship script.
"""

from __future__ import annotations

import hashlib
import json
import re
from datetime import timedelta

from django.db import transaction
from django.utils import timezone

from board.models import (
    ACTION_APPROVED,
    ACTION_DECLINED,
    ACTION_PENDING,
    ACTION_PULLED,
    BoardAction,
    BoardActionTap,
)

KINDS: tuple[str, ...] = (
    "deploy_engine",
    "publish_phone",
    "publish_headunit",
    "flag_on",
    "flag_off",
    "spend",
)
SHIP_KINDS = ("deploy_engine", "publish_phone", "publish_headunit")
FLAG_KINDS = ("flag_on", "flag_off")
TIERS = ("GREEN", "YELLOW", "RED")
FIELDS = frozenset(
    {
        "kind",
        "sha",
        "tier",
        "review_ok_sha",
        "ci_green",
        "flags",
        "amount_cents",
        "payee",
        "purpose",
    }
)
SPEND_CAP_CENTS = 2500
EXPIRES_AFTER = timedelta(minutes=30)
MAX_FLAGS = 8
SHA = re.compile(r"^[0-9a-f]{40}$")
FLAG = re.compile(r"^LEGION_[A-Z0-9_]{1,40}$")
PAYEE_MAX = 60
PURPOSE_MAX = 120
_PLAIN = re.compile(r"^[A-Za-z0-9 .,&'()/-]*$")
LIVE_FOR_LIST = timedelta(days=1)

NOT_ALLOWED = "Nothing was stored. An action record takes only its typed fields; {name} is not one."
BAD = "Nothing was stored. {why}"
CHANGED = "Nothing was allowed. The record changed after you saw it."
REPLAY = "Nothing was allowed. That record was already decided."
EXPIRED = "Nothing was allowed. That record expired; ask for a new one."
OVER_CAP = f"Nothing was allowed. A spend tap is capped at ${SPEND_CAP_CENTS // 100}."
UNKNOWN = "Nothing was allowed. No such record."
ALLOWED = (
    "Allowed. Nothing runs by itself: the laptop picks it up and the orchestrator re-checks it."
)
DECLINED = "Declined. Nothing will run."


class Refused(ValueError):
    def __init__(self, message: str, status: int = 400):
        super().__init__(message)
        self.status = status


def _cents(value) -> int | None:
    if isinstance(value, bool) or not isinstance(value, int):
        return None
    return value


def validate(body) -> dict:
    """The typed fields of a new record, or `Refused` naming what is wrong (never a value)."""
    if not isinstance(body, dict):
        raise Refused(BAD.format(why="The body must be an object."))
    for name in body:
        if name not in FIELDS:
            raise Refused(NOT_ALLOWED.format(name=str(name)[:40]))
    kind = body.get("kind")
    if kind not in KINDS:
        raise Refused(BAD.format(why="Unknown kind."))
    out = {
        "kind": kind,
        "sha": "",
        "tier": "",
        "review_ok_sha": "",
        "ci_green": False,
        "flags": [],
        "amount_cents": None,
        "payee": "",
        "purpose": "",
    }
    if kind in SHIP_KINDS or kind in FLAG_KINDS:
        sha, review = body.get("sha"), body.get("review_ok_sha")
        if not isinstance(sha, str) or not SHA.match(sha):
            raise Refused(BAD.format(why="A ship or flag record names the full 40-character sha."))
        if review != sha:
            raise Refused(BAD.format(why="The review must have confirmed this same sha."))
        if body.get("tier") not in TIERS:
            raise Refused(BAD.format(why="The tier is GREEN, YELLOW or RED."))
        if body.get("ci_green") is not True:
            raise Refused(BAD.format(why="Every workflow on that sha must be green."))
        out.update(sha=sha, review_ok_sha=review, tier=body["tier"], ci_green=True)
    if kind in FLAG_KINDS:
        flags = body.get("flags")
        if (
            not isinstance(flags, list)
            or not 1 <= len(flags) <= MAX_FLAGS
            or not all(isinstance(f, str) and FLAG.match(f) for f in flags)
            or len(set(flags)) != len(flags)
        ):
            raise Refused(BAD.format(why="A flag record names one to eight LEGION_ switches."))
        out["flags"] = sorted(flags)
    elif body.get("flags") not in (None, []):
        raise Refused(BAD.format(why="Only a flag record names switches."))
    if kind == "spend":
        cents = _cents(body.get("amount_cents"))
        if cents is None or cents <= 0:
            raise Refused(BAD.format(why="A spend names a whole number of cents above zero."))
        if cents > SPEND_CAP_CENTS:
            raise Refused(BAD.format(why=f"A spend tap is capped at ${SPEND_CAP_CENTS // 100}."))
        payee, purpose = body.get("payee"), body.get("purpose")
        for value, limit, name in ((payee, PAYEE_MAX, "payee"), (purpose, PURPOSE_MAX, "purpose")):
            if not isinstance(value, str) or not value.strip() or len(value) > limit:
                raise Refused(BAD.format(why=f"A spend names its {name} in plain words."))
            if not _PLAIN.match(value):
                raise Refused(
                    BAD.format(why=f"The {name} takes letters, digits and . , & ' ( ) / -")
                )
        out.update(amount_cents=cents, payee=payee.strip(), purpose=purpose.strip())
    else:
        for name in ("amount_cents", "payee", "purpose"):
            if body.get(name) not in (None, ""):
                raise Refused(BAD.format(why="Only a spend names an amount, a payee or a purpose."))
    return out


def record_hash(fields: dict, record_id, expires_at) -> str:
    """SHA-256 over the typed fields, the record's id and its expiry, as compact sorted JSON."""
    canonical = {**{k: fields[k] for k in sorted(FIELDS)}, "id": str(record_id)}
    canonical["expires_at"] = expires_at.isoformat()
    raw = json.dumps(canonical, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


def _fields(row: BoardAction) -> dict:
    return {
        "kind": row.kind,
        "sha": row.sha,
        "tier": row.tier,
        "review_ok_sha": row.review_ok_sha,
        "ci_green": row.ci_green,
        "flags": list(row.flags or []),
        "amount_cents": row.amount_cents,
        "payee": row.payee,
        "purpose": row.purpose,
    }


def create(*, household, owner_user, body, now=None) -> BoardAction:
    fields = validate(body)
    now = now or timezone.now()
    row = BoardAction(
        household=household,
        owner_user=owner_user,
        created_at=now,
        expires_at=now + EXPIRES_AFTER,
        **fields,
    )
    row.record_sha256 = record_hash(fields, row.id, row.expires_at)
    row.save()
    return row


def _tap(row: BoardAction, *, owner_user, decision, sha, result) -> None:
    BoardActionTap.objects.create(
        household_id=row.household_id,
        owner_user=owner_user,
        action_id=row.id,
        decision=decision,
        record_sha256=sha if isinstance(sha, str) else "",
        result=result,
    )


def decide(*, owner_user, action_id, record_sha256, allow: bool, now=None) -> BoardAction:
    """His tap: Allow (`allow`) or Decline, once, on the record he saw."""
    decision = "allow" if allow else "decline"
    sha = record_sha256 if isinstance(record_sha256, str) else ""
    with transaction.atomic():
        row = (
            BoardAction.objects.select_for_update()
            .filter(owner_user=owner_user, pk=action_id)
            .first()
        )
        if row is None:
            raise Refused(UNKNOWN, 404)
        now = now or timezone.now()
        fields = _fields(row)
        refusal = None
        if row.status != ACTION_PENDING:
            refusal = ("replay", REPLAY, 409)
        elif now >= row.expires_at:
            refusal = ("expired", EXPIRED, 410)
        elif sha != row.record_sha256 or record_hash(fields, row.id, row.expires_at) != sha:
            refusal = ("changed", CHANGED, 409)
        elif allow and row.kind == "spend" and not 0 < (row.amount_cents or 0) <= SPEND_CAP_CENTS:
            refusal = ("over_cap", OVER_CAP, 400)
        if refusal is not None:
            # Logged and committed: the refusal is raised only after the block, so the tap log
            # keeps it while the record stays untouched.
            _tap(row, owner_user=owner_user, decision=decision, sha=sha, result=refusal[0])
            return_refusal = Refused(refusal[1], refusal[2])
        else:
            row.status = ACTION_APPROVED if allow else ACTION_DECLINED
            row.decided_at = now
            row.save(update_fields=["status", "decided_at"])
            _tap(row, owner_user=owner_user, decision=decision, sha=sha, result="applied")
            return_refusal = None
    if return_refusal is not None:
        raise return_refusal
    return row


def open_for(owner_user, now=None) -> list[BoardAction]:
    """Pending records not yet expired, plus anything decided in the last day, newest first."""
    now = now or timezone.now()
    rows = BoardAction.objects.filter(owner_user=owner_user, created_at__gte=now - LIVE_FOR_LIST)
    return list(rows.order_by("-created_at"))


def pending_count(owner_user, now=None) -> int:
    now = now or timezone.now()
    return BoardAction.objects.filter(
        owner_user=owner_user, status=ACTION_PENDING, expires_at__gt=now
    ).count()


def approved_for(owner_user) -> list[BoardAction]:
    return list(
        BoardAction.objects.filter(owner_user=owner_user, status=ACTION_APPROVED).order_by(
            "decided_at"
        )
    )


def mark_pulled(owner_user, action_id, now=None) -> int:
    return BoardAction.objects.filter(
        owner_user=owner_user, pk=action_id, status=ACTION_APPROVED
    ).update(status=ACTION_PULLED, pulled_at=now or timezone.now())


def as_dict(row: BoardAction, now=None) -> dict:
    now = now or timezone.now()
    status = row.status
    if status == ACTION_PENDING and now >= row.expires_at:
        status = "expired"
    return {
        "id": row.id,
        **_fields(row),
        "record_sha256": row.record_sha256,
        "status": status,
        "created_at": row.created_at,
        "expires_at": row.expires_at,
        "decided_at": row.decided_at,
    }
