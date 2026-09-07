"""Shared OpenAPI plumbing: the pieces `server/openapi.yaml` needs that no
single view owns (django-engine ticket 04's "the schema is the handoff").

`server/openapi.yaml` is the contract ticket 09 codes against, and a second
Android app generates its client from. That makes one property
load-bearing above all others: **the schema must describe what the
endpoints actually do.** A schema that lies is worse than no schema,
because a generated client believes it and the mistake surfaces as a
runtime decode failure on a phone rather than as a red build here.

Three things live here.

## 1. The security scheme

Without an `OpenApiAuthenticationExtension`, drf-spectacular cannot see
`DeviceTokenAuthentication` at all - it emitted "could not resolve
authenticator" for every authenticated view and left `security:` off the
whole document. A generated client built from that schema would have had
no way to know an `Authorization: Token <key>` header exists, which is the
single fact it most needs.

## 2. `paged_serializer`

Every `?since=` list route answers `{"results": [...], "next": <cursor or
null>}` (`api/sync.paginate_since`). Before this file existed, the schema
described those responses as a BARE ARRAY of rows - measured against the
deployed engine on 2026-09-07, `GET /api/places/?active=1` returns the
envelope, so the schema was wrong about every list route in the API. This
factory builds the envelope component from the row serializer, so the two
cannot drift and a new synced table gets a correct one for free.

## 3. `SyncedAutoSchema`

`api/synced.SyncedModelViewSet` is one class serving thirteen tables, so a
static `@extend_schema` on its methods cannot name the right serializer -
there are thirteen of them. This `AutoSchema` subclass reads
`view.serializer_class` and `view.action` at generation time instead, so
every registered table is described from the same source `api/registry.py`
routes it from. Registering an aspect gets its schema automatically; there
is no per-viewset decorator to forget.

## The 400 bodies are described as free-form objects, deliberately

This API refuses a bad write in two different shapes, both real:

- `{"detail": "..."}` - `SyncedSerializer.to_internal_value`'s unknown-field
  refusal, `api/sync.save_or_400`'s database refusal, and every hand-written
  404/405 body.
- `{"<field>": ["..."]}` - DRF's own `serializer.is_valid(raise_exception=True)`,
  which is what a field-level `validate_*` raises.

Declaring one of those two as THE 400 body would be a lie about the other
half, and OpenAPI 3.0's `oneOf` would describe a union no client here
wants to unpack. A free-form object with the two shapes named in prose is
the honest description; `WRITE_REFUSED` below is that response, reused
everywhere rather than re-worded per view.
"""
from __future__ import annotations

from drf_spectacular.extensions import OpenApiAuthenticationExtension
from drf_spectacular.openapi import AutoSchema
from drf_spectacular.types import OpenApiTypes
from drf_spectacular.utils import OpenApiParameter, OpenApiResponse
from rest_framework import serializers


class DeviceTokenScheme(OpenApiAuthenticationExtension):
    """`Authorization: Token <key>`, one token per device (ADR 0044 rule 3).

    Registered by import - `api/apps.py`'s `ready()` imports this module for
    exactly that reason, and nothing else imports it for its side effect.
    """

    target_class = "household.authentication.DeviceTokenAuthentication"
    name = "deviceToken"

    def get_security_definition(self, auto_schema) -> dict:
        return {
            "type": "apiKey",
            "in": "header",
            "name": "Authorization",
            "description": (
                "One token per device (phone, browser, future robot), revocable alone. "
                "Send the value as `Token <key>`, including the word Token and the space. "
                "Get a key from POST /api/auth/login; POST /api/auth/logout revokes only "
                "the key that made the request."
            ),
        }


class DetailSerializer(serializers.Serializer):
    """`{"detail": "..."}` - the shape every hand-written refusal in this API
    uses, and the shape DRF itself uses for 401/403/404/405. The strings are
    written to be shown to a person: they say what did NOT happen (CLAUDE.md
    section 7's outcome-verb rule), so a client should surface `detail`
    rather than inventing its own wording."""

    detail = serializers.CharField()


