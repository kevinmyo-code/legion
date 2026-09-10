"""The auth endpoints: log in, log out, and 'who and what device am I' - the
phone's own membership check, plus (web-and-households ticket 03 narrow
slice, 2026-09-10) a Django session pair for the browser and a csrf-cookie
primer.

Two credentials live side by side on purpose (ADR 0044 rule 3): a device
token, one per phone/robot, revocable alone; and a Django session, one per
browser tab's cookie jar, CSRF-protected the way a cookie credential has to
be. Neither path mints the other's credential."""
from __future__ import annotations

from django.contrib.auth import authenticate, login, logout
from django.utils.decorators import method_decorator
from django.views.decorators.csrf import ensure_csrf_cookie
from drf_spectacular.utils import OpenApiResponse, extend_schema
from rest_framework import status
from rest_framework.authentication import SessionAuthentication
from rest_framework.permissions import AllowAny
from rest_framework.response import Response
from rest_framework.throttling import ScopedRateThrottle
from rest_framework.views import APIView

from api.schema import DetailSerializer
from household.models import DeviceToken
from household.serializers import (
    LoginRequestSerializer,
    LoginResponseSerializer,
    MeResponseSerializer,
    SessionLoginRequestSerializer,
)

AUTH_TAGS = ["auth"]


class LoginView(APIView):
    """`POST /api/auth/login`. Rate-limited 5/min per IP so a leaked email
    cannot be brute-forced into a working password - the household is two
    people, not a userbase, so five attempts a minute is generous, not
    tight."""

    authentication_classes: list = []
    permission_classes = [AllowAny]
    throttle_classes = [ScopedRateThrottle]
    throttle_scope = "login"

    @extend_schema(
        operation_id="api_auth_login_create",
        tags=AUTH_TAGS,
        request=LoginRequestSerializer,
        responses={
            200: OpenApiResponse(
                response=LoginResponseSerializer,
                description=(
                    "A new device token. `token` is the raw key and is shown ONCE - the "
                    "server stores only its hash and cannot hand it back again. Send it as "
                    "`Authorization: Token <key>` on every later call."
                ),
            ),
            401: OpenApiResponse(
                response=DetailSerializer,
                description="Wrong email or password, or the account is inactive. No token "
                "was issued.",
            ),
            429: OpenApiResponse(
                response=DetailSerializer,
                description="Rate limited: 5 attempts a minute per IP. `detail` says when to "
                "try again.",
            ),
        },
    )
    def post(self, request):
        serializer = LoginRequestSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        data = serializer.validated_data

        user = authenticate(
            request, username=data["email"], password=data["password"]
        )
        if user is None or not user.is_active:
            # No token is minted here, matching the outcome-verb rule in
            # spirit: a failed login must never look like a successful one.
            return Response(
                {"detail": "Invalid email or password."},
                status=status.HTTP_401_UNAUTHORIZED,
            )

        _token, raw_key = DeviceToken.issue(user, data["device_name"])
        body = LoginResponseSerializer({"token": raw_key, "user_id": user.id}).data
        return Response(body, status=status.HTTP_200_OK)


class LogoutView(APIView):
    """`POST /api/auth/logout`. Revokes only the token making the request -
    every other device this user owns keeps working.

    Send it with an explicit `Content-Length: 0` even though there is no
    body: measured against the deployed Cloud Run service on 2026-09-07, a
    POST carrying neither a body nor that header is answered 411 Length
    Required by the frontend and never reaches Django, so the token is NOT
    revoked and the caller has been told nothing about why. Most HTTP
    clients set the header themselves for an empty POST; `curl` without
    `-d` does not.
    """

    @extend_schema(
        operation_id="api_auth_logout_create",
        tags=AUTH_TAGS,
        request=None,
        responses={
            204: OpenApiResponse(
                description="This token is revoked. Every other device keeps working."
            ),
            400: OpenApiResponse(
                response=DetailSerializer,
                description="The request carried no device token, so there was nothing to "
                "revoke. Nothing was changed.",
            ),
        },
    )
    def post(self, request):
        token = request.auth
        if not isinstance(token, DeviceToken):
            return Response(
                {"detail": "No device token on this request."},
                status=status.HTTP_400_BAD_REQUEST,
            )
        from django.utils import timezone

        token.revoked_at = timezone.now()
        token.save(update_fields=["revoked_at"])
        return Response(status=status.HTTP_204_NO_CONTENT)


