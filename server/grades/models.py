"""`public.canvas_course_grades`: each course's totals as Canvas stated them (Kevin, 2026-11-12:
"Canvas grades + what-I-need").

One row per (household, member, Canvas course), written only by `manage.py canvas_poll`
(`ingest/canvas.py` reads, `grades/store.py` writes) in the same transaction as the Canvas tasks,
so a run lands whole or not at all. **What is stored is what Canvas said:** `current_score`,
`current_grade`, `final_score` and `final_grade` are the student enrollment's `computed_*` values,
NULL when the course hides its totals; nothing here or downstream computes a substitute and calls
it Canvas's. `groups` holds each assignment group's id, name, weight and drop rules
(`[{id, name, weight, rules}]`), and `grading_scheme` the course's own letters and cut-offs when
the course names a scheme (`{title, levels: [{name, value}]}`, `value` a fraction), NULL otherwise.

**Private to the member whose Canvas login was read** (ADR 0052, like the Canvas task rows since
ingest migration 0012): `owner_user` is never null, `household.tenancy.visible()` hands each member
their own rows and nobody else's, and the owner guard trigger (`household/visibility_sql.py`)
refuses an owner from outside the row's household. Born tenanted and listed in `TENANT_TABLES`.
"""

from __future__ import annotations

import uuid

from django.db import models
from django.db.models.functions import Now

from household.tenancy_sql import household_unique_name


class CourseGrade(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    household = models.ForeignKey("household.Household", on_delete=models.PROTECT, related_name="+")
    owner_user = models.ForeignKey("household.User", on_delete=models.CASCADE, related_name="+")
    canvas_course_id = models.BigIntegerField()
    course_label = models.TextField()
    course_code = models.TextField(null=True, blank=True)
    # Canvas's own figures, exactly as stated; NULL when Canvas stated none.
    current_score = models.DecimalField(max_digits=12, decimal_places=6, null=True, blank=True)
    current_grade = models.TextField(null=True, blank=True)
    final_score = models.DecimalField(max_digits=12, decimal_places=6, null=True, blank=True)
    final_grade = models.TextField(null=True, blank=True)
    apply_weights = models.BooleanField(default=False)
    groups = models.JSONField(default=list)
    grading_scheme = models.JSONField(null=True, blank=True)
    # The Canvas read this row reflects (every run that read the course whole sets it).
    read_at = models.DateTimeField()
    created_at = models.DateTimeField(db_default=Now())
    updated_at = models.DateTimeField(db_default=Now())

    class Meta:
        db_table = "canvas_course_grades"
        indexes = [
            models.Index(fields=["household", "owner_user"], name="canvas_course_grades_owner_idx")
        ]
        constraints = [
            models.UniqueConstraint(
                fields=["household", "owner_user", "canvas_course_id"],
                name=household_unique_name(
                    "canvas_course_grades", ["owner_user_id", "canvas_course_id"]
                ),
            ),
        ]
