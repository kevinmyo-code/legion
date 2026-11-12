"""`/api/releases/*`: published builds of the LEGION clients (alfred-ota ticket 04).

    GET  /api/releases/latest?app=phone   any member: the newest release (highest version_code)
    GET  /api/releases/<id>/download      any member: a V4 signed URL, valid 15 minutes
    POST /api/releases/                   an owner: record a release after the APK is uploaded

The API's default authentication (device token or session) and `IsHouseholdMember` guard all
three; publishing adds `IsReleasePublisher`. A read-scoped token cannot publish (the write fence
in `IsHouseholdMember`), and a recruiter's visit is refused every route here
(`household.recruiter_routes.RECRUITER_DENIED`).

Releases are engine-wide (`releases/models.py`), so nothing here calls `household_of`: every
member reads the same releases. Publishing is not a data route either; like the bank link
(`ingest/plaid_views.py`) it is an engine action an owner takes. `tools/release_apk.py` is the
one caller of the POST; Kevin runs it, or the worker after his yes (map decision 4).
"""

from __future__ import annotations

from django.db import IntegrityError, transaction
from django.utils import timezone
from drf_spectacular.types import OpenApiTypes
from drf_spectacular.utils import (
    OpenApiParameter,
    OpenApiResponse,
    extend_schema,
    inline_serializer,
)
from rest_framework import serializers, status
from rest_framework.response import Response
from rest_framework.views import APIView

from api.schema import DetailSerializer
from household.models import HouseholdMember
from household.permissions import IsHouseholdOwner
from household.tenancy import resolve_default_household
from releases import signing
from releases.models import APPS, HEX64, AppRelease

TAGS = ["releases"]


class IsReleasePublisher(IsHouseholdOwner):
    """An owner of the household that runs this engine (the bootstrap household), or a superuser.

    Releases are engine-wide: one row serves every household's phones. An owner of a second
    household (ADR 0045) administers that household's membership, not the engine, so being an
    owner somewhere is not enough to publish. It gates no data."""

    message = (
        "Nothing was published. Only an owner of the household that runs this engine may "
        "publish a release. Every member can read the newest release and download it."
    )

    def has_permission(self, request, view) -> bool:
        if not super().has_permission(request, view):
            return False
        if getattr(request.user, "is_superuser", False):
            return True
        engine_household = resolve_default_household()
        return engine_household is not None and HouseholdMember.objects.filter(
            user=request.user, household=engine_household, role=HouseholdMember.OWNER
        ).exists()


class AppReleaseSerializer(serializers.ModelSerializer):
    """What a client sees. Never `object_name` (where it sits in the bucket) or who published."""

    class Meta:
        model = AppRelease
        fields = [
            "id",
            "app",
            "version_code",
            "version_name",
            "sha256",
            "size_bytes",
            "notes",
            "signer_sha256",
            "published_at",
        ]
        read_only_fields = fields


class PublishSerializer(serializers.Serializer):
    app = serializers.ChoiceField(choices=list(APPS))
    version_code = serializers.IntegerField(min_value=1, max_value=2_147_483_647)
    version_name = serializers.CharField(max_length=200)
    sha256 = serializers.RegexField(HEX64, help_text="The APK's SHA-256, lowercase hex.")
    size_bytes = serializers.IntegerField(min_value=1)
    object_name = serializers.CharField(
        max_length=1024, help_text="The uploaded object's name inside the APK bucket."
    )
    notes = serializers.CharField(max_length=2000, allow_blank=True, required=False, default="")
    signer_sha256 = serializers.RegexField(
        HEX64, help_text="SHA-256 of the signing certificate, lowercase hex."
    )

    def validate_object_name(self, value: str) -> str:
        if value.startswith("/") or "\\" in value or ".." in value.split("/"):
            raise serializers.ValidationError(
                "A plain object name inside the bucket, e.g. phone/x.apk."
            )
        return value


DownloadResponse = inline_serializer(
    name="AppReleaseDownload",
    fields={
        "url": serializers.URLField(help_text="A V4 signed GET URL for the APK."),
        "expires_at": serializers.DateTimeField(),
        "sha256": serializers.CharField(help_text="Verify the download against this."),
        "size_bytes": serializers.IntegerField(),
        "version_code": serializers.IntegerField(),
    },
)


