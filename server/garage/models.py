"""Checked fastener specs (option B, slice 1). One row per fastener the owner VERIFIED himself.

**Only verified figures are ever stored.** `manage.py load_garage_specs <sheet.csv>` reads the
private checking sheet and loads a row only when it is ticked `verified` with a figure, a unit,
what it applies to, a source, a page and a date (`garage.specs.read_sheet`). An unticked row is
skipped, so an unchecked figure never enters the engine and can never be spoken.

Not tenanted, like `diagrams.RecruiterDiagram`: a fastener's torque is a fact about a vehicle,
not a household's row. Recruiters never reach `show_part` (`assistant.surface`).
"""

from __future__ import annotations

from django.db import models

FT_LB = "ft-lb"
N_M = "N-m"
UNITS = ((FT_LB, "foot-pounds"), (N_M, "newton-metres"))
NM_PER_FTLB = 1.3558179483


class FastenerSpec(models.Model):
    model = models.CharField(max_length=48)
    fastener_id = models.CharField(max_length=120)
    torque_min = models.DecimalField(max_digits=6, decimal_places=1)
    # Null for a single figure; the top of the range when the source gives one.
    torque_max = models.DecimalField(max_digits=6, decimal_places=1, null=True, blank=True)
    unit = models.CharField(max_length=8, choices=UNITS)
    socket = models.CharField(max_length=60, blank=True, default="")
    thread = models.CharField(max_length=60, blank=True, default="")
    applies_to = models.CharField(max_length=200)
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
        ]
        ordering = ["model", "fastener_id"]

    def __str__(self) -> str:
        return f"Spec {self.model} {self.fastener_id}"
