"""Where a delivered file's bytes wait for the phone (files to phone, ADR 0080).

`FileStore` is the seam, the shape `releases/signing.py` gives the APK bucket: the views ask
`get_store()` and never import Cloud Storage themselves, so tests hand them a fake and a clone with
no bucket never loads the library.

- `GcsFileStore`: the private bucket `LEGION_CLAUDE_FILES_BUCKET` names
  (`deploy/claude_files/setup_bucket.sh`: uniform access, public access prevention, a one-day
  lifecycle delete). Objects are keyed `claude-files/<household>/<task>/<file>/<random>`: never the
  file's name, so nothing in the bucket's own listings or audit logs says what a file is. The engine
  reads them back with its own credentials and streams them to the phone over the household token;
  no signed URL is ever minted, so no link exists that works without the token.
- With no bucket set, `get_store()` returns None and the bytes go in the row's `content` column,
  the way every other task file is kept (ADR 0067). They are deleted on the same two triggers.

Nothing here logs. A failure is a `StorageError` whose message names the step, never the key or a
file name.
"""

from __future__ import annotations

import secrets
from typing import Protocol

from django.conf import settings

KEY_PREFIX = "claude-files"


class StorageError(Exception):
    """The store could not do it. The message says which step, in words."""


class FileStore(Protocol):
    def put(self, key: str, blob: bytes, content_type: str) -> None: ...

    def get(self, key: str) -> bytes: ...

    def delete(self, key: str) -> None: ...


def new_key(household_id, task_id: int, file_id: int) -> str:
    """An object name that says whose and which row, never what the file is."""
    return f"{KEY_PREFIX}/{household_id}/{int(task_id)}/{int(file_id)}/{secrets.token_hex(16)}"


class GcsFileStore:
    def __init__(self, bucket: str):
        self.bucket_name = bucket
        self._bucket = None

    def _get_bucket(self):
        if self._bucket is None:
            try:
                from google.cloud import storage
            except ImportError as exc:
                raise StorageError(f"google-cloud-storage is not installed here ({exc}).") from exc
            self._bucket = storage.Client().bucket(self.bucket_name)
        return self._bucket

    def put(self, key: str, blob: bytes, content_type: str) -> None:
        try:
            self._get_bucket().blob(key).upload_from_string(
                blob, content_type=content_type or "application/octet-stream"
            )
        except StorageError:
            raise
        except Exception as exc:  # noqa: BLE001 - every Google failure becomes words
            raise StorageError(f"the bucket refused the upload ({type(exc).__name__}).") from exc

    def get(self, key: str) -> bytes:
        try:
            return self._get_bucket().blob(key).download_as_bytes()
        except StorageError:
            raise
        except Exception as exc:  # noqa: BLE001
            raise StorageError(f"the bucket refused the read ({type(exc).__name__}).") from exc

    def delete(self, key: str) -> None:
        try:
            from google.api_core.exceptions import NotFound
        except ImportError:  # pragma: no cover - comes with google-cloud-storage
            NotFound = ()  # noqa: N806
        try:
            self._get_bucket().blob(key).delete()
        except NotFound:
            return  # already gone (the lifecycle rule got there first): that is deleted
        except StorageError:
            raise
        except Exception as exc:  # noqa: BLE001
            raise StorageError(f"the bucket refused the delete ({type(exc).__name__}).") from exc


def get_store() -> FileStore | None:
    """The bucket's store, or None when this engine has none (`LEGION_CLAUDE_FILES_BUCKET`)."""
    bucket = getattr(settings, "LEGION_CLAUDE_FILES_BUCKET", "")
    return GcsFileStore(bucket) if bucket else None
