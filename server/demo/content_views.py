"""The recruiter page's reads: who this visit is, the page content, its two files.

recruiter-demo tickets 07 and 10 (ADR 0068).

- `GET /api/demo/session`: a recruiter's own visit (`expires_at`, `ref`).
  Recruiters only.
- `GET /api/demo/content`: the page, from `demo.models.RecruiterContent`,
  without `profile` (that is `about_kevin`'s alone).
- `GET /api/demo/asset/<report|resume>`: the stored bytes.

The last two are read by a recruiter's visit AND by a household owner, so
Kevin can preview the page signed in as himself. A plain member is refused:
the page is about one person, written for strangers, and nothing a member
needs. Every route opts in with `allows_recruiter`, and
`household.permissions.IsHouseholdMember` still refuses a recruiter whose
visit is over.
"""

from __future__ import annotations

from django.http import HttpResponse
from drf_spectacular.types import OpenApiTypes
from drf_spectacular.utils import OpenApiParameter, OpenApiResponse, extend_schema
from rest_framework import exceptions, serializers
from rest_framework.response import Response
from rest_framework.views import APIView

from api.schema import DetailSerializer
from demo.content import ASSET_KINDS, public_content
from demo.models import DemoVisit, RecruiterContent
from household.models import HouseholdMember, is_recruiter
from household.permissions import RECRUITER_VISIT_OVER_CODE, IsHouseholdMember

DEMO_TAGS = ["demo"]

NOT_SIGNED_IN = "Nothing was read. Enter the invite code first, or sign in."
NOT_A_VISIT = "Nothing was read. This is not a recruiter's demo visit."
NOT_A_READER = (
    "Nothing was read. The recruiter page is shown to a recruiter's demo visit and to a "
    "household owner previewing it; this account is neither."
)
NO_CONTENT = (
    "Nothing was read. The recruiter page has no content on this engine yet: the operator "
    "has to run 'manage.py load_recruiter_content <folder>' first."
)
RESUME_DOWNLOAD_NAME = "Kevin-Win-Resume.pdf"
REPORT_CSP = "sandbox allow-scripts"
NOT_SIGNED_IN_CODE = "not_signed_in"


def _no_such_asset(kind: str) -> str:
    return f"Nothing was read. No {kind} has been loaded on this engine."


class _SignedInMember(IsHouseholdMember):
    """`IsHouseholdMember`, but a request with no identity is refused with a
    403 in words rather than DRF's 401, so the page has one answer to read."""

    def has_permission(self, request, view) -> bool:
        user = request.user
        if not user or not user.is_authenticated:
            raise exceptions.PermissionDenied(
                {"detail": NOT_SIGNED_IN, "code": NOT_SIGNED_IN_CODE}
            )
        if not super().has_permission(request, view):
            raise exceptions.PermissionDenied(NOT_A_READER)
        return True


class RecruiterOnly(_SignedInMember):
    def has_permission(self, request, view) -> bool:
        super().has_permission(request, view)
        if not is_recruiter(request.user):
            raise exceptions.PermissionDenied(NOT_A_VISIT)
        return True


class RecruiterOrOwner(_SignedInMember):
    def has_permission(self, request, view) -> bool:
        super().has_permission(request, view)
        if is_recruiter(request.user):
            return True
        if HouseholdMember.objects.filter(
            user=request.user, role=HouseholdMember.OWNER
        ).exists():
            return True
        raise exceptions.PermissionDenied(NOT_A_READER)


# =============================================================================
# Shapes, for the contract (`openapi.yaml` -> `frontend/src/api/schema.d.ts`)
# =============================================================================


class DemoRefusalSerializer(serializers.Serializer):
    detail = serializers.CharField(help_text="What did not happen, in words. Show it.")
    # A CharField, not a ChoiceField: an enum named `code` could rename an
    # existing schema component, as `role` would have.
    code = serializers.CharField(
        required=False,
        help_text=(
            f"`{RECRUITER_VISIT_OVER_CODE}`: this recruiter visit expired or was closed (enter "
            f"the code again). `{NOT_SIGNED_IN_CODE}`: no visit and no sign-in at all. Absent "
            "for every other refusal (a member who is not an owner, for one)."
        ),
    )


class DemoSessionSerializer(serializers.Serializer):
    # A CharField, not a ChoiceField: a second enum named `role` would rename
    # the schema's existing `RoleEnum` and break every client that imports it.
    role = serializers.CharField(help_text='Always "recruiter" here.')
    expires_at = serializers.DateTimeField(help_text="The visit ends at this time.")
    ref = serializers.CharField(
        allow_null=True, help_text="The `ref` the resume link carried, or null."
    )


class RecruiterTitleBodySerializer(serializers.Serializer):
    title = serializers.CharField()
    body = serializers.CharField()


class RecruiterHeroSerializer(serializers.Serializer):
    title = serializers.CharField()
    lead = serializers.CharField()
    facts = serializers.ListField(child=serializers.CharField())


class RecruiterStatSerializer(serializers.Serializer):
    value = serializers.CharField()
    label = serializers.CharField()


class RecruiterWorkSerializer(serializers.Serializer):
    title = serializers.CharField()
    intro = serializers.CharField()
    stats = RecruiterStatSerializer(many=True)
    diagram_svg = serializers.CharField(help_text="Inline SVG markup, as Kevin wrote it.")
    diagram_caption = serializers.CharField()
    cards = RecruiterTitleBodySerializer(many=True)


