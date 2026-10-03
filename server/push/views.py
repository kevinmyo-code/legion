"""`/api/push/*` (web-revamp ticket 15, spec D7).

Every route is the caller's own: their subscriptions, their preferences.
Scoped by household like every tenant table (ADR 0045), and by user within
it, because which notifications a person gets is theirs alone.
"""

from __future__ import annotations

from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from django.db import transaction
from django.db.models.functions import Now
from drf_spectacular.extensions import OpenApiAuthenticationExtension
from drf_spectacular.utils import OpenApiResponse, extend_schema
from rest_framework import serializers, status
from rest_framework.authentication import SessionAuthentication
from rest_framework.response import Response
from rest_framework.views import APIView

from api.schema import DetailSerializer
from household.authentication import DeviceTokenAuthentication
from household.tenancy import household_of, scoped
from push import copy
from push.models import KINDS, PushPreference, PushSubscription
from push.vapid import vapid_config

PUSH_TAGS = ["push"]


class VapidKeySerializer(serializers.Serializer):
    enabled = serializers.BooleanField()
    public_key = serializers.CharField(allow_null=True)
    detail = serializers.CharField(
        allow_null=True,
        help_text="When push is off, the sentence to show: notifications are not set up here.",
    )


class SubscriptionKeysSerializer(serializers.Serializer):
    p256dh = serializers.CharField()
    auth = serializers.CharField()


class SubscriptionRequestSerializer(serializers.Serializer):
    """`PushSubscription.toJSON()` as a browser gives it, plus the device's zone."""

    endpoint = serializers.CharField()
    keys = SubscriptionKeysSerializer()
    user_agent = serializers.CharField(required=False, allow_blank=True, default="")
    tz = serializers.CharField(
        required=False,
        default="UTC",
        help_text="The browser's IANA zone. It decides which local day 'this morning' is.",
    )

    def validate_endpoint(self, value: str) -> str:
        if not value.startswith("https://"):
            raise serializers.ValidationError(
                "A push endpoint is an https:// address from the browser. Nothing was saved."
            )
        return value

    def validate_tz(self, value: str) -> str:
        try:
            ZoneInfo(value)
        except (ZoneInfoNotFoundError, ValueError) as exc:
            raise serializers.ValidationError(
                f"{value!r} is not a time zone this server knows. Nothing was saved."
            ) from exc
        return value


class SubscriptionSerializer(serializers.ModelSerializer):
    """What a member sees of a subscription: never the keys."""

    class Meta:
        model = PushSubscription
        fields = ["id", "user_agent", "tz", "created_at", "last_ok_at"]
        read_only_fields = fields


class PreferenceSerializer(serializers.Serializer):
    list_changes = serializers.BooleanField()
    event_reminders = serializers.BooleanField()
    task_due_morning = serializers.BooleanField()
    morning_time = serializers.TimeField(format="%H:%M", input_formats=["%H:%M", "%H:%M:%S"])


class OffRequestSerializer(serializers.Serializer):
    kind = serializers.CharField(help_text="list_changes, event_reminders or task_due_morning.")


class OffResponseSerializer(PreferenceSerializer):
    detail = serializers.CharField()


def _preferences(request) -> PushPreference:
    pref = scoped(PushPreference, request).filter(user=request.user).first()
    if pref is None:
        pref = PushPreference(household=household_of(request), user=request.user)
    return pref


def _preference_body(pref: PushPreference) -> dict:
    return PreferenceSerializer(
        {
            "list_changes": pref.list_changes,
            "event_reminders": pref.event_reminders,
            "task_due_morning": pref.task_due_morning,
            "morning_time": pref.morning_time,
        }
    ).data


class VapidKeyView(APIView):
    @extend_schema(
        operation_id="api_push_vapid_public_key_retrieve",
        tags=PUSH_TAGS,
        responses={200: VapidKeySerializer},
    )
    def get(self, request):
        config = vapid_config()
        if config is None:
            body = {"enabled": False, "public_key": None, "detail": copy.PUSH_OFF}
        else:
            body = {"enabled": True, "public_key": config.public_key, "detail": None}
        return Response(VapidKeySerializer(body).data)


