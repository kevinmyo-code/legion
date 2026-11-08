"""`POST /api/demo/enter`: the shared invite code in, one recruiter visitor out.

recruiter-demo ticket 02 (ADR 0068). Anonymous by construction, like the
login doors: this is the request that creates the credential. Throttled per
IP (`demo_enter`), because the caller has no identity yet and the code is
shared, so the IP is the only thing that tells one guesser from another.

What a successful entry leaves behind:

- a fresh household of its own (ticket 13, Kevin 2026-11-02, ADR 0069),
  named "Demo visit <id>", flagged `is_demo_visit`, seeded with the demo data
  by `household.demo_seed.seed_household` through the same gate and
  serializers the shared demo household uses. Nobody else is a member of it,
  so what the visitor adds, changes or deletes reaches no one else;
- a throwaway `User` (unusable password, `visitor-<hex>@demo.invalid`), a
  `recruiter` member of that household and of nothing else;
- a `read`-scoped `DeviceToken` that expires in two hours, returned once;
- a Django session for the same visitor, expiring with the token and tied to
  it (`household.permissions.DEMO_TOKEN_SESSION_KEY`). The session is what
  the app and the assistant accept, and the one credential that may write,
  and only inside the visit's own household (`write_refusal`);
- a `DemoVisit` row carrying the `ref` and the household.

All of it in one transaction: a seed that fails leaves nothing behind, so
"Nothing was created" stays true. The shared "Demo" household
(`household.demo`) is no longer joined by anyone; `seed_demo_household`
keeps building it for previews and tests.
"""

from __future__ import annotations

import logging
import uuid
from datetime import timedelta

from django.contrib.auth import get_user, login
from django.db import transaction
from django.utils import timezone
from drf_spectacular.utils import OpenApiResponse, extend_schema, inline_serializer
from rest_framework import exceptions, serializers, status
from rest_framework.permissions import AllowAny
from rest_framework.response import Response
from rest_framework.throttling import ScopedRateThrottle
from rest_framework.views import APIView

from api.schema import DetailSerializer
from demo.briefing import seed_visit_procedures
from demo.models import DemoCode, DemoVisit
from demo.visitor import (
    edge_ip_hash,
    is_owner_request,
    is_real_member,
    owner_from_cookie,
    set_owner_cookie,
    visitor_details,
)
from household.demo_seed import DEMO_TIMEZONE, seed_household
from household.models import DeviceToken, Household, HouseholdMember, User
from household.permissions import DEMO_TOKEN_SESSION_KEY

logger = logging.getLogger(__name__)

DEMO_TAGS = ["demo"]
VISIT_LENGTH = timedelta(hours=2)

WRONG_CODE = "That invite code is not right. Nothing was created; check the code and try again."
RETIRED_CODE = (
    "That invite code has been retired. Nothing was created; ask for the current one."
)
NOT_AN_OWNER_DEVICE = (
    "Nothing was set. Only a member of a real household can mark a browser as theirs."
)
DOOR_CLOSED = "The demo is closed right now. Nothing was created."
SEED_FAILED = (
    "The demo could not be set up just now. Nothing was created; try again in a moment."
)
TOO_MANY = (
    "Too many tries from this network in a short time. Nothing was created; wait a while "
    "and try again."
)


class DemoEnterRequestSerializer(serializers.Serializer):
    code = serializers.CharField(max_length=64, trim_whitespace=True)
    ref = serializers.RegexField(
        r"^[A-Za-z0-9_.-]{1,64}$",
        required=False,
        allow_blank=True,
        help_text=(
            "Where the visitor came from, as the resume link said (`/try?ref=acme`). Letters, "
            "digits, `_`, `-` and `.`, up to 64. Stored on the visit; never shown to the model."
        ),
    )


DemoEnterResponse = inline_serializer(
    name="DemoVisitIssued",
    fields={
        "token": serializers.CharField(
            help_text=(
                "The visitor's read-only device token, shown once. The browser also holds a "
                "session for the same visitor, which is what the assistant accepts."
            )
        ),
        "expires_at": serializers.DateTimeField(
            help_text="The token and the session both stop working at this time."
        ),
        "ref": serializers.CharField(allow_blank=True),
    },
)


class DemoEnterThrottle(ScopedRateThrottle):
    """Per caller IP. Behind the kevinwin.dev Worker `REMOTE_ADDR` is Cloudflare's
    address, which would put every recruiter in one bucket, so the key is the
    edge's verified IP (its HMAC, `demo.visitor.edge_ip_hash`) when the edge
    secret matches, and `REMOTE_ADDR` otherwise: a spoofed header without the
    secret buys nothing. The owner's own browser is not throttled."""

    def allow_request(self, request, view):
        if is_owner_request(request):
            return True
        return super().allow_request(request, view)

    def get_cache_key(self, request, view):
        ident = edge_ip_hash(request) or self.get_ident(request)
        return self.cache_format % {"scope": self.scope, "ident": ident}


