"""One resource shape for every table that syncs the same way (django-engine
ticket 04's `SyncedModelViewSet`, built in Phase 5 for places, voice notes,
body and memory - `research/execution-plan.md`'s "widen" phase).

The Phase 2 slice (`api/events.py`, `checklists/views.py`) hand-wrote
`POST`/`PATCH`/soft-`DELETE` per table, which was right for two aspects and
does not scale to the thirteen tables this file routes. Every one of those
thirteen answers the same four questions - what changed since a watermark,
what is live now, store this row under the identity the client already
minted, tombstone this row - so the answers live here once and an aspect
costs a serializer plus one registry entry (`api/registry.py`).

## The four routes

- `GET <table>/?since=<iso>` replaces `fetchChanged*Since`: `updated_at >=
  since`, **tombstones included**, ordered by `updated_at`, 500 rows per
  page with a `next` cursor. A missing `since` means everything.
- `GET <table>/?active=1` replaces `fetchActive*`: the same feed narrowed
  to `deleted_at is null`. The two parameters compose.
- `PUT <table>/<identity>/` replaces `upsert*(originGuid, fields)`:
  idempotent, insert if absent, update if present, 200 either way, and the
  body is the row AS STORED.
- `DELETE <table>/<identity>/` replaces `softDelete*(originGuid)`: sets
  `deleted_at`, 204, idempotent. Never a hard delete.

`POST <table>/` exists only where the identity column IS the primary key
(voice notes), because a PUT to an id the server has never minted cannot
sensibly insert - see `identity_is_primary_key` below.

## The gated tables get the GET half and nothing else

Ticket 04's own exceptions table: "`statements`, `receipts`,
`ledger_transactions`, `receipt_line_items` - **no PUT, no DELETE.** Written
only by ticket 03's gate. GET only", and "`ingested_files` - GET only".
`GatedReadViewSet` below is that row. It is the same `list` over the same
`paginate_since`, with the write half removed at the URL layer rather than
guarded inside a handler, and with a 405 that names the gate endpoint instead
of DRF's bare `Method "PUT" not allowed.` The reason is CLAUDE.md section 4
rule 2 expressed as routing: a row that could be edited into existence around
the gate would not be a gated row.

**Two things those five tables do NOT have, confirmed against the live schema
on 2026-09-07 rather than inferred: `updated_at` and `deleted_at`.** So
`cursor_field` below keys their feed on `created_at` (`last_attempt_at` for
`ingested_files`, the only column there that moves), and `has_tombstones` is
False - there is no tombstone to include, because there is no column to write
one in. That is not a shortfall of this API; it is what an append-only table
is. `private.forbid_mutation_of_facts` blocks UPDATE on all four outright and
blocks DELETE except on an `UNRECONCILED` row, which a rule 7 supersession
removes PHYSICALLY. `LedgerBackend.fetchChangedTransactionsSince`'s own doc
comment says the same thing from the phone's side, in words: "'Includes
tombstones' does not apply here... `created_at` is the only clock it has".
The consequence a client must know, and it is stated in the schema rather
than left to be discovered: a provisional row that is superseded vanishes
with no trace in the feed, so a client that wants to notice must re-read,
not merely page forward.

## What is deliberately configurable, and why each one exists

Nothing here is a general-purpose knob. Every attribute below is a
departure ticket 04's own exceptions table names, or one the phone's
`*Backend.kt` seam already draws:

- `identity_field` - `origin_guid` for body and memory, `label` for places
  (ticket 04: "keyed by `label`"; `places` genuinely has no `origin_guid`
  column, confirmed against the live schema, not inferred), `id` for voice
  notes (which has no `origin_guid` column either).
- `identity_is_primary_key` - when the identity IS the server's own uuid,
  PUT updates and never inserts (ticket 04's own words for the `events` /
  `voice_notes` / `vehicles` row), and POST is the create path instead.
- `put_revives_tombstone` - places only. `SupabasePlacesBackend`'s
  `PlaceUpsertDto` puts an explicit `deleted_at: null` on the wire so that
  re-tagging a forgotten label brings the row back (see that class's own
  doc comment for why the field is non-defaulted there); `SupabaseBodyBackend`
  and `SupabaseMemoryBackend`'s upsert DTOs carry no `deleted_at` at all,
  so an `on conflict do update` there leaves an existing tombstone alone.
  Mirrored rather than unified, because the two behaviours are both
  deliberate on the phone side.
- `allow_delete` - false for `memory_audit`, which is append-only
  (`MemoryBackend` has no `softDeleteMemoryAudit`, and says so in its class
  doc). A DELETE there answers 405 in words, not a silent success.

## Timestamps come from the DATABASE clock, never this process's

`created_at` / `updated_at` on insert are written as
`django.db.models.functions.Now()` - a SQL expression evaluated by
Postgres - and never as `django.utils.timezone.now()`, which is what
`api/events.py` (the Phase 2 slice) does.

The reason is measured, not stylistic. `private.touch_updated_at` stamps
`updated_at := now()` from the DATABASE's clock on every UPDATE. Django
does not run on that machine. Probed against this project's own Postgres
on 2026-09-06, the two clocks differed by roughly half a second, with
Python BEHIND: a row inserted with a Python timestamp and then updated
could come back with an `updated_at` EARLIER than its own insert - which
would silently drop it out of a `?since=` feed keyed on the value the
client was handed at create time. One clock for both writes removes the
whole class of failure. The `events` divergence is flagged in this
ticket's report rather than changed here.

A consequence worth knowing when reading the tests: `Now()` compiles to
`STATEMENT_TIMESTAMP()` on Postgres while the trigger uses `now()`
(= transaction start). Inside pytest's one-transaction-per-test wrapper
those differ, so an updated row's `updated_at` moves BACKWARD relative to
rows inserted earlier in the same test. In production, where each request
is its own transaction, they agree to within a statement.

## Every write reads the row back before answering

`refresh_from_db()` after each insert and update, because the response body
is documented as "the row as stored" and three of the values in it -
`updated_at` from the trigger, and both timestamps from `Now()` - do not
exist in Python until Postgres has computed them.
"""
from __future__ import annotations

