"""Signed download links for the private APK bucket (alfred-ota ticket 04).

`UrlSigner` is the seam: the views ask `get_signer()` for one and never import Cloud Storage
themselves, so tests hand them a fake and a clone with no bucket never loads the library.

`GcsUrlSigner` makes a V4 signed GET URL. On Cloud Run there is no private key: the runtime
account's credentials carry an email and an access token, and `generate_signed_url` given both
signs through the IAM Credentials `signBlob` API (google-cloud-storage 3.17.1,
`_signing._sign_message`). That needs `roles/iam.serviceAccountTokenCreator` on the account itself,
and reading the object needs `roles/storage.objectViewer` on the bucket; `setup_bucket.sh` grants
both.
"""

from __future__ import annotations

from datetime import timedelta
from typing import Protocol

from django.conf import settings

DOWNLOAD_TTL = timedelta(minutes=15)


class SigningError(Exception):
    """The link could not be made. The message says why, in words."""


class UrlSigner(Protocol):
    def sign_get(self, object_name: str, expires: timedelta) -> str: ...


class GcsUrlSigner:
    def __init__(self, bucket: str):
        self.bucket = bucket

    def sign_get(self, object_name: str, expires: timedelta) -> str:
        try:
            import google.auth
            from google.auth.transport.requests import Request
            from google.cloud import storage
        except ImportError as exc:
            raise SigningError(f"google-cloud-storage is not installed here ({exc}).") from exc
        try:
            credentials, project = google.auth.default(
                scopes=["https://www.googleapis.com/auth/cloud-platform"]
            )
            credentials.refresh(Request())
            email = getattr(credentials, "service_account_email", None)
            if not email or not credentials.token:
                raise SigningError(
                    "the engine's Google credentials are not a service account, so they cannot "
                    "sign a link through IAM"
                )
            blob = storage.Client(project=project, credentials=credentials).bucket(
                self.bucket
            ).blob(object_name)
            return blob.generate_signed_url(
                version="v4",
                expiration=expires,
                method="GET",
                service_account_email=email,
                access_token=credentials.token,
            )
        except SigningError:
            raise
        except Exception as exc:  # noqa: BLE001 - every Google failure becomes words
            raise SigningError(f"Google refused to sign it ({type(exc).__name__}: {exc}).") from exc


def get_signer() -> UrlSigner | None:
    """The bucket's signer, or None when this engine has no bucket set (`LEGION_APK_BUCKET`)."""
    bucket = getattr(settings, "LEGION_APK_BUCKET", "")
    return GcsUrlSigner(bucket) if bucket else None