class DemoEnterView(APIView):
    authentication_classes: list = []
    permission_classes = [AllowAny]
    throttle_classes = [DemoEnterThrottle]
    throttle_scope = "demo_enter"

    def throttled(self, request, wait):
        raise exceptions.Throttled(wait, detail=TOO_MANY)

    @extend_schema(
        tags=DEMO_TAGS,
        operation_id="api_demo_enter_create",
        summary="Enter the recruiter demo with the shared invite code",
        request=DemoEnterRequestSerializer,
        responses={
            201: DemoEnterResponse,
            400: OpenApiResponse(DetailSerializer, description="The body did not fit."),
            403: OpenApiResponse(
                DetailSerializer,
                description="Wrong code, a retired code, or the demo is closed. Nothing created.",
            ),
            429: OpenApiResponse(
                DetailSerializer, description="Too many tries from this IP. Nothing created."
            ),
            503: OpenApiResponse(
                DetailSerializer,
                description="The visit's demo household could not be seeded. Nothing created.",
            ),
        },
    )
    def post(self, request):
        body = DemoEnterRequestSerializer(data=request.data)
        if not body.is_valid():
            return Response(
                {"detail": f"Nothing was created. The request did not fit: {body.errors}"},
                status=status.HTTP_400_BAD_REQUEST,
            )
        code_text = body.validated_data["code"]
        ref = body.validated_data.get("ref", "") or ""

        if not DemoCode.live().exists():
            return _refuse(DOOR_CLOSED, status.HTTP_403_FORBIDDEN)
        code = DemoCode.match(code_text)
        if code is None:
            return _refuse(WRONG_CODE, status.HTTP_403_FORBIDDEN)
        if code.revoked_at is not None:
            return _refuse(RETIRED_CODE, status.HTTP_403_FORBIDDEN)

        # Read before `login()` below replaces the session. `request.user` is
        # anonymous here (no authentication classes, and DRF writes that
        # anonymous user back onto the Django request too), so the session is
        # read directly. A recruiter's browser has a session as well, after one
        # entry: `is_real_member` is what keeps that from counting as the owner.
        session_user = get_user(request._request)
        session_is_member = is_real_member(session_user)
        by_owner = session_is_member or owner_from_cookie(request)
        details = visitor_details(request)

        expires_at = timezone.now() + VISIT_LENGTH
        try:
            with transaction.atomic():
                household = _make_visit_household()
                user, token, raw_key = _make_visitor(household, ref, expires_at)
                DemoVisit.objects.create(
                    user=user,
                    token=token,
                    code=code,
                    ref=ref,
                    expires_at=expires_at,
                    household=household,
                    by_owner=by_owner,
                    **details,
                )
        except Exception:  # noqa: BLE001 - any seed failure; the transaction rolled back
            logger.exception("demo enter: seeding the visit household failed")
            return _refuse(SEED_FAILED, status.HTTP_503_SERVICE_UNAVAILABLE)

        login(request, user, backend="django.contrib.auth.backends.ModelBackend")
        request.session.set_expiry(int(VISIT_LENGTH.total_seconds()))
        request.session[DEMO_TOKEN_SESSION_KEY] = token.pk
        response = Response(
            {"token": raw_key, "expires_at": expires_at, "ref": ref},
            status=status.HTTP_201_CREATED,
            headers={"Cache-Control": "no-store"},
        )
        if session_is_member:
            set_owner_cookie(response, session_user)
        return response


class MarkOwnerDeviceView(APIView):
    """`POST /api/demo/mark-owner-device`: marks this browser as a real member's,
    so a demo visit made from it later is flagged `by_owner` (`demo/visitor.py`).
    Any credential the engine accepts, but a recruiter visitor is refused: the
    flag would be a lie told by the person it exists to tell apart."""

    @extend_schema(
        tags=DEMO_TAGS,
        operation_id="api_demo_mark_owner_device_create",
        summary="Mark this browser as a household member's own",
        request=None,
        responses={
            204: OpenApiResponse(description="The owner-device cookie is set."),
            403: OpenApiResponse(
                DetailSerializer,
                description="A recruiter visit, or not a member of a real household. Nothing set.",
            ),
        },
    )
    def post(self, request):
        response = Response(status=status.HTTP_204_NO_CONTENT)
        if not set_owner_cookie(response, request.user):
            return _refuse(NOT_AN_OWNER_DEVICE, status.HTTP_403_FORBIDDEN)
        return response


def _refuse(message: str, code: int) -> Response:
    return Response({"detail": message}, status=code)


def _make_visit_household() -> Household:
    """The visit's own household, flagged and seeded. The flag is set here and
    nowhere else in the engine (ADR 0069)."""
    tag = uuid.uuid4().hex[:8]
    household = Household.objects.create(
        name=f"Demo visit {tag}", timezone=DEMO_TIMEZONE, is_demo_visit=True
    )
    seed_household(household, created=True)
    # The visit's own morning briefing, built only from the visit's tools (`demo/briefing.py`).
    seed_visit_procedures(household)
    return household


def _make_visitor(household: Household, ref: str, expires_at):
    user = User.objects.create_user(email=f"visitor-{uuid.uuid4().hex[:16]}@demo.invalid")
    user.set_unusable_password()
    user.save(update_fields=["password"])
    HouseholdMember.objects.create(
        user=user, household=household, role=HouseholdMember.RECRUITER
    )
    token, raw_key = DeviceToken.issue(
        user,
        f"Demo visit ({ref})" if ref else "Demo visit",
        DeviceToken.SCOPE_READ,
        expires_at=expires_at,
    )
    return user, token, raw_key