class SubscriptionListCreateView(APIView):
    @extend_schema(
        operation_id="api_push_subscriptions_create",
        tags=PUSH_TAGS,
        request=SubscriptionRequestSerializer,
        responses={
            201: OpenApiResponse(response=SubscriptionSerializer, description="Subscribed."),
            200: OpenApiResponse(
                response=SubscriptionSerializer,
                description="This browser was already subscribed; its keys and zone are updated.",
            ),
            400: OpenApiResponse(response=DetailSerializer, description="Nothing was saved."),
            503: OpenApiResponse(
                response=DetailSerializer,
                description="Notifications are not set up on this server. Nothing was saved.",
            ),
        },
    )
    def post(self, request):
        if vapid_config() is None:
            return Response(
                {"detail": copy.PUSH_OFF_NOTHING_SAVED},
                status=status.HTTP_503_SERVICE_UNAVAILABLE,
            )
        body = SubscriptionRequestSerializer(data=request.data)
        body.is_valid(raise_exception=True)
        data = body.validated_data
        household = household_of(request)
        with transaction.atomic():
            # An endpoint is a capability one browser holds. If that browser was
            # subscribed under another household's account before, that row must
            # go, or the old household's notifications would keep reaching a
            # browser now signed in elsewhere. Deleting it reveals nothing and
            # sends nothing: it only removes a delivery route.
            PushSubscription.objects.filter(endpoint=data["endpoint"]).exclude(
                household=household
            ).delete()
            existing = scoped(PushSubscription, request).filter(endpoint=data["endpoint"]).first()
            fields = {
                "user": request.user,
                "p256dh": data["keys"]["p256dh"],
                "auth": data["keys"]["auth"],
                "user_agent": data.get("user_agent", ""),
                "tz": data.get("tz", "UTC"),
                "failure_count": 0,
            }
            if existing is None:
                subscription = PushSubscription.objects.create(
                    household=household, endpoint=data["endpoint"], **fields
                )
                code = status.HTTP_201_CREATED
            else:
                for name, value in fields.items():
                    setattr(existing, name, value)
                existing.save()
                subscription = existing
                code = status.HTTP_200_OK
            pref = _preferences(request)
            if pref._state.adding:
                pref.save()
        subscription.refresh_from_db()
        return Response(SubscriptionSerializer(subscription).data, status=code)


class SubscriptionDetailView(APIView):
    @extend_schema(
        operation_id="api_push_subscriptions_destroy",
        tags=PUSH_TAGS,
        responses={
            204: OpenApiResponse(description="Unsubscribed. This browser gets nothing more."),
            404: OpenApiResponse(response=DetailSerializer, description=copy.NOT_YOUR_SUBSCRIPTION),
        },
    )
    def delete(self, request, subscription_id):
        deleted, _ = (
            scoped(PushSubscription, request).filter(pk=subscription_id, user=request.user).delete()
        )
        if not deleted:
            return Response(
                {"detail": copy.NOT_YOUR_SUBSCRIPTION}, status=status.HTTP_404_NOT_FOUND
            )
        return Response(status=status.HTTP_204_NO_CONTENT)


class PreferenceView(APIView):
    @extend_schema(
        operation_id="api_push_preferences_retrieve",
        tags=PUSH_TAGS,
        responses={200: PreferenceSerializer},
    )
    def get(self, request):
        return Response(_preference_body(_preferences(request)))

    @extend_schema(
        operation_id="api_push_preferences_update",
        tags=PUSH_TAGS,
        request=PreferenceSerializer,
        responses={200: PreferenceSerializer, 400: DetailSerializer},
    )
    def put(self, request):
        body = PreferenceSerializer(data=request.data)
        body.is_valid(raise_exception=True)
        pref = _preferences(request)
        for name, value in body.validated_data.items():
            setattr(pref, name, value)
        pref.updated_at = Now()
        pref.save()
        pref.refresh_from_db()
        return Response(_preference_body(pref))


class _SessionWithoutCsrf(SessionAuthentication):
    """For the one route a notification's "Turn these off" button calls from
    the service worker, which cannot read the CSRF cookie. Safe because the
    route can only ever turn a kind OFF: a forged request could silence a
    notification, never send one, read one or change anything else."""

    def enforce_csrf(self, request):
        return None


class _SessionWithoutCsrfScheme(OpenApiAuthenticationExtension):
    """The session cookie, named apart from `cookieAuth` because on this one
    route no CSRF token is asked for (see `_SessionWithoutCsrf`)."""

    target_class = "push.views._SessionWithoutCsrf"
    name = "cookieAuthNoCsrf"

    def get_security_definition(self, auto_schema):
        return {
            "type": "apiKey",
            "in": "cookie",
            "name": "sessionid",
            "description": "The browser session, with no CSRF token: this route can only "
            "turn a notification kind off.",
        }


class PreferenceOffView(APIView):
    """`POST /api/push/preferences/off {kind}` - the one-tap silence (spec D7,
    compulsion test (d))."""

    authentication_classes = [DeviceTokenAuthentication, _SessionWithoutCsrf]

    @extend_schema(
        operation_id="api_push_preferences_off_create",
        tags=PUSH_TAGS,
        request=OffRequestSerializer,
        responses={200: OffResponseSerializer, 400: DetailSerializer},
    )
    def post(self, request):
        body = OffRequestSerializer(data=request.data)
        body.is_valid(raise_exception=True)
        kind = body.validated_data["kind"]
        if kind not in KINDS:
            return Response(
                {"detail": copy.UNKNOWN_KIND.format(kind=kind, kinds=", ".join(KINDS))},
                status=status.HTTP_400_BAD_REQUEST,
            )
        pref = _preferences(request)
        setattr(pref, kind, False)
        pref.updated_at = Now()
        pref.save()
        pref.refresh_from_db()
        return Response(
            {**_preference_body(pref), "detail": copy.TURNED_OFF.format(kind=copy.KIND_NAMES[kind])}
        )