class RecruiterExperienceSerializer(serializers.Serializer):
    when = serializers.CharField()
    title = serializers.CharField()
    bullets = serializers.ListField(child=serializers.CharField())


class RecruiterLinksSerializer(serializers.Serializer):
    linkedin = serializers.CharField()
    github = serializers.CharField()
    email = serializers.CharField()


class RecruiterAssetsSerializer(serializers.Serializer):
    report = serializers.CharField(
        allow_null=True, help_text="`/api/demo/asset/report`, or null when none is loaded."
    )
    resume = serializers.CharField(
        allow_null=True, help_text="`/api/demo/asset/resume`, or null when none is loaded."
    )


class RecruiterPageSerializer(serializers.Serializer):
    hero = RecruiterHeroSerializer()
    look_for = RecruiterTitleBodySerializer(many=True)
    work = RecruiterWorkSerializer(
        required=False, help_text="Absent in a version that leaves the work section out."
    )
    experience = RecruiterExperienceSerializer(many=True)
    links = RecruiterLinksSerializer()
    assets = RecruiterAssetsSerializer()


# =============================================================================
# Views
# =============================================================================


class DemoSessionView(APIView):
    permission_classes = [RecruiterOnly]
    allows_recruiter = True

    @extend_schema(
        tags=DEMO_TAGS,
        operation_id="api_demo_session_retrieve",
        summary="This recruiter visit: its role, when it ends, and its ref",
        responses={
            200: DemoSessionSerializer,
            403: OpenApiResponse(
                DemoRefusalSerializer,
                description="Not a recruiter's live demo visit (signed out, a member, or over).",
            ),
        },
    )
    def get(self, request):
        visit = DemoVisit.objects.filter(user=request.user).first()
        if visit is None:
            raise exceptions.PermissionDenied(NOT_A_VISIT)
        return Response(
            {"role": "recruiter", "expires_at": visit.expires_at, "ref": visit.ref or None},
            headers={"Cache-Control": "no-store"},
        )


class DemoContentView(APIView):
    permission_classes = [RecruiterOrOwner]
    allows_recruiter = True

    @extend_schema(
        tags=DEMO_TAGS,
        operation_id="api_demo_content_retrieve",
        summary="The recruiter page's content (never the about_kevin profile)",
        responses={
            200: RecruiterPageSerializer,
            403: OpenApiResponse(
                DemoRefusalSerializer,
                description="Neither a recruiter's visit nor a household owner.",
            ),
            404: OpenApiResponse(DetailSerializer, description="No content loaded yet."),
        },
    )
    def get(self, request):
        row = RecruiterContent.for_user(request.user)
        if row is None:
            return Response({"detail": NO_CONTENT}, status=404)
        body = public_content(row.content)
        body["assets"] = {
            kind: (f"/api/demo/asset/{kind}" if getattr(row, kind) is not None else None)
            for kind in ASSET_KINDS
        }
        return Response(body, headers={"Cache-Control": "private, no-store"})


_ASSET_200 = (
    "`report`: the stored HTML, with `Content-Security-Policy: sandbox allow-scripts` and "
    f'`X-Content-Type-Options: nosniff`. `resume`: the stored PDF, as an attachment named '
    f'"{RESUME_DOWNLOAD_NAME}".'
)


class DemoAssetView(APIView):
    permission_classes = [RecruiterOrOwner]
    allows_recruiter = True

    @extend_schema(
        tags=DEMO_TAGS,
        operation_id="api_demo_asset_retrieve",
        summary="The report (HTML, sandboxed) or the resume (PDF, a download)",
        parameters=[
            OpenApiParameter(
                "kind", OpenApiTypes.STR, OpenApiParameter.PATH, enum=list(ASSET_KINDS)
            )
        ],
        responses={
            (200, "text/html"): OpenApiResponse(OpenApiTypes.BINARY, description=_ASSET_200),
            (200, "application/pdf"): OpenApiResponse(
                OpenApiTypes.BINARY, description=_ASSET_200
            ),
            403: OpenApiResponse(
                DemoRefusalSerializer,
                description="Neither a recruiter's visit nor a household owner.",
            ),
            404: OpenApiResponse(DetailSerializer, description="That file is not loaded."),
        },
    )
    def get(self, request, kind: str):
        row = RecruiterContent.for_user(request.user)
        stored = row.asset(kind) if row is not None and kind in ASSET_KINDS else None
        if stored is None:
            return Response({"detail": _no_such_asset(kind)}, status=404)
        data, content_type, _filename = stored
        response = HttpResponse(data, content_type=content_type)
        response["X-Content-Type-Options"] = "nosniff"
        response["Cache-Control"] = "private, no-store"
        if kind == "report":
            # Scripts run, but in an opaque origin: no cookie, no engine call
            # as the visitor. Same-origin framing so the page can embed it.
            response["Content-Security-Policy"] = REPORT_CSP
            response["X-Frame-Options"] = "SAMEORIGIN"
        else:
            response["Content-Disposition"] = f'attachment; filename="{RESUME_DOWNLOAD_NAME}"'
        return response