WRITE_REFUSED = OpenApiResponse(
    response=OpenApiTypes.OBJECT,
    description=(
        "The write was refused and NOTHING was written. Two body shapes occur and both are "
        "JSON objects: `{\"detail\": \"...\"}` for an unknown field, a database refusal or a "
        "hand-written check, and `{\"<field>\": [\"...\"]}` for a field-level validation "
        "error. Both carry text meant to be shown to a person."
    ),
)

NOT_FOUND = OpenApiResponse(
    response=DetailSerializer,
    description="No such row. Nothing was changed.",
)

NO_CONTENT = OpenApiResponse(
    description="Done. Soft-deleted (the tombstone row stays so an unsynced client learns of it).",
)

SINCE_PARAMETER = OpenApiParameter(
    name="since",
    location=OpenApiParameter.QUERY,
    type=OpenApiTypes.DATETIME,
    required=False,
    description=(
        "ISO-8601 UTC watermark. Returns rows with `updated_at >= since`, tombstones "
        "included, oldest first. **Omitted or unparsable means EVERYTHING, never nothing** "
        "(api/sync.parse_since), so a fresh client's first pull is the whole table. Hand "
        "back the `next` cursor from the previous page, or `server_time` from GET "
        "/api/changes; both are already `Z`-suffixed so they need no extra encoding."
    ),
)

ACTIVE_PARAMETER = OpenApiParameter(
    name="active",
    location=OpenApiParameter.QUERY,
    type=OpenApiTypes.STR,
    required=False,
    enum=["1", "true", "yes", "on"],
    description=(
        "Narrows the feed to live rows (`deleted_at is null`). Composes with `since`. "
        "Anything else, `0` and `false` included, means the narrowing was not asked for "
        "and tombstones stay in - a caller who cannot be understood sees too much, never "
        "silently nothing."
    ),
)

# One envelope component per row serializer. Cached because building two
# classes with the same name would give drf-spectacular two components
# called `PagedPlace` and it would resolve the collision with a numeral,
# which is exactly the sort of generated-client noise this file exists to
# avoid.
_PAGED_CACHE: dict[type, type] = {}


def paged_serializer(item_serializer: type[serializers.BaseSerializer]) -> type:
    """The `{"results": [...], "next": ...}` envelope over `item_serializer`.

    `next` is the `updated_at` of the last row on a FULL page, rendered by
    DRF's own `DateTimeField` so it carries a `Z` and not a `+00:00` (see
    `api/sync.paginate_since` for the query-string footgun that forced
    that), and null when this was the last page. A client pages by
    re-requesting with `since=<next>` until `next` comes back null.
    """
    cached = _PAGED_CACHE.get(item_serializer)
    if cached is not None:
        return cached
    name = item_serializer.__name__.removesuffix("Serializer")
    built = type(
        f"Paged{name}Serializer",
        (serializers.Serializer,),
        {
            "results": item_serializer(many=True),
            "next": serializers.DateTimeField(
                allow_null=True,
                help_text=(
                    "Cursor for the next page: hand it back as `?since=`. Null means this "
                    "was the last page."
                ),
            ),
        },
    )
    _PAGED_CACHE[item_serializer] = built
    return built


