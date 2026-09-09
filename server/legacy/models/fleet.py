"""Fleet: vehicles, their decoded specs, drives, OBD data, and maintenance
history. Twelve of the 41 `public` tables (ticket 02's table). Every model
here is `managed = False` - Django never issues DDL for these; the tables
are, and remain, owned by `supabase/migrations/` until a later ticket hands
a table over for real (ADR 0044, execution-plan.md Phase 1).

Column types were read from `information_schema.columns` and `pg_constraint`
against the live schema through the read-only `legion_reader` role, not
guessed from `inspectdb`'s output for the enum and json columns (`inspectdb`
cannot resolve `USER-DEFINED`/`jsonb` on its own - see `legacy/enums.py`).
"""
from __future__ import annotations

from django.db import models

from legacy.enums import Provenance
from legacy.models.tenancy import household_field, household_unique


class Vehicle(models.Model):
    id = models.UUIDField(primary_key=True)
    name = models.TextField()
    make = models.TextField()
    model = models.TextField()
    year = models.IntegerField()
    trim = models.TextField(null=True)
    engine = models.TextField(null=True)
    confirmed = models.BooleanField()
    odometer_baseline = models.IntegerField(null=True)
    odometer_baseline_at = models.DateTimeField(null=True)
    provenance = models.TextField(choices=Provenance.choices)
    created_at = models.DateTimeField()
    updated_at = models.DateTimeField()
    deleted_at = models.DateTimeField(null=True)
    # Nullable AND unique: `vehicles_origin_guid_idx` is a plain unique btree
    # index, not a partial one - Postgres treats every NULL as distinct from
    # every other NULL under a unique constraint, so nullable-and-unique is
    # the correct, faithful pairing here.
    origin_guid = models.TextField(null=True)
    archived = models.BooleanField()
    last_obd_mac = models.TextField(null=True)

    household = household_field()

    class Meta:
        managed = False
        db_table = "vehicles"
        constraints = [
            household_unique("vehicles", "origin_guid"),
        ]


class VehicleSpec(models.Model):
    """One row per vehicle - `vehicle_id` is this table's own primary key,
    not a separate `id` column, so the relation is a `OneToOneField` with
    `primary_key=True` rather than a plain `ForeignKey`."""

    vehicle = models.OneToOneField(
        Vehicle,
        primary_key=True,
        db_column="vehicle_id",
        on_delete=models.DO_NOTHING,
        related_name="+",
    )
    vin = models.TextField()
    engine_cylinders = models.IntegerField(null=True)
    displacement_l = models.FloatField(null=True)
    engine_hp = models.IntegerField(null=True)
    engine_config = models.TextField()
    fuel_type = models.TextField()
    transmission_style = models.TextField()
    transmission_speeds = models.TextField()
    drive_type = models.TextField()
    body_class = models.TextField()
    doors = models.IntegerField(null=True)
    series = models.TextField()
    vehicle_type = models.TextField()
    manufacturer = models.TextField()
    plant_city = models.TextField()
    plant_country = models.TextField()
    paint_color = models.TextField()
    paint_code = models.TextField()
    build_notes = models.TextField()
    decoded_at = models.DateTimeField(null=True)
    provenance = models.TextField(choices=Provenance.choices)
    created_at = models.DateTimeField()
    updated_at = models.DateTimeField()

    household = household_field()

    class Meta:
        managed = False
        db_table = "vehicle_specs"


class Drive(models.Model):
    id = models.UUIDField(primary_key=True)
    sync_id = models.TextField()
    vehicle = models.ForeignKey(
        Vehicle, db_column="vehicle_id", on_delete=models.DO_NOTHING, related_name="+"
    )
    started_at = models.DateTimeField()
    ended_at = models.DateTimeField()
    miles = models.FloatField()
    gallons = models.FloatField(null=True)
    end_reason = models.TextField()
    provenance = models.TextField(choices=Provenance.choices)
    created_at = models.DateTimeField()
    updated_at = models.DateTimeField()
    deleted_at = models.DateTimeField(null=True)

    household = household_field()

    class Meta:
        managed = False
        db_table = "drives"
        constraints = [
            household_unique("drives", "sync_id"),
        ]


