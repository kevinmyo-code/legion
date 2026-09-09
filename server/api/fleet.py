"""`/api/fleet/<table>` - twelve tables, and unlike every other aspect they do
NOT share one identity shape.

Ledger and pantry each split two ways (authored config vs the section 4 gate's
output). Fleet splits SIX, and the split is not this module's invention:
`app/.../backend/FleetBackend.kt`'s own class doc enumerates four identity
shapes and says why each one is genuine ("four identity shapes in one aspect,
because the aspect genuinely has four, not because this interface picked one
arbitrarily"). Every row below was re-confirmed against the LIVE schema through
`information_schema.columns`, `pg_constraint`, `pg_indexes` and `pg_trigger` on
2026-09-07, not inferred from that doc or from `legacy/models/fleet.py`.

| Shape | Identity | Tables |
|---|---|---|
| 1 | `origin_guid`, the engine record's guid | `vehicles`, `service_history` |
| 2 | `sync_id`, the phone's own portable key | `drives`, `code_events`,
  `code_clear_events`, `oil_analyses`, `build_entries`, `drive_reassignments` |
| 3 | `quirk_id`, a natural text primary key | `chassis_quirks` |
| 4 | `vehicle_id`, a primary key that is also a foreign key | `vehicle_specs` |
| 5 | `(vehicle_id, service_name)`, COMPOSITE | `maintenance_schedules` |
| 6 | `(vehicle_id, pid, recorded_at)`, content-derived | `obd_samples` |

    PUT fleet/vehicles/<origin_guid>/                       shape 1
    PUT fleet/drives/<sync_id>/                             shape 2
    PUT fleet/chassis_quirks/<quirk_id>/                    shape 3, no DELETE
    PUT fleet/vehicle_specs/<vehicle_id>/                   shape 4, no DELETE
    PUT fleet/maintenance_schedules/<vehicle_id>/<name>/    shape 5
    GET fleet/obd_samples/?vehicle=&since=                  shape 6, no PUT, no DELETE

Shapes 1 to 4 are the generic `SyncedModelViewSet` with `identity_field` set,
which is exactly what that attribute exists for. Shapes 5 and 6 are NOT, and
both are written out rather than bent onto it - see their own sections.

## What did NOT fit, stated plainly rather than left to be noticed

**`maintenance_schedules` (shape 5).** Its identity is the pair
`maintenance_schedules_unique_per_vehicle` enforces, and a URL with one
`<identity>` segment cannot carry a pair. It keeps the generic `list` - the
`?since=`/`?active=` feed over this table is identical to every other, and a
second copy of that would be a second thing to keep in step - and writes its
own `upsert`, `destroy`, `_lookup` and two-segment detail path.
`SyncedModelViewSet.detail_path_suffix` and `identity_path_parameters` exist
for it, and their docstrings say so.

**`obd_samples` (shape 6).** Not on the generic viewset at all, and not in
`api/registry.py`. Four reasons, each independent:

1. **No tombstone column, and no `updated_at` either.** `RemoteObdSample`'s own
   doc: telemetry is append-only and never corrected. So there is no DELETE
   route, no `?active=1`, and the since-feed has no tombstones to include -
   nothing was removed, because nothing can be.
2. **20,796 rows live** (counted 2026-09-07), roughly thirteen times every
   other table in this database combined. An unbounded `?since=` over it is
   not a feed, it is a download.
3. **The pull is windowed and per-vehicle by design.**
   `FleetBackend.fetchObdSamplesSince(vehicleServerId, sinceMs)` has no
   whole-table form, and Kevin's 2026-09-03 OBD-volume ruling is "recent window
   only, roughly the last 30 days". The phone is a CACHE for this table, not
   the archive.
4. **Upload is a resumable batch, not a per-row upsert.**
   `ObdSampleReconcile` pages 500 rows at a time; a PUT per sample would be
   twenty thousand requests.

### Why the feed REFUSES a request with no vehicle rather than defaulting one

`api/sync.parse_since` states this API's posture for a missing parameter: a
caller who cannot be understood should see too much, never silently see
nothing. That works because erring toward MORE rows is the safe direction
there. Here it is the one direction that is not affordable, so the same
principle points the other way and the honest options narrow to two: refuse, or
silently narrow.

Silently narrowing loses. A client that asked for a whole table and was handed
thirty days of one car has been told nothing about the difference, and would
have no way to tell a quiet window from a car with no telemetry - the same
"unreadable and empty are different sentences" trap CLAUDE.md section 1 names.
So `GET /api/fleet/obd_samples/` **requires `?vehicle=<uuid>`** and answers 400
naming the parameter when it is absent, 404 when it names no vehicle. The
30-day window stays where it belongs: the client passes it as `?since=`, and
the server serves what was asked for, paged, rather than deciding on the
client's behalf how much of its own history it is allowed to see.

### There is no server-side retention, and this ticket does not add one

Nothing deletes an old `obd_samples` row. The server holds every sample ever
uploaded and will keep growing at the phone's ~600/day. That is a real open
question - it is what makes the "phone is a cache" ruling work, since the
archive has to live somewhere - and it is deliberately NOT decided here: a
retention policy is a ruling about how much history the household keeps, not an
implementation detail of a route.

## Provenance defaults differ per table, and each matches its own column

`SyncedSerializer.default_provenance` is `USER` - "a person typing, or a phone
replaying what a person did". Five fleet tables carry a DB default of
`DETERMINISTIC` instead and are set to match: `drives`, `code_events` and
`code_clear_events` are dongle-read and code-finalised with no model in the
path; `vehicle_specs` is an NHTSA vPIC VIN decode; `chassis_quirks` is parsed
from a bundled JSON asset. The other six are USER, and `oil_analyses` is the
one worth naming - a person transcribed a lab report, so it is USER and not
DETERMINISTIC despite being full of instrument readings. Read from the live
schema's own column defaults, one by one.

**None of these is `LLM_RECONCILED` or `UNRECONCILED`, and none of them can be.**
No fleet table goes through the section 4 gate: there is no document with a
printed total anywhere in this aspect. The gate's rules are not weakened here,
they simply have no subject.

## Blank strings: allowed exactly where the column's own DEFAULT is `''`

DRF refuses a blank `CharField` by default, which is stricter than Postgres.
That strictness is kept - a vehicle named `""` is useless and a blank
`service_name` would take a slot in
`maintenance_schedules_unique_per_vehicle` - EXCEPT on the columns whose live
DDL says `not null default ''`. A column with that default is one where the
schema itself says blank is the expected value for "not supplied"
(`oil_brand`, `ack_raw`, every text column on `vehicle_specs`, and a dozen
more), and refusing it would be refusing the row the phone actually sends.
`obd_samples.unit` is the one addition to that rule: it has no `''` default,
but a dimensionless reading has no honest unit label, and a 400 there would
drop a whole telemetry batch over a cosmetic field.

## Money is integer cents, checked against the live schema

CLAUDE.md section 4 rule 3. `service_history.cost_cents`,
`build_entries.cost_cents`, `chassis_quirks.cost_low_cents` and
`cost_high_cents` are all `bigint` in Postgres, read from
`information_schema.columns` on 2026-09-07 rather than trusted from the model
file, and `BigIntegerField` in `legacy/models/fleet.py`, which DRF renders as a
JSON integer. `build_entries` and `chassis_quirks` carry a `>= 0` CHECK and
this module mirrors it; **`service_history.cost_cents` does NOT** (confirmed
against `pg_constraint` - that table's only CHECKs are on `kind` and
`mileage`), so no non-negative rule is invented for it here. A negative service
cost is odd, but it is storable today and a route that refused it would be
refusing rows the database already holds.
"""
from __future__ import annotations

import uuid

from django.db import DatabaseError, connection, transaction
from django.db.models.functions import Now
from django.urls import path
from drf_spectacular.types import OpenApiTypes
from drf_spectacular.utils import OpenApiParameter, OpenApiResponse, extend_schema
from rest_framework import serializers, status
from rest_framework.response import Response
from rest_framework.views import APIView

from api.schema import DetailSerializer, paged_serializer
from api.sync import PAGE_SIZE, paginate_since, parse_since, save_or_400
from api.synced import (
    HouseholdScopedPrimaryKeyRelatedField,
    SyncedModelViewSet,
    SyncedSerializer,
    blank_error,
    choice_error,
    minimum_error,
    range_error,
)
from household.tenancy import household_of, scoped
from legacy.enums import Provenance
from legacy.models.fleet import (
    BuildEntry,
    ChassisQuirk,
    CodeClearEvent,
    CodeEvent,
    Drive,
    DriveReassignment,
    MaintenanceSchedule,
    ObdSample,
    OilAnalysis,
    ServiceHistory,
    Vehicle,
    VehicleSpec,
)