class SessionLoginView(APIView):
    """`POST /api/auth/session/login`. Sets a Django session cookie for the
    browser. Deliberately mints NO device token - a session and a token are
    different credentials with different revocation stories (ADR 0044 rule
    3) - and is throttled the same as `LoginView`, same scope, so the two
    login doors share one rate budget rather than doubling the attempts a
    leaked email tolerates.

    Anonymous by construction (`authentication_classes = []`, same as
    `LoginView`): this is the request that CREATES the session, so there is
    nothing yet for `SessionAuthentication` to attach to, and DRF's own
    `APIView.as_view()` exempts every view from Django's blanket CSRF
    middleware for exactly this reason - CSRF protection for the session
    path starts at the NEXT request, once `SessionAuthentication` has a
    logged-in user to enforce it against (see `SessionLogoutView`).
    """

    authentication_classes: list = []
    permission_classes = [AllowAny]
    throttle_classes = [ScopedRateThrottle]
    throttle_scope = "login"

    @extend_schema(
        operation_id="api_auth_session_login_create",
        tags=AUTH_TAGS,
        request=SessionLoginRequestSerializer,
        responses={
            200: OpenApiResponse(
                response=MeResponseSerializer,
                description=(
                    "A Django session cookie is set. Body is the same shape `/api/auth/me` "
                    "returns; `device_name` is always empty since a session names no device."
                ),
            ),
            401: OpenApiResponse(
                response=DetailSerializer,
                description="Wrong email or password, or the account is inactive. No "
                "session was created.",
            ),
            429: OpenApiResponse(
                response=DetailSerializer,
                description="Rate limited: 5 attempts a minute per IP, shared with "
                "`/api/auth/login`. `detail` says when to try again.",
            ),
        },
    )
    def post(self, request):
        serializer = SessionLoginRequestSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        data = serializer.validated_data

        user = authenticate(
            request, username=data["email"], password=data["password"]
        )
        if user is None or not user.is_active:
            return Response(
                {"detail": "Invalid email or password."},
                status=status.HTTP_401_UNAUTHORIZED,
            )

        login(request, user)
        body = MeResponseSerializer(
            {"user_id": user.id, "email": user.email, "device_name": ""}
        ).data
        return Response(body, status=status.HTTP_200_OK)


class SessionLogoutView(APIView):
    """`POST /api/auth/session/logout`. Ends the Django session. Never
    touches a device token - `authentication_classes` names only
    `SessionAuthentication`, so a request carrying a device token instead of
    a session cookie authenticates as nobody here and is refused by the
    default `IsHouseholdMember` permission, the same as any other
    unauthenticated request.

    Reachable only once a session already exists, which is exactly the case
    `rest_framework.authentication.SessionAuthentication.authenticate()`
    calls `enforce_csrf()` on - so this endpoint requires `X-CSRFToken`
    without anything here asking for it by hand. Do not subclass
    `SessionAuthentication` to turn that off.
    """

    authentication_classes = [SessionAuthentication]

    @extend_schema(
        operation_id="api_auth_session_logout_create",
        tags=AUTH_TAGS,
        request=None,
        responses={
            204: OpenApiResponse(description="The session is ended."),
            403: OpenApiResponse(
                response=DetailSerializer,
                description="No active session, or a missing/invalid CSRF token.",
            ),
        },
    )
    def post(self, request):
        logout(request)
        return Response(status=status.HTTP_204_NO_CONTENT)


class CsrfView(APIView):
    """`GET /api/auth/csrf`. Ensures the `csrftoken` cookie is set so the
    browser has something to echo back in `X-CSRFToken` on the session login
    POST and everything session-authenticated after it
    (`frontend/src/api/client.ts`'s `djangoSession` middleware reads this
    exact cookie name). Anonymous and side-effect-free beyond the cookie
    itself - `ensure_csrf_cookie` forces Django to set it even though this
    view reads nothing that would otherwise trigger it.
    """

    authentication_classes: list = []
    permission_classes = [AllowAny]

    @extend_schema(
        operation_id="api_auth_csrf_retrieve",
        tags=AUTH_TAGS,
        responses={200: OpenApiResponse(description="The `csrftoken` cookie is set.")},
    )
    @method_decorator(ensure_csrf_cookie)
    def get(self, request):
        return Response({})


class MeView(APIView):
    """`GET /api/auth/me`. This is the membership check for both credentials
    - if this call succeeds, the calling token or session is live and its
    user is a household member; if it 401s or 403s, the caller knows to ask
    for a new one. `DEFAULT_AUTHENTICATION_CLASSES` tries the device token
    first and the session second, so a phone request never pays a session
    lookup."""

    @extend_schema(
        operation_id="api_auth_me_retrieve",
        tags=AUTH_TAGS,
        responses={
            200: OpenApiResponse(
                response=MeResponseSerializer,
                description=(
                    "The token or session is live and its user is a household member. "
                    "`device_name` is the name the token was issued under at login, or "
                    "empty for a session."
                ),
            ),
            401: OpenApiResponse(
                response=DetailSerializer,
                description="No credential, an unknown token, or a revoked one. Ask for a "
                "new one.",
            ),
            403: OpenApiResponse(
                response=DetailSerializer,
                description=(
                    "The credential is live but its user is not a household member. A "
                    "`User` row alone is not enough - see `manage.py add_household_member`."
                ),
            ),
        },
    )
    def get(self, request):
        token = request.auth
        device_name = token.name if isinstance(token, DeviceToken) else ""
        body = MeResponseSerializer(
            {"user_id": request.user.id, "email": request.user.email, "device_name": device_name}
        ).data
        return Response(body, status=status.HTTP_200_OK)
