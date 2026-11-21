"""Checked fastener specs and the owner's truck profile (option B, slice 1).

**Only verified figures are ever stored.** `manage.py load_garage_specs <sheet.csv> --profile
<truck.csv>` reads the private checking sheet and loads a row only when it is ticked `verified`
with a figure, an explicit unit, what vehicle and hardware it is for, a source of an allowed kind,
a page and a date (`garage.specs.read_sheet`). An unticked row is skipped, so an unchecked figure
never enters the engine and can never be spoken.

**The truck profile** (`GarageTruck`) is the owner's year, engine, front axle and lift, and which
fasteners carry aftermarket hardware on his truck. It loads with the sheet from the same private
folder, so the public repo holds no detail of his truck. `show_part` speaks a row only when its
year, engine and axle cover the profile, and never a stock figure for a fastener the profile marks
aftermarket (`garage.tool`).

Not tenanted, like `diagrams.RecruiterDiagram`: it is one owner's truck, and `show_part` is
offered to the engine household's owner only (`assistant.surface.member_only_tools`).
"""

from __future__ import annotations

from django.db import models

FT_LB = "ft-lb"
IN_LB = "in-lb"
N_M = "N-m"
UNITS = ((FT_LB, "foot-pounds"), (IN_LB, "inch-pounds"), (N_M, "newton-metres"))
NM_PER_FTLB = 1.3558179483
NM_PER_INLB = NM_PER_FTLB / 12

STOCK = "stock"
KIT = "kit"
HARDWARE = ((STOCK, "the stock part"), (KIT, "an aftermarket kit's part"))

# The only kinds of source a verified figure may come from (no forum, no video, no chat).
SOURCE_KINDS = (
    ("fsm", "factory service manual"),
    ("reprint", "licensed factory manual reprint"),
    ("alldata", "ALLDATA"),
    ("kit_instructions", "the kit maker's instructions"),
)


class FastenerSpec(models.Model):
    model = models.CharField(max_length=48)
    fastener_id = models.CharField(max_length=120)
    torque_min = models.DecimalField(max_digits=6, decimal_places=1)
    # Null for a single figure; the top of the range when the source gives one.
    torque_max = models.DecimalField(max_digits=6, decimal_places=1, null=True, blank=True)
    unit = models.CharField(max_length=8, choices=UNITS)
    socket = models.CharField(max_length=60, blank=True, default="")
    thread = models.CharField(max_length=60, blank=True, default="")
    # What the figure is for: the source's years, engine and front axle ("any" when the source
    # says it does not depend on one), and the hardware.
    year_from = models.PositiveSmallIntegerField()
    year_to = models.PositiveSmallIntegerField()
    engine = models.CharField(max_length=20)
    axle = models.CharField(max_length=40)
    hardware = models.CharField(max_length=8, choices=HARDWARE)
    kit_name = models.CharField(max_length=120, blank=True, default="")
    source_kind = models.CharField(max_length=20, choices=SOURCE_KINDS)
    source = models.CharField(max_length=200)
    source_page = models.CharField(max_length=200)
    verified_on = models.DateField()
    loaded_at = models.DateTimeField(auto_now=True)

    class Meta:
        db_table = "garage_fastener_specs"
        constraints = [
            models.UniqueConstraint(fields=["model", "fastener_id"], name="garage_spec_unique"),
            models.CheckConstraint(
                condition=models.Q(torque_max__isnull=True)
                | models.Q(torque_max__gte=models.F("torque_min")),
                name="garage_spec_range",
            ),
            models.CheckConstraint(
                condition=models.Q(year_to__gte=models.F("year_from")),
                name="garage_spec_years",
            ),
        ]
        ordering = ["model", "fastener_id"]

    def __str__(self) -> str:
        return f"Spec {self.model} {self.fastener_id}"


class GarageTruck(models.Model):
    """The owner's truck, as the figures must fit it. One row per model."""

    model = models.CharField(max_length=48, unique=True)
    year = models.PositiveSmallIntegerField()
    engine = models.CharField(max_length=20)
    axle = models.CharField(max_length=40)
    lift_in = models.DecimalField(max_digits=3, decimal_places=1)
    # Fastener ids whose hardware on his truck is aftermarket (a lift kit's track bar, arms ...).
    kit_fasteners = models.JSONField(default=list)
    loaded_at = models.DateTimeField(auto_now=True)

    class Meta:
        db_table = "garage_trucks"

    def __str__(self) -> str:
        return f"Truck {self.model}"
