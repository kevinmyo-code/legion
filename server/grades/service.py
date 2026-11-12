"""The member's Canvas grades, read through `visible()`, for REST (`grades/views.py`) and the engine
tools `canvas_grades` and `what_i_need` (`engine_mcp/tools.py`). One code path for both doors.

Every read is the caller's own: `canvas_course_grades` rows are private to the member whose
Canvas login was read (never null), and the per-assignment scores come from the Canvas task rows
in `events` through `visible(Event, request)`, the same lookup `watches/evaluate._canvas_items`
uses. Another member's course is simply absent, a 404 on its route.

Words, everywhere: figures Canvas stated are said to be Canvas's; anything worked out here is said
to be worked out, and is an ESTIMATE in words when a drop rule or a mismatch with Canvas's own
current score makes it one (`grades/calculator.py`). A total Canvas does not show is said to be
absent, never filled in.
"""

from __future__ import annotations

from fractions import Fraction

from grades.calculator import (
    Course,
    Group,
    Item,
    Refusal,
    current_total,
    exact,
    number_words,
    percent_words,
    select_remaining,
    shown,
    what_i_need,
)
from grades.models import CourseGrade
from household.models import is_recruiter
from household.recruiter_routes import RECRUITER_DENIED
from household.tenancy import visible

TITLE_SEPARATOR = " · "


class NotFound(Exception):
    def __init__(self, message: str):
        super().__init__(message)
        self.message = message


def recruiter_reason() -> str:
    return RECRUITER_DENIED["grades.views.GradeListView"]


def refusal(request) -> str | None:
    """A recruiter's visit never reads Canvas grades (a second fence beside the route table and
    the tool allowlist)."""
    if is_recruiter(getattr(request, "user", None)):
        reason = recruiter_reason()
        return f"Nothing was read. {reason[0].upper()}{reason[1:]}."
    return None


# =============================================================================
# Reads
# =============================================================================


def my_courses(request):
    return visible(CourseGrade, request).order_by("course_label", "canvas_course_id")


def find_course(request, ref=None) -> CourseGrade:
    """One of the caller's courses: by Canvas course id, or words from its label or code. With no
    `ref`, the only course on file. Ambiguity and absence are refused in words."""
    rows = list(my_courses(request))
    if not rows:
        raise NotFound(
            "No Canvas grades are on file for you. That is a real empty result: the Canvas read "
            "stores them on its next run once your Canvas login is connected from your own "
            "account."
        )
    if ref is None or str(ref).strip() == "":
        if len(rows) == 1:
            return rows[0]
        raise NotFound(
            "Nothing was read. You have several courses on file; name one: "
            + "; ".join(r.course_label for r in rows)
            + "."
        )
    text = str(ref).strip()
    found = [r for r in rows if str(r.canvas_course_id) == text]
    if not found:
        needle = text.lower()
        found = [
            r
            for r in rows
            if needle in (r.course_label or "").lower() or needle in (r.course_code or "").lower()
        ]
    if not found:
        raise NotFound(
            f"Nothing was read. None of your courses on file matches {text!r}. They are: "
            + "; ".join(r.course_label for r in rows)
            + "."
        )
    if len(found) > 1:
        raise NotFound(
            f"Nothing was read. {text!r} matches {len(found)} courses ("
            + "; ".join(r.course_label for r in found)
            + "). Name one."
        )
    return found[0]


def assignment_rows(request, course_id: int):
    from legacy.models.dates import Event

    return (
        visible(Event, request)
        .filter(deleted_at__isnull=True, structured_meta__canvas_course_id=int(course_id))
        .filter(structured_meta__has_key="canvas_assignment_id")
        .exclude(structured_meta__has_key="parent_canvas_assignment_id")
        .order_by("starts_at", "title")
    )


def _name_of(row) -> str:
    title = row.title or ""
    return title.split(TITLE_SEPARATOR, 1)[1] if TITLE_SEPARATOR in title else title


def _flag(value) -> bool:
    return value is True


def item_of(row) -> Item:
    meta = row.structured_meta or {}
    group = meta.get("assignment_group_id")
    return Item(
        id=int(meta["canvas_assignment_id"]),
        name=_name_of(row),
        points_possible=exact(meta.get("points_possible")) or Fraction(0),
        score=exact(meta.get("score")),
        group_id=int(group) if group is not None else None,
        excused=_flag(meta.get("excused")),
        counts=meta.get("grading_type") != "not_graded" and not _flag(
            meta.get("omit_from_final_grade")
        ),
    )


def course_of(row: CourseGrade) -> Course:
    groups = []
    for group in row.groups or []:
        rules = group.get("rules") or {}
        groups.append(
            Group(
                id=int(group["id"]),
                name=group.get("name") or f"Group {group['id']}",
                weight=exact(group.get("weight")),
                drop_lowest=int(rules.get("drop_lowest") or 0),
                drop_highest=int(rules.get("drop_highest") or 0),
            )
        )
    scale = None
    scheme = row.grading_scheme or None
    if scheme and scheme.get("levels"):
        scale = tuple(
            (str(level["name"]), exact(level["value"]) * 100) for level in scheme["levels"]
        )
    return Course(
        label=row.course_label,
        apply_weights=row.apply_weights,
        groups=tuple(groups),
        canvas_current_score=exact(row.current_score),
        scale=scale,
        scale_title=(scheme or {}).get("title"),
    )