# `legacy/CONSTRAINTS.md`'s own fleet sections, every one read from the live
# schema. A caller outside a set is told the set (ticket 04: "a 400 naming the
# allowed set"), never handed Postgres's own constraint name.
SERVICE_KIND_CHOICES = ("OBSERVED", "ASSERTED")
CLEAR_OUTCOME_CHOICES = ("CLEARED", "RETURNED", "UNVERIFIED")
QUIRK_SEVERITY_CHOICES = ("MONITOR", "SERVICE_SOON", "CRITICAL")

# `vehicles_year_check`.
YEAR_BOUNDS = (1885, 2200)

# A text column whose live DDL reads `not null default ''`. See this module's
# own doc comment on blank strings for why these three kwargs travel together.
BLANK_OK = {"required": False, "allow_blank": True, "default": ""}


def _refuse_out_of_range(data: dict, fields: tuple[str, ...], *, exclusive: bool) -> None:
    """Raise a field-keyed 400 for any of `fields` that is present, not null,
    and below the bound its own CHECK constraint sets.

    Written as a loop rather than as twelve `validate_<metal>` methods because
    `oil_analyses` alone has twelve `IS NULL OR >= 0` columns and a dozen
    near-identical methods is a dozen places for one of them to be missed. The
    error is raised keyed on the field name, so the response body is the same
    `{"<field>": ["..."]}` shape DRF's own field validation produces and
    `api/schema.WRITE_REFUSED` already documents.
    """
    minimum = 0
    errors: dict[str, list] = {}
    for name in fields:
        value = data.get(name)
        if value is None:
            continue
        bad = value <= minimum if exclusive else value < minimum
        if bad:
            errors[name] = minimum_error(name, value, minimum, exclusive=exclusive).detail
    if errors:
        raise serializers.ValidationError(errors)


def _refuse_non_array(value, field: str):
    """`jsonb_typeof(<col>) = 'array'` said in words and before the round trip.

    The three columns this guards (`code_events.codes`,
    `code_clear_events.codes_before` / `codes_after`) are `jsonb`, so Postgres
    happily stores a string or a number in them and only the CHECK refuses it.
    A JSON string `"[\\"P0420\\"]"` and a JSON array `["P0420"]` look nearly
    identical in a payload and are completely different values; the phone sends
    the array (`SupabaseFleetBackend`'s `CodeEventUpsertDto.codes` is a
    `JsonElement`, not a `String`), and a client that sent the string would
    otherwise learn about it from a database error.
    """
    if not isinstance(value, list):
        raise serializers.ValidationError(
            f"{field} must be a JSON array of code strings, for example [\"P0420\"]. "
            f"A JSON string containing an array is not the same value and is refused by "
            f"the database."
        )
    return value


def _refuse_non_object(value, field: str):
    """`jsonb_typeof(<col>) = 'object'`, for the freeze-frame columns. NULL is
    allowed and means the adapter returned no freeze frame at all - some older
    ELM327 clones skip Mode 02 entirely - which is a different fact from an
    empty object and is stored as a different value."""
    if value is None:
        return value
    if not isinstance(value, dict):
        raise serializers.ValidationError(
            f"{field} must be a JSON object, or null when the adapter returned no freeze "
            f"frame at all. Null and an empty object are different facts here and are "
            f"stored differently."
        )
    return value


# =============================================================================
# SHAPE 1: keyed by `origin_guid`. `vehicles` and `service_history` are engine
# records (`FleetAspectSeeder` defines Vehicle and ServiceHistory), so they
# carry `records.guid` verbatim - exactly like the body and memory tables.
#
# Both columns are NULLABLE and unique, which is a faithful pairing rather than
# a mistake: `vehicles_origin_guid_idx` and `service_history_origin_guid_idx`
# are plain unique btrees, and Postgres treats every NULL as distinct from every
# other NULL under one, so a row created directly against the server carries no
# guid and collides with nothing.
#
# **Ticket 04's exceptions table also names a second key for `vehicles`** -
# "phone upserts by server `id`, creates by `origin_guid`... PUT accepts either
# key; the `id` form never inserts". That either-key form is NOT built here, and
# it was already on ticket 04's own not-done list before this ticket
# (alongside the same form for `events`). What is here is the `origin_guid`
# half; the `id` half stays owed.
# =============================================================================


class VehicleSerializer(SyncedSerializer):
    """Field-for-field `RemoteVehicle` / `VehicleUpload` (`FleetBackend.kt`).

    `confirmed` and `archived` default to False when omitted, matching their own
    column defaults. **That is a statement about the whole row, not a merge**:
    PUT here means "the row should be in this state", so a flag left out of the
    body reads as false rather than as "leave whatever is there". The same is
    true of every optional field on every table in this API; it is called out
    on this one because `archived` is USER state a person set deliberately
    (ticket 27, 2026-08-29) and silently clearing it would be worse than
    silently clearing a spec sheet's blank string.
    """

    class Meta:
        model = Vehicle
        fields = [
            "id",
            "name",
            "make",
            "model",
            "year",
            "trim",
            "engine",
            "confirmed",
            "odometer_baseline",
            "odometer_baseline_at",
            "provenance",
            "created_at",
            "updated_at",
            "deleted_at",
            "origin_guid",
            "archived",
            "last_obd_mac",
        ]
        read_only_fields = ["id", "provenance", "created_at", "updated_at", "deleted_at"]
        extra_kwargs = {
            "confirmed": {"required": False, "default": False},
            "archived": {"required": False, "default": False},
            "last_obd_mac": {
                "help_text": (
                    "The MAC of the OBD dongle this car was last seen on. A best-effort "
                    "rebuild HINT, never an identity: a car that changed dongles carries its "
                    "old value here until the next sync, and nothing may be assigned on the "
                    "strength of it alone."
                )
            },
            "odometer_baseline": {
                "help_text": (
                    "An odometer reading, paired with `odometer_baseline_at`. Both or "
                    "neither: a baseline without its timestamp cannot be projected forward, "
                    "so it is worse than none - it looks like knowledge and is not "
                    "(`vehicles_odometer_baseline_paired`)."
                )
            },
        }

    def validate_name(self, value: str) -> str:
        if not value or not value.strip():
            raise blank_error("name")
        return value

    def validate_year(self, value: int) -> int:
        low, high = YEAR_BOUNDS
        if not low <= value <= high:
            raise range_error("year", value, low, high)
        return value

    def validate_odometer_baseline(self, value):
        if value is not None and value < 0:
            raise minimum_error("odometer_baseline", value, 0, exclusive=False)
        return value

    def validate(self, attrs):
        # `vehicles_odometer_baseline_paired`, in words. Both fields are
        # optional, so a PUT that supplies neither is fine; supplying exactly
        # one is what this refuses.
        reading = attrs.get("odometer_baseline")
        taken_at = attrs.get("odometer_baseline_at")
        if (reading is None) != (taken_at is None):
            raise serializers.ValidationError(
                "odometer_baseline and odometer_baseline_at go together or not at all. A "
                "reading with no timestamp cannot be projected forward, and a timestamp with "
                "no reading records nothing."
            )
        return attrs


class ServiceHistorySerializer(SyncedSerializer):
    """Field-for-field `RemoteServiceHistory` / `ServiceHistoryUpload`.

    `kind` is the fleet aspect's own small provenance and is deliberately a
    separate column from `provenance`: provenance says how the ROW was
    produced, `kind` says how the EVENT was witnessed. OBSERVED means LEGION
    saw it or read it from a document; ASSERTED means the driver said so with
    no independent evidence. A client rendering a service record must not
    collapse the two, for the same reason section 4 rule 5 will not let an
    estimate be shown as a measurement.
    """

    vehicle_id = HouseholdScopedPrimaryKeyRelatedField(
        source="vehicle", queryset=Vehicle.objects.all()
    )

    class Meta:
        model = ServiceHistory
        fields = [
            "id",
            "vehicle_id",
            "service_name",
            "mileage",
            "service_date",
            "cost_cents",
            "kind",
            "provenance",
            "created_at",
            "updated_at",
            "deleted_at",
            "origin_guid",
        ]
        read_only_fields = ["id", "provenance", "created_at", "updated_at", "deleted_at"]
        extra_kwargs = {
            "kind": {
                "help_text": (
                    "OBSERVED means LEGION saw it happen or read it from a document; "
                    "ASSERTED means the driver said so, with no independent evidence. NOT the "
                    "same axis as `provenance`, which says how the row was produced rather "
                    "than how the event was witnessed."
                )
            },
            "cost_cents": {
                "help_text": (
                    "In cents (CLAUDE.md section 4 rule 3), or null when no figure was "
                    "recorded. Unlike `build_entries.cost_cents` this column carries no "
                    "non-negative CHECK on the live schema, so none is invented here."
                )
            },
        }

    def validate_service_name(self, value: str) -> str:
        if not value or not value.strip():
            raise blank_error("service_name")
        return value

    def validate_kind(self, value: str) -> str:
        if value not in SERVICE_KIND_CHOICES:
            raise choice_error("kind", value, SERVICE_KIND_CHOICES)
        return value

    def validate_mileage(self, value):
        if value is not None and value < 0:
            raise minimum_error("mileage", value, 0, exclusive=False)
        return value


