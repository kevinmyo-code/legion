"""`/api/ingest/plaid` - the bank connection (ADR 0057).

    GET  /api/ingest/plaid                    status, for every member
    POST /api/ingest/plaid/link-token         a Link token for the FIRST link (owner)
    POST /api/ingest/plaid/exchange           store the Item the first link made (owner)
    POST /api/ingest/plaid/update-link-token  a Link token for "Sign in again" (owner)
    POST /api/ingest/plaid/sync               sync now, for every member

**No response ever carries the access token**, and none carries the item id:
every body here is built by hand from named fields.

**One Item per household, and never a second link.** Plaid's Trial plan
allows 10 Items for the Plaid team's lifetime and removing one never frees
the slot. So the first-link routes refuse, in words, once an Item is stored,
there is deliberately no remove route, and a broken connection is repaired
only through update mode (same access token, no new slot).

**Owner only for the three Link routes**, for the reason the session vault's
PUT is (`ingest/sessions.py`): they decide which outside account the
household's ledger runs on, which is who speaks for the household. "Sync now"
writes nothing a member could not already cause by waiting six hours, so any
member may press it.
"""
from __future__ import annotations

from django.db import IntegrityError
from django.utils import timezone
from drf_spectacular.utils import OpenApiResponse, extend_schema
from rest_framework import serializers, status
from rest_framework.response import Response
from rest_framework.views import APIView

from household.models import HouseholdMember
from household.permissions import IsHouseholdOwner
from household.tenancy import household_of
from ingest import plaid_client, plaid_sync, vault
from ingest.freshness import freshness_for
from ingest.jobs import run_job
from ingest.models import IngestRun, PlaidItem, Source

TAGS = ["ingest"]

SLOT_WARNING = (
    "Plaid's free plan allows 10 bank connections for the life of the Plaid account, and "
    "removing one never gives it back. Linking Bank of America again would use up another "
    "one, so this page never re-links: when the bank asks, choose Sign in again."
)
ALREADY_LINKED = (
    "Nothing was linked. This household already has a bank connection, and linking again "
    "would spend one of Plaid's 10 lifetime connections. Use Sign in again to repair it."
)


class IsHouseholdOwnerForBank(IsHouseholdOwner):
    message = (
        "Nothing was changed. Only an owner of this household may connect its bank. Every "
        "member can see the connection's status."
    )


class BankAccountSerializer(serializers.Serializer):
    name = serializers.CharField(allow_null=True)
    mask = serializers.CharField(allow_null=True)
    type = serializers.CharField(allow_null=True)
    subtype = serializers.CharField(allow_null=True)


class BankStatusSerializer(serializers.Serializer):
    configured = serializers.BooleanField(
        help_text="The server has Plaid keys. False means nothing here can be linked."
    )
    configuration_problem = serializers.CharField(allow_null=True)
    environment = serializers.CharField(help_text="`sandbox` or `production`.")
    environment_sentence = serializers.CharField(
        help_text=(
            "Which Plaid environment is active, in words. Sandbox is Plaid's test bank: "
            "show this so a test link is never mistaken for real bank data."
        )
    )
    connected = serializers.BooleanField()
    institution_name = serializers.CharField(allow_null=True)
    accounts = BankAccountSerializer(many=True)
    last_synced_at = serializers.DateTimeField(allow_null=True)
    consent_expires_at = serializers.DateTimeField(allow_null=True)
    needs_sign_in = serializers.BooleanField(
        help_text="The bank wants the person again (or its consent ends within 14 days)."
    )
    sentence = serializers.CharField(help_text="The bank connection's freshness line.")
    slot_warning = serializers.CharField()
    is_owner = serializers.BooleanField()


class LinkTokenSerializer(serializers.Serializer):
    link_token = serializers.CharField(help_text="Short-lived; hand it to Plaid Link.")
    expiration = serializers.DateTimeField(allow_null=True)
    mode = serializers.ChoiceField(choices=["create", "update"])


class ExchangeSerializer(serializers.Serializer):
    public_token = serializers.CharField(help_text="What Plaid Link's onSuccess returned.")


class SyncReportSerializer(serializers.Serializer):
    added = serializers.IntegerField()
    modified = serializers.IntegerField()
    removed = serializers.IntegerField()
    unchanged = serializers.IntegerField()
    pending_posted = serializers.IntegerField()
    replaced_older = serializers.IntegerField()
    categories_moved = serializers.IntegerField()
    kept_older = serializers.IntegerField()
    notes = serializers.ListField(child=serializers.CharField())