class DriveReassignment(models.Model):
    id = models.UUIDField(primary_key=True)
    sync_id = models.TextField()
    vehicle = models.ForeignKey(
        Vehicle, db_column="vehicle_id", on_delete=models.DO_NOTHING, related_name="+"
    )
    new_vehicle = models.ForeignKey(
        Vehicle, db_column="new_vehicle_id", on_delete=models.DO_NOTHING, related_name="+"
    )
    from_at = models.DateTimeField()
    to_at = models.DateTimeField()
    provenance = models.TextField(choices=Provenance.choices)
    created_at = models.DateTimeField()
    updated_at = models.DateTimeField()
    deleted_at = models.DateTimeField(null=True)

    household = household_field()

    class Meta:
        managed = False
        db_table = "drive_reassignments"
        constraints = [
            household_unique("drive_reassignments", "sync_id"),
        ]


class CodeEvent(models.Model):
    id = models.UUIDField(primary_key=True)
    sync_id = models.TextField()
    vehicle = models.ForeignKey(
        Vehicle, db_column="vehicle_id", on_delete=models.DO_NOTHING, related_name="+"
    )
    occurred_at = models.DateTimeField()
    mileage = models.IntegerField(null=True)
    codes = models.JSONField()
    freeze_frame = models.JSONField(null=True)
    provenance = models.TextField(choices=Provenance.choices)
    created_at = models.DateTimeField()
    updated_at = models.DateTimeField()
    deleted_at = models.DateTimeField(null=True)

    household = household_field()

    class Meta:
        managed = False
        db_table = "code_events"
        constraints = [
            household_unique("code_events", "sync_id"),
        ]


class CodeClearEvent(models.Model):
    id = models.UUIDField(primary_key=True)
    sync_id = models.TextField()
    vehicle = models.ForeignKey(
        Vehicle, db_column="vehicle_id", on_delete=models.DO_NOTHING, related_name="+"
    )
    occurred_at = models.DateTimeField()
    mileage = models.IntegerField(null=True)
    codes_before = models.JSONField()
    freeze_frame = models.JSONField(null=True)
    codes_after = models.JSONField(null=True)
    outcome = models.TextField()
    ack_raw = models.TextField()
    provenance = models.TextField(choices=Provenance.choices)
    created_at = models.DateTimeField()
    updated_at = models.DateTimeField()
    deleted_at = models.DateTimeField(null=True)

    household = household_field()

    class Meta:
        managed = False
        db_table = "code_clear_events"
        constraints = [
            household_unique("code_clear_events", "sync_id"),
        ]


class ObdSample(models.Model):
    """No `provenance`, no `updated_at`, no `deleted_at` - unlike every
    other fleet table. Raw sensor readings are append-only telemetry, never
    edited or soft-deleted, so those columns were never added (confirmed
    against the live schema, not assumed from the sibling tables' shape).
    20,796 rows live here already - by far the largest of the 41 tables."""

    id = models.UUIDField(primary_key=True)
    vehicle = models.ForeignKey(
        Vehicle, db_column="vehicle_id", on_delete=models.DO_NOTHING, related_name="+"
    )
    pid = models.TextField()
    value = models.FloatField()
    unit = models.TextField()
    recorded_at = models.DateTimeField()
    lat = models.FloatField(null=True)
    lng = models.FloatField(null=True)
    created_at = models.DateTimeField()

    household = household_field()

    class Meta:
        managed = False
        db_table = "obd_samples"
        # Mirrors `obd_samples_natural_key_idx`, a real unique index in the
        # live schema. Documentation only - `managed = False` means Django
        # never enforces it.
        unique_together = (("vehicle", "pid", "recorded_at"),)


class OilAnalysis(models.Model):
    id = models.UUIDField(primary_key=True)
    sync_id = models.TextField()
    vehicle = models.ForeignKey(
        Vehicle, db_column="vehicle_id", on_delete=models.DO_NOTHING, related_name="+"
    )
    analyzed_at = models.DateTimeField()
    mileage = models.IntegerField(null=True)
    oil_brand = models.TextField()
    oil_grade = models.TextField()
    drain_interval_miles = models.IntegerField(null=True)
    # Metals panel, all parts-per-million counts. Every one nullable and
    # unbounded above; only a "not negative" CHECK binds each (CONSTRAINTS.md).
    iron = models.IntegerField(null=True)
    copper = models.IntegerField(null=True)
    lead = models.IntegerField(null=True)
    tin = models.IntegerField(null=True)
    aluminum = models.IntegerField(null=True)
    chromium = models.IntegerField(null=True)
    nickel = models.IntegerField(null=True)
    sodium = models.IntegerField(null=True)
    potassium = models.IntegerField(null=True)
    silicon = models.IntegerField(null=True)
    boron = models.IntegerField(null=True)
    magnesium = models.IntegerField(null=True)
    fuel_percent = models.FloatField(null=True)
    water_percent = models.FloatField(null=True)
    tbn = models.FloatField(null=True)
    viscosity_cst = models.FloatField(null=True)
    lab_notes = models.TextField()
    provenance = models.TextField(choices=Provenance.choices)
    created_at = models.DateTimeField()
    updated_at = models.DateTimeField()
    deleted_at = models.DateTimeField(null=True)

    household = household_field()

    class Meta:
        managed = False
        db_table = "oil_analyses"
        constraints = [
            household_unique("oil_analyses", "sync_id"),
        ]