# =============================================================================
# SHAPE 2: keyed by `sync_id`. Six tables, none of them an engine record - each
# already carried its own portable key before any of this existed (confirmed
# against `sync/SyncEngine.kt`'s registry and each `@Entity`), and every one of
# the six columns is `text not null unique check (length(trim(sync_id)) > 0)`.
#
# `FleetBackend`'s own doc for these: "no update, no delete, so a repost is
# always free". A PUT here is either a genuinely new row or a harmless
# re-upload, never an edit - which is what makes the phone's retry-on-failure
# path safe.
# =============================================================================


class _SyncIdSerializer(SyncedSerializer):
    """The one field the six shape-2 tables share, and the one refusal it
    needs. `sync_id` rides in the URL, so a blank one would have to arrive
    percent-encoded; refused anyway, because the CHECK is real."""

    def validate_sync_id(self, value: str) -> str:
        if not value or not value.strip():
            raise blank_error("sync_id")
        return value


class DriveSerializer(_SyncIdSerializer):
    """Field-for-field `RemoteDrive` / `DriveUpload`.

    `gallons` is null, never 0.0, when MAF was silent for the whole drive.
    `Drive.gallons`' own comment is emphatic about this and the distinction is
    load-bearing for MPG: a drive that burned an unknown amount of fuel must
    not read as a drive that burned none.
    """

    vehicle_id = HouseholdScopedPrimaryKeyRelatedField(
        source="vehicle", queryset=Vehicle.objects.all()
    )
    # DETERMINISTIC, not USER: a drive is measured by the dongle and finalised
    # by code, with no model anywhere in the path. Matches the column default.
    default_provenance = Provenance.DETERMINISTIC

    class Meta:
        model = Drive
        fields = [
            "id",
            "sync_id",
            "vehicle_id",
            "started_at",
            "ended_at",
            "miles",
            "gallons",
            "end_reason",
            "provenance",
            "created_at",
            "updated_at",
            "deleted_at",
        ]
        read_only_fields = ["id", "provenance", "created_at", "updated_at", "deleted_at"]
        extra_kwargs = {
            "gallons": {
                "help_text": (
                    "Null, never 0.0, when the mass-airflow sensor was silent for the whole "
                    "drive. Unknown fuel and no fuel are different facts and must not "
                    "collapse - an MPG figure computed over a null here is not a low MPG, it "
                    "is no MPG."
                )
            },
            "end_reason": {
                "help_text": (
                    "'ENGINE_OFF' or 'LINK_LOST' today. Deliberately unconstrained text on "
                    "the live schema, so widening it needs no migration and this API refuses "
                    "no value."
                )
            },
        }

    def validate_miles(self, value: float) -> float:
        if value < 0:
            raise minimum_error("miles", value, 0, exclusive=False)
        return value

    def validate_gallons(self, value):
        if value is not None and value < 0:
            raise minimum_error("gallons", value, 0, exclusive=False)
        return value

    def validate(self, attrs):
        # `drives_ends_after_start`.
        started, ended = attrs.get("started_at"), attrs.get("ended_at")
        if started is not None and ended is not None and ended < started:
            raise serializers.ValidationError(
                "ended_at is before started_at. A drive cannot end before it begins."
            )
        return attrs


class CodeEventSerializer(_SyncIdSerializer):
    """Field-for-field `RemoteCodeEvent` / `CodeEventUpload`: one ELM327 DTC
    read plus its Mode 02 freeze frame."""

    vehicle_id = HouseholdScopedPrimaryKeyRelatedField(
        source="vehicle", queryset=Vehicle.objects.all()
    )
    default_provenance = Provenance.DETERMINISTIC

    class Meta:
        model = CodeEvent
        fields = [
            "id",
            "sync_id",
            "vehicle_id",
            "occurred_at",
            "mileage",
            "codes",
            "freeze_frame",
            "provenance",
            "created_at",
            "updated_at",
            "deleted_at",
        ]
        read_only_fields = ["id", "provenance", "created_at", "updated_at", "deleted_at"]
        extra_kwargs = {
            "mileage": {
                "help_text": (
                    "An estimate, not a measurement: a frozen snapshot of the vehicle's "
                    "current mileage at read time, with nothing captured to prove how stale "
                    "it was. Diagnostic metadata, never a gated figure."
                )
            },
            "codes": {"help_text": 'A JSON array of DTC strings, e.g. ["P0420","P0128"].'},
            "freeze_frame": {
                "help_text": (
                    "A JSON object, or null when the adapter returned no freeze frame at all "
                    "- some older ELM327 clones skip Mode 02 entirely. Null and an empty "
                    "object are different facts."
                )
            },
        }

    def validate_mileage(self, value):
        if value is not None and value < 0:
            raise minimum_error("mileage", value, 0, exclusive=False)
        return value

    def validate_codes(self, value):
        return _refuse_non_array(value, "codes")

    def validate_freeze_frame(self, value):
        return _refuse_non_object(value, "freeze_frame")


class CodeClearEventSerializer(_SyncIdSerializer):
    """Field-for-field `RemoteCodeClearEvent` / `CodeClearEventUpload`: the
    outcome of one Mode 04 clear-codes send.

    A row exists only for CLEARED, RETURNED or UNVERIFIED. The two no-op
    outcomes (`NOTHING_TO_CLEAR`, `REFUSED`) never send anything and never
    reach this table, which is why they are not in the allowed set.
    """

    vehicle_id = HouseholdScopedPrimaryKeyRelatedField(
        source="vehicle", queryset=Vehicle.objects.all()
    )
    default_provenance = Provenance.DETERMINISTIC

    class Meta:
        model = CodeClearEvent
        fields = [
            "id",
            "sync_id",
            "vehicle_id",
            "occurred_at",
            "mileage",
            "codes_before",
            "freeze_frame",
            "codes_after",
            "outcome",
            "ack_raw",
            "provenance",
            "created_at",
            "updated_at",
            "deleted_at",
        ]
        read_only_fields = ["id", "provenance", "created_at", "updated_at", "deleted_at"]
        extra_kwargs = {
            "ack_raw": BLANK_OK
            | {
                "help_text": (
                    "The adapter's raw acknowledgement. DIAGNOSTIC ONLY: a failed send "
                    "returns an empty string and a quiet link answers exactly like a real "
                    "ack, so `outcome` is never asserted off this field."
                )
            },
            "codes_after": {
                "help_text": (
                    "THREE-way, not two. Null means the post-send re-read never completed "
                    "(UNVERIFIED). An empty array means it ran and found nothing (CLEARED). "
                    "A non-empty array names the codes that survived (RETURNED). Do not "
                    "collapse null and the empty array."
                )
            },
        }

    def validate_mileage(self, value):
        if value is not None and value < 0:
            raise minimum_error("mileage", value, 0, exclusive=False)
        return value

    def validate_outcome(self, value: str) -> str:
        if value not in CLEAR_OUTCOME_CHOICES:
            raise choice_error("outcome", value, CLEAR_OUTCOME_CHOICES)
        return value

    def validate_codes_before(self, value):
        return _refuse_non_array(value, "codes_before")

    def validate_codes_after(self, value):
        if value is None:
            return value
        return _refuse_non_array(value, "codes_after")

    def validate_freeze_frame(self, value):
        return _refuse_non_object(value, "freeze_frame")

    def validate(self, attrs):
        # `code_clear_events_after_matches_outcome`. The three-way distinction
        # stated as a real constraint rather than left to trust: UNVERIFIED
        # means no re-read landed, and the other two outcomes require one.
        outcome = attrs.get("outcome")
        after = attrs.get("codes_after")
        if (outcome == "UNVERIFIED") != (after is None):
            raise serializers.ValidationError(
                "outcome and codes_after disagree. UNVERIFIED means the post-send re-read "
                "never completed, so codes_after must be null; CLEARED and RETURNED both mean "
                "it did, so codes_after must be an array (empty for CLEARED)."
            )
        return attrs


# `oil_analyses`' twelve wear-metal and contaminant columns, all
# `IS NULL OR >= 0`. NULL means the lab did not report that element - older
# reports omit some - never 0, which would claim a clean reading the lab never
# made.
OIL_METALS = (
    "iron",
    "copper",
    "lead",
    "tin",
    "aluminum",
    "chromium",
    "nickel",
    "sodium",
    "potassium",
    "silicon",
    "boron",
    "magnesium",
)
OIL_NON_NEGATIVE = (*OIL_METALS, "mileage", "fuel_percent", "water_percent", "tbn", "viscosity_cst")


