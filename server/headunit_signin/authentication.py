"""`Authorization: Token hu1_...`: a head unit's own token (T3b).

The same header the head unit already sends (`Token <key>`), told apart by its `hu1_` prefix
without a query. Listed only on the views `headunit_signin.scope.ROUTES` names, ahead of
`DeviceTokenAuthentication`; everywhere else a head-unit key is an unknown device token (401).

**One answer for every dead key.** Unknown, expired, revoked, the switch off, an owner no longer
an active member of the credential's household, or a recruiter: all are the same 401 with the
same words device tokens use ("Invalid token."), so the response never tells a holder which.

**Checked on every request**: the hash (looked up by its index, then compared in constant time),
`revoked_at`, `expires_at`, the owner's `is_active` and membership. One query, joined.

**Last used** is written at most once per `TOUCH_EVERY` (5 minutes) per credential: a turn
streams many requests (turn, cancel, uploads) and a write on each would put a row lock on the hot
path for a value only the member's list reads.
"""

from __future__ import annotations

import hmac
from datetime import timedelta

from django.utils import timezone
from rest_framework import authentication, exceptions

from headunit_signin import flags, scope
from headunit_signin.models import TOKEN_PREFIX, HeadUnitCredential, hash_token
from household.models import HouseholdMember

AUTH_HEADER_PREFIX = "Token"
# The words `DeviceTokenAuthentication` uses for an unknown key, so the two cannot be told apart.
UNKNOWN = "Invalid token."
ROUTE_REFUSAL = (
    "Nothing was read or written. This is a head unit's token: it reaches only the head unit's "
    "voice turn and its own uploads."
)
TOUCH_EVERY = timedelta(minutes=5)


def headunit_key(request) -> str | None:
    """The raw key when the request carries a head-unit token, else None. No query."""
    header = authentication.get_authorization_header(request).split()
    if len(header) != 2 or header[0].decode("latin-1") != AUTH_HEADER_PREFIX:
        return None
    raw = header[1].decode("latin-1")
    return raw if raw.startswith(TOKEN_PREFIX) else None


def live_credential(raw: str, now=None) -> HeadUnitCredential | None:
    """The live credential this key belongs to, or None for any dead or unknown key."""
    now = now or timezone.now()
    digest = hash_token(raw)
    row = (
        HeadUnitCredential.objects.select_related("owner_user__household_member")
        .filter(key_hash=digest)
        .first()
    )
    if row is None or not hmac.compare_digest(row.key_hash, digest):
        return None
    if not row.is_live(now) or not row.owner_user.is_active:
        return None
    member = getattr(row.owner_user, "household_member", None)
    if (
        member is None
        or member.household_id != row.household_id
        or member.role == HouseholdMember.RECRUITER
    ):
        return None
    return row


def touch(row: HeadUnitCredential, now) -> None:
    if row.last_used_at is None or now - row.last_used_at >= TOUCH_EVERY:
        HeadUnitCredential.objects.filter(pk=row.pk).update(last_used_at=now)
        row.last_used_at = now


class HeadUnitTokenAuthentication(authentication.BaseAuthentication):
    def authenticate(self, request):
        raw = headunit_key(request)
        if raw is None:
            return None
        if not flags.signin_enabled():
            raise exceptions.AuthenticationFailed(UNKNOWN)
        now = timezone.now()
        row = live_credential(raw, now)
        if row is None:
            raise exceptions.AuthenticationFailed(UNKNOWN)
        # The second lock: this class opens nothing on a route the allow-list does not name.
        if not scope.route_allowed(request):
            raise exceptions.PermissionDenied(ROUTE_REFUSAL)
        touch(row, now)
        return row.owner_user, row

    def authenticate_header(self, request):
        return AUTH_HEADER_PREFIX