class SyncResultSerializer(serializers.Serializer):
    outcome = serializers.CharField(help_text="ok, failed, needs_login, skipped or skipped_locked.")
    sentence = serializers.CharField()
    report = SyncReportSerializer(allow_null=True)


def _is_owner(request) -> bool:
    return HouseholdMember.objects.filter(
        user=request.user, role=HouseholdMember.OWNER
    ).exists()


def status_body(request) -> dict:
    household = household_of(request)
    item = PlaidItem.objects.filter(household=household).first()
    problem = plaid_client.configuration_problem()
    entry = freshness_for(
        IngestRun.objects.filter(household=household),
        Source.PLAID,
        timezone.now(),
        plaid_item=item,
    )
    return {
        "configured": problem is None,
        "configuration_problem": problem,
        "environment": plaid_client.environment(),
        "environment_sentence": plaid_client.environment_sentence(),
        "connected": item is not None,
        "institution_name": item.institution_name if item else None,
        "accounts": [
            {
                "name": account.get("name"),
                "mask": account.get("mask"),
                "type": account.get("type"),
                "subtype": account.get("subtype"),
            }
            for account in (item.accounts if item else [])
        ],
        "last_synced_at": item.last_synced_at if item else None,
        "consent_expires_at": item.consent_expires_at if item else None,
        "needs_sign_in": bool(item and plaid_sync.needs_sign_in(item)),
        "sentence": entry["sentence"] if item else "No bank is connected yet.",
        "slot_warning": SLOT_WARNING,
        "is_owner": _is_owner(request),
    }


def _refused(sentence: str, code: int) -> Response:
    return Response({"detail": sentence}, status=code)


def _gateway_or_refusal():
    problem = plaid_client.configuration_problem()
    if problem:
        return None, _refused(
            f"Nothing was changed. {problem}", status.HTTP_503_SERVICE_UNAVAILABLE
        )
    return plaid_client.gateway(), None


def _plaid_refused(exc: plaid_client.PlaidError) -> Response:
    return _refused(
        f"Nothing was changed. Plaid refused the request ({exc.code}"
        f"{': ' + exc.message if exc.message else ''}).",
        status.HTTP_502_BAD_GATEWAY,
    )


class BankStatusView(APIView):
    @extend_schema(
        operation_id="api_ingest_plaid_retrieve",
        tags=TAGS,
        responses={200: BankStatusSerializer},
        description="The household's bank connection. Never the access token.",
    )
    def get(self, request):
        return Response(BankStatusSerializer(status_body(request)).data)


class BankLinkTokenView(APIView):
    permission_classes = [IsHouseholdOwnerForBank]

    @extend_schema(
        operation_id="api_ingest_plaid_link_token_create",
        tags=TAGS,
        request=None,
        responses={
            200: LinkTokenSerializer,
            403: OpenApiResponse(description="Not an owner. Nothing was changed."),
            409: OpenApiResponse(description="A bank is already connected; use update mode."),
            502: OpenApiResponse(description="Plaid refused. `detail` says what it said."),
            503: OpenApiResponse(description="The server has no Plaid keys."),
        },
    )
    def post(self, request):
        household = household_of(request)
        if PlaidItem.objects.filter(household=household).exists():
            return _refused(ALREADY_LINKED, status.HTTP_409_CONFLICT)
        gw, refusal = _gateway_or_refusal()
        if refusal:
            return refusal
        try:
            body = gw.create_link_token(client_user_id=str(household.pk))
        except plaid_client.PlaidError as exc:
            return _plaid_refused(exc)
        return Response(LinkTokenSerializer({**body, "mode": "create"}).data)


class BankUpdateLinkTokenView(APIView):
    permission_classes = [IsHouseholdOwnerForBank]

    @extend_schema(
        operation_id="api_ingest_plaid_update_link_token_create",
        tags=TAGS,
        request=None,
        responses={
            200: LinkTokenSerializer,
            403: OpenApiResponse(description="Not an owner. Nothing was changed."),
            404: OpenApiResponse(description="No bank is connected."),
            502: OpenApiResponse(description="Plaid refused. `detail` says what it said."),
            503: OpenApiResponse(description="The server has no Plaid keys, or no vault key."),
        },
        description=(
            "A Link token in update mode: the person signs in to the bank again on the SAME "
            "connection. No new connection is made and none of Plaid's 10 is spent."
        ),
    )
    def post(self, request):
        household = household_of(request)
        item = PlaidItem.objects.filter(household=household).first()
        if item is None:
            return _refused(
                "Nothing was changed. No bank is connected, so there is nothing to sign in "
                "to again.",
                status.HTTP_404_NOT_FOUND,
            )
        gw, refusal = _gateway_or_refusal()
        if refusal:
            return refusal
        try:
            token = plaid_sync.access_token_of(item)
        except vault.VaultUnavailable as exc:
            return _refused(f"Nothing was changed. {exc}", status.HTTP_503_SERVICE_UNAVAILABLE)
        try:
            body = gw.create_link_token(client_user_id=str(household.pk), access_token=token)
        except plaid_client.PlaidError as exc:
            return _plaid_refused(exc)
        return Response(LinkTokenSerializer({**body, "mode": "update"}).data)