class OilAnalysisSerializer(_SyncIdSerializer):
    """Field-for-field `RemoteOilAnalysis` / `OilAnalysisUpload`: a used-oil lab
    report, voice-entered or typed by the driver.

    USER provenance, not DETERMINISTIC, and the distinction is the point: a
    person transcribed these numbers, code did not derive them. Every figure
    here is as trustworthy as the typing.
    """

    vehicle_id = HouseholdScopedPrimaryKeyRelatedField(
        source="vehicle", queryset=Vehicle.objects.all()
    )

    class Meta:
        model = OilAnalysis
        fields = [
            "id",
            "sync_id",
            "vehicle_id",
            "analyzed_at",
            "mileage",
            "oil_brand",
            "oil_grade",
            "drain_interval_miles",
            *OIL_METALS,
            "fuel_percent",
            "water_percent",
            "tbn",
            "viscosity_cst",
            "lab_notes",
            "provenance",
            "created_at",
            "updated_at",
            "deleted_at",
        ]
        read_only_fields = ["id", "provenance", "created_at", "updated_at", "deleted_at"]
        extra_kwargs = {
            "oil_brand": BLANK_OK,
            "oil_grade": BLANK_OK,
            "lab_notes": BLANK_OK,
            "iron": {
                "help_text": (
                    "Parts per million. Null means the lab did not report iron, never 0 - a "
                    "report that omits an element is not the same fact as one that measured "
                    "zero of it. The same is true of every metal and contaminant here."
                )
            },
        }

    def validate_drain_interval_miles(self, value):
        if value is not None and value <= 0:
            raise minimum_error("drain_interval_miles", value, 0, exclusive=True)
        return value

    def validate(self, attrs):
        _refuse_out_of_range(attrs, OIL_NON_NEGATIVE, exclusive=False)
        return attrs


class BuildEntrySerializer(_SyncIdSerializer):
    """Field-for-field `RemoteBuildEntry` / `BuildEntryUpload`: one
    driver-authored logbook line (a mod, part, repair, consumable, or general
    spend entry)."""

    vehicle_id = HouseholdScopedPrimaryKeyRelatedField(
        source="vehicle", queryset=Vehicle.objects.all()
    )

    class Meta:
        model = BuildEntry
        fields = [
            "id",
            "sync_id",
            "vehicle_id",
            "entry_type",
            "title",
            "vendor",
            "part_number",
            "cost_cents",
            "logged_at",
            "mileage",
            "notes",
            "provenance",
            "created_at",
            "updated_at",
            "deleted_at",
        ]
        read_only_fields = ["id", "provenance", "created_at", "updated_at", "deleted_at"]
        extra_kwargs = {
            "vendor": BLANK_OK,
            "part_number": BLANK_OK,
            "notes": BLANK_OK,
            "entry_type": {
                "help_text": (
                    "'mod', 'part', 'repair', 'consumable' or 'other' on the phone, and "
                    "unconstrained text on the live schema - the list is open-ended, so this "
                    "API refuses no value."
                )
            },
            "cost_cents": {
                "help_text": (
                    "In cents (CLAUDE.md section 4 rule 3). Null means the driver logged what "
                    "was done with no dollar figure; never 0, which would assert it was free."
                )
            },
        }

    def validate_title(self, value: str) -> str:
        if not value or not value.strip():
            raise blank_error("title")
        return value

    def validate(self, attrs):
        _refuse_out_of_range(attrs, ("cost_cents", "mileage"), exclusive=False)
        return attrs


class DriveReassignmentSerializer(_SyncIdSerializer):
    """Field-for-field `RemoteDriveReassignment` / `DriveReassignmentUpload`.

    A correction RULE over `drives`, not a mutation of the drives themselves:
    "samples in this window belong to `new_vehicle_id`, not `vehicle_id`". The
    window is a time RANGE rather than a list of row ids, so that samples
    landing in it later, from any device, are still caught.

    Nothing here refuses a rule that names the same vehicle twice. That is the
    live schema's own choice and it is deliberate: such a rule is a no-op the
    app should never produce, and a client retry of an already-applied
    correction is a legitimate no-op write.
    """

    vehicle_id = HouseholdScopedPrimaryKeyRelatedField(
        source="vehicle", queryset=Vehicle.objects.all()
    )
    new_vehicle_id = HouseholdScopedPrimaryKeyRelatedField(
        source="new_vehicle", queryset=Vehicle.objects.all()
    )

    class Meta:
        model = DriveReassignment
        fields = [
            "id",
            "sync_id",
            "vehicle_id",
            "new_vehicle_id",
            "from_at",
            "to_at",
            "provenance",
            "created_at",
            "updated_at",
            "deleted_at",
        ]
        read_only_fields = ["id", "provenance", "created_at", "updated_at", "deleted_at"]

    def validate(self, attrs):
        # `drive_reassignments_window`.
        start, end = attrs.get("from_at"), attrs.get("to_at")
        if start is not None and end is not None and end < start:
            raise serializers.ValidationError(
                "to_at is before from_at. A reassignment window cannot end before it starts."
            )
        return attrs


# =============================================================================
# SHAPE 3: a natural TEXT primary key. `chassis_quirks` alone.
#
# Household-shared reference data - the bundled Chassis Quirk Index - not a
# per-vehicle observation. Hence no `vehicle_id` column at all, and hence no
# `deleted_at` either: this table is REPLACE-semantics content re-seeded on APK
# updates and community PRs, not a per-user record a driver deletes.
# =============================================================================


class ChassisQuirkSerializer(SyncedSerializer):
    """Field-for-field `RemoteChassisQuirk` / `ChassisQuirkUpload`.

    `chassis` is a comma-delimited list of chassis codes, e.g. "E46,E46M3",
    carried verbatim rather than normalised into a join table - it matches the
    phone's own shape, and it is NOT a `vehicle_id`: this is platform reference
    data, independent of any specific vehicle row.

    The cost and mileage bounds are null, never -1, when unknown. The phone
    stores -1 as its unbounded sentinel and that sentinel must not be carried
    forward - it is the zero-default trap wearing a different number.
    """

    # Declared rather than left to `ModelSerializer`, which infers a field from
    # the model. `quirk_id` IS the primary key here, and the identity a client
    # mints (a stable slug, `ChassisQuirk.quirkId`) rather than one the server
    # issues - so it has to be writable, and declaring it removes any question
    # about how DRF would have mapped a `TextField(primary_key=True)`.
    quirk_id = serializers.CharField()

    default_provenance = Provenance.DETERMINISTIC

    class Meta:
        model = ChassisQuirk
        # No `id` and no `deleted_at` in this list, and neither is an omission:
        # the live table has neither column.
        fields = [
            "quirk_id",
            "chassis",
            "engine",
            "title",
            "symptom",
            "verification_steps",
            "mileage_low",
            "mileage_high",
            "severity",
            "cost_low_cents",
            "cost_high_cents",
            "fix_notes",
            "source_url",
            "provenance",
            "created_at",
            "updated_at",
        ]
        read_only_fields = ["provenance", "created_at", "updated_at"]
        extra_kwargs = {
            "engine": BLANK_OK,
            "fix_notes": BLANK_OK,
            "source_url": BLANK_OK,
            "mileage_low": {
                "help_text": "Null means no lower bound. Never -1, which is the phone's own "
                "sentinel and must not be carried forward."
            },
            "cost_low_cents": {
                "help_text": (
                    "Typical repair cost range, in cents (CLAUDE.md section 4 rule 3). Null "
                    "means unknown or DIY-variable, never 0."
                )
            },
        }

    def validate_quirk_id(self, value: str) -> str:
        if not value or not value.strip():
            raise blank_error("quirk_id")
        return value

    def validate_severity(self, value: str) -> str:
        if value not in QUIRK_SEVERITY_CHOICES:
            raise choice_error("severity", value, QUIRK_SEVERITY_CHOICES)
        return value

    def validate(self, attrs):
        _refuse_out_of_range(
            attrs,
            ("mileage_low", "mileage_high", "cost_low_cents", "cost_high_cents"),
            exclusive=False,
        )
        return attrs


# =============================================================================
# SHAPE 4: a primary key that is ALSO a foreign key. `vehicle_specs` alone.
#
# One row per vehicle: `vehicle_id` is the primary key AND the reference to
# `vehicles.id`. So unlike `chassis_quirks` it does carry a vehicle, and unlike
# the six `sync_id` tables that vehicle IS the whole identity rather than a
# parallel key alongside a separate row id. No `deleted_at` here either.
# =============================================================================


