"""The store-rep demo's routes, for the /try page's own panel (`frontend/src/components/demo/
store-rep/`). A recruiter's visit only, like every demo route.

- `GET  /api/demo/shop/state`: the scenario, the catalogue, the visit's orders, and what asking for
  a person would do now. Reads only.
- `POST /api/demo/shop/scenario`: the chip's switch, the same one the voice tools use (ADR 0035).
- `GET  /api/demo/shop/call-card`: the after-call card for the latest conversation (`call_card`).
- `POST /api/demo/shop/callback`: the callback form (`callback`), behind `LEGION_DEMO_HANDOFF`.
"""

from __future__ import annotations

from drf_spectacular.utils import OpenApiResponse, extend_schema, inline_serializer
from rest_framework import serializers
from rest_framework.response import Response
from rest_framework.throttling import SimpleRateThrottle
from rest_framework.views import APIView

from api.schema import DetailSerializer
from demo.content_views import DEMO_TAGS, DemoRefusalSerializer, RecruiterOnly
from demo.models import ShopOrder
from demo.shop import call_card, callback, catalogue, scenario
from legion.client_ip import ClientIpThrottleMixin, client_ip

NO_STORE = {"Cache-Control": "private, no-store"}


class ShopIpThrottle(ClientIpThrottleMixin, SimpleRateThrottle):
    scope = "demo_shop_ip"

    def get_cache_key(self, request, view):
        return self.cache_format % {"scope": self.scope, "ident": self.get_ident(request)}


class CallbackIpThrottle(ShopIpThrottle):
    scope = "demo_callback_ip"


class _ShopView(APIView):
    permission_classes = [RecruiterOnly]
    allows_recruiter = True
    throttle_classes = [ShopIpThrottle]


# ---- shapes, for the contract ---------------------------------------------------------------


class ShopProductSerializer(serializers.Serializer):
    sku = serializers.CharField()
    name = serializers.CharField()
    category = serializers.CharField()
    price_cents = serializers.IntegerField()
    in_stock = serializers.IntegerField(help_text="This visit's count: its placed orders are off.")
    blurb = serializers.CharField()


class ShopOrderLineSerializer(serializers.Serializer):
    sku = serializers.CharField()
    name = serializers.CharField()
    qty = serializers.IntegerField()
    unit_cents = serializers.IntegerField()
    line_cents = serializers.IntegerField()


class ShopOrderSerializer(serializers.Serializer):
    ref = serializers.CharField()
    status = serializers.ChoiceField(choices=[ShopOrder.QUOTED, ShopOrder.PLACED])
    lines = ShopOrderLineSerializer(many=True)
    bundle_saving_cents = serializers.IntegerField()
    shipping_cents = serializers.IntegerField()
    total_cents = serializers.IntegerField()


class ShopStateSerializer(serializers.Serializer):
    active = serializers.BooleanField(help_text="The visit is in the store-rep scenario.")
    shop_name = serializers.CharField()
    products = ShopProductSerializer(many=True)
    orders = ShopOrderSerializer(many=True)
    callback = serializers.ChoiceField(
        choices=[
            callback.OFF,
            callback.AVAILABLE,
            callback.ALREADY,
            callback.DAY_FULL,
            callback.NO_VISIT,
        ],
        help_text="What asking for a person would do now: `off` (switched off), `available`, "
        "`already` (this visit asked), `day_full` (today's cap is used up), `no_visit`.",
    )


class ShopScenarioRequestSerializer(serializers.Serializer):
    active = serializers.BooleanField()


class CallTurnSerializer(serializers.Serializer):
    kind = serializers.ChoiceField(choices=["greeting", "turn"])
    spoken = serializers.BooleanField()
    first_audio_ms = serializers.IntegerField(
        allow_null=True,
        help_text="Engine-side: from the utterance reaching the engine to its first audio out.",
    )
    answer_audio_ms = serializers.IntegerField(allow_null=True)
    stt_ms = serializers.IntegerField(allow_null=True)
    turn_ms = serializers.IntegerField(allow_null=True)


class CallLineSerializer(serializers.Serializer):
    who = serializers.ChoiceField(choices=["visitor", "assistant"])
    text = serializers.CharField()
    typed = serializers.BooleanField()
    at = serializers.CharField()


class CallCardSerializer(serializers.Serializer):
    outcome = serializers.ChoiceField(choices=list(call_card.OUTCOME_WORDS))
    outcome_words = serializers.CharField()
    summary = serializers.ListField(child=serializers.CharField())
    transcript = CallLineSerializer(many=True)
    turns = CallTurnSerializer(many=True)
    median_first_audio_ms = serializers.IntegerField(allow_null=True)
    record = serializers.DictField(help_text="What a CRM would receive: a plain JSON record.")


