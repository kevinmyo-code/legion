"""The three auth endpoints ticket 01 promises: log in, log out, and 'who
and what device am I' - the phone's own membership check.
"""
from __future__ import annotations

from django.contrib.auth import authenticate
from drf_spectacular.utils import OpenApiResponse, extend_schema
from rest_framework import status
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


class MeView(APIView):
    """`GET /api/auth/me`. This is the phone's membership check - if this
    call succeeds, the calling token is live and its user is a household
    member; if it 401s or 403s, the phone knows to ask for a new token."""

    @extend_schema(
        operation_id="api_auth_me_retrieve",
        tags=AUTH_TAGS,
        responses={
            200: OpenApiResponse(
                response=MeResponseSerializer,
                description=(
                    "The token is live and its user is a household member. `device_name` is "
                    "the name the token was issued under at login."
                ),
            ),
            401: OpenApiResponse(
                response=DetailSerializer,
                description="No token, an unknown token, or a revoked one. Ask for a new one.",
            ),
            403: OpenApiResponse(
                response=DetailSerializer,
                description=(
                    "The token is live but its user is not a household member. A `User` row "
                    "alone is not enough - see `manage.py add_household_member`."
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
