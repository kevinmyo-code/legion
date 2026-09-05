"""The three auth endpoints ticket 01 promises: log in, log out, and 'who
and what device am I' - the phone's own membership check.
"""
from __future__ import annotations

from django.contrib.auth import authenticate
from rest_framework import status
from rest_framework.permissions import AllowAny
from rest_framework.response import Response
from rest_framework.throttling import ScopedRateThrottle
from rest_framework.views import APIView

from household.models import DeviceToken
from household.serializers import (
    LoginRequestSerializer,
    LoginResponseSerializer,
    MeResponseSerializer,
)


class LoginView(APIView):
    """`POST /api/auth/login`. Rate-limited 5/min per IP so a leaked email
    cannot be brute-forced into a working password - the household is two
    people, not a userbase, so five attempts a minute is generous, not
    tight."""

    authentication_classes: list = []
    permission_classes = [AllowAny]
    throttle_classes = [ScopedRateThrottle]
    throttle_scope = "login"

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
    every other device this user owns keeps working."""

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

    def get(self, request):
        token = request.auth
        device_name = token.name if isinstance(token, DeviceToken) else ""
        body = MeResponseSerializer(
            {"user_id": request.user.id, "email": request.user.email, "device_name": device_name}
        ).data
        return Response(body, status=status.HTTP_200_OK)