class CallbackRequestSerializer(serializers.Serializer):
    name = serializers.CharField(max_length=60)
    contact = serializers.CharField(max_length=120, help_text="A phone number or an email.")
    note = serializers.CharField(max_length=280, required=False, allow_blank=True, default="")
    # Left empty by a person; a form filler that fills every field is refused.
    website = serializers.CharField(required=False, allow_blank=True, default="")


CallbackResponse = inline_serializer(
    name="DemoCallbackResult",
    fields={
        "status": serializers.CharField(help_text="`sent` or `saved`."),
        "detail": serializers.CharField(help_text="What happened, in words. Show it."),
    },
)


# ---- views ------------------------------------------------------------------------------------


def _state(user) -> dict:
    visit = scenario.visit_of(user)
    orders = list(ShopOrder.objects.filter(visit=visit).order_by("created_at")) if visit else []
    placed: dict[str, int] = {}
    for order in orders:
        if order.status == ShopOrder.PLACED:
            for line in order.lines:
                placed[line["sku"]] = placed.get(line["sku"], 0) + line["qty"]
    return {
        "active": scenario.active(user),
        "shop_name": catalogue.SHOP_NAME,
        "products": [
            {
                "sku": p.sku,
                "name": p.name,
                "category": p.category,
                "price_cents": p.price_cents,
                "in_stock": catalogue.available(p.sku, placed),
                "blurb": p.blurb,
            }
            for p in catalogue.PRODUCTS
        ],
        "orders": [call_card.order_json(o) for o in orders],
        "callback": callback.state_for(user),
    }


class ShopStateView(_ShopView):
    @extend_schema(
        tags=DEMO_TAGS,
        operation_id="api_demo_shop_state_retrieve",
        summary="The store-rep demo's state for this visit (reads only)",
        responses={
            200: ShopStateSerializer,
            403: OpenApiResponse(DemoRefusalSerializer, description="Not a recruiter's visit."),
        },
    )
    def get(self, request):
        return Response(_state(request.user), headers=NO_STORE)


class ShopScenarioView(_ShopView):
    @extend_schema(
        tags=DEMO_TAGS,
        operation_id="api_demo_shop_scenario_create",
        summary="Switch this visit into or out of the store-rep scenario",
        request=ShopScenarioRequestSerializer,
        responses={
            200: ShopStateSerializer,
            400: DetailSerializer,
            403: OpenApiResponse(DemoRefusalSerializer, description="Not a recruiter's visit."),
        },
    )
    def post(self, request):
        body = ShopScenarioRequestSerializer(data=request.data)
        if not body.is_valid():
            return Response({"detail": f"Nothing changed: {body.errors}"}, status=400)
        if not scenario.switch(request.user, body.validated_data["active"]):
            return Response({"detail": "Nothing changed: this visit has no record."}, status=400)
        return Response(_state(request.user), headers=NO_STORE)


class CallCardView(_ShopView):
    @extend_schema(
        tags=DEMO_TAGS,
        operation_id="api_demo_shop_call_card_retrieve",
        summary="The after-call card for this visit's latest conversation (reads only)",
        responses={
            200: CallCardSerializer,
            403: OpenApiResponse(DemoRefusalSerializer, description="Not a recruiter's visit."),
            404: DetailSerializer,
        },
    )
    def get(self, request):
        card = call_card.build(request.user)
        if card is None:
            return Response({"detail": "No conversation on this visit yet."}, status=404)
        return Response(card, headers=NO_STORE)


class CallbackView(_ShopView):
    throttle_classes = [CallbackIpThrottle]

    @extend_schema(
        tags=DEMO_TAGS,
        operation_id="api_demo_shop_callback_create",
        summary="Ask for a callback from a person (switched off unless LEGION_DEMO_HANDOFF=on)",
        request=CallbackRequestSerializer,
        responses={
            201: CallbackResponse,
            400: DetailSerializer,
            403: OpenApiResponse(DemoRefusalSerializer, description="Not a recruiter's visit."),
            409: DetailSerializer,
            429: DetailSerializer,
        },
    )
    def post(self, request):
        body = CallbackRequestSerializer(data=request.data)
        if not body.is_valid():
            return Response({"detail": f"Nothing was sent: {body.errors}"}, status=400)
        data = body.validated_data
        if data["website"]:
            return Response({"detail": "Nothing was sent."}, status=400)
        outcome = callback.request_callback(
            request.user,
            name=data["name"],
            contact=data["contact"],
            note=data["note"],
            ip=client_ip(request),
        )
        if outcome.http >= 300:
            return Response({"detail": outcome.words}, status=outcome.http)
        return Response({"status": outcome.status, "detail": outcome.words}, status=outcome.http)