def _figure(value) -> float | None:
    return shown(exact(value)) if value is not None else None


def assignment_dict(row) -> dict:
    meta = row.structured_meta or {}
    item = item_of(row)
    return {
        "id": item.id,
        "event_id": str(row.id),
        "name": item.name,
        "group_id": item.group_id,
        "due_at": row.starts_at,
        "points_possible": _figure(meta.get("points_possible")),
        "score": _figure(meta.get("score")),
        "grade": None if meta.get("grade") is None else str(meta.get("grade")),
        "graded": item.graded,
        "excused": item.excused,
        "missing": _flag(meta.get("missing")),
        "late": _flag(meta.get("late")),
        "counts": item.counts,
    }


def canvas_says(row: CourseGrade) -> str:
    """Canvas's own totals for the course, in words, or that Canvas shows none."""
    parts = []
    if row.current_score is not None or row.current_grade:
        bits = []
        if row.current_score is not None:
            bits.append(percent_words(exact(row.current_score), "half"))
        if row.current_grade:
            bits.append(row.current_grade)
        parts.append(f"Canvas says your current grade is {', '.join(bits)}")
    if row.final_score is not None or row.final_grade:
        bits = []
        if row.final_score is not None:
            bits.append(percent_words(exact(row.final_score), "half"))
        if row.final_grade:
            bits.append(row.final_grade)
        parts.append(f"its final grade, which counts ungraded work as 0, is {', '.join(bits)}")
    if not parts:
        return (
            f"Canvas shows no total for {row.course_label} (the course hides it, or nothing is "
            f"graded), so none is stated here."
        )
    return f"{row.course_label}: " + "; ".join(parts) + "."


def course_dict(request, row: CourseGrade, *, with_assignments: bool = True) -> dict:
    rows = list(assignment_rows(request, row.canvas_course_id))
    worked = None
    note = None
    try:
        worked = current_total(course_of(row), [item_of(r) for r in rows])
    except Refusal as exc:
        note = exc.message
    body = {
        "canvas_course_id": row.canvas_course_id,
        "course": row.course_label,
        "course_code": row.course_code,
        "canvas_current_score": _figure(row.current_score),
        "canvas_current_grade": row.current_grade,
        "canvas_final_score": _figure(row.final_score),
        "canvas_final_grade": row.final_grade,
        "canvas_says": canvas_says(row),
        "current_worked_out_percent": shown(worked),
        "worked_out_note": note,
        "apply_weights": row.apply_weights,
        "groups": [
            {
                "id": g.get("id"),
                "name": g.get("name"),
                "weight": _figure(g.get("weight")),
                "rules": g.get("rules") or {},
            }
            for g in row.groups or []
        ],
        "grading_scheme": row.grading_scheme,
        "read_at": row.read_at,
    }
    if with_assignments:
        body["assignments"] = [assignment_dict(r) for r in rows]
    return body


def need(request, course_ref, target, *, assignment=None, group=None, others_at=None) -> dict:
    """`what_i_need` over the caller's own course and assignment rows. Raises `NotFound` or
    `Refusal` with the reason in words."""
    row = find_course(request, course_ref)
    course = course_of(row)
    items = [item_of(r) for r in assignment_rows(request, row.canvas_course_id)]
    ids, words = select_remaining(course, items, assignment=assignment, group=group)
    answer = what_i_need(
        course, items, target, remaining=ids, remaining_words=words, others_at=others_at
    )
    body = answer.as_dict()
    body["canvas_course_id"] = row.canvas_course_id
    body["read_at"] = row.read_at
    return body


# =============================================================================
# Words for the engine tools
# =============================================================================


def _score_words(a: dict) -> str:
    if a["excused"]:
        return "excused"
    if not a["graded"]:
        return "no score yet" + (", missing" if a["missing"] else "")
    possible = a["points_possible"]
    text = (
        f"{number_words(exact(a['score']), 'half')} of {number_words(exact(possible), 'half')}"
        if possible is not None
        else f"score {number_words(exact(a['score']), 'half')}"
    )
    return text + (", late" if a["late"] else "")


def grades_words(bodies: list[dict], *, detail: bool) -> str:
    parts = []
    for body in bodies:
        parts.append(body["canvas_says"])
        if body["current_worked_out_percent"] is not None and body["canvas_current_score"] is None:
            parts.append(
                f"Worked out here from the scores on file (not Canvas's figure): "
                f"{number_words(exact(body['current_worked_out_percent']), 'half')}%."
            )
        if detail:
            graded = [a for a in body.get("assignments", []) if a["counts"]]
            if graded:
                parts.append(
                    "Assignments: " + "; ".join(f"{a['name']} {_score_words(a)}" for a in graded)
                    + "."
                )
            if body["apply_weights"] and body["groups"]:
                parts.append(
                    "Weights: "
                    + "; ".join(
                        f"{g['name']} {number_words(exact(g['weight'] or 0), 'half')}%"
                        + (" (has a drop rule)" if g["rules"].get("drop_lowest")
                           or g["rules"].get("drop_highest") else "")
                        for g in body["groups"]
                    )
                    + "."
                )
    return " ".join(parts)
