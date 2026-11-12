"""Writing `canvas_course_grades` from one Canvas run (`ingest/canvas.apply_plan`).

Called inside the run's own transaction, after the tasks and tombstones, so the grade rows land
with the tasks or not at all. Each payload is what Canvas said about one course
(`ingest/canvas.course_grade_payload`); a course the run could not read whole has no payload, and
its existing row is left exactly as it was. Rows are never deleted here: a course that drops off
the active list keeps its last row and its `read_at` says how old it is.
"""

from __future__ import annotations

import datetime

from django.db.models.functions import Now

from grades.models import CourseGrade

FIELDS = (
    "course_label",
    "course_code",
    "current_score",
    "current_grade",
    "final_score",
    "final_grade",
    "apply_weights",
    "groups",
    "grading_scheme",
)


def write_course_grades(
    household, owner_user_id, payloads: list[dict], read_at: datetime.datetime
) -> list[dict]:
    """One result per payload, in the shape `ingest/canvas.describe` prints: `inserted`,
    `updated` (with the fields that changed) or `unchanged` (only `read_at` moved)."""
    out = []
    for payload in payloads:
        title = f"grades · {payload['course_label']}"
        row = CourseGrade.objects.filter(
            household=household,
            owner_user_id=owner_user_id,
            canvas_course_id=payload["canvas_course_id"],
        ).first()
        if row is None:
            CourseGrade.objects.create(
                household=household, owner_user_id=owner_user_id, read_at=read_at, **payload
            )
            out.append({"action": "inserted", "title": title})
            continue
        changed = [name for name in FIELDS if getattr(row, name) != payload[name]]
        if changed:
            CourseGrade.objects.filter(pk=row.pk).update(
                **{name: payload[name] for name in changed}, read_at=read_at, updated_at=Now()
            )
            out.append({"action": "updated", "title": title, "changed": changed})
        else:
            CourseGrade.objects.filter(pk=row.pk).update(read_at=read_at)
            out.append({"action": "unchanged", "title": title})
    return out
