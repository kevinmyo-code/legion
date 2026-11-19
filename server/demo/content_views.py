"""The recruiter page's reads: who this visit is, the page content, its two files.

recruiter-demo tickets 07 and 10 (ADR 0068).

- `GET /api/demo/session`: a recruiter's own visit (`expires_at`, `ref`).
  Recruiters only.
- `GET /api/demo/content`: the page, from `demo.models.RecruiterContent`,
  without `profile` (that is `about_kevin`'s alone).
- `GET /api/demo/asset/<report|resume|resume_image>`: the stored bytes.
- `GET /api/demo/media/<name>`: a media file the content names (a model, a still, a video,
  captions), byte ranges included.

The last two are read by a recruiter's visit AND by a household owner, so
Kevin can preview the page signed in as himself. A plain member is refused:
the page is about one person, written for strangers, and nothing a member
needs. Every route opts in with `allows_recruiter`, and
`household.permissions.IsHouseholdMember` still refuses a recruiter whose
visit is over.
"""

from __future__ import annotations

import re

from django.db.models import BinaryField, F, Func, IntegerField, Value
from django.http import HttpResponse, StreamingHttpResponse
from drf_spectacular.types import OpenApiTypes
from drf_spectacular.utils import OpenApiParameter, OpenApiResponse, extend_schema
from rest_framework import exceptions, serializers
from rest_framework.response import Response
from rest_framework.throttling import SimpleRateThrottle
from rest_framework.views import APIView

from api.schema import DetailSerializer
from demo.content import (
    ASSET_KINDS,
    CAPTIONS_NAME,
    MODEL_NAME,
    POSTER_NAME,
    VIDEO_NAME,
    public_content,
)
from demo.models import DemoVisit, RecruiterContent, RecruiterMediaFile
from household.models import HouseholdMember, is_recruiter
from household.permissions import RECRUITER_VISIT_OVER_CODE, IsHouseholdMember
from legion.client_ip import ClientIpThrottleMixin

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
    resume_image = serializers.CharField(
        allow_null=True,
        help_text=(
            "`/api/demo/asset/resume_image` (the resume's page as a PNG, for the resume modal), "
            "or null when none is loaded."
        ),
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
    f'"{RESUME_DOWNLOAD_NAME}". `resume_image`: the page of the resume as a PNG, shown inline.'
)