class VehicleSpecSerializer(SyncedSerializer):
    """Field-for-field `RemoteVehicleSpec` / `VehicleSpecUpload`: the factory
    build details for one car, mostly NHTSA vPIC VIN-decode output.

    DETERMINISTIC despite the three manually-typed columns (`paint_color`,
    `paint_code`, `build_notes`) that vPIC cannot supply - the column default
    says so and this matches it. `decoded_at` is null, not 0, for "never
    decoded": the phone stores 0L for that and carrying it forward would read
    as an actual moment in 1970.
    """

    vehicle_id = HouseholdScopedPrimaryKeyRelatedField(
        source="vehicle", queryset=Vehicle.objects.all()
    )
    default_provenance = Provenance.DETERMINISTIC

    class Meta:
        model = VehicleSpec
        fields = [
            "vehicle_id",
            "vin",
            "engine_cylinders",
            "displacement_l",
            "engine_hp",
            "engine_config",
            "fuel_type",
            "transmission_style",
            "transmission_speeds",
            "drive_type",
            "body_class",
            "doors",
            "series",
            "vehicle_type",
            "manufacturer",
            "plant_city",
            "plant_country",
            "paint_color",
            "paint_code",
            "build_notes",
            "decoded_at",
            "provenance",
            "created_at",
            "updated_at",
        ]
        read_only_fields = ["provenance", "created_at", "updated_at"]
        extra_kwargs = {
            name: BLANK_OK
            for name in (
                "vin",
                "engine_config",
                "fuel_type",
                "transmission_style",
                "transmission_speeds",
                "drive_type",
                "body_class",
                "series",
                "vehicle_type",
                "manufacturer",
                "plant_city",
                "plant_country",
                "paint_color",
                "paint_code",
                "build_notes",
            )
        } | {
            "decoded_at": {
                "help_text": (
                    "Null means never decoded. Never 0 or an epoch timestamp - the phone "
                    "stores 0L for this and it would read as an actual moment in 1970."
                )
            }
        }

    def validate(self, attrs):
        _refuse_out_of_range(
            attrs, ("engine_cylinders", "displacement_l", "engine_hp", "doors"), exclusive=True
        )
        return attrs


# =============================================================================
# SHAPE 5: a COMPOSITE key. `maintenance_schedules` alone, and the one table in
# this API whose detail route is not `<identity>/`.
# =============================================================================


class MaintenanceScheduleSerializer(SyncedSerializer):
    """Field-for-field `RemoteMaintenanceSchedule` / `MaintenanceScheduleUpload`.

    Deliberately carries NO `last_done_mileage` or `last_done_date`, matching
    the engine's own decision: when a service was last done is a fact about
    SERVICE HISTORY, and duplicating it here is how the two drift into
    disagreeing. "Is this due" is computed by reading the latest matching
    `service_history` row against these intervals.
    """

    vehicle_id = HouseholdScopedPrimaryKeyRelatedField(
        source="vehicle", queryset=Vehicle.objects.all()
    )

    class Meta:
        model = MaintenanceSchedule
        fields = [
            "id",
            "vehicle_id",
            "service_name",
            "interval_miles",
            "interval_months",
            "interval_source",
            "never_done",
            "provenance",
            "created_at",
            "updated_at",
            "deleted_at",
        ]
        read_only_fields = ["id", "provenance", "created_at", "updated_at", "deleted_at"]
        extra_kwargs = {
            # `default false` on the live column since 20260901000200.
            "never_done": {"required": False, "default": False},
            "interval_source": {
                "help_text": (
                    "Free text rather than a constrained set - the engine left this open, and "
                    "values like 'SEEDED' and a manual entry both occur."
                )
            },
        }

    def validate_service_name(self, value: str) -> str:
        if not value or not value.strip():
            raise blank_error("service_name")
        return value

    def validate(self, attrs):
        _refuse_out_of_range(attrs, ("interval_miles", "interval_months"), exclusive=True)
        # `maintenance_schedules_has_an_interval`. A schedule with neither
        # interval can never come due, which is always a data-entry mistake
        # rather than an intent.
        if attrs.get("interval_miles") is None and attrs.get("interval_months") is None:
            raise serializers.ValidationError(
                "A maintenance schedule needs at least one interval: interval_miles, "
                "interval_months, or both. With neither it can never come due, so it would "
                "silently never fire."
            )
        return attrs


# =============================================================================
# The eleven non-telemetry viewsets.
# =============================================================================


class _FleetViewSet(SyncedModelViewSet):
    aspect = "fleet"


class VehicleViewSet(_FleetViewSet):
    table = "vehicles"
    serializer_class = VehicleSerializer


class ServiceHistoryViewSet(_FleetViewSet):
    table = "service_history"
    serializer_class = ServiceHistorySerializer


class _SyncIdViewSet(_FleetViewSet):
    """The six shape-2 tables. `sync_id` matches the same `str` path converter
    an `origin_guid` does, so only `identity_field` changes."""

    identity_field = "sync_id"


class DriveViewSet(_SyncIdViewSet):
    table = "drives"
    serializer_class = DriveSerializer


class CodeEventViewSet(_SyncIdViewSet):
    table = "code_events"
    serializer_class = CodeEventSerializer


class CodeClearEventViewSet(_SyncIdViewSet):
    table = "code_clear_events"
    serializer_class = CodeClearEventSerializer


class OilAnalysisViewSet(_SyncIdViewSet):
    table = "oil_analyses"
    serializer_class = OilAnalysisSerializer


class BuildEntryViewSet(_SyncIdViewSet):
    table = "build_entries"
    serializer_class = BuildEntrySerializer


class DriveReassignmentViewSet(_SyncIdViewSet):
    table = "drive_reassignments"
    serializer_class = DriveReassignmentSerializer


class ChassisQuirkViewSet(_FleetViewSet):
    """Shape 3. Household-shared, not per-vehicle: there is no `?vehicle=`
    filter here and there is nothing to filter on - the table has no
    `vehicle_id` column. Every quirk in the index is visible to every device in
    the household, which is what "reference data" means."""

    table = "chassis_quirks"
    serializer_class = ChassisQuirkSerializer
    identity_field = "quirk_id"
    # NOT `identity_is_primary_key = True`, even though `quirk_id` IS the
    # primary key. That flag means something narrower: "the identity is the
    # SERVER's own uuid, so PUT updates and never inserts, and POST is the
    # create path" (see its own comment in `api/synced.py`). A quirk slug is
    # minted by the client from a bundled JSON asset, so PUT must insert -
    # setting the flag would add a POST route and break the one path
    # `FleetBackend.upsertChassisQuirk` actually uses.
    identity_is_primary_key = False
    # No `deleted_at` column on the live table, so there is no tombstone to
    # write and no `?active=1` to honour. Both are stated rather than left to
    # fail at runtime: `destroy` would set an attribute no field backs and
    # `save(update_fields=["deleted_at"])` would raise.
    has_tombstones = False
    allow_delete = False
    no_delete_reason = (
        "chassis_quirks is household-shared reference data with no deleted_at column at all: "
        "it is re-seeded wholesale on an app update, not deleted row by row."
    )


class VehicleSpecViewSet(_FleetViewSet):
    """Shape 4. The identity is a uuid, so the path converter is `uuid` and not
    `str` - but `identity_is_primary_key` stays False for the same reason it
    does on `chassis_quirks`: a PUT here must INSERT the spec row when a
    vehicle has none yet, which is exactly what
    `FleetBackend.upsertVehicleSpec`'s REPLACE-on-conflict does."""

    table = "vehicle_specs"
    serializer_class = VehicleSpecSerializer
    identity_field = "vehicle_id"
    identity_url_converter = "uuid"
    identity_is_primary_key = False
    has_tombstones = False
    allow_delete = False
    no_delete_reason = (
        "vehicle_specs has no deleted_at column at all: a spec sheet belongs to its vehicle "
        "and goes when the vehicle does, so there is nothing to tombstone independently."
    )


