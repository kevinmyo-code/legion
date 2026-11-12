"""`/api/grades/`: the member's Canvas grades and what they need (Kevin, 2026-11-12). Read only.
The engine tools `canvas_grades` and `what_i_need` call the same `grades/service.py` (ADR 0035).

- `GET /api/grades/`                          every course on file for the caller, with scores
- `GET /api/grades/<course_id>`               one course
- `GET /api/grades/<course_id>/what-i-need`   `?target=B` (or `85`), optional `assignment`,
                                              `group`, `others_at`

Every route refuses a recruiter's visit first, then reads through `visible()`: another member's
course is a 404, the same shape as another household's.
"""

from __future__ import annotations

from drf_spectacular.utils import OpenApiParameter, extend_schema
from rest_framework import serializers, status
from rest_framework.response import Response
from rest_framework.views import APIView

from api.schema import DetailSerializer
from grades import service
from grades.calculator import Refusal
from grades.models import CourseGrade
from household.tenancy import visible

TAGS = ["grades"]


class GroupSerializer(serializers.Serializer):
    id = serializers.IntegerField()
    name = serializers.CharField()
    weight = serializers.FloatField(allow_null=True)
    rules = serializers.DictField(help_text="drop_lowest, drop_highest, never_drop as Canvas set.")


class AssignmentSerializer(serializers.Serializer):
    id = serializers.IntegerField(help_text="The Canvas assignment id.")
    event_id = serializers.UUIDField(help_text="The task row in events.")
    name = serializers.CharField()
    group_id = serializers.IntegerField(allow_null=True)
    due_at = serializers.DateTimeField(allow_null=True)
    points_possible = serializers.FloatField(allow_null=True)
    score = serializers.FloatField(allow_null=True)
    grade = serializers.CharField(allow_null=True)
    graded = serializers.BooleanField()
    excused = serializers.BooleanField()
    missing = serializers.BooleanField()
    late = serializers.BooleanField()
    counts = serializers.BooleanField(help_text="False when Canvas leaves it out of the grade.")


class CourseGradeSerializer(serializers.Serializer):
    canvas_course_id = serializers.IntegerField()
    course = serializers.CharField()
    course_code = serializers.CharField(allow_null=True)
    canvas_current_score = serializers.FloatField(
        allow_null=True, help_text="Canvas's own figure; null when Canvas shows none."
    )
    canvas_current_grade = serializers.CharField(allow_null=True)
    canvas_final_score = serializers.FloatField(allow_null=True)
    canvas_final_grade = serializers.CharField(allow_null=True)
    canvas_says = serializers.CharField(
        help_text="Canvas's totals, or that it shows none, in words."
    )
    current_worked_out_percent = serializers.FloatField(
        allow_null=True, help_text="Worked out here from the scores on file; never Canvas's."
    )
    worked_out_note = serializers.CharField(allow_null=True)
    apply_weights = serializers.BooleanField()
    groups = GroupSerializer(many=True)
    grading_scheme = serializers.DictField(allow_null=True)
    read_at = serializers.DateTimeField()
    assignments = AssignmentSerializer(many=True)


class CourseGradeListSerializer(serializers.Serializer):
    results = CourseGradeSerializer(many=True)


class NeedSerializer(serializers.Serializer):
    status = serializers.ChoiceField(
        choices=("needed", "guaranteed", "impossible", "nothing_left")
    )
    course = serializers.CharField()
    canvas_course_id = serializers.IntegerField()
    target_percent = serializers.FloatField()
    target_letter = serializers.CharField(allow_null=True)
    scale = serializers.CharField(allow_null=True, help_text="How a letter became a percent.")
    remaining = serializers.CharField()
    remaining_assignment_ids = serializers.ListField(child=serializers.IntegerField())
    remaining_points = serializers.FloatField()
    needed_percent = serializers.FloatField(allow_null=True, help_text="Rounded up.")
    needed_points = serializers.FloatField(allow_null=True, help_text="Rounded up.")
    best_reachable_percent = serializers.FloatField(allow_null=True, help_text="Rounded down.")
    worst_reachable_percent = serializers.FloatField(allow_null=True, help_text="Rounded down.")
    current_worked_out_percent = serializers.FloatField(allow_null=True)
    canvas_current_score = serializers.FloatField(allow_null=True)
    anchor = serializers.ChoiceField(
        choices=("matches", "differs", "canvas_shows_no_total", "nothing_graded")
    )
    estimate = serializers.BooleanField()
    estimate_reasons = serializers.ListField(child=serializers.CharField())
    assumptions = serializers.ListField(child=serializers.CharField())
    words = serializers.CharField()
    read_at = serializers.DateTimeField()