class DemoAssetView(APIView):
    permission_classes = [RecruiterOrOwner]
    allows_recruiter = True

    @extend_schema(
        tags=DEMO_TAGS,
        operation_id="api_demo_asset_retrieve",
        summary="The report (HTML, sandboxed), the resume (PDF, a download) or its page (PNG)",
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
            (200, "image/png"): OpenApiResponse(OpenApiTypes.BINARY, description=_ASSET_200),
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
        elif kind == "resume":
            response["Content-Disposition"] = f'attachment; filename="{RESUME_DOWNLOAD_NAME}"'
        # `resume_image` is drawn inline by the resume modal: no disposition.
        return response


# =============================================================================
# GET /api/demo/media/<name>: a media file the content names (`demo.content.MEDIA_TYPES`)
# =============================================================================

_MEDIA_NAME = re.compile("|".join((MODEL_NAME, POSTER_NAME, VIDEO_NAME, CAPTIONS_NAME)))
# The most one response (or one read of a streamed file) takes from the row: bounds the memory a
# request holds, whatever the file's size. A browser seeking a video asks again from where it is.
RANGE_CHUNK = 2 * 1024 * 1024
_RANGE = re.compile(r"bytes=(\d*)-(\d*)\Z")
_UNSATISFIABLE = "unsatisfiable"
_MEDIA_TYPES_DOC = (
    "model/gltf-binary",
    "image/png",
    "image/jpeg",
    "image/webp",
    "video/mp4",
    "video/webm",
    "text/vtt",
)


def _byte_range(header: str | None, size: int) -> tuple[int, int] | str | None:
    """(first, last) byte of a single `Range: bytes=...` header, `_UNSATISFIABLE`, or None to
    send the whole file (no header, several ranges, or one this route does not read)."""
    match = _RANGE.match(header.strip()) if header else None
    if match is None:
        return None
    first, last = match.groups()
    if not first and not last:
        return None
    if not first:  # the last N bytes
        count = int(last)
        return (max(0, size - count), size - 1) if count > 0 and size > 0 else _UNSATISFIABLE
    start = int(first)
    if last and int(last) < start:
        return None  # not a valid range: RFC 9110 says ignore the header
    if start >= size:
        return _UNSATISFIABLE
    return start, min(int(last), size - 1) if last else size - 1


def _read_part(pk: int, start: int, length: int) -> bytes:
    """`length` bytes of a stored file from `start`, read in Postgres: never the whole row."""
    part = (
        RecruiterMediaFile.objects.filter(pk=pk)
        .annotate(
            part=Func(
                F("data"),
                Value(start + 1),
                Value(length),
                function="substr",
                output_field=BinaryField(),
            )
        )
        .values_list("part", flat=True)
        .first()
    )
    return bytes(part or b"")


def _streamed(pk: int, size: int):
    for start in range(0, size, RANGE_CHUNK):
        yield _read_part(pk, start, min(RANGE_CHUNK, size - start))


class DemoMediaView(APIView):
    """A media file the caller's version of the content names: a model, a poster, or a section's or
    phone clip's video, poster or captions. Private like the resume: a visit or an owner, never the
    public. Cached by validator only (`private, no-cache` + the file's hash as ETag): a revisit
    costs a 304, and a reload of the content is seen at once. Byte ranges (one per request, at most
    `RANGE_CHUNK`) let a video seek; a whole file over `RANGE_CHUNK` is streamed in such reads."""

    permission_classes = [RecruiterOrOwner]
    allows_recruiter = True

    @extend_schema(
        tags=DEMO_TAGS,
        operation_id="api_demo_media_retrieve",
        summary="A media file the recruiter page names: a 3D model, a still, a video or captions",
        parameters=[
            OpenApiParameter("name", OpenApiTypes.STR, OpenApiParameter.PATH),
            OpenApiParameter(
                "Range",
                OpenApiTypes.STR,
                OpenApiParameter.HEADER,
                required=False,
                description="One `bytes=first-last`, `bytes=first-` or `bytes=-count` range.",
            ),
        ],
        responses={
            **{(200, kind): OpenApiResponse(OpenApiTypes.BINARY) for kind in _MEDIA_TYPES_DOC},
            **{
                (206, kind): OpenApiResponse(
                    OpenApiTypes.BINARY,
                    description="The asked range, at most 2 MiB; Content-Range says which.",
                )
                for kind in _MEDIA_TYPES_DOC
            },
            304: OpenApiResponse(description="The copy the browser holds is current."),
            403: OpenApiResponse(
                DemoRefusalSerializer,
                description="Neither a recruiter's visit nor a household owner.",
            ),
            404: OpenApiResponse(DetailSerializer, description="No such file is loaded."),
            416: OpenApiResponse(description="The range starts past the end of the file."),
        },
    )
    def get(self, request, name: str):
        row = RecruiterContent.for_user(request.user)
        if row is None or not _MEDIA_NAME.fullmatch(name):
            return Response({"detail": _no_such_asset("such file")}, status=404)
        meta = (
            RecruiterMediaFile.objects.filter(content=row, name=name)
            .annotate(size=Func(F("data"), function="octet_length", output_field=IntegerField()))
            .values("pk", "sha256", "content_type", "size")
            .first()
        )
        if meta is None:
            return Response({"detail": _no_such_asset(name)}, status=404)
        etag, size, kind = f'"{meta["sha256"]}"', meta["size"], meta["content_type"]
        wanted = None
        if request.headers.get("If-Range", etag) == etag:
            wanted = _byte_range(request.headers.get("Range"), size)
        if etag in [tag.strip() for tag in request.headers.get("If-None-Match", "").split(",")]:
            response = HttpResponse(status=304)
        elif wanted == _UNSATISFIABLE:
            response = HttpResponse(status=416)
            response["Content-Range"] = f"bytes */{size}"
        elif wanted is not None:
            start, end = wanted
            end = min(end, start + RANGE_CHUNK - 1)
            response = HttpResponse(
                _read_part(meta["pk"], start, end - start + 1), status=206, content_type=kind
            )
            response["Content-Range"] = f"bytes {start}-{end}/{size}"
        elif size > RANGE_CHUNK:
            response = StreamingHttpResponse(_streamed(meta["pk"], size), content_type=kind)
            response["Content-Length"] = str(size)
        else:
            response = HttpResponse(_read_part(meta["pk"], 0, size), content_type=kind)
        response["X-Content-Type-Options"] = "nosniff"
        response["Accept-Ranges"] = "bytes"
        response["ETag"] = etag
        response["Cache-Control"] = "private, no-cache"
        return response


# =============================================================================
# POST /api/demo/chips: the checked suggestion chips for a turn's offer (`demo.suggestions`)
# =============================================================================


class DemoChipsRequestSerializer(serializers.Serializer):
    offer = serializers.CharField(
        max_length=8_000,
        help_text="The `offer` of a turn's `done` event (the greeting's, or an answer's).",
    )


class DemoChipSerializer(serializers.Serializer):
    label = serializers.CharField(help_text="What the chip shows.")
    question = serializers.CharField(help_text="The whole question a tap asks, word for word.")
    token = serializers.CharField(help_text="Sent back as the turn's `chip` with the question.")


class DemoChipsSerializer(serializers.Serializer):
    chips = DemoChipSerializer(many=True)


class DemoChipsThrottle(SimpleRateThrottle):
    """Per visitor."""

    scope = "demo_chips"

    def get_cache_key(self, request, view):
        return self.cache_format % {"scope": self.scope, "ident": request.user.pk}


class DemoChipsIpThrottle(ClientIpThrottleMixin, SimpleRateThrottle):
    """Per address too: every visit is a fresh user."""

    scope = "demo_chips_ip"

    def get_cache_key(self, request, view):
        return self.cache_format % {"scope": self.scope, "ident": self.get_ident(request)}


class DemoChipsView(APIView):
    """After the answer has gone out: the offer's chips, each checked answerable for THIS visitor
    (`demo.suggestions.answerable`). Reads only. An offer made for someone else, or expired, gives
    no chips (an empty list, never an error the page must show)."""

    permission_classes = [RecruiterOnly]
    allows_recruiter = True
    throttle_classes = [DemoChipsThrottle, DemoChipsIpThrottle]

    def throttled(self, request, wait):
        # Over the rate: no chips, never an error the page would show (`post`).
        self.over_rate = True

    @extend_schema(
        tags=DEMO_TAGS,
        operation_id="api_demo_chips_create",
        summary="The checked suggestion chips for a turn's offer (reads only)",
        request=DemoChipsRequestSerializer,
        responses={
            200: DemoChipsSerializer,
            400: DetailSerializer,
            403: OpenApiResponse(DemoRefusalSerializer, description="Not a recruiter's visit."),
        },
    )
    def post(self, request):
        from demo import suggestions

        if getattr(self, "over_rate", False):
            return Response({"chips": []}, headers={"Cache-Control": "private, no-store"})
        body = DemoChipsRequestSerializer(data=request.data)
        if not body.is_valid():
            return Response({"detail": f"No chips: {body.errors}"}, status=400)
        chips = suggestions.chips_for_offer(request, body.validated_data["offer"]) or []
        return Response({"chips": chips}, headers={"Cache-Control": "private, no-store"})