class SyncedAutoSchema(AutoSchema):
    """Describes `api/synced.SyncedModelViewSet` from the viewset's own
    `serializer_class`, `action` and identity settings.

    Set once on the base viewset, so all thirteen registered tables (and
    every future one) are described without a decorator anyone has to
    remember. The four actions map to what the code actually returns:

    | action | method | status | body |
    |---|---|---|---|
    | `list` | GET | 200 | the paged envelope |
    | `retrieve` | GET | 200 | one row (gated tables only) |
    | `create` | POST | 201 | the row as stored |
    | `upsert` | PUT | 200 | the row as stored, created or updated |
    | `destroy` | DELETE | 204 | empty |

    **The 405 a gated table answers PUT and DELETE with is not in this
    document, and that is correct.** `api/synced.GatedReadViewSet` declares no
    PUT and no DELETE at all, so there is no operation to attach a response
    to; a schema that listed them would be advertising write routes that do
    not exist. The refusal is still a sentence rather than DRF's default -
    `SyncedModelViewSet._gate_refusal` - because a client that guessed wrong
    deserves the address of the gate, not just a status code.
    """

    def get_tags(self) -> list[str]:
        """One tag per aspect rather than the path-derived `api` for all of
        them - a generated client groups by tag, and `api` on every route
        would put 26 methods on one class."""
        return [self.view.aspect]

    def _is_list_view(self, serializer=None) -> bool:
        """Always False, and this is the correction that matters most in
        this file.

        `AutoSchema._is_list_view` answers True for any action named
        `list`, and `_get_response_for_code` then wraps the response
        component in `type: array`. That produced a schema saying
        `GET /api/places/` returns `[Place, ...]` when it returns
        `{"results": [...], "next": ...}` - checked against the deployed
        engine on 2026-09-07, not inferred. The envelope from
        `paged_serializer` is already the whole body, and wrapping it in an
        array a second time describes a response this API has never sent.
        """
        return False

    def get_operation_id(self) -> str:
        """`_is_list_view` above is what the base class uses to decide
        between the `_list` and `_retrieve` suffix, so turning it off would
        have renamed every collection GET to `..._retrieve` - accurate
        about the body's shape and misleading about what the call does. The
        suffix is restored from the action instead, which is where the fact
        actually lives."""
        if self.view.action == "list":
            return "_".join([t.replace("-", "_") for t in self._tokenize_path()] + ["list"])
        return super().get_operation_id()

    def get_request_serializer(self):
        if self.view.action in {"create", "upsert"}:
            return self.view.serializer_class
        return None

    def get_response_serializers(self):
        view = self.view
        item = view.serializer_class
        action = view.action
        if action == "list":
            return {200: paged_serializer(item)}
        if action == "retrieve":
            return {200: item, 404: NOT_FOUND}
        if action == "create":
            return {201: item, 400: WRITE_REFUSED}
        if action == "upsert":
            responses = {200: item, 400: WRITE_REFUSED}
            if view.identity_is_primary_key:
                # `PUT /<id>/` on an id this server never minted is a 404,
                # not an insert - ticket 04's "the `id` form never inserts".
                responses[404] = OpenApiResponse(
                    response=DetailSerializer,
                    description=(
                        "This identity is the server's own primary key, and it has not "
                        "issued this one. Nothing was created. POST the collection instead."
                    ),
                )
            return responses
        if action == "destroy":
            return {204: NO_CONTENT, 404: NOT_FOUND}
        return None

    def get_override_parameters(self) -> list:
        view = self.view
        if view.action == "list":
            # `active` is dropped for a table with no `deleted_at` column.
            # Advertising a filter that cannot narrow anything would be the
            # same class of lie as the bare-array list response this file was
            # written to fix: true of the code, false about the effect. See
            # `api/synced.SyncedModelViewSet.has_tombstones`.
            if not view.has_tombstones:
                return [SINCE_PARAMETER]
            return [SINCE_PARAMETER, ACTIVE_PARAMETER]
        if view.action in {"retrieve", "upsert", "destroy"}:
            # Replaces the auto-resolved path parameter with the same type
            # (the URL converter still decides `str` vs `uuid`) plus a
            # description - `identity` is `origin_guid` on most tables,
            # `label` on places and the server's own `id` on voice notes,
            # and a client generated from a bare `identity: string` has no
            # way to know which.
            uuid_converter = view.identity_url_converter == "uuid"
            return [
                OpenApiParameter(
                    name="identity",
                    location=OpenApiParameter.PATH,
                    type=OpenApiTypes.UUID if uuid_converter else OpenApiTypes.STR,
                    required=True,
                    description=f"The row's `{view.identity_field}`.",
                )
            ]
        return []