class ServiceHistory(models.Model):
    id = models.UUIDField(primary_key=True)
    vehicle = models.ForeignKey(
        Vehicle, db_column="vehicle_id", on_delete=models.DO_NOTHING, related_name="+"
    )
    service_name = models.TextField()
    mileage = models.IntegerField(null=True)
    service_date = models.DateField(null=True)
    cost_cents = models.BigIntegerField(null=True)
    kind = models.TextField()  # CHECK: OBSERVED | ASSERTED (CONSTRAINTS.md)
    provenance = models.TextField(choices=Provenance.choices)
    created_at = models.DateTimeField()
    updated_at = models.DateTimeField()
    deleted_at = models.DateTimeField(null=True)
    origin_guid = models.TextField(null=True)

    household = household_field()

    class Meta:
        managed = False
        db_table = "service_history"
        constraints = [
            household_unique("service_history", "origin_guid"),
        ]


class MaintenanceSchedule(models.Model):
    id = models.UUIDField(primary_key=True)
    vehicle = models.ForeignKey(
        Vehicle, db_column="vehicle_id", on_delete=models.DO_NOTHING, related_name="+"
    )
    service_name = models.TextField()
    interval_miles = models.IntegerField(null=True)
    interval_months = models.IntegerField(null=True)
    interval_source = models.TextField()
    never_done = models.BooleanField()
    provenance = models.TextField(choices=Provenance.choices)
    created_at = models.DateTimeField()
    updated_at = models.DateTimeField()
    deleted_at = models.DateTimeField(null=True)

    household = household_field()

    class Meta:
        managed = False
        db_table = "maintenance_schedules"
        unique_together = (("vehicle", "service_name"),)


class ChassisQuirk(models.Model):
    """Model-wide reference data (a chassis quirk is not tied to one
    driver's vehicle), so its primary key is a human-assigned `quirk_id`
    text, not a `gen_random_uuid()` column - the only fleet table shaped
    this way. No `deleted_at` either: confirmed absent from the live
    schema, not an oversight in this port."""

    quirk_id = models.TextField(primary_key=True)
    chassis = models.TextField()
    engine = models.TextField()
    title = models.TextField()
    symptom = models.TextField()
    verification_steps = models.TextField()
    mileage_low = models.IntegerField(null=True)
    mileage_high = models.IntegerField(null=True)
    severity = models.TextField()  # CHECK: MONITOR | SERVICE_SOON | CRITICAL
    cost_low_cents = models.BigIntegerField(null=True)
    cost_high_cents = models.BigIntegerField(null=True)
    fix_notes = models.TextField()
    source_url = models.TextField()
    provenance = models.TextField(choices=Provenance.choices)
    created_at = models.DateTimeField()
    updated_at = models.DateTimeField()

    household = household_field()

    class Meta:
        managed = False
        db_table = "chassis_quirks"


class BuildEntry(models.Model):
    id = models.UUIDField(primary_key=True)
    sync_id = models.TextField()
    vehicle = models.ForeignKey(
        Vehicle, db_column="vehicle_id", on_delete=models.DO_NOTHING, related_name="+"
    )
    entry_type = models.TextField()
    title = models.TextField()
    vendor = models.TextField()
    part_number = models.TextField()
    cost_cents = models.BigIntegerField(null=True)
    logged_at = models.DateTimeField()
    mileage = models.IntegerField(null=True)
    notes = models.TextField()
    provenance = models.TextField(choices=Provenance.choices)
    created_at = models.DateTimeField()
    updated_at = models.DateTimeField()
    deleted_at = models.DateTimeField(null=True)

    household = household_field()

    class Meta:
        managed = False
        db_table = "build_entries"
        constraints = [
            household_unique("build_entries", "sync_id"),
        ]