def _no_release(app: str) -> Response:
    return Response(
        {"detail": f"No release has been published for {app} on this engine."},
        status=status.HTTP_404_NOT_FOUND,
    )


class ReleasePublishView(APIView):
    permission_classes = [IsReleasePublisher]

    @extend_schema(
        tags=TAGS,
        operation_id="api_releases_create",
        summary="Record a release (owner only), after its APK is in the bucket",
        request=PublishSerializer,
        responses={
            201: AppReleaseSerializer,
            400: DetailSerializer,
            403: DetailSerializer,
            409: OpenApiResponse(
                response=DetailSerializer,
                description="The version code is not higher than the newest release.",
            ),
        },
    )
    def post(self, request):
        incoming = PublishSerializer(data=request.data)
        incoming.is_valid(raise_exception=True)
        data = incoming.validated_data
        try:
            with transaction.atomic():
                newest = (
                    AppRelease.objects.select_for_update()
                    .filter(app=data["app"])
                    .order_by("-version_code")
                    .first()
                )
                if newest is not None and data["version_code"] <= newest.version_code:
                    return Response(
                        {
                            "detail": (
                                f"Nothing was published. Version code {data['version_code']} is "
                                f"not higher than the newest {data['app']} release, "
                                f"{newest.version_code}; a phone only installs an update with "
                                f"a higher one."
                            )
                        },
                        status=status.HTTP_409_CONFLICT,
                    )
                release = AppRelease.objects.create(**data, published_by=request.user)
        except IntegrityError:
            return Response(
                {
                    "detail": (
                        f"Nothing was published. A {data['app']} release with version code "
                        f"{data['version_code']} already exists."
                    )
                },
                status=status.HTTP_409_CONFLICT,
            )
        return Response(AppReleaseSerializer(release).data, status=status.HTTP_201_CREATED)


class ReleaseLatestView(APIView):
    @extend_schema(
        tags=TAGS,
        operation_id="api_releases_latest_retrieve",
        summary="The newest release of one app (highest version code)",
        parameters=[
            OpenApiParameter("app", OpenApiTypes.STR, enum=list(APPS), required=False),
        ],
        responses={200: AppReleaseSerializer, 400: DetailSerializer, 404: DetailSerializer},
    )
    def get(self, request):
        app = request.query_params.get("app", "phone")
        if app not in APPS:
            return Response(
                {"detail": f"There is no app called {app!r}; ask for one of: {', '.join(APPS)}."},
                status=status.HTTP_400_BAD_REQUEST,
            )
        newest = AppRelease.objects.filter(app=app).order_by("-version_code").first()
        if newest is None:
            return _no_release(app)
        return Response(AppReleaseSerializer(newest).data)


class ReleaseDownloadView(APIView):
    @extend_schema(
        tags=TAGS,
        operation_id="api_releases_download_retrieve",
        summary="A signed link to download one release's APK, valid 15 minutes",
        responses={
            200: DownloadResponse,
            404: DetailSerializer,
            503: OpenApiResponse(
                response=DetailSerializer, description="No link could be made, said in words."
            ),
        },
    )
    def get(self, request, release_id):
        release = AppRelease.objects.filter(pk=release_id).first()
        if release is None:
            return Response(
                {"detail": "No download link was made. There is no such release."},
                status=status.HTTP_404_NOT_FOUND,
            )
        signer = signing.get_signer()
        if signer is None:
            return Response(
                {
                    "detail": (
                        "No download link was made. This engine has no APK bucket set "
                        "(LEGION_APK_BUCKET), so it cannot hand out the file."
                    )
                },
                status=status.HTTP_503_SERVICE_UNAVAILABLE,
            )
        expires_at = timezone.now() + signing.DOWNLOAD_TTL
        try:
            url = signer.sign_get(release.object_name, signing.DOWNLOAD_TTL)
        except signing.SigningError as exc:
            return Response(
                {"detail": f"No download link was made: {exc}"},
                status=status.HTTP_503_SERVICE_UNAVAILABLE,
            )
        return Response(
            {
                "url": url,
                "expires_at": expires_at,
                "sha256": release.sha256,
                "size_bytes": release.size_bytes,
                "version_code": release.version_code,
            }
        )