def _refused(request):
    reason = service.refusal(request)
    if reason is None:
        return None
    return Response({"detail": reason}, status=status.HTTP_403_FORBIDDEN)


def _mine(request, course_id: int) -> CourseGrade | None:
    return visible(CourseGrade, request).filter(canvas_course_id=course_id).first()


NOT_FOUND = "Nothing was read. No Canvas grades for that course are on file for you."


class GradeListView(APIView):
    @extend_schema(
        operation_id="api_grades_list",
        tags=TAGS,
        responses={200: CourseGradeListSerializer, 403: DetailSerializer},
        description="The caller's own Canvas courses: Canvas's totals as stated, and each score.",
    )
    def get(self, request):
        refused = _refused(request)
        if refused:
            return refused
        return Response(
            {"results": [service.course_dict(request, row) for row in service.my_courses(request)]}
        )


class GradeDetailView(APIView):
    @extend_schema(
        operation_id="api_grades_detail",
        tags=TAGS,
        responses={200: CourseGradeSerializer, 403: DetailSerializer, 404: DetailSerializer},
        description="One of the caller's Canvas courses, by Canvas course id.",
    )
    def get(self, request, course_id: int):
        refused = _refused(request)
        if refused:
            return refused
        row = _mine(request, course_id)
        if row is None:
            return Response({"detail": NOT_FOUND}, status=status.HTTP_404_NOT_FOUND)
        return Response(service.course_dict(request, row))


class WhatINeedView(APIView):
    @extend_schema(
        operation_id="api_grades_what_i_need",
        tags=TAGS,
        parameters=[
            OpenApiParameter("target", str, required=True,
                             description="A percent (85) or a letter (B)."),
            OpenApiParameter("assignment", str, description="One assignment: id or name words."),
            OpenApiParameter("group", str, description="One assignment group: id or name words."),
            OpenApiParameter("others_at", str,
                             description="Assume other ungraded work scores this percent."),
        ],
        responses={
            200: NeedSerializer,
            400: DetailSerializer,
            403: DetailSerializer,
            404: DetailSerializer,
        },
        description=(
            "The score needed on the remaining work (or one assignment or group) to reach a "
            "target, worked out from Canvas's scores and group weights. Never Canvas's figure; "
            "an ESTIMATE in words when a drop rule applies or the total disagrees with Canvas's."
        ),
    )
    def get(self, request, course_id: int):
        refused = _refused(request)
        if refused:
            return refused
        row = _mine(request, course_id)
        if row is None:
            return Response({"detail": NOT_FOUND}, status=status.HTTP_404_NOT_FOUND)
        target = request.query_params.get("target")
        if not target:
            return Response(
                {"detail": "Nothing was worked out. Say a target: a percent (85) or a letter (B)."},
                status=status.HTTP_400_BAD_REQUEST,
            )
        try:
            body = service.need(
                request,
                row.canvas_course_id,
                target,
                assignment=request.query_params.get("assignment") or None,
                group=request.query_params.get("group") or None,
                others_at=request.query_params.get("others_at") or None,
            )
        except (Refusal, service.NotFound) as exc:
            return Response({"detail": exc.message}, status=status.HTTP_400_BAD_REQUEST)
        return Response(body)