import uuid

from django.db.models.functions import Now
from django.urls import path
from rest_framework import serializers, status, viewsets
from rest_framework.response import Response

from api.schema import SyncedAutoSchema
from api.sync import paginate_since, parse_since, save_or_400
from legacy.enums import Provenance

# What `?active=1` accepts. Anything else - including `active=0` and
# `active=false` - means the caller did not ask for the narrowing, and gets
# the full changed-since feed with tombstones in it. Erring toward MORE
# rows is the same posture `api/sync.parse_since` takes for a missing
# `since`: a caller who cannot be understood should see too much, never
# silently see nothing.
TRUTHY = {"1", "true", "yes", "on"}


def choice_error(field: str, value, allowed: tuple[str, ...]) -> serializers.ValidationError:
    """The 400 body for a value a CHECK constraint would refuse, naming the
    allowed set so the caller does not have to guess (ticket 04's own rule).
    Same wording as `api/events.py` and `checklists/serializers.py` build by
    hand; those two predate this module and are left alone."""
    return serializers.ValidationError(
        f"'{value}' is not a valid {field}. Use one of: {', '.join(allowed)}."
    )


def range_error(field: str, value, low, high) -> serializers.ValidationError:
    return serializers.ValidationError(
        f"{value} is out of range for {field}. It must be between {low} and {high}."
    )


def minimum_error(field: str, value, minimum, *, exclusive: bool) -> serializers.ValidationError:
    comparison = "greater than" if exclusive else "at least"
    return serializers.ValidationError(
        f"{value} is not a valid {field}. It must be {comparison} {minimum}."
    )


def blank_error(field: str) -> serializers.ValidationError:
    """Postgres spells this `length(trim(col)) > 0` on a dozen of these
    tables; a whitespace-only string is refused there and is refused here,
    in words, before the round trip."""
    return serializers.ValidationError(f"{field} cannot be blank.")