class MaintenanceScheduleViewSet(_FleetViewSet):
    """Shape 5, and the one viewset here that departs from the generic detail
    route. See this module's own "what did NOT fit" section.

    The `list` action is inherited untouched - the `?since=`/`?active=` feed
    over this table is identical to every other one, and a hand-written copy of
    it would be a second thing to keep in step for no gain. Everything that
    touches the IDENTITY is written out below.
    """

    table = "maintenance_schedules"
    serializer_class = MaintenanceScheduleSerializer
    # Not a column. Nothing reads it - `_lookup`, `upsert` and `destroy` are all
    # overridden below and the schema reads `identity_path_parameters` instead -
    # and it is set to the pair rather than to one of the two columns so that a
    # reader who finds it does not conclude this table is keyed on either alone.
    identity_field = "(vehicle_id, service_name)"

    @classmethod
    def detail_path_suffix(cls) -> str:
        """Two segments, because the identity is two columns.

        **Known limit, stated rather than discovered later:** Django's `str`
        converter matches any non-empty string without a `/`, so a service name
        containing a slash cannot be addressed through this route. Service
        names come from the maintenance seeder and from voice ("oil change",
        "brake fluid"); a slash in one has never occurred, and if it ever does,
        the fix is a `path_converter` here rather than a second identity
        column.
        """
        return "<uuid:vehicle_id>/<str:service_name>/"

    @classmethod
    def identity_path_parameters(cls) -> list[tuple[str, str, str]]:
        return [
            ("vehicle_id", "uuid", "vehicle_id"),
            ("service_name", "str", "service_name"),
        ]

    def _lookup(self, identity):
        raise NotImplementedError(
            "maintenance_schedules is keyed on the pair (vehicle_id, service_name); use "
            "_lookup_pair. If this raised, a route was wired to an inherited handler that "
            "expects a single identity segment."
        )

    def _lookup_pair(self, vehicle_id, service_name):
        # `self.queryset()`, not `self.model().objects` - this is the one read
        # path in this file that does not go through the inherited `_lookup`,
        # and it needs the household filter for the same reason that one does.
        return self.queryset().filter(
            vehicle_id=vehicle_id, service_name=service_name
        ).first()

    def _pair_not_found(self, vehicle_id, service_name) -> Response:
        return Response(
            {
                "detail": (
                    f"Nothing was changed. No maintenance_schedules row has vehicle_id "
                    f"{vehicle_id} and service_name {service_name!r}."
                )
            },
            status=status.HTTP_404_NOT_FOUND,
        )

    def upsert(self, request, vehicle_id, service_name):
        """`PUT maintenance_schedules/<vehicle_id>/<service_name>/`.

        Idempotent for the same reason the generic one is: the identity comes
        from the URL, so a retry cannot make a second row - and here the URL
        carries both halves of the unique constraint, so a retry cannot violate
        `maintenance_schedules_unique_per_vehicle` either.
        """
        instance = self._lookup_pair(vehicle_id, service_name)
        data = dict(request.data.items())
        # The URL is the authority on identity, exactly as in the generic
        # `upsert` - both halves of it.
        data["vehicle_id"] = vehicle_id
        data["service_name"] = service_name
        # `self.serializer_context()`, the same one the generic `upsert`
        # passes. This override predates ADR 0045 and had no context at all;
        # without it `SyncedSerializer.create` has no household to write into
        # and `HouseholdScopedPrimaryKeyRelatedField` has no set to narrow to,
        # and both refuse in words rather than guessing.
        context = self.serializer_context()
        if instance is None:
            serializer = self.serializer_class(data=data, context=context)
        else:
            serializer = self.serializer_class(instance, data=data, context=context)
        serializer.is_valid(raise_exception=True)
        saved, error = save_or_400(serializer.save)
        if error is not None:
            return error
        return Response(self._serialize(saved), status=status.HTTP_200_OK)

    def destroy(self, request, vehicle_id, service_name):
        """`DELETE maintenance_schedules/<vehicle_id>/<service_name>/`. Sets
        the tombstone; idempotent; a second delete is still a 204."""
        instance = self._lookup_pair(vehicle_id, service_name)
        if instance is None:
            return self._pair_not_found(vehicle_id, service_name)
        if instance.deleted_at is None:
            instance.deleted_at = Now()
            _saved, error = save_or_400(lambda: instance.save(update_fields=["deleted_at"]))
            if error is not None:
                return error
        return Response(status=status.HTTP_204_NO_CONTENT)


# Registry order is the order routes are declared and the order the changes
# feed's keys are built: `vehicles` first because everything else references it,
# then the shape-1 sibling, then the six shape-2 tables in the order
# `FleetBackend.kt` introduces them, then the three one-off shapes.
#
# `obd_samples` is deliberately NOT in this list - see this module's doc comment
# and `api/registry.py`'s own note on the one exception to "routed implies in
# the changes feed".
FLEET_VIEWSETS = [
    VehicleViewSet,
    ServiceHistoryViewSet,
    DriveViewSet,
    CodeEventViewSet,
    CodeClearEventViewSet,
    OilAnalysisViewSet,
    BuildEntryViewSet,
    DriveReassignmentViewSet,
    ChassisQuirkViewSet,
    VehicleSpecViewSet,
    MaintenanceScheduleViewSet,
]


# =============================================================================
# SHAPE 6: `obd_samples`. Three hand-written routes, none of them generic.
# =============================================================================

# Twice the phone's own `ObdSampleReconcile.BATCH_SIZE` of 500, so the client's
# batch sits comfortably inside it and a client that doubles up still fits. The
# cap exists because the insert below is ONE statement with eight placeholders
# per row: Postgres's own limit is 65535 bind parameters, and 1000 rows is 8000
# of them, an order of magnitude clear of it. A larger batch is refused in
# words rather than truncated - a truncated batch would report success for rows
# it never wrote.
OBD_BATCH_MAX = 1000

OBD_SINCE_PARAMETER = OpenApiParameter(
    name="since",
    location=OpenApiParameter.QUERY,
    type=OpenApiTypes.DATETIME,
    required=False,
    description=(
        "ISO-8601 UTC watermark, matched against `recorded_at` - the instant the sample was "
        "READ FROM THE CAR, not the instant it reached this server. That is the column "
        "`FleetBackend.fetchObdSamplesSince` filters on, and the only one that means anything "
        "to a client rebuilding a window of telemetry. Omitted means this vehicle's whole "
        "history, paged; the phone passes roughly the last 30 days (Kevin's 2026-09-03 "
        "OBD-volume ruling), and that window is the CLIENT's choice, never a narrowing this "
        "server applies on its behalf."
    ),
)

OBD_VEHICLE_PARAMETER = OpenApiParameter(
    name="vehicle",
    location=OpenApiParameter.QUERY,
    type=OpenApiTypes.UUID,
    required=True,
    description=(
        "REQUIRED. The `vehicles.id` whose samples to return. This is the one list route in "
        "the API with a mandatory filter: `obd_samples` held 20,796 rows on 2026-09-07 and an "
        "unbounded feed over it is a download, not a feed. Absent is a 400 naming this "
        "parameter; a uuid naming no vehicle is a 404, never an empty page - an empty page "
        "would read as 'this car has no telemetry' when the truth is 'there is no such car'."
    ),
)


class ObdSampleSerializer(serializers.ModelSerializer):
    """One `public.obd_samples` row as the windowed feed returns it.

    Not a `SyncedSerializer`: that base's `create`/`update` exist to write rows
    through the four generic routes, and this table has no such route. Reads
    only.

    **No `id` on the wire, and that is deliberate rather than an oversight.**
    `RemoteObdSample`'s own doc comment: "no `serverId`/`syncId` at all - the
    table's own natural key is `(vehicle_id, pid, recorded_at)`, which
    `FleetSync`'s pull-side dedup matches against directly rather than needing a
    separate identity column". There is no detail route to address a sample by
    its uuid, so publishing one would hand clients an identity that addresses
    nothing and invite them to key a cache on it.
    """

    vehicle_id = serializers.PrimaryKeyRelatedField(source="vehicle", read_only=True)

    class Meta:
        model = ObdSample
        fields = [
            "vehicle_id",
            "pid",
            "value",
            "unit",
            "recorded_at",
            "lat",
            "lng",
            "created_at",
        ]
        extra_kwargs = {
            "recorded_at": {
                "help_text": (
                    "When the reading was taken from the car. Part of the natural key "
                    "`(vehicle_id, pid, recorded_at)`, and the column this feed's `?since=` "
                    "and `next` cursor are keyed on."
                )
            },
            "created_at": {
                "help_text": (
                    "When this server stored the sample - NOT when it was read. The two can "
                    "be far apart: the phone batches uploads and resumes them across "
                    "sessions."
                )
            },
        }


