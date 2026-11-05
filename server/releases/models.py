"""`AppRelease`: one published build of a LEGION client (alfred-ota ticket 04).

**Engine-wide, not household-scoped, on purpose.** A release is a build of the app, not a row of
anyone's life: every member of every household on this engine reads the same "newest phone
build", and the APK it names holds no household data. So the table has no `household_id`, is NOT
in `household.tenancy.TENANT_TABLES`, and is named in `household.tenancy.ENGINE_WIDE_TABLES`
instead, which `tests/test_tenancy.py` checks. It lives in the `django` schema (no `db_table`),
like `demo.DemoCode`, the other engine-wide table. One engine holds one household's builds (the
map's words); a second household on the engine would see the same releases, and its phone would
refuse any APK not signed by the pinned key (ticket 05) as Android itself does.

The APK itself is in a private bucket (`deploy/release/setup_bucket.sh`); `object_name` says where
and is never sent to a client. A client downloads through a V4 signed URL that lives 15 minutes
(`releases/signing.py`). `sha256` and `signer_sha256` are what the phone verifies before it asks
to install (ticket 05).
"""

from __future__ import annotations

import uuid

from django.conf import settings
from django.db import models
from django.utils import timezone

PHONE = "phone"
HEADUNIT = "headunit"
APPS: tuple[str, ...] = (PHONE, HEADUNIT)

HEX64 = r"^[0-9a-f]{64}$"


class AppRelease(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    app = models.TextField(choices=[(a, a) for a in APPS])
    version_code = models.IntegerField()
    version_name = models.TextField()
    sha256 = models.CharField(max_length=64)
    size_bytes = models.BigIntegerField()
    object_name = models.TextField()
    notes = models.TextField(blank=True, default="")
    signer_sha256 = models.CharField(max_length=64)
    published_at = models.DateTimeField(default=timezone.now)
    published_by = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        null=True,
        blank=True,
        on_delete=models.SET_NULL,
        related_name="+",
    )

    class Meta:
        constraints = [
            models.UniqueConstraint(
                fields=["app", "version_code"], name="app_release_one_per_code"
            ),
            models.CheckConstraint(condition=models.Q(app__in=APPS), name="app_release_app_valid"),
            models.CheckConstraint(
                condition=models.Q(version_code__gt=0), name="app_release_version_code_positive"
            ),
            models.CheckConstraint(
                condition=models.Q(size_bytes__gt=0), name="app_release_size_positive"
            ),
            models.CheckConstraint(
                condition=models.Q(sha256__regex=HEX64), name="app_release_sha256_hex"
            ),
            models.CheckConstraint(
                condition=models.Q(signer_sha256__regex=HEX64), name="app_release_signer_hex"
            ),
        ]

    def __str__(self) -> str:
        return f"{self.app} {self.version_code} ({self.version_name})"