class SyncedSerializer(serializers.ModelSerializer):
    """Base for every serializer this viewset drives.

    Two behaviours, both ticket 04's own text:

    1. **Unknown fields are refused, never dropped.** "A limb sending a
       column the server does not know is a version skew, and it says so
       with a 400 naming the field, never a silent drop." A phone one
       release ahead, writing a column this server has never heard of,
       must be told - a silent drop would look exactly like a successful
       write and lose the value.
    2. **The server facts are the server's.** `id`, `provenance`,
       `created_at`, `updated_at` and `deleted_at` are read-only on every
       subclass; `deleted_at` moves only via DELETE, `updated_at` only via
       the `touch_updated_at` trigger.

    Subclasses set `Meta.model` / `Meta.fields` and, where the table's
    `provenance` column is not the shared `USER` default,
    `default_provenance`.
    """

    # Every row this API creates is AUTHORED - a person typing, or a phone
    # replaying what a person did - never a document that came through
    # CLAUDE.md section 4's gate. `Provenance.USER` says exactly that, and
    # matches each of these tables' own DB default. Overridden only by
    # voice notes, whose column is a one-value CHECK rather than the shared
    # enum (see `api/voice_notes.py`).
    default_provenance: str = Provenance.USER

    def to_internal_value(self, data):
        if hasattr(data, "keys"):
            unknown = sorted(set(data.keys()) - set(self.fields))
            if unknown:
                raise serializers.ValidationError(
                    {
                        "detail": (
                            f"Nothing was written. This server does not know "
                            f"{'these fields' if len(unknown) > 1 else 'this field'} on "
                            f"{self.Meta.model._meta.db_table}: {', '.join(unknown)}. "
                            f"Known fields: {', '.join(sorted(self.fields))}."
                        )
                    }
                )
        return super().to_internal_value(data)

    def create(self, validated_data: dict):
        model = self.Meta.model
        field_names = {f.name for f in model._meta.get_fields()}
        # An inspectdb-generated `managed = False` model captures no column
        # DEFAULT at all, and Django sends every field's current Python
        # value on INSERT - `None` for anything nobody set - so a DB-side
        # `default gen_random_uuid()` / `default now()` / `default 'USER'`
        # never gets the chance to fire. Each is therefore supplied here.
        # `api/events.py`'s own CREATE_DEFAULTS comment is the longer
        # version of this paragraph; it was found there first.
        validated_data["id"] = uuid.uuid4()
        validated_data["created_at"] = Now()
        validated_data["updated_at"] = Now()
        if "provenance" in field_names:
            validated_data["provenance"] = self.default_provenance
        instance = model.objects.create(**validated_data)
        # `Now()` is an unevaluated SQL expression until Postgres runs it;
        # the in-memory instance still holds the expression object, not a
        # datetime, so the row is read back before anything renders it.
        instance.refresh_from_db()
        return instance

    def update(self, instance, validated_data: dict):
        for field, value in validated_data.items():
            setattr(instance, field, value)
        if self.context.get("revive_tombstone") and instance.deleted_at is not None:
            # Places only - see this module's own doc comment.
            instance.deleted_at = None
        instance.save()
        # The `touch_updated_at` trigger has just overwritten `updated_at`
        # with the database's own clock; the value Django sent is stale the
        # instant the UPDATE lands.
        instance.refresh_from_db()
        return instance


