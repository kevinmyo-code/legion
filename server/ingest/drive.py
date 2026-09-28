"""A small Google Drive v3 client over the standard library (backend-etl
ticket 03; ticket 06's statements watcher reuses it).

**Why not google-api-python-client.** The four things a job does against Drive
(redeem a refresh token, find or create a folder, upload one large file, list
and trash what it made) are six REST calls. The client library would add a
dozen transitive pins to `requirements.txt` for them; `urllib` adds none.

**The credential is the vault's `drive` session** (ticket 02): a refresh token
plus the household's own OAuth client id and secret. Scopes are
`drive.readonly` + `drive.file`, so this client can read anything but can only
write, trash or delete what an app on the same OAuth client created.

**What counts as a refusal.** The token endpoint answering `invalid_grant`
(revoked, expired, a Testing-mode client's 7-day token), or any 401, means a
person has to log in again: `DriveRefused` is raised and the job turns it into
`needs_login` through `vault.refuse_session`. A 403 is NOT a refusal here:
Drive uses 403 for rate limits and quota, and marking the login dead for a
busy afternoon would stop every Drive job until someone re-ran the login
script for no reason. It is an ordinary failure, and the next run retries.
"""
from __future__ import annotations

import json
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path
from typing import BinaryIO

TOKEN_URI = "https://oauth2.googleapis.com/token"
API = "https://www.googleapis.com/drive/v3"
UPLOAD_API = "https://www.googleapis.com/upload/drive/v3"
FOLDER_MIME = "application/vnd.google-apps.folder"

# Resumable chunks must be a multiple of 256 KiB (Drive's own rule). 8 MiB
# keeps a Cloud Run job's memory flat however large the dump grows.
CHUNK_QUANTUM = 256 * 1024
DEFAULT_CHUNK = 32 * CHUNK_QUANTUM

TIMEOUT_SECONDS = 120


@dataclass
class Response:
    status: int
    headers: dict[str, str]
    body: bytes

    def json(self):
        return json.loads(self.body or b"{}")

    def header(self, name: str) -> str | None:
        lowered = name.lower()
        for key, value in self.headers.items():
            if key.lower() == lowered:
                return value
        return None


# (method, url, headers, body) -> Response. Tests replace it; nothing else does.
Transport = Callable[[str, str, dict[str, str], bytes | None], Response]


class _ReturnEverything(urllib.request.HTTPErrorProcessor):
    """Hands every status back to the caller instead of raising, and so never
    follows a redirect either: a resumable upload's `308 Resume Incomplete`
    is a protocol step with no Location header, not a redirect."""

    def http_response(self, request, response):
        return response

    https_response = http_response


_OPENER = urllib.request.build_opener(_ReturnEverything)


def urllib_transport(
    method: str, url: str, headers: dict[str, str], body: bytes | None
) -> Response:
    request = urllib.request.Request(url, data=body, method=method, headers=headers)
    with _OPENER.open(request, timeout=TIMEOUT_SECONDS) as reply:
        return Response(reply.status, dict(reply.headers.items()), reply.read())


class DriveRefused(Exception):
    """Google refused the saved login. The message is what Google did, never
    the token."""


class DriveError(Exception):
    """Drive answered, but not with success. Not a login problem."""


def _describe(response: Response) -> str:
    try:
        payload = response.json()
    except ValueError:
        return f"HTTP {response.status}"
    error = payload.get("error")
    if isinstance(error, dict):
        return f"HTTP {response.status} {error.get('message', '')}".strip()
    if isinstance(error, str):
        return f"HTTP {response.status} {error}"
    return f"HTTP {response.status}"


def _quote(value: str) -> str:
    return value.replace("\\", "\\\\").replace("'", "\\'")


def app_property_clause(key: str, value: str) -> str:
    return f"appProperties has {{ key='{_quote(key)}' and value='{_quote(value)}' }}"


