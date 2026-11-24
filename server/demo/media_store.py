"""Where the recruiter page's media files' bytes live (Supabase egress cut 4, 2026-11-23).

The MP4s and GLBs were stored as `demo.models.RecruiterMediaFile.data`, a bytea column, and every
request read a 2 MiB window back out of Postgres. Bytea leaves Postgres hex-encoded, so each
window cost about 4 MiB of database egress, roughly 2 GB a month against a 5 GB quota. The bytes
sit in a private bucket now and Postgres carries only the name of the object.

`MediaStore` is the seam, the shape `releases/signing.py` and `claude_session/storage.py` give
their buckets: the loader and the view ask `get_store()` and never import Cloud Storage
themselves, so tests hand them a fake and an engine with no bucket never loads the library.

**The engine reads the object and streams the bytes, and no signed URL or redirect is minted.**
Two reasons, and either alone decides it. The page's own Content-Security-Policy is
`media-src 'self'` and `img-src 'self' data: blob:` (`legion/csp.py`), so a `<video>` or `<img>`
pointed at `storage.googleapis.com` is refused by the browser, report-only today and enforced on
the switch. And a signed URL is a link that works without a session, which is the one thing this
route has never allowed: the files are private like the resume, to a visit or an owner. The cut
is egress out of POSTGRES; Cloud Run to the visitor is Google egress, and Cloud Storage to Cloud
Run in the same region is free.

**Its own bucket, not one of the three that exist.** The Claude-file bucket deletes every object a
day old (`deploy/claude_files/setup_bucket.sh`) and these files are the live page; the APK bucket
holds signed release downloads and shares nothing with them but a storage class; and the clips
bucket the Pages site uses has `allUsers` on it, which would publish them.

With no bucket set (`LEGION_DEMO_MEDIA_BUCKET`), `get_store()` returns None and everything works
exactly as it did: the loader writes only the column and the view reads only the column.

**The column is still written this release**, so a rolled-back revision, a bucket that is not
reachable or an IAM grant that was never made degrades to the old behaviour instead of a dead
demo page. It can go one release after the bucket has served the live page: drop the writes in
`demo.content._store_media`, then the column.

The object name is the file's own sha256 and name, so a reload of the same bytes names the same
object and nothing is ever overwritten with different content. Nothing here logs; a failure is a
`MediaStoreError` whose message names the step.
"""

from __future__ import annotations

from functools import lru_cache
from typing import Protocol

from django.conf import settings

KEY_PREFIX = "demo-media"


class MediaStoreError(Exception):
    """The bucket could not do it. The message says which step, in words."""


class MediaStore(Protocol):
    def put(self, key: str, blob: bytes, content_type: str) -> None: ...

    def get_range(self, key: str, start: int, length: int) -> bytes: ...


def object_key(sha256: str, name: str) -> str:
    """The object a file's bytes live in: its own hash, then its name so a listing is readable.
    Content-addressed, so a load that changes nothing writes the same object again."""
    return f"{KEY_PREFIX}/{sha256}/{name}"


class GcsMediaStore:
    def __init__(self, bucket: str):
        self.bucket_name = bucket
        self._bucket = None

    def _get_bucket(self):
        if self._bucket is None:
            try:
                from google.cloud import storage
            except ImportError as exc:
                raise MediaStoreError(
                    f"google-cloud-storage is not installed here ({exc})."
                ) from exc
            self._bucket = storage.Client().bucket(self.bucket_name)
        return self._bucket

    def put(self, key: str, blob: bytes, content_type: str) -> None:
        try:
            self._get_bucket().blob(key).upload_from_string(
                blob, content_type=content_type or "application/octet-stream"
            )
        except MediaStoreError:
            raise
        except Exception as exc:  # noqa: BLE001 - every Google failure becomes words
            raise MediaStoreError(f"the bucket refused the upload ({type(exc).__name__}).") from exc

    def get_range(self, key: str, start: int, length: int) -> bytes:
        """`length` bytes from `start`. `end` is the LAST byte wanted, as in an HTTP range."""
        try:
            return self._get_bucket().blob(key).download_as_bytes(
                start=start, end=start + length - 1
            )
        except MediaStoreError:
            raise
        except Exception as exc:  # noqa: BLE001
            raise MediaStoreError(f"the bucket refused the read ({type(exc).__name__}).") from exc


@lru_cache(maxsize=4)
def _store_for(bucket: str) -> GcsMediaStore:
    """One store, and so one Cloud Storage client, per bucket name: a media request is 2 MiB of
    one file and building a client each time would cost a credentials lookup per window."""
    return GcsMediaStore(bucket)


def get_store() -> MediaStore | None:
    """The bucket's store, or None when this engine has none (`LEGION_DEMO_MEDIA_BUCKET`)."""
    bucket = getattr(settings, "LEGION_DEMO_MEDIA_BUCKET", "")
    return _store_for(bucket) if bucket else None