class SyncedModelViewSet(viewsets.ViewSet):
    """The four routes at the top of this module, over one model. Subclass,
    set `aspect` / `table` / `serializer_class`, and register in
    `api/registry.py`.

    Authentication and permissions are the project defaults
    (`DeviceTokenAuthentication` + `IsHouseholdMember`, `REST_FRAMEWORK` in
    `legion/settings.py`) and are deliberately NOT restated per viewset: a
    route that forgot to name them would be a route that quietly allowed
    anyone, and the only way to make that impossible is to never write the
    attribute at all.
    """

    # How this viewset is described in `server/openapi.yaml`. Set on the
    # base class, so every registered table is described from its own
    # `serializer_class` / `action` / identity settings and no subclass has
    # a decorator to forget. See `api/schema.SyncedAutoSchema` - in
    # particular for the list route, whose response is the
    # `{"results": [...], "next": ...}` envelope and NOT the bare array
    # drf-spectacular guesses for a list action.
    schema = SyncedAutoSchema()

    aspect: str = ""
    table: str = ""
    # The path segment, when it is not the table's own name. Ticket 04 and this
    # ticket's brief name three paths that deliberately do not repeat their
    # table: `/api/ledger/transactions/` (not `ledger_transactions`, which
    # would read `ledger/ledger_transactions`), `/api/pantry/line-items/` (not
    # `receipt_line_items`) and `/api/ingest/files/` (not `ingested_files`).
    # `table` stays the TABLE name regardless, because it is also the key
    # `api/changes.py` publishes the rows under, and that key is documented as
    # naming the table.
    url_segment: str = ""
    serializer_class: type[SyncedSerializer] | None = None

    # The column the `?since=` feed is keyed on, and the one `next` is rendered
    # from. `updated_at` everywhere it exists; see this module's own doc comment
    # and `api/sync.paginate_since` for the five tables where it does not.
    cursor_field: str = "updated_at"
    # Whether the table has a `deleted_at` column at all. False turns `?active=1`
    # into a no-op that is honest rather than misleading: on a table with no
    # tombstones every row that exists IS live, so the unnarrowed feed already
    # is the active set. The parameter is dropped from the schema for those
    # routes (`api/schema.SyncedAutoSchema`) rather than advertised and ignored.
    has_tombstones: bool = True

    identity_field: str = "origin_guid"
    # The path converter for the identity segment. `str` matches anything
    # but a slash, which covers an `origin_guid` and a place label; `uuid`
    # for a table whose identity is the server's own primary key.
    identity_url_converter: str = "str"
    identity_is_primary_key: bool = False

    allow_delete: bool = True
    put_revives_tombstone: bool = False
    # False strips PUT, POST and DELETE from the URL map entirely. See
    # `GatedReadViewSet`.
    writable: bool = True
    # Set on a read-only viewset whose rows come from the section 4 gate: the
    # endpoint a caller should have used, quoted back at them in the 405.
    # Empty on every writable table, where DRF's own 405 is already accurate.
    gate_endpoint: str = ""

    @classmethod
    def model(cls):
        return cls.serializer_class.Meta.model

    @classmethod
    def route_prefix(cls) -> str:
        """`places/` for a one-table aspect, `body/bodyweight_logs/` for a
        table inside a multi-table one - ticket 04's `/api/<app>/<table>/`,
        collapsed where app and table would repeat the same word.

        `url_segment` overrides the table half where the two differ - see that
        attribute's own comment."""
        segment = cls.url_segment or cls.table
        if cls.aspect == segment:
            return f"{segment}/"
        return f"{cls.aspect}/{segment}/"

    @classmethod
    def route_name(cls) -> str:
        return f"{cls.aspect}-{cls.table}".replace("_", "-")

    # -- helpers ------------------------------------------------------------

    def _lookup(self, identity):
        return self.model().objects.filter(**{self.identity_field: identity}).first()

    def _not_found(self, identity) -> Response:
        return Response(
            {
                "detail": (
                    f"Nothing was changed. No {self.table} row has "
                    f"{self.identity_field} {identity!r}."
                )
            },
            status=status.HTTP_404_NOT_FOUND,
        )

    def _serialize(self, instance_or_queryset, many: bool = False):
        return self.serializer_class(instance_or_queryset, many=many).data

    # -- routes -------------------------------------------------------------

    def list(self, request):
        """`?since=<iso>` (tombstones included) and `?active=1` (live rows
        only). They compose: `?active=1&since=<iso>` is a live-rows-changed
        feed, and `?active=1` alone is every live row since the epoch.

        A missing `since` means EVERYTHING, never nothing - `api/sync.parse_since`
        holds that rule for the whole API and quotes the phone-side cursor
        comment it comes from.
        """
        since = parse_since(request.query_params.get("since"))
        queryset = self.model().objects.filter(**{f"{self.cursor_field}__gte": since})
        if self.has_tombstones and request.query_params.get("active", "").strip().lower() in TRUTHY:
            queryset = queryset.filter(deleted_at__isnull=True)
        # Secondary sort on the primary key so a page boundary is stable
        # when several rows share one `updated_at` - which they routinely
        # do, since `now()` is fixed for a transaction. Known limit, stated
        # rather than papered over: a full page of rows sharing ONE
        # timestamp would re-serve itself forever, because the cursor is an
        # inclusive `updated_at` and cannot express "after this row". 500
        # simultaneous writes to one table is not a shape this app has, and
        # fixing it properly means a compound cursor, which is a bigger
        # change than this ticket.
        queryset = queryset.order_by(self.cursor_field, "pk")
        page, next_since = paginate_since(queryset, cursor_field=self.cursor_field)
        return Response({"results": self._serialize(page, many=True), "next": next_since})

    def retrieve(self, request, identity):
        """`GET <table>/<identity>/`. One row, tombstoned or not.

        Routed ONLY on a `GatedReadViewSet`, and it exists there because the
        405 has to have somewhere to happen. A PUT to a path no URL pattern
        matches is a 404, not a 405, and DRF refuses `as_view({})` outright
        ("The `actions` argument must be provided"), so a detail route that
        carried no action at all could not be declared. The minimum honest
        action on a read-only resource is the read, and it is keyed on the
        server's own `id` because none of the five gated tables has a
        client-minted identity that is reliably present: `statements` has no
        `origin_guid` column at all, and it is nullable on
        `ledger_transactions`, `receipts` and `receipt_line_items` (null on
        everything the gate itself committed - see
        `20260826000100_origin_guid.sql`).
        """
        instance = self._lookup(identity)
        if instance is None:
            return self._not_found(identity)
        return Response(self._serialize(instance))

    def create(self, request):
        """`POST <table>/`. Routed only where the identity IS the primary
        key (voice notes) - everywhere else the client mints the identity
        and PUT is both create and update, so a second create path would be
        a second way to do one thing."""
        serializer = self.serializer_class(data=request.data)
        serializer.is_valid(raise_exception=True)
        instance, error = save_or_400(serializer.save)
        if error is not None:
            return error
        return Response(self._serialize(instance), status=status.HTTP_201_CREATED)

    def upsert(self, request, identity):
        """`PUT <table>/<identity>/`. Idempotent by construction: the
        identity comes from the URL, so a retry cannot make a second row.
        200 whether the row was created or updated - the caller asked for
        the row to exist in this state and it does, and which of the two
        happened is not something a retrying client can act on.
        """
        instance = self._lookup(identity)
        if instance is None and self.identity_is_primary_key:
            # Ticket 04's own exceptions table: "PUT accepts either key; the
            # `id` form never inserts." A server-minted uuid the server has
            # never minted is not an identity a client may invent.
            return Response(
                {
                    "detail": (
                        f"Nothing was created. {identity} is not an id this server issued, and "
                        f"a {self.table} row cannot be created under an id chosen by the caller. "
                        f"POST to /api/{self.route_prefix()} to create one."
                    )
                },
                status=status.HTTP_404_NOT_FOUND,
            )

        data = dict(request.data.items())
        # The URL is the authority on identity - the same precedence
        # `ChecklistItemListCreateView.post` gives its `checklist_id`.
        data[self.identity_field] = identity

        context = {"revive_tombstone": self.put_revives_tombstone}
        if instance is None:
            serializer = self.serializer_class(data=data, context=context)
        else:
            serializer = self.serializer_class(instance, data=data, context=context)
        serializer.is_valid(raise_exception=True)
        saved, error = save_or_400(serializer.save)
        if error is not None:
            return error
        return Response(self._serialize(saved), status=status.HTTP_200_OK)

    def destroy(self, request, identity):
        """`DELETE <table>/<identity>/`. Sets the tombstone column; the row
        stays, because a phone that has not synced since still needs to
        learn the row is gone. Idempotent - a second delete is still a 204,
        matching `EventsBackend.softDelete`'s own contract."""
        instance = self._lookup(identity)
        if instance is None:
            return self._not_found(identity)
        if instance.deleted_at is None:
            instance.deleted_at = Now()
            _saved, error = save_or_400(lambda: instance.save(update_fields=["deleted_at"]))
            if error is not None:
                return error
        return Response(status=status.HTTP_204_NO_CONTENT)

    def _gate_refusal(self, method: str) -> Response:
        """The 405 body for a write to a table the section 4 gate owns.

        Ticket 04's exceptions table says only "no PUT, no DELETE"; CLAUDE.md
        section 7 says a failure result states in words what did NOT happen and
        offers the nearest thing there IS a path to. So the sentence names both
        the reason and the door: a client that guessed at a CRUD route learns
        the gate endpoint from the refusal rather than from a document it does
        not have open.
        """
        verb = "deleted" if method == "DELETE" else "written"
        return Response(
            {
                "detail": (
                    f"Nothing was {verb}. {self.table} rows come only from the reconciliation "
                    f"gate (CLAUDE.md section 4), never from this API: a row that could be "
                    f"edited into existence around the gate would not be a gated row. The way "
                    f"in is {self.gate_endpoint}, which either commits the document or "
                    f"quarantines it with a reason. This route is read-only."
                )
            },
            status=status.HTTP_405_METHOD_NOT_ALLOWED,
        )

    def http_method_not_allowed(self, request, *args, **kwargs):
        """DRF's own 405 body is `Method "DELETE" not allowed.`, which is
        true and says nothing about why. For an append-only table the why
        is the whole point (CLAUDE.md section 7: a failure result says in
        words what did NOT happen), so it is spelled out."""
        if self.gate_endpoint and request.method in {"POST", "PUT", "PATCH", "DELETE"}:
            return self._gate_refusal(request.method)
        if request.method == "DELETE" and not self.allow_delete:
            return Response(
                {
                    "detail": (
                        f"Nothing was deleted. {self.table} is append-only: it is an audit "
                        f"trail, and a trail with rows removed from it is not one. There is "
                        f"no delete route for this table."
                    )
                },
                status=status.HTTP_405_METHOD_NOT_ALLOWED,
            )
        return super().http_method_not_allowed(request, *args, **kwargs)