class DriveClient:
    """One authorised Drive session. Every call raises `DriveRefused` on a
    refused login and `DriveError` on anything else that is not success."""

    def __init__(self, secret: dict, *, transport: Transport | None = None):
        self._secret = secret
        self._transport = transport or urllib_transport
        self._access_token: str | None = None

    # -- auth -----------------------------------------------------------------

    def _refresh(self) -> str:
        body = urllib.parse.urlencode(
            {
                "client_id": self._secret["client_id"],
                "client_secret": self._secret["client_secret"],
                "refresh_token": self._secret["refresh_token"],
                "grant_type": "refresh_token",
            }
        ).encode()
        response = self._transport(
            "POST",
            self._secret.get("token_uri") or TOKEN_URI,
            {"Content-Type": "application/x-www-form-urlencoded"},
            body,
        )
        if response.status == 200:
            token = response.json().get("access_token")
            if not token:
                raise DriveError("Google's token endpoint answered 200 with no access token.")
            return token
        described = _describe(response)
        if response.status == 401 or "invalid_grant" in described:
            raise DriveRefused(f"Google refused the refresh token ({described})")
        raise DriveError(f"Google's token endpoint failed ({described})")

    def _token(self) -> str:
        if self._access_token is None:
            self._access_token = self._refresh()
        return self._access_token

    def _call(
        self,
        method: str,
        url: str,
        *,
        headers: dict[str, str] | None = None,
        body: bytes | None = None,
        ok: tuple[int, ...] = (200,),
    ) -> Response:
        all_headers = {"Authorization": f"Bearer {self._token()}", **(headers or {})}
        response = self._transport(method, url, all_headers, body)
        if response.status == 401:
            raise DriveRefused(f"Drive answered {_describe(response)}")
        if response.status not in ok:
            raise DriveError(f"Drive {method} failed: {_describe(response)}")
        return response

    # -- files ----------------------------------------------------------------

    def list_files(self, query: str, *, order_by: str | None = None) -> list[dict]:
        """Every file matching `query`, following pages. Each dict has id,
        name, mimeType, parents, appProperties, createdTime, modifiedTime and
        size (absent for a Google-native doc, which has no bytes of its own)."""
        files: list[dict] = []
        page_token = None
        while True:
            params = {
                "q": query,
                "fields": (
                    "nextPageToken,files(id,name,mimeType,parents,appProperties,"
                    "createdTime,modifiedTime,size)"
                ),
                "pageSize": "1000",
                "spaces": "drive",
            }
            if order_by:
                params["orderBy"] = order_by
            if page_token:
                params["pageToken"] = page_token
            response = self._call("GET", f"{API}/files?{urllib.parse.urlencode(params)}")
            payload = response.json()
            files.extend(payload.get("files", []))
            page_token = payload.get("nextPageToken")
            if not page_token:
                return files

    def create_folder(
        self, name: str, *, parent: str | None, app_properties: dict[str, str]
    ) -> dict:
        metadata: dict = {"name": name, "mimeType": FOLDER_MIME, "appProperties": app_properties}
        if parent:
            metadata["parents"] = [parent]
        response = self._call(
            "POST",
            f"{API}/files?fields=id,name,appProperties,parents",
            headers={"Content-Type": "application/json; charset=UTF-8"},
            body=json.dumps(metadata).encode(),
        )
        return response.json()

    def trash(self, file_id: str) -> None:
        """To the trash, not a permanent delete: Drive keeps it 30 days, so a
        retention bug is recoverable by hand."""
        self._call(
            "PATCH",
            f"{API}/files/{urllib.parse.quote(file_id)}?fields=id,trashed",
            headers={"Content-Type": "application/json; charset=UTF-8"},
            body=json.dumps({"trashed": True}).encode(),
        )

    def upload_resumable(
        self,
        path: Path,
        *,
        name: str,
        parent: str,
        mime_type: str,
        app_properties: dict[str, str],
        chunk_size: int = DEFAULT_CHUNK,
    ) -> dict:
        """Upload `path` as a new file, in chunks, through Drive's resumable
        protocol. Returns the created file's metadata (id, name, size)."""
        if chunk_size <= 0 or chunk_size % CHUNK_QUANTUM:
            raise ValueError(f"chunk_size must be a positive multiple of {CHUNK_QUANTUM}.")
        total = path.stat().st_size
        metadata = {"name": name, "parents": [parent], "appProperties": app_properties}
        start = self._call(
            "POST",
            f"{UPLOAD_API}/files?uploadType=resumable&fields=id,name,size",
            headers={
                "Content-Type": "application/json; charset=UTF-8",
                "X-Upload-Content-Type": mime_type,
                "X-Upload-Content-Length": str(total),
            },
            body=json.dumps(metadata).encode(),
        )
        session = start.header("Location")
        if not session:
            raise DriveError("Drive opened a resumable upload but returned no session URL.")
        with path.open("rb") as handle:
            return self._send_chunks(session, handle, total, chunk_size)

    def _send_chunks(self, session: str, handle: BinaryIO, total: int, chunk: int) -> dict:
        offset = 0
        if total == 0:
            response = self._call(
                "PUT",
                session,
                headers={"Content-Range": "bytes */0"},
                body=b"",
                ok=(200, 201),
            )
            return response.json()
        while True:
            handle.seek(offset)
            data = handle.read(chunk)
            end = offset + len(data) - 1
            response = self._call(
                "PUT",
                session,
                headers={"Content-Range": f"bytes {offset}-{end}/{total}"},
                body=data,
                ok=(200, 201, 308),
            )
            if response.status in (200, 201):
                return response.json()
            # 308: Drive says how much it has. Resume from there, not from
            # where this loop thinks it is: a chunk can land partially.
            received = response.header("Range")
            if received:
                offset = int(received.rsplit("-", 1)[1]) + 1
            else:
                offset = 0
            if offset >= total:
                raise DriveError(
                    "Drive reported the whole file received but never completed the upload."
                )

    def download(self, file_id: str, destination: Path) -> int:
        """Write the file's bytes to `destination`; returns the byte count."""
        response = self._call("GET", f"{API}/files/{urllib.parse.quote(file_id)}?alt=media")
        destination.write_bytes(response.body)
        return len(response.body)

    def download_bytes(self, file_id: str, *, max_bytes: int) -> bytes:
        """The file's bytes, refused above `max_bytes` (ticket 06: a Cloud Run
        job has 512Mi). The caller checks the listed size first so an
        oversized file is never fetched; this is the backstop for a listing
        that did not state one."""
        response = self._call("GET", f"{API}/files/{urllib.parse.quote(file_id)}?alt=media")
        if len(response.body) > max_bytes:
            raise DriveError(
                f"Drive returned {len(response.body)} bytes, over the {max_bytes}-byte limit."
            )
        return response.body
