"""`Authorization: Token bw1_...`: the laptop's board writer token.

Listed ONLY on the views that name it, ahead of `DeviceTokenAuthentication`, and on each only the
methods that view lists in `writer_methods`: `PUT /api/board` (send the board), `GET
/api/board/ideas/pending` and `POST /api/board/ideas/<id>/delivered` (pull the member's backlog
ideas; backlog intake 1122). A view that does not say is `PUT` only. On every other route a `bw1_`
key reaches `DeviceTokenAuthentication` alone, which finds no device token for it and answers 401
"Invalid token.": deny by construction.

Every dead key (unknown, revoked, the switch off, an owner no longer an active member of the row's
household, or a recruiter) gets the same 401 with the device-token words, so a holder cannot tell
which. `writer_last_used_at` is written at most once a minute.
"""

from __future__ import annotations

import hmac
from datetime import timedelta

from django.utils import timezone
from rest_framework import authentication, exceptions

from board import flags
from board.models import TOKEN_PREFIX, BoardSnapshot, hash_token
from household.models import HouseholdMember

AUTH_HEADER_PREFIX = "Token"
UNKNOWN = "Invalid token."
METHOD_REFUSAL = (
    "Nothing was read or written. This is the laptop's board writer token: it may only send the "
    "board (PUT /api/board) and pick up backlog ideas."
)
DEFAULT_METHODS = ("PUT",)
TOUCH_EVERY = timedelta(minutes=1)


def writer_key(request) -> str | None:
    header = authentication.get_authorization_header(request).split()
    if len(header) != 2 or header[0].decode("latin-1") != AUTH_HEADER_PREFIX:
        return None
    raw = header[1].decode("latin-1")
    return raw if raw.startswith(TOKEN_PREFIX) else None


def live_writer(raw: str) -> BoardSnapshot | None:
    digest = hash_token(raw)
    row = (
        BoardSnapshot.objects.select_related("owner_user__household_member")
        .filter(writer_key_hash=digest)
        .first()
    )
    if row is None or not hmac.compare_digest(row.writer_key_hash or "", digest):
        return None
    if not row.owner_user.is_active:
        return None
    member = getattr(row.owner_user, "household_member", None)
    if (
        member is None
        or member.household_id != row.household_id
        or member.role == HouseholdMember.RECRUITER
    ):
        return None
    return row


class BoardWriterAuthentication(authentication.BaseAuthentication):
    def authenticate(self, request):
        raw = writer_key(request)
        if raw is None:
            return None
        if not flags.board_enabled():
            raise exceptions.AuthenticationFailed(UNKNOWN)
        row = live_writer(raw)
        if row is None:
            raise exceptions.AuthenticationFailed(UNKNOWN)
        view = (getattr(request, "parser_context", None) or {}).get("view")
        allowed = getattr(view, "writer_methods", DEFAULT_METHODS)
        if (request.method or "").upper() not in allowed:
            raise exceptions.PermissionDenied(METHOD_REFUSAL)
        now = timezone.now()
        if row.writer_last_used_at is None or now - row.writer_last_used_at >= TOUCH_EVERY:
            BoardSnapshot.objects.filter(pk=row.pk).update(writer_last_used_at=now)
            row.writer_last_used_at = now
        return row.owner_user, row

    def authenticate_header(self, request):
        return AUTH_HEADER_PREFIX