class GatedReadSerializer(SyncedSerializer):
    """Every field read-only, for a table nothing but the section 4 gate writes.

    Not decoration, and not defensive coding either - these serializers are
    never handed a request body, because `GatedReadViewSet` routes no method
    that has one. What it buys is a HONEST SCHEMA: drf-spectacular renders a
    read-only field as `readOnly: true`, so `server/openapi.yaml` tells a
    generated client that `total_cents` is something it receives and never
    sends. `api/schema.py`'s own header states the rule this serves - "the
    schema must describe what the endpoints actually do" - and a component
    whose fields looked writable would be describing a write path that does
    not exist.

    `required = False` moves with `read_only = True` because DRF asserts the
    two are never both set, and a field left `required` would make the
    component's `required:` list claim a caller has to send it.
    """

    def get_fields(self):
        fields = super().get_fields()
        for field in fields.values():
            field.read_only = True
            field.required = False
        return fields


class GatedReadViewSet(SyncedModelViewSet):
    """`GET` and nothing else, over a table the section 4 gate owns.

    Subclasses set `aspect` / `table` / `serializer_class` / `gate_endpoint`
    like any other, and inherit the four departures this shape needs. See this
    module's own doc comment for why each one is forced by the schema rather
    than chosen.
    """

    # There is no write path at all, so there is nothing to key one on: the
    # identity is the server's own primary key, and it is used only by
    # `retrieve`. See that method's docstring for why `origin_guid` could not
    # serve here even where the column exists.
    identity_field = "id"
    identity_url_converter = "uuid"
    # Deliberately NOT `identity_is_primary_key = True`: that flag means "PUT
    # updates and never inserts, POST creates instead", and both halves of it
    # describe write routes this viewset does not have. Setting it would add a
    # POST to the list map, which is the exact thing this class exists to
    # prevent.
    writable = False
    allow_delete = False
    cursor_field = "created_at"
    has_tombstones = False


