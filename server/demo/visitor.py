"""Who a demo visit was: the owner's own device, or someone else, and from where.

Kevin, 2026-11-06: "lets make a way to differentiate myself and others". Two
signals land on `DemoVisit` when `/api/demo/enter` makes it:

**`by_owner`.** A browser where a real household member (any role but
`recruiter`, in a household that is not a demo visit) has been signed in
carries `OWNER_COOKIE`: a Django-signed, HttpOnly, Secure cookie holding that
member's user id, path-scoped to `/api/demo/` so no other route ever sees it.
It is set by the session login, by `GET /api/auth/me` on a session (the web
app calls it on every load, so an existing sign-in marks the device without
signing in again), and by `POST /api/demo/mark-owner-device`. Cookies are per
origin: kevinwin.dev and the run.app host each need one signed-in visit per
browser. `/enter` also counts a real member's live session on the request
itself. A recruiter can never set it: the only writers check the role and the
household first, and the signature (salted, on `SECRET_KEY`) cannot be forged.
At `/enter` the user in the cookie is re-checked, so a cookie for a member who
has since left counts for nothing.

**Where from.** Cloud Run sees only Cloudflare's address. The kevinwin.dev
Worker forwards what Cloudflare knows about the caller (`X-Legion-*` headers)
beside a shared secret (`X-Edge-Auth`, `settings.LEGION_EDGE_SECRET`). With
the secret right, the visit stores an HMAC of the IP (never the IP), the
country, region and city, and the network's ASN and organisation name: a
company network names the company, a home line names the ISP. With the secret
missing or wrong (someone calling run.app directly, or the setting unset)
nothing from those headers is stored. The user agent is the browser's own
claim either way, so it is stored as given.

`prune_demo_visitors` clears all of that (not `by_owner`) 30 days after the
visit (`VISITOR_DETAIL_DAYS`), since a `DemoVisit` row itself is kept.
"""

from __future__ import annotations

import hashlib
import hmac
from datetime import timedelta
from urllib.parse import unquote

from django.conf import settings
from django.core import signing
from django.core.exceptions import ValidationError
from django.db.models import Q

from demo.models import DemoVisit
from household.models import HouseholdMember, User

OWNER_COOKIE = "legion_owner_device"
OWNER_COOKIE_PATH = "/api/demo/"
OWNER_COOKIE_SALT = "demo.owner-device"
# Chrome keeps no cookie longer than 400 days, whatever it is told.
OWNER_COOKIE_AGE = timedelta(days=400)

VISITOR_DETAIL_DAYS = 30
IP_HASH_SALT = b"legion.demo.visit-ip"

EDGE_AUTH_HEADER = "HTTP_X_EDGE_AUTH"
EDGE_FIELDS = {
    # DemoVisit field: (META key, max length)
    "country": ("HTTP_X_LEGION_COUNTRY", 2),
    "region": ("HTTP_X_LEGION_REGION", 64),
    "city": ("HTTP_X_LEGION_CITY", 64),
    "as_org": ("HTTP_X_LEGION_AS_ORG", 128),
}
EDGE_IP = "HTTP_X_LEGION_CLIENT_IP"
EDGE_ASN = "HTTP_X_LEGION_ASN"
USER_AGENT_MAX = 512


def is_real_member(user) -> bool:
    """A signed-in person of a real household: not a recruiter visitor, not in
    a demo visit's household. One query; never cached on the user."""
    if user is None or not getattr(user, "is_authenticated", False):
        return False
    return (
        HouseholdMember.objects.filter(user=user, household__is_demo_visit=False)
        .exclude(role=HouseholdMember.RECRUITER)
        .exists()
    )


def set_owner_cookie(response, user) -> bool:
    """Marks this browser as the owner's, if `user` is a real member. Returns
    whether it did."""
    if not is_real_member(user):
        return False
    response.set_cookie(
        OWNER_COOKIE,
        signing.dumps({"u": str(user.pk)}, salt=OWNER_COOKIE_SALT),
        max_age=int(OWNER_COOKIE_AGE.total_seconds()),
        path=OWNER_COOKIE_PATH,
        secure=not settings.DEBUG,
        httponly=True,
        samesite="Lax",
    )
    return True


def owner_from_cookie(request) -> bool:
    raw = request.COOKIES.get(OWNER_COOKIE)
    if not raw:
        return False
    try:
        data = signing.loads(
            raw, salt=OWNER_COOKIE_SALT, max_age=OWNER_COOKIE_AGE.total_seconds()
        )
    except signing.BadSignature:
        return False
    user_id = data.get("u") if isinstance(data, dict) else None
    try:
        user = User.objects.filter(pk=user_id, is_active=True).first()
    except (TypeError, ValueError, ValidationError):
        return False
    return is_real_member(user)


def hash_ip(ip: str) -> str:
    """HMAC-SHA256 of the address under a key derived from `SECRET_KEY`. The
    same address hashes the same until `SECRET_KEY` is rotated."""
    key = hmac.new(settings.SECRET_KEY.encode("utf-8"), IP_HASH_SALT, hashlib.sha256).digest()
    return hmac.new(key, ip.strip().encode("utf-8"), hashlib.sha256).hexdigest()


def _edge_trusted(meta) -> bool:
    secret = getattr(settings, "LEGION_EDGE_SECRET", "")
    given = meta.get(EDGE_AUTH_HEADER, "")
    if not secret or not given:
        return False
    return hmac.compare_digest(given.encode("utf-8"), secret.encode("utf-8"))


def _header(meta, key: str, limit: int) -> str:
    # The Worker percent-encodes values, since a header carries no UTF-8.
    return unquote(meta.get(key, "")).strip()[:limit]


def visitor_details(request) -> dict:
    """The `DemoVisit` fields this request can fill: the user agent always,
    the edge's view of the caller only when the edge secret matches."""
    meta = request.META
    details: dict = {"user_agent": meta.get("HTTP_USER_AGENT", "")[:USER_AGENT_MAX]}
    if not _edge_trusted(meta):
        return details
    ip = _header(meta, EDGE_IP, 64)
    if ip:
        details["ip_hash"] = hash_ip(ip)
    for field, (key, limit) in EDGE_FIELDS.items():
        details[field] = _header(meta, key, limit)
    asn = _header(meta, EDGE_ASN, 12)
    details["asn"] = int(asn) if asn.isdigit() else None
    return details


def stale_details(cutoff):
    """Visits made before `cutoff` that still hold any visitor detail."""
    has_any = (
        ~Q(ip_hash="") | ~Q(country="") | ~Q(region="") | ~Q(city="")
        | Q(asn__isnull=False) | ~Q(as_org="") | ~Q(user_agent="")
    )
    return DemoVisit.objects.filter(created_at__lt=cutoff).filter(has_any)


def clear_old_details(now) -> int:
    """Blanks the edge and browser details of every visit older than
    `VISITOR_DETAIL_DAYS`. `by_owner`, `ref` and the rest stay."""
    return stale_details(now - timedelta(days=VISITOR_DETAIL_DAYS)).update(
        ip_hash="", country="", region="", city="", asn=None, as_org="", user_agent=""
    )
