"""The auth endpoints under `/api/auth/`: log in, log out, sign up, redeem or
preview an invite code, 'who and what device am I', and a member's own list
of devices.

**The household RESOURCE is not here.** `GET`/`PATCH /api/households/me`,
its invites and its member list live in `household/households.py`, mounted
at a different prefix, because they are one resource an owner administers
rather than doors a credential goes through. The split also keeps the rule
`tests/test_tenancy.py` guards visible: nothing under `/api/auth/` says
anything about which household the caller is in.

Two credentials live side by side on purpose (ADR 0044 rule 3): a device
token, one per phone/robot, revocable alone; and a Django session, one per
browser tab's cookie jar, CSRF-protected the way a cookie credential has to
be. Neither path mints the other's credential - except at signup, which
mints a device token because it is the login the new account has not had a
chance to do yet.

**This docstring used to read** "log in, log out, and 'who and what device
am I' - the phone's own membership check, plus (web-and-households ticket 03
narrow slice, 2026-09-10) a Django session pair for the browser and a
csrf-cookie primer". The narrow slice is no longer narrow; the rest of
ticket 03 landed the same day."""
from __future__ import annotations

from django.conf import settings
from django.contrib.auth import authenticate, login, logout
from django.contrib.auth.password_validation import validate_password
from django.core.exceptions import ValidationError as DjangoValidationError
from django.db import transaction
from django.utils import timezone
from django.utils.decorators import method_decorator
from django.views.decorators.csrf import ensure_csrf_cookie
from drf_spectacular.utils import OpenApiResponse, extend_schema
from rest_framework import status
from rest_framework.authentication import SessionAuthentication
from rest_framework.exceptions import ValidationError as DRFValidationError
from rest_framework.permissions import AllowAny
from rest_framework.response import Response
from rest_framework.throttling import ScopedRateThrottle
from rest_framework.views import APIView

