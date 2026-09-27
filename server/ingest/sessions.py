"""`/api/ingest/sessions` - the session vault's two routes (backend-etl ticket 02).

    GET /api/ingest/sessions            every stored session's metadata
    GET /api/ingest/sessions/<source>   one session's metadata
    PUT /api/ingest/sessions/<source>   hand over a session (owner only)

**No response ever carries the secret**, the PUT's included: what comes back
is when it was captured, when it may expire, whether an upstream has refused
it, and its non-secret `config`. `tools/connect_session.py` is the one
intended caller of the PUT.

**Owner only, for the PUT, and why that does not break ADR 0045's "the role
governs membership only".** This is not a data route: nobody, owner included,
can ever read the secret back, and every member sees the same metadata. What
the PUT decides is which outside account the household's feeds run as, which
is a question about who speaks for the household, the same shape as who may
invite. A member can still see that Canvas needs a login and ask the owner.

**BofA is refused by name** (map ruling 7, ticket 09), with a 400 that says
why, before the vault is touched.
"""
from __future__ import annotations

from django.http import Http404
from drf_spectacular.utils import OpenApiResponse, extend_schema
from rest_framework import serializers, status
from rest_framework.response import Response
from rest_framework.views import APIView

from household.permissions import IsHouseholdOwner
from household.tenancy import household_of, scoped
from ingest import vault
from ingest.models import CredentialKind, SessionSource, SourceCredential

SESSION_TAGS = ["ingest"]


class IsHouseholdOwnerForSessions(IsHouseholdOwner):
    """`IsHouseholdOwner`, with the refusal worded for this route."""

    message = (
        "Nothing was stored. Only an owner of this household may hand over a login for "
        "its feeds. Every member can see which feeds need one."
    )


class SessionPutSerializer(serializers.Serializer):
    secret = serializers.JSONField(
        help_text=(
            'canvas/webassign: {"cookies": [{"name", "value", "domain", ...}]}. '
            'drive: {"refresh_token", "client_id", "client_secret", "token_uri", '
            '"scopes"}. Never a password.'
        )
    )
    config = serializers.JSONField(
        required=False,
        help_text=(
            "Non-secret settings a job needs (Canvas base_url, a Drive folder id). "
            "Merged into what is stored. Shown to every member."
        ),
    )
    expires_hint = serializers.DateTimeField(required=False, allow_null=True)


class SessionMetadataSerializer(serializers.Serializer):
    source = serializers.ChoiceField(choices=SessionSource.choices)
    kind = serializers.ChoiceField(choices=CredentialKind.choices)
    captured_at = serializers.DateTimeField()
    expires_hint = serializers.DateTimeField(allow_null=True)
    invalid_since = serializers.DateTimeField(allow_null=True)
    config = serializers.JSONField()


class SessionListSerializer(serializers.Serializer):
    sessions = SessionMetadataSerializer(many=True)


def _metadata(credential: SourceCredential) -> dict:
    return {
        "source": credential.source,
        "kind": credential.kind,
        "captured_at": credential.captured_at,
        "expires_hint": credential.expires_hint,
        "invalid_since": credential.invalid_since,
        "config": credential.config,
    }


class SessionListView(APIView):
    @extend_schema(
        operation_id="api_ingest_sessions_list",
        tags=SESSION_TAGS,
        responses={
            200: OpenApiResponse(
                response=SessionListSerializer,
                description=(
                    "Metadata for every login this household has handed over. Never the "
                    "secret. `invalid_since` set means an upstream refused it."
                ),
            )
        },
    )
    def get(self, request):
        rows = scoped(SourceCredential, request).order_by("source")
        return Response(SessionListSerializer({"sessions": [_metadata(r) for r in rows]}).data)


class SessionDetailView(APIView):
    def get_permissions(self):
        if self.request.method == "PUT":
            return [IsHouseholdOwnerForSessions()]
        return super().get_permissions()

    @extend_schema(
        operation_id="api_ingest_sessions_retrieve",
        tags=SESSION_TAGS,
        responses={
            200: OpenApiResponse(
                response=SessionMetadataSerializer,
                description="One stored login's metadata. Never the secret.",
            ),
            404: OpenApiResponse(description="No login stored for this source."),
        },
    )
    def get(self, request, source: str):
        credential = scoped(SourceCredential, request).filter(source=source).first()
        if credential is None:
            raise Http404(f"No {source} login is stored for this household.")
        return Response(SessionMetadataSerializer(_metadata(credential)).data)

    @extend_schema(
        operation_id="api_ingest_sessions_update",
        tags=SESSION_TAGS,
        request=SessionPutSerializer,
        responses={
            200: OpenApiResponse(
                response=SessionMetadataSerializer,
                description=(
                    "Stored, replacing any earlier login for this source and clearing "
                    "`invalid_since`. The body is metadata only; the secret is not echoed."
                ),
            ),
            400: OpenApiResponse(
                description=(
                    "Nothing was stored: `bofa` (never leaves the laptop), an unknown "
                    "source, a secret of the wrong shape, anything naming a password, or "
                    "a config key that names a secret. `detail` says which."
                )
            ),
            403: OpenApiResponse(description="Nothing was stored: the caller is not an owner."),
            503: OpenApiResponse(
                description="Nothing was stored: LEGION_VAULT_KEY is unset or invalid."
            ),
        },
    )
    def put(self, request, source: str):
        try:
            vault.session_source(source)
        except vault.SecretRejected as exc:
            return Response({"detail": str(exc)}, status=status.HTTP_400_BAD_REQUEST)
        body = SessionPutSerializer(data=request.data)
        body.is_valid(raise_exception=True)
        data = body.validated_data
        try:
            credential = vault.store(
                household_of(request),
                source,
                data["secret"],
                config=data.get("config"),
                expires_hint=data.get("expires_hint"),
            )
        except vault.SecretRejected as exc:
            return Response({"detail": str(exc)}, status=status.HTTP_400_BAD_REQUEST)
        except vault.VaultUnavailable as exc:
            return Response({"detail": str(exc)}, status=status.HTTP_503_SERVICE_UNAVAILABLE)
        return Response(SessionMetadataSerializer(_metadata(credential)).data)