class ObdSampleUploadSerializer(serializers.Serializer):
    """One sample in a `POST .../batch/` body.

    A plain `Serializer`, not a `ModelSerializer`, because nothing here goes
    through the ORM: the insert is one statement with an explicit
    `on conflict (vehicle_id, pid, recorded_at) do nothing` (see
    `ObdSampleBatchView`), and a model serializer would suggest a per-row
    `.save()` that does not happen.

    `vehicle_id` is a bare `UUIDField` rather than a `PrimaryKeyRelatedField`
    for a measured reason: the related field issues one SELECT per row to prove
    the vehicle exists, which for a 500-row batch is 500 round trips against a
    remote Postgres. The view checks each DISTINCT vehicle once instead, which
    is the same guarantee at 1/500th the cost.
    """

    vehicle_id = serializers.UUIDField()
    pid = serializers.CharField()
    value = serializers.FloatField()
    # `allow_blank`: `obd_samples.unit` has no `''` default, unlike the other
    # blank-tolerant columns in this module, but a dimensionless reading has no
    # honest unit label and a 400 here would drop an entire telemetry batch
    # over a cosmetic field.
    unit = serializers.CharField(allow_blank=True)
    recorded_at = serializers.DateTimeField()
    lat = serializers.FloatField(required=False, allow_null=True, default=None)
    lng = serializers.FloatField(required=False, allow_null=True, default=None)

    def validate_pid(self, value: str) -> str:
        # `obd_samples_pid_check`, the table's only CHECK.
        if not value or not value.strip():
            raise blank_error("pid")
        return value


class ObdBatchResultSerializer(serializers.Serializer):
    """What `POST .../batch/` answers with.

    Four numbers rather than one, because a batch upload can partly no-op for
    two completely different reasons and a client that cannot tell them apart
    cannot tell a healthy retry from a bug. `FleetBackend.uploadObdSampleBatch`
    itself only reads success or failure - its own doc explains that decoding
    500 rows back to count them would spend the request-size budget the
    batching exists to protect - and these four cost one integer each.
    """

    received = serializers.IntegerField(
        help_text="How many samples were in the request body."
    )
    inserted = serializers.IntegerField(
        help_text="How many rows this call actually wrote. Zero on a full re-post."
    )
    already_present = serializers.IntegerField(
        help_text=(
            "How many were already stored under the same natural key "
            "`(vehicle_id, pid, recorded_at)` and were therefore skipped. A re-post of a "
            "batch that already landed reports every row here, which is what makes an "
            "unacknowledged upload safe to retry."
        )
    )
    duplicates_in_batch = serializers.IntegerField(
        help_text=(
            "How many rows in THIS body shared a natural key with an earlier row in the same "
            "body and were folded into it. Normally 0. Non-zero means the client sent two "
            "readings claiming the same car, PID and instant - which the table cannot store "
            "separately and which are indistinguishable to it - so the first was kept. "
            "Reported rather than silently dropped."
        )
    )


class ObdSampleCountSerializer(serializers.Serializer):
    count = serializers.IntegerField(
        help_text="How many `obd_samples` rows exist, for the whole table or for one vehicle."
    )


def _refuse(detail: str, code: int = status.HTTP_400_BAD_REQUEST) -> Response:
    return Response({"detail": detail}, status=code)


def _required_vehicle(request, *, required: bool):
    """Resolve `?vehicle=<uuid>` to a `Vehicle`, or return the Response that
    refuses. Returns `(vehicle_or_None, error_or_None)` - exactly one is
    not-None, except when `required` is False and the parameter is absent, in
    which case both are None and the caller means the whole table.

    The existence check is the point. A uuid naming no vehicle would otherwise
    produce an empty page, which reads as "this car has no telemetry" when the
    truth is "there is no such car" - the same unreadable-versus-empty
    distinction CLAUDE.md section 1 draws for a refused calendar permission.
    """
    raw = (request.query_params.get("vehicle") or "").strip()
    if not raw:
        if not required:
            return None, None
        return None, _refuse(
            "Nothing was returned. This route needs ?vehicle=<uuid>. obd_samples is the "
            "largest table in this database and an unbounded feed over it is a download "
            "rather than a feed, so the vehicle is required rather than defaulted - and the "
            "time window is yours to set with ?since=, never one this server picks for you."
        )
    try:
        parsed = uuid.UUID(raw)
    except ValueError:
        return None, _refuse(
            f"Nothing was returned. ?vehicle={raw!r} is not a uuid. It is a `vehicles.id`, "
            f"which GET /api/fleet/vehicles/ returns as each row's `id`."
        )
    # Scoped: a vehicle in ANOTHER household is not a vehicle this caller may
    # name, and the 404 below says exactly that in the words it already used -
    # "there is no such car" is true from inside this household.
    vehicle = scoped(Vehicle, request).filter(id=parsed).first()
    if vehicle is None:
        return None, _refuse(
            f"Nothing was returned. No vehicle has id {parsed}. This is a 404 rather than an "
            f"empty page on purpose: an empty page would read as 'this car has no telemetry' "
            f"when the truth is that there is no such car.",
            status.HTTP_404_NOT_FOUND,
        )
    return vehicle, None


class ObdSampleListView(APIView):
    """`GET /api/fleet/obd_samples/?vehicle=<uuid>&since=<iso>` - the windowed,
    per-vehicle pull `FleetBackend.fetchObdSamplesSince` calls.

    No `?active=1`: this table has no `deleted_at`, so every row that exists is
    live and the unnarrowed feed already IS the active set. The parameter is
    absent from the schema rather than advertised and ignored, which is the
    same choice `api/synced.SyncedModelViewSet.has_tombstones` makes for the
    gated tables.

    Paged with the same 500-row `next` cursor every other feed uses, keyed on
    `recorded_at`. The known limit `api/synced.py` states for its own cursor
    applies here too and is MORE reachable: the cursor is an inclusive
    timestamp and cannot express "after this row", so a full page of samples
    sharing one `recorded_at` would re-serve itself forever. It needs 500
    samples at one instant for one car and one PID set; the natural key makes
    exact duplicates impossible, so the 500 would have to be 500 distinct PIDs
    read in the same millisecond.
    """

    @extend_schema(
        operation_id="api_fleet_obd_samples_list",
        tags=["fleet"],
        parameters=[OBD_VEHICLE_PARAMETER, OBD_SINCE_PARAMETER],
        responses={
            200: OpenApiResponse(
                response=paged_serializer(ObdSampleSerializer),
                description=(
                    "One page of one vehicle's samples, oldest first. No tombstones, because "
                    "this table has no tombstone column - telemetry is append-only and is "
                    "corrected by a newer observation, never by editing an old one."
                ),
            ),
            400: OpenApiResponse(
                response=DetailSerializer,
                description="`vehicle` was missing or was not a uuid. Nothing was returned.",
            ),
            404: OpenApiResponse(
                response=DetailSerializer,
                description="`vehicle` named no vehicle this server knows.",
            ),
        },
    )
    def get(self, request):
        vehicle, error = _required_vehicle(request, required=True)
        if error is not None:
            return error
        since = parse_since(request.query_params.get("since"))
        # The household filter is redundant given `vehicle` was resolved inside
        # it, and it is here anyway: a second reader of this line should not
        # have to trace `_required_vehicle` to know the feed is scoped, and a
        # future edit that resolves the vehicle differently must not silently
        # widen it.
        queryset = (
            scoped(ObdSample, request)
            .filter(vehicle=vehicle, recorded_at__gte=since)
            .order_by("recorded_at", "pk")
        )
        page, next_since = paginate_since(queryset, cursor_field="recorded_at")
        return Response(
            {"results": ObdSampleSerializer(page, many=True).data, "next": next_since}
        )


class ObdSampleCountView(APIView):
    """`GET /api/fleet/obd_samples/count/` - `FleetBackend.countObdSamples`.

    A number and nothing else. `ObdSampleReconcile`'s own class doc explains at
    length why a full-row fetch here "would defeat the entire batching effort
    for a report line"; against PostgREST that was a `HEAD` with
    `Prefer: count=exact`, and here it is a `COUNT(*)`, which is the same
    bargain - one integer, no rows.

    `?vehicle=` is OPTIONAL here, unlike on the feed, and the asymmetry is not
    an inconsistency: a count of the whole table is one aggregate whatever its
    size, so the reason the feed refuses an unbounded request simply does not
    apply.
    """

    @extend_schema(
        operation_id="api_fleet_obd_samples_count",
        tags=["fleet"],
        parameters=[
            OpenApiParameter(
                name="vehicle",
                location=OpenApiParameter.QUERY,
                type=OpenApiTypes.UUID,
                required=False,
                description=(
                    "Optional. Narrows the count to one vehicle. Omitted counts the whole "
                    "table, which is what `FleetBackend.countObdSamples` asks for."
                ),
            )
        ],
        responses={
            200: ObdSampleCountSerializer,
            400: OpenApiResponse(
                response=DetailSerializer, description="`vehicle` was not a uuid."
            ),
            404: OpenApiResponse(
                response=DetailSerializer,
                description="`vehicle` named no vehicle this server knows.",
            ),
        },
    )
    def get(self, request):
        vehicle, error = _required_vehicle(request, required=False)
        if error is not None:
            return error
        # `?vehicle=` is OPTIONAL on this route, so without the household
        # filter a bare count would be a count of every household's telemetry.
        queryset = scoped(ObdSample, request)
        if vehicle is not None:
            queryset = queryset.filter(vehicle=vehicle)
        return Response({"count": queryset.count()})


