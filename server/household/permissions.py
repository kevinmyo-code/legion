"""Section 1's trust model as a permission class.

**It used to read** "two adults, no roles, no tenancy. Every authenticated
request either belongs to the one household or it is refused outright."
ADR 0045 changed the middle clause and left the rest exactly as it was: there
are households now, so this answers "is this user in ANY household" and
`household.tenancy.household_of` answers "which one". Inside a household
access is still all-or-nothing - no partial access to grant, no role that
gates data - so this class is unchanged in code, and that is the point worth
recording rather than the diff.

**A second class joined it 2026-09-10** (web-and-households ticket 03):
`IsHouseholdOwner`, for the four membership verbs ADR 0045's one role
exists for. It does not weaken the paragraph above - inside a household
access is still all-or-nothing, and the owner role still gates no data.

**ADR 0068 (2026-11-02) amends that once, and only to narrow:** a member
whose role is `recruiter` (a throwaway visitor, recruiter-demo tickets 01
and 02) is fenced, and a browser session it holds dies with the visitor
token it was issued beside. Owners and members are untouched by every line
of that.

**ADR 0069 (ticket 13, Kevin 2026-11-02) moved the fence, and it still only
narrows.** Each visit now has its own throwaway household
(`Household.is_demo_visit`) with the visitor as its only member, so the
recruiter uses the normal app on it: every route named in
`household.recruiter_routes` as allowed, reads and writes, and nothing named
there as denied (outside accounts, devices, body, settings, `/mcp`). A view
in neither list is refused. A recruiter whose household is NOT a demo visit
(one made before ticket 13, in the shared demo household) is refused
everything, as an ended visit.
"""
from __future__ import annotations

from rest_framework import exceptions, permissions

from household.models import DeviceToken, HouseholdMember, is_recruiter
from household.recruiter_routes import route_refusal

READ_SCOPE_REFUSAL = (
    "Nothing was written. This device token is read-only (scope `read`), so it may read "
    "this household's data and change none of it. Use a token issued with scope `write` "
    "to make changes."
)


RECRUITER_WRITE_REFUSAL = (
    "Nothing was written. This is a recruiter's demo visit, and it may change only its "
    "own copy of the demo household, which this is not."
)

RECRUITER_VISIT_OVER = (
    "Nothing was read or written. This demo visit has ended or was closed. Enter the "
    "invite code again to start a new one."
)

# Beside the words, so a client can tell a visit that ended from a caller who
# was never signed in: `{"detail": RECRUITER_VISIT_OVER, "code": ...}`.
RECRUITER_VISIT_OVER_CODE = "demo_visit_ended"

# The session key `demo/views.py` writes beside `login()`: the id of the visitor
# token minted in the same request. A recruiter's browser session is honoured
# only while that token is live, so expiry and revocation reach the cookie too.
DEMO_TOKEN_SESSION_KEY = "demo_token_id"


def token_may_write(request) -> bool:
    """False only for a request authenticated by a `read`-scoped device token.

    A browser session (`request.auth` is None) and every token minted before
    engine-mcp ticket 05 (migration 0004 gave them `write`) may write, exactly
    as before. `/mcp` asks this per TOOL rather than per HTTP method, because
    every MCP call is a POST whether it reads or writes."""
    auth = getattr(request, "auth", None)
    return not (isinstance(auth, DeviceToken) and not auth.can_write)


def write_refusal(request) -> str | None:
    """Why this request may not write, in words, or None when it may.

    Two fences, checked in this order. A recruiter (ADR 0068) may write only
    inside its own demo-visit household (ADR 0069: `is_demo_visit`, a
    household it alone belongs to), whatever credential it carries. Then the
    token's own scope (engine-mcp ticket 05): the visitor token is `read`, so
    a visit writes through its browser session only."""
    if is_recruiter(getattr(request, "user", None)) and not _on_a_demo_visit(request.user):
        return RECRUITER_WRITE_REFUSAL
    if not token_may_write(request):
        return READ_SCOPE_REFUSAL
    return None


def _on_a_demo_visit(user) -> bool:
    """The recruiter's household is a per-visit copy (ADR 0069). Read from the
    row as it is now, never cached."""
    return HouseholdMember.objects.filter(
        user=user, role=HouseholdMember.RECRUITER, household__is_demo_visit=True
    ).exists()