class BankExchangeView(APIView):
    permission_classes = [IsHouseholdOwnerForBank]

    @extend_schema(
        operation_id="api_ingest_plaid_exchange_create",
        tags=TAGS,
        request=ExchangeSerializer,
        responses={
            201: BankStatusSerializer,
            403: OpenApiResponse(description="Not an owner. Nothing was stored."),
            409: OpenApiResponse(description="A bank is already connected. Nothing was stored."),
            502: OpenApiResponse(description="Plaid refused. Nothing was stored."),
            503: OpenApiResponse(description="No Plaid keys or no vault key. Nothing was stored."),
        },
        description=(
            "Turns Link's public token into the household's connection. The access token is "
            "sealed by the session vault and never returned. The first sync runs on the next "
            "schedule, or with POST /api/ingest/plaid/sync."
        ),
    )
    def post(self, request):
        household = household_of(request)
        if PlaidItem.objects.filter(household=household).exists():
            return _refused(ALREADY_LINKED, status.HTTP_409_CONFLICT)
        body = ExchangeSerializer(data=request.data)
        body.is_valid(raise_exception=True)
        gw, refusal = _gateway_or_refusal()
        if refusal:
            return refusal
        try:
            # Sealing is checked BEFORE Plaid is asked: a token Plaid hands
            # back that cannot be stored would be a spent slot with no way in.
            vault.seal({"probe": True})
        except vault.VaultUnavailable as exc:
            return _refused(
                f"Nothing was stored. {exc}", status.HTTP_503_SERVICE_UNAVAILABLE
            )
        try:
            exchanged = gw.exchange_public_token(body.validated_data["public_token"])
        except plaid_client.PlaidError as exc:
            return _plaid_refused(exc)
        try:
            item = PlaidItem.objects.create(
                household=household,
                item_id=exchanged["item_id"],
                access_token_ciphertext=vault.seal({"access_token": exchanged["access_token"]}),
                linked_by=request.user,
            )
        except IntegrityError:
            return _refused(
                "Nothing was stored. Another link for this household finished first; that "
                "one is the household's connection.",
                status.HTTP_409_CONFLICT,
            )
        # Best effort: the token is safe already, and the first sync fills in
        # whatever this could not.
        try:
            plaid_sync._record_item(item, gw.get_item(exchanged["access_token"]))
        except plaid_client.PlaidError:
            pass
        return Response(
            BankStatusSerializer(status_body(request)).data, status=status.HTTP_201_CREATED
        )


class BankSyncView(APIView):
    @extend_schema(
        operation_id="api_ingest_plaid_sync_create",
        tags=TAGS,
        request=None,
        responses={200: SyncResultSerializer},
        description=(
            "Runs the bank sync now, recorded like a scheduled run. `sentence` says what "
            "happened; a run that could not sync says so with outcome failed or needs_login."
        ),
    )
    def post(self, request):
        household = household_of(request)
        if not PlaidItem.objects.filter(household=household).exists():
            return Response(
                SyncResultSerializer(
                    {
                        "outcome": "skipped",
                        "sentence": "Nothing was synced. No bank is connected.",
                        "report": None,
                    }
                ).data
            )
        holder: dict = {}

        def job(run):
            outcome = plaid_sync.run_sync(run)
            holder["report"] = getattr(run, "last_report", None)
            return outcome

        run = run_job(Source.PLAID, household, job)
        report = holder.get("report")
        if run.outcome == "ok" and report is not None:
            sentence = report.sentence()
        elif run.outcome == "skipped_locked":
            sentence = "A bank sync is already running. Nothing more was started."
        else:
            sentence = f"The bank sync did not finish. {run.error or ''}".strip()
        return Response(
            SyncResultSerializer(
                {
                    "outcome": run.outcome,
                    "sentence": sentence,
                    "report": report.summary() if report is not None else None,
                }
            ).data
        )