def synced_paths(viewset: type[SyncedModelViewSet]) -> list:
    """The two `path()` entries one synced table costs. Method-to-action
    mapping is explicit rather than router-generated: `DefaultRouter` would
    also invent list/retrieve/partial_update routes this shape does not
    have, and a route nobody wrote is a route nobody reviewed.

    A table with `allow_delete = False` gets no `delete` in its map at all,
    so the 405 comes from the URL layer rather than from a guard inside a
    view that could be forgotten. `writable = False` does the same for the
    whole write half: a gated table's detail route carries `get` and nothing
    else, so PUT and DELETE reach `http_method_not_allowed` and are answered
    with the gate's own address."""
    prefix = viewset.route_prefix()
    list_map = {"get": "list"}
    if not viewset.writable:
        return [
            path(prefix, viewset.as_view(list_map), name=f"{viewset.route_name()}-list"),
            path(
                f"{prefix}<{viewset.identity_url_converter}:identity>/",
                viewset.as_view({"get": "retrieve"}),
                name=f"{viewset.route_name()}-detail",
            ),
        ]
    detail_map = {"put": "upsert"}
    if viewset.allow_delete:
        detail_map["delete"] = "destroy"
    if viewset.identity_is_primary_key:
        list_map["post"] = "create"
    return [
        path(prefix, viewset.as_view(list_map), name=f"{viewset.route_name()}-list"),
        path(
            f"{prefix}<{viewset.identity_url_converter}:identity>/",
            viewset.as_view(detail_map),
            name=f"{viewset.route_name()}-detail",
        ),
    ]