from api.schema import DetailSerializer
from household.models import DeviceToken, Household, HouseholdMember, Invite, User
from household.serializers import (
    DeviceTokenSerializer,
    InvitePreviewSerializer,
    LoginRequestSerializer,
    LoginResponseSerializer,
    MeResponseSerializer,
    SessionLoginRequestSerializer,
    SignupRequestSerializer,
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


# =============================================================================
# Signup, invite preview, and a member's own devices
# (web-and-households ticket 03)
# =============================================================================


def join_url_for(request, code: str) -> str:
    """The link an owner sends someone: `https://<this host>/join/<code>`.

    Built from the REQUEST rather than from a configured base URL, because
    there is no honest configured value - the same engine is reached at
    `localhost:8000` in dev, at a household domain through Caddy in
    production, and at whatever a stranger's own compose stack answers on.
    `build_absolute_uri` uses the host Django already validated against
    `ALLOWED_HOSTS`, so it cannot be pointed somewhere else by a header.

    `/join/<code>` is the SPA's route (ticket 05). It is not a Django URL and
    must not become one: `legion/urls.py`'s catch-all hands anything that is
    not `api/`, `admin/`, `static/`, `media/` or a health path to the client,
    which is exactly what should serve this.
    """
    return request.build_absolute_uri(f"/join/{code}")


def refuse(message: str, code: int = status.HTTP_400_BAD_REQUEST) -> Response:
    """A `{"detail": ...}` refusal that says what did NOT happen.

    Section 7's outcome-verb rule pointed at an HTTP body: every string
    passed here names the thing that was not created, not merely what was
    wrong with the request.
    """
    return Response({"detail": message}, status=code)


class SignupView(APIView):
    """`POST /api/auth/signup`. Creates a user, puts them in a household, and
    issues a device token - the whole of "my wife wants to use this" in one
    call.

    **Invite-only by default (ADR 0045 decision 3).** With no
    `LEGION_OPEN_SIGNUP`, a request carrying no `invite_code` is refused
    outright; this is a household engine, not a service with a sign-up page.
    With the switch on, a codeless request founds a household - and a request
    that DOES carry a code is still honoured exactly as it would be with the
    switch off, so the second adult on a stranger's compose stack joins the
    first one's household rather than founding a rival one.

    **Atomic, and the invite row is locked.** Everything below is one
    transaction: a refused password must not leave a user behind, and a
    `max_uses` of one must mean one even if two people press the button
    together (`Invite.find(for_update=True)`).
    """

    authentication_classes: list = []
    permission_classes = [AllowAny]
    throttle_classes = [ScopedRateThrottle]
    throttle_scope = "signup"

    @extend_schema(
        operation_id="api_auth_signup_create",
        tags=AUTH_TAGS,
        request=SignupRequestSerializer,
        responses={
            201: OpenApiResponse(
                response=LoginResponseSerializer,
                description=(
                    "The account exists, is a member of a household, and `token` is a new "
                    "device token for it - shown ONCE, exactly as POST /api/auth/login "
                    "returns one. Send it as `Authorization: Token <key>`."
                ),
            ),
            400: OpenApiResponse(
                response=DetailSerializer,
                description=(
                    "NOTHING was created. `detail` names which of these it was: no invite "
                    "code on an invite-only engine, a code that does not exist, a code "
                    "that is revoked/expired/spent, an email already registered, a "
                    "rejected password, or a missing `household_name` for a code that "
                    "founds a household. A field-level shape error answers with the "
                    "field-keyed body instead."
                ),
            ),
            429: OpenApiResponse(
                response=DetailSerializer,
                description=(
                    "Rate limited: 5 signup attempts a minute per IP, shared with "
                    "GET /api/auth/invite/{code} so guessing codes cannot be split across "
                    "the two. Nothing was created."
                ),
            ),
        },
    )
    @transaction.atomic
    def post(self, request):
        serializer = SignupRequestSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        data = serializer.validated_data

        email = User.objects.normalize_email(data["email"].strip())
        raw_code = data["invite_code"].strip()
        household_name = data["household_name"].strip()

        if not raw_code and not settings.LEGION_OPEN_SIGNUP:
            return refuse(
                "No account was created. This engine is invite-only: ask someone already "
                "in the household for an invite link, and sign up with the code in it."
            )

        invite = None
        if raw_code:
            invite = Invite.find(raw_code, for_update=True)
            if invite is None:
                return refuse(
                    "No account was created. There is no invite with that code - check it "
                    "for a typo, or ask for a new link."
                )
            unavailable = invite.unavailable_reason()
            if unavailable is not None:
                return refuse(f"No account was created. {unavailable}")

        # Case-insensitively, because two accounts differing only in the case
        # of their email is a support problem nobody wants and Postgres's
        # unique index would happily allow it.
        if User.objects.filter(email__iexact=email).exists():
            return refuse(
                "No account was created. An account with that email already exists - sign "
                "in instead, or use a different address."
            )

        try:
            validate_password(data["password"])
        except DjangoValidationError as exc:
            # DRF's own field-error shape, so a form can put the message next
            # to the password box rather than in a banner.
            raise DRFValidationError({"password": list(exc.messages)}) from exc

        founding = invite is None or (invite.creates_household and invite.household_id is None)
        if founding and not household_name:
            return refuse(
                "No account was created. This code creates a NEW household, so it needs a "
                "`household_name` - what your family calls itself on this server."
            )

        if founding:
            household = Household.objects.create(name=household_name)
            role = HouseholdMember.OWNER
        else:
            household = invite.household
            role = HouseholdMember.MEMBER
            if household is None:
                # Unreachable while `invite_joins_or_creates_a_household`
                # holds, and refused in words rather than raising, because a
                # 500 tells the person signing up nothing at all.
                return refuse(
                    "No account was created. That invite code is attached to no household, "
                    "which should not be possible - tell whoever runs this server."
                )

        user = User.objects.create_user(
            email=email, password=data["password"], first_name=data["name"].strip()
        )
        HouseholdMember.objects.create(user=user, household=household, role=role)

        if invite is not None:
            invite.used_count += 1
            if founding:
                # Later uses of the same code JOIN what this signup founded -
                # ADR 0045's "one code reaches both parents".
                invite.household = household
            invite.save(update_fields=["used_count", "household"])

        _token, raw_key = DeviceToken.issue(user, data["device_name"])
        body = LoginResponseSerializer({"token": raw_key, "user_id": user.id}).data
        return Response(body, status=status.HTTP_201_CREATED)


class InvitePreviewView(APIView):
    """`GET /api/auth/invite/<code>`. What this code will do, without
    spending it.

    Ticket 05's `/join/<code>` screen calls this on load: a person who was
    sent a link has to be told whether it still works, and whether following
    it joins a family or starts one, BEFORE they type a password into a form
    that then fails. Nothing here increments `used_count` - previewing a code
    is not using it.

    Anonymous, and throttled on the same `signup` scope as the redemption
    endpoint, so the two together give a stranger five guesses a minute at
    the code space rather than ten.

    A code that does not exist answers 404; a real code that is revoked,
    expired or spent answers 200 with `live: false` and a `reason`. The split
    is deliberate - "you mistyped it" and "it expired" are different things
    for the screen to say, and a 12-character random code is not a namespace
    an attacker enumerates five guesses a minute.
    """

    authentication_classes: list = []
    permission_classes = [AllowAny]
    throttle_classes = [ScopedRateThrottle]
    throttle_scope = "signup"

    @extend_schema(
        operation_id="api_auth_invite_retrieve",
        tags=AUTH_TAGS,
        responses={
            200: OpenApiResponse(
                response=InvitePreviewSerializer,
                description=(
                    "The code exists. `live` says whether it can still be used and "
                    "`reason` says why not when it cannot. Nothing was spent."
                ),
            ),
            404: OpenApiResponse(
                response=DetailSerializer,
                description="No invite has that code. Nothing was spent.",
            ),
            429: OpenApiResponse(
                response=DetailSerializer,
                description="Rate limited: 5/min per IP, shared with POST /api/auth/signup.",
            ),
        },
    )
    def get(self, request, code: str):
        invite = Invite.find(code)
        if invite is None:
            return refuse(
                "No invite has that code. Nothing was spent - check the link for a typo, "
                "or ask for a new one.",
                status.HTTP_404_NOT_FOUND,
            )
        reason = invite.unavailable_reason()
        founding = invite.creates_household and invite.household_id is None
        body = InvitePreviewSerializer(
            {
                "code": invite.code,
                "live": reason is None,
                "creates_household": founding,
                # Named, never id'd: someone signing up should be able to see
                # they are joining the right family, and can do nothing honest
                # with a uuid.
                "household_name": None if founding else invite.household.name,
                "uses_left": max(invite.max_uses - invite.used_count, 0),
                "expires_at": invite.expires_at,
                "reason": reason,
            }
        ).data
        return Response(body, status=status.HTTP_200_OK)


class DeviceListView(APIView):
    """`GET /api/auth/devices`. The calling user's own LIVE device tokens.

    Own, not the household's: a device token is a credential belonging to one
    person, and ADR 0045's owner role governs membership, never someone
    else's phone. An owner who wants a person gone removes the member, which
    revokes their tokens (`DELETE /api/households/me/members/<user_id>`).

    Revoked tokens are not listed. The list answers "what can currently reach
    this account", which is the question a person opens this screen to ask; a
    login history is a different feature and this is not a half-built one.
    """

    @extend_schema(
        operation_id="api_auth_devices_list",
        tags=AUTH_TAGS,
        responses={
            200: OpenApiResponse(
                response=DeviceTokenSerializer(many=True),
                description=(
                    "Live device tokens for the calling user, newest first. Never a key - "
                    "the server holds only hashes."
                ),
            )
        },
    )
    def get(self, request):
        current = request.auth if isinstance(request.auth, DeviceToken) else None
        tokens = DeviceToken.objects.filter(
            user=request.user, revoked_at__isnull=True
        ).order_by("-created_at")
        body = DeviceTokenSerializer(
            [
                {
                    "id": token.id,
                    "name": token.name,
                    "created_at": token.created_at,
                    "last_seen_at": token.last_seen_at,
                    "current": current is not None and token.id == current.id,
                }
                for token in tokens
            ],
            many=True,
        ).data
        return Response(body, status=status.HTTP_200_OK)


class DeviceDetailView(APIView):
    """`DELETE /api/auth/devices/<id>`. Revokes one of the calling user's own
    device tokens - the hands path to "I lost my phone".

    Scoped to `request.user`, so another member's token id 404s rather than
    403s: from here that token does not exist, and a 403 would confirm that
    it does.
    """

    @extend_schema(
        operation_id="api_auth_devices_destroy",
        tags=AUTH_TAGS,
        responses={
            204: OpenApiResponse(
                description=(
                    "That device token is revoked and every other device keeps working. "
                    "Already-revoked is the same answer: the outcome asked for holds."
                )
            ),
            404: OpenApiResponse(
                response=DetailSerializer,
                description="No device token of yours has that id. Nothing was revoked.",
            ),
        },
    )
    def delete(self, request, token_id: int):
        token = DeviceToken.objects.filter(user=request.user, id=token_id).first()
        if token is None:
            return refuse(
                "Nothing was revoked: no device token of yours has that id.",
                status.HTTP_404_NOT_FOUND,
            )
        if token.revoked_at is None:
            token.revoked_at = timezone.now()
            token.save(update_fields=["revoked_at"])
        return Response(status=status.HTTP_204_NO_CONTENT)