def _recruiter_visit_is_live(request) -> bool:
    """A recruiter's credential must be its live visitor token, or a session
    tied to one. `DeviceTokenAuthentication` has already refused a dead token,
    so only the session needs looking up here."""
    if isinstance(getattr(request, "auth", None), DeviceToken):
        return True
    session = getattr(request, "session", None)
    token_id = session.get(DEMO_TOKEN_SESSION_KEY) if session is not None else None
    if token_id is None:
        return False
    token = DeviceToken.objects.filter(pk=token_id, user=request.user).first()
    return token is not None and token.is_live


class IsHouseholdMember(permissions.BasePermission):
    def has_permission(self, request, view) -> bool:
        user = request.user
        if not user or not user.is_authenticated:
            return False
        if not HouseholdMember.objects.filter(user=user).exists():
            return False
        # ADR 0068/0069: a recruiter's visit must be live and on its own
        # per-visit household, and the route must be one
        # `household.recruiter_routes` opens to it - here, once, so a route
        # added next month is closed to it until someone classifies it.
        # Checked before the write fence so the refusal names the real reason.
        if is_recruiter(user):
            if not _recruiter_visit_is_live(request) or not _on_a_demo_visit(user):
                raise exceptions.PermissionDenied(
                    {"detail": RECRUITER_VISIT_OVER, "code": RECRUITER_VISIT_OVER_CODE}
                )
            refusal = route_refusal(view, request.method, permissions.SAFE_METHODS)
            if refusal is not None:
                raise exceptions.PermissionDenied(refusal)
        # engine-mcp ticket 05: a read-scoped token is refused on every unsafe
        # method, here, once, rather than per view - a scope that only the MCP
        # layer honoured would let a leaked read token write through REST.
        # `/mcp` opts out (`scope_checked_per_call`) because its POST carries
        # reads and writes alike, and it refuses write TOOLS itself, in words.
        if (
            request.method not in permissions.SAFE_METHODS
            and not getattr(view, "scope_checked_per_call", False)
        ):
            refusal = write_refusal(request)
            if refusal is not None:
                raise exceptions.PermissionDenied(refusal)
        return True


class IsHouseholdOwner(IsHouseholdMember):
    """The ONE role, and it governs membership only (ADR 0045).

    Guards four membership verbs, all in `household/households.py`: rename the
    household, mint an invite, revoke an invite, remove a member. It must
    never appear on a data route - an owner and a member see exactly the same
    rows, and the day this class guards a read is the day "no roles inside a
    household" stopped being true.

    **One more verb, 2026-09-27** (backend-etl ticket 02, which names "owner
    role only"): handing the household a login for a feed, `PUT
    /api/ingest/sessions/<source>`, through the subclass in
    `ingest/sessions.py`. It is not a data route in the sense above: the
    secret is never readable by anyone, owner included, and every member
    sees the same metadata. It decides which outside account the household's
    feeds run as, which is who speaks for the household, the same shape as
    who may invite.

    **And one more, 2026-10-05** (Kevin): setting the household's timezone,
    `PATCH /api/households/me` with `timezone`. Every member reads it; it
    decides which calendar day "today" is for the whole household, so it is
    set once by the person who administers the household rather than moved by
    each member. It gates no row.

    **And one more, 2026-11-05** (alfred-ota ticket 04): publishing an app release, `POST
    /api/releases/`, through `releases.views.IsReleasePublisher`. Releases are engine-wide
    builds of the app, not household rows; every member reads them the same. It gates no row.

    Subclasses `IsHouseholdMember` rather than restating it: an owner is a
    member first, and the household-membership check is the one that decides
    whether the caller is inside the tenancy at all.
    """

    message = (
        "Nothing was changed. Only an owner of this household may rename it, invite "
        "people or remove them. An owner and a member see exactly the same data - the "
        "role governs membership and nothing else."
    )

    def has_permission(self, request, view) -> bool:
        if not super().has_permission(request, view):
            return False
        return HouseholdMember.objects.filter(
            user=request.user, role=HouseholdMember.OWNER
        ).exists()