# One statement, an explicit conflict target, and `returning id` so the row
# count is the number ACTUALLY inserted rather than the number attempted.
#
# Raw SQL rather than `bulk_create(ignore_conflicts=True)`, for two reasons and
# not for speed. First, Django's `ignore_conflicts` emits `on conflict do
# nothing` with NO target, so it would also swallow a primary-key collision -
# a different failure wearing the same silence. Second, it returns nothing on
# Postgres when conflicts are ignored, so there would be no honest way to fill
# in `inserted` and `already_present`, and a batch endpoint that cannot say how
# many rows it wrote is one whose idempotency claim cannot be tested.
_OBD_INSERT_HEAD = (
    "insert into public.obd_samples "
    "(id, household_id, vehicle_id, pid, value, unit, recorded_at, lat, lng, created_at) "
    "values "
)
_OBD_INSERT_TAIL = (
    " on conflict (vehicle_id, pid, recorded_at) do nothing returning id"
)
# `statement_timestamp()` rather than a Python timestamp, for the reason
# `api/synced.py` sets out at length: Django does not run on the database's
# machine, the two clocks were measured half a second apart on 2026-09-06, and
# one clock for every write removes the whole class of failure.
_OBD_ROW_PLACEHOLDERS = "(%s, %s, %s, %s, %s, %s, %s, %s, %s, statement_timestamp())"


class ObdSampleBatchView(APIView):
    """`POST /api/fleet/obd_samples/batch/` - `FleetBackend.uploadObdSampleBatch`.

    The body is a JSON ARRAY of samples, matching ticket 04's own exceptions
    table (`POST /api/fleet/obd_samples/batch/ [..]`) and what
    `SupabaseFleetBackend` already sends.

    **Idempotent on the natural key, by construction rather than by checking.**
    `obd_samples_natural_key_idx` is unique on `(vehicle_id, pid, recorded_at)`
    and the insert names it as the `on conflict` target, so a re-post of a
    batch that already landed writes nothing and answers 200 with
    `inserted: 0`. That is not a nicety: `ObdSampleReconcile` resumes an
    interrupted upload from a cursor it may have failed to advance, so re-posts
    are the normal case and not the exceptional one. A sample IS its car, its
    PID and its instant - the key is content-derived, so a re-post is genuinely
    the same sample and not merely one that looks like it.

    **Nothing is corrected here and nothing can be.** There is no PUT, no
    PATCH and no DELETE anywhere on this table. A telemetry observation is
    superseded by a newer observation, never by editing an old one, which is
    why `obd_samples` carries no `forbid_mutation_of_facts` trigger either -
    the immutability trigger exists for gated aspects where a row asserts a
    VERIFIED fact, and this row asserts an observation.
    """

    @extend_schema(
        operation_id="api_fleet_obd_samples_batch_create",
        tags=["fleet"],
        request=ObdSampleUploadSerializer(many=True),
        responses={
            201: OpenApiResponse(
                response=ObdBatchResultSerializer,
                description="At least one sample was written.",
            ),
            200: OpenApiResponse(
                response=ObdBatchResultSerializer,
                description=(
                    "Nothing was written, and that is a normal answer: every sample in the "
                    "batch was already stored under the same natural key, or the batch was "
                    "empty. Not an error, and not something to retry differently."
                ),
            ),
            400: OpenApiResponse(
                response=DetailSerializer,
                description=(
                    "Nothing was written. The body was not a JSON array, a sample was "
                    "malformed, the batch was over the size cap, or a `vehicle_id` named no "
                    "vehicle. Field-level errors come back keyed by index."
                ),
            ),
        },
    )
    def post(self, request):
        payload = request.data
        if not isinstance(payload, list):
            return _refuse(
                "Nothing was written. The body of this endpoint is a JSON ARRAY of samples, "
                'for example [{"vehicle_id": "...", "pid": "0104", "value": 41.2, "unit": '
                '"%", "recorded_at": "2026-09-07T12:00:00Z"}].'
            )
        if len(payload) > OBD_BATCH_MAX:
            return _refuse(
                f"Nothing was written. That batch has {len(payload)} samples and the limit is "
                f"{OBD_BATCH_MAX}. The phone uploads {PAGE_SIZE} at a time; split the batch "
                f"and post it again. It is refused whole rather than truncated, because a "
                f"truncated batch would report success for rows nobody stored."
            )

        serializer = ObdSampleUploadSerializer(data=payload, many=True)
        serializer.is_valid(raise_exception=True)
        rows = serializer.validated_data

        # Every DISTINCT vehicle checked once, not once per row - see
        # `ObdSampleUploadSerializer`'s own doc for why the relation field was
        # not used. An unknown vehicle refuses the WHOLE batch: the foreign key
        # would refuse the statement anyway, and a 400 naming the id is a
        # better answer than the database's own message.
        wanted = {row["vehicle_id"] for row in rows}
        known = set(
            scoped(Vehicle, request).filter(id__in=wanted).values_list("id", flat=True)
        )
        missing = sorted(str(v) for v in wanted - known)
        if missing:
            return _refuse(
                f"Nothing was written. These vehicle_id values name no vehicle this server "
                f"knows: {', '.join(missing)}. The whole batch was refused rather than the "
                f"rows that named them, so nothing partial was stored."
            )

        # Fold rows that share a natural key with an earlier row in the SAME
        # body. `on conflict do nothing` would pick one of them arbitrarily and
        # say nothing; the count is reported instead. Two readings claiming one
        # car, one PID and one instant are indistinguishable to this table -
        # its unique index means only one can exist - so the first is kept.
        seen: set[tuple] = set()
        deduped = []
        for row in rows:
            key = (row["vehicle_id"], row["pid"], row["recorded_at"])
            if key in seen:
                continue
            seen.add(key)
            deduped.append(row)
        duplicates_in_batch = len(rows) - len(deduped)

        household = household_of(request)
        inserted = 0
        if deduped:
            params: list = []
            for row in deduped:
                params.extend(
                    [
                        # Minted here for the same reason `SyncedSerializer.create`
                        # mints one: an `inspectdb`-generated `managed = False`
                        # model captures no column DEFAULT, so Postgres's own
                        # `default gen_random_uuid()` never gets the chance to
                        # fire and a NULL would be sent instead.
                        uuid.uuid4(),
                        # ADR 0045. This is the one write in this file that
                        # bypasses a serializer entirely (see the comment above
                        # `_OBD_INSERT_HEAD` for why it is raw SQL), so it is
                        # also the one place `household_id` has to be named by
                        # hand rather than assigned by `SyncedSerializer`.
                        household.id,
                        row["vehicle_id"],
                        row["pid"],
                        row["value"],
                        row["unit"],
                        row["recorded_at"],
                        row["lat"],
                        row["lng"],
                    ]
                )
            sql = (
                _OBD_INSERT_HEAD
                + ", ".join([_OBD_ROW_PLACEHOLDERS] * len(deduped))
                + _OBD_INSERT_TAIL
            )
            try:
                # The SAVEPOINT is load-bearing for the same reason
                # `api/sync.save_or_400` gives: Postgres aborts an entire
                # transaction on any statement error, so catching this in plain
                # Python without `atomic()` would poison every query that ran
                # afterwards in the same transaction - which is the shape of
                # every test in this suite.
                with transaction.atomic(), connection.cursor() as cursor:
                    cursor.execute(sql, params)
                    inserted = len(cursor.fetchall())
            except DatabaseError as exc:
                return _refuse(f"Nothing was written. The database refused the batch: {exc}")

        body = {
            "received": len(rows),
            "inserted": inserted,
            "already_present": len(deduped) - inserted,
            "duplicates_in_batch": duplicates_in_batch,
        }
        code = status.HTTP_201_CREATED if inserted else status.HTTP_200_OK
        return Response(body, status=code)


# Declared here rather than in `api/urls.py`'s registry loop, because
# `obd_samples` is not in the registry - see this module's doc comment.
#
# `batch/` and `count/` are listed BEFORE nothing at all: there is no
# `obd_samples/<identity>/` detail route for them to be shadowed by, since this
# table has no addressable single-row identity. That absence is the reason the
# two literal paths are safe, so it is written down rather than left as luck.
OBD_SAMPLE_PATHS = [
    path("fleet/obd_samples/", ObdSampleListView.as_view(), name="fleet-obd-samples-list"),
    path(
        "fleet/obd_samples/batch/",
        ObdSampleBatchView.as_view(),
        name="fleet-obd-samples-batch",
    ),
    path(
        "fleet/obd_samples/count/",
        ObdSampleCountView.as_view(),
        name="fleet-obd-samples-count",
    ),
]
