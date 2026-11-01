"""The worker's side of the engine API (map claude-session, "The API"; engine is ticket 03).

All under `<engine>/api/claude/`, `Authorization: Token <device key>`. Stdlib urllib only.
Raises `EngineDown` for anything that should make the worker back off (unreachable, timeout,
5xx, 401/403), `EngineError` for a 4xx that is about one request.
"""
from __future__ import annotations

import json
import mimetypes
import uuid
import urllib.error
import urllib.request
from pathlib import Path


class EngineDown(Exception):
    pass


class EngineError(Exception):
    def __init__(self, status: int, message: str = ""):
        super().__init__(f"HTTP {status}")
        self.status = status
        self.message = message


class EngineClient:
    def __init__(self, base_url: str, token: str, timeout: float = 15.0):
        self.base = base_url.rstrip("/") + "/api/claude/"
        self.token = token
        self.timeout = timeout

    def _request(self, method: str, path: str, body: bytes | None = None,
                 content_type: str | None = None):
        req = urllib.request.Request(self.base + path, data=body, method=method)
        req.add_header("Authorization", f"Token {self.token}")
        req.add_header("Accept", "application/json")
        if content_type:
            req.add_header("Content-Type", content_type)
        if body is None and method == "POST":
            req.add_header("Content-Length", "0")  # see household LogoutView: 411 otherwise
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                raw = resp.read()
                if resp.status == 204 or not raw:
                    return resp.status, None
                return resp.status, json.loads(raw.decode("utf-8"))
        except urllib.error.HTTPError as e:
            if e.code >= 500 or e.code in (401, 403, 429):
                raise EngineDown(f"HTTP {e.code}") from None
            raise EngineError(e.code) from None
        except (urllib.error.URLError, TimeoutError, OSError, ValueError) as e:
            raise EngineDown(type(e).__name__) from None

    def _json(self, method: str, path: str, payload) -> object:
        _, data = self._request(method, path, json.dumps(payload).encode("utf-8"), "application/json")
        return data

    def heartbeat(self, payload: dict) -> dict:
        return self._json("POST", "machines/heartbeat/", payload) or {}

    def claim(self, name: str) -> list[dict]:
        data = self._json("POST", "tasks/claim/", {"name": name})
        if data is None:
            return []
        if isinstance(data, dict):  # tolerate {"tasks": [...]} or a single task
            data = data.get("tasks", [data] if "id" in data else [])
        return list(data)

    def finish(self, task_id: int, payload: dict) -> None:
        self._json("POST", f"tasks/{int(task_id)}/finish/", payload)

    def upload(self, task_id: int, file_id, path: Path) -> None:
        boundary = "----legion" + uuid.uuid4().hex
        path = Path(path)
        ctype = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
        head = (
            f"--{boundary}\r\nContent-Disposition: form-data; name=\"file_id\"\r\n\r\n{file_id}\r\n"
            f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; "
            f"filename=\"{path.name}\"\r\nContent-Type: {ctype}\r\n\r\n"
        ).encode("utf-8")
        tail = f"\r\n--{boundary}--\r\n".encode("utf-8")
        body = head + path.read_bytes() + tail
        self._request("POST", f"tasks/{int(task_id)}/files/", body,
                      f"multipart/form-data; boundary={boundary}")
