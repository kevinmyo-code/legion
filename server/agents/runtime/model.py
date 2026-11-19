"""The model-provider seam (plan section 13.6): `ModelClient.stream(req)` yields model events.

One real client, `GeminiClient`: REST `streamGenerateContent?alt=sse`, the request the phone's
cascade already sends (`service/cascade/GeminiStream.kt`): system instruction, contents, function
declarations, `maxOutputTokens`, and on the last round `functionCallingConfig: NONE`. Every part
the model returns is kept verbatim (`PartEvent`), so a thought signature goes back on the next
round exactly as it came. The key comes from a callable the caller hands in, goes into one
`x-goog-api-key` header, and is never put in a URL, an error or a log line (ADR 0056).

The transport is injected: tests use `FakeModel` / `CassetteModel` and no test reaches Google.
`RecordingModel` wraps a real client and keeps every round's events for the cassette.

A second provider would be a second class with the same `stream`; it needs a translator for
contents, tool schemas and thought signatures, and is not built (plan section 13.6).
"""

from __future__ import annotations

import http.client
import json
import ssl
import threading
import time
import urllib.parse
from collections.abc import Callable, Iterable, Iterator
from dataclasses import dataclass, field
from typing import Protocol


API = "https://generativelanguage.googleapis.com/v1beta"


# =============================================================================
# Events and the request
# =============================================================================


@dataclass(frozen=True)
class TextDelta:
    text: str


@dataclass(frozen=True)
class ToolCallEvent:
    id: str
    name: str
    args: dict


@dataclass(frozen=True)
class PartEvent:
    """One raw part as the model sent it: what goes back into history, verbatim."""

    part: dict


@dataclass(frozen=True)
class Usage:
    prompt_tokens: int = 0
    cached_tokens: int = 0
    # Output plus thinking: both are billed at the output rate.
    output_tokens: int = 0
    total_tokens: int = 0


@dataclass(frozen=True)
class Finish:
    reason: str


@dataclass(frozen=True)
class Waiting:
    """Not from the model: a client that reads the model on another thread says "still nothing"
    every so often, so the turn can act on time while a round has not started answering
    (`assistant.cascade.Ticking`). The loop passes it up as a `waiting` event; nothing else."""


ModelEvent = TextDelta | ToolCallEvent | PartEvent | Usage | Finish


class ModelFailure(Exception):
    """The model could not be asked or did not answer. `str(exc)` is safe to log: it never holds
    the key, the prompt or the reply."""


@dataclass(frozen=True)
class ModelRequest:
    model: str
    system: str
    contents: list[dict]
    declarations: list[dict] = field(default_factory=list)
    # False on the last round: declarations stay (the prefix is unchanged) but calling is off.
    allow_tools: bool = True
    timeout_s: float = 8.0
    max_output_tokens: int = 800
    # An explicit cache holding `system` and `declarations` (probe and the generalist).
    cached_content: str | None = None


class ModelClient(Protocol):
    def stream(self, req: ModelRequest) -> Iterator[ModelEvent]: ...


# =============================================================================
# Serialisation (cassettes and checkpoints)
# =============================================================================


def event_to_json(ev: ModelEvent) -> dict:
    if isinstance(ev, TextDelta):
        return {"t": "text", "text": ev.text}
    if isinstance(ev, ToolCallEvent):
        return {"t": "call", "id": ev.id, "name": ev.name, "args": ev.args}
    if isinstance(ev, PartEvent):
        return {"t": "part", "part": ev.part}
    if isinstance(ev, Usage):
        return {
            "t": "usage",
            "prompt": ev.prompt_tokens,
            "cached": ev.cached_tokens,
            "output": ev.output_tokens,
            "total": ev.total_tokens,
        }
    return {"t": "finish", "reason": ev.reason}


def event_from_json(raw: dict) -> ModelEvent:
    kind = raw["t"]
    if kind == "text":
        return TextDelta(raw["text"])
    if kind == "call":
        return ToolCallEvent(raw["id"], raw["name"], raw["args"])
    if kind == "part":
        return PartEvent(raw["part"])
    if kind == "usage":
        return Usage(raw["prompt"], raw["cached"], raw["output"], raw["total"])
    if kind == "finish":
        return Finish(raw["reason"])
    raise ValueError(f"unknown cassette event {kind!r}")


# =============================================================================
# Gemini over REST
# =============================================================================


def _error_words_of(raw: bytes) -> str:
    """Google's error message from a response body, cut short; "" when there is none."""
    try:
        message = json.loads(raw.decode("utf-8", "replace"))["error"]["message"]
    except Exception:  # noqa: BLE001 - no body, or not Google's shape: the code alone
        return ""
    return f": {str(message)[:200]}" if message else ""


def _connect(host: str, timeout: float) -> http.client.HTTPSConnection:
    """A new HTTPS connection (a test's seam)."""
    return http.client.HTTPSConnection(host, timeout=timeout, context=ssl.create_default_context())


class _Pool:
    """Kept-open HTTPS connections, shared by the whole worker process (perf/try-faster-first-word
    fix 3). Per process, not per conversation: a turn's speech runs on threads made for that turn,
    and the next turn of the same conversation may land on another worker, so only the process
    outlives both. Measured from the laptop: a new connection costs ~60 to 90 ms before a byte is
    sent; a spoken turn makes 3 to 6 calls in a row. A connection is taken by one call at a time
    and handed back only after its response was read to the end."""

    IDLE_S = 45.0
    MAX_IDLE = 8

    def __init__(self, clock: Callable[[], float] = time.monotonic):
        self._idle: dict[str, list[tuple[http.client.HTTPSConnection, float]]] = {}
        self._lock = threading.Lock()
        self._clock = clock

    def take(self, host: str, timeout: float) -> tuple[http.client.HTTPSConnection, bool]:
        """(a connection, whether it was kept open from an earlier call)."""
        now = self._clock()
        stale = []
        conn = None
        with self._lock:
            stack = self._idle.get(host, [])
            while stack:
                candidate, since = stack.pop()
                if now - since < self.IDLE_S:
                    conn = candidate
                    break
                stale.append(candidate)
        for old in stale:
            old.close()
        if conn is None:
            return _connect(host, timeout), False
        conn.timeout = timeout
        if conn.sock is not None:
            conn.sock.settimeout(timeout)
        return conn, True

    def give(self, host: str, conn: http.client.HTTPSConnection) -> None:
        with self._lock:
            stack = self._idle.setdefault(host, [])
            if len(stack) < self.MAX_IDLE:
                stack.append((conn, self._clock()))
                return
        conn.close()


POOL = _Pool()

# A kept-open connection the server has already closed fails like this before any reply: that one
# call is sent again on a new connection. Nothing was answered, so nothing is sent twice.
_STALE = (
    http.client.RemoteDisconnected,
    http.client.CannotSendRequest,
    ConnectionResetError,
    ConnectionAbortedError,
    BrokenPipeError,
)


class UrllibTransport:
    """The stdlib transport (`http.client` over kept-open connections, `POOL`). `lines` streams the
    body line by line; `request` is one plain call. Both raise `ModelFailure` with the HTTP status
    only. A connection is reused only after its response was read to the end; one cut off, failed
    or abandoned is closed."""

    def _open(self, method: str, url: str, headers: dict, body: bytes | None, timeout: float):
        parts = urllib.parse.urlsplit(url)
        path = parts.path + (f"?{parts.query}" if parts.query else "")
        for attempt in (0, 1):
            conn, reused = POOL.take(parts.netloc, timeout)
            try:
                conn.request(method, path, body=body, headers=headers)
                return conn, conn.getresponse(), parts.netloc
            except _STALE as exc:
                conn.close()
                if reused and attempt == 0:
                    continue
                raise ModelFailure(f"Gemini could not be reached ({type(exc).__name__})") from None
            except (OSError, http.client.HTTPException) as exc:
                conn.close()
                raise ModelFailure(f"Gemini could not be reached ({type(exc).__name__})") from None
        raise AssertionError("unreachable")

    def lines(self, url: str, headers: dict, body: bytes, timeout: float) -> Iterable[bytes]:
        conn, resp, host = self._open("POST", url, headers, body, timeout)
        if resp.status >= 400:
            raw = _read_quietly(resp, 2048)
            conn.close()
            raise ModelFailure(f"Gemini answered HTTP {resp.status}{_error_words_of(raw)}")
        whole = False
        try:
            yield from resp
            whole = True
        except (OSError, http.client.HTTPException) as exc:
            # A timeout, a reset connection, a cut-off body or a TLS error mid-response: the caller
            # retries or says it in words (blind review 2026-11-17, round 2 finding 6). `OSError`
            # covers `TimeoutError`, `ConnectionError` and `ssl.SSLError`; `IncompleteRead` is an
            # `HTTPException`.
            raise ModelFailure(f"Gemini stopped answering ({type(exc).__name__})") from None
        finally:
            if whole and not resp.will_close:
                POOL.give(host, conn)
            else:
                # Abandoned mid-stream (a cancel), failed, or the server said it will close.
                conn.close()

    def request(
        self, method: str, url: str, headers: dict, body: bytes | None, timeout: float
    ) -> tuple[int, bytes]:
        conn, resp, host = self._open(method, url, headers, body, timeout)
        try:
            raw = resp.read()
        except (OSError, http.client.HTTPException) as exc:
            conn.close()
            raise ModelFailure(f"Gemini could not be reached ({type(exc).__name__})") from None
        if resp.status >= 400:
            conn.close()
            return resp.status, b""
        if resp.will_close:
            conn.close()
        else:
            POOL.give(host, conn)
        return resp.status, raw


def _read_quietly(resp, size: int) -> bytes:
    try:
        return resp.read(size)
    except (OSError, http.client.HTTPException):
        return b""


def request_body(req: ModelRequest) -> dict:
    body: dict = {
        "contents": req.contents,
        "generationConfig": {"maxOutputTokens": req.max_output_tokens},
    }
    if req.cached_content:
        body["cachedContent"] = req.cached_content
    else:
        body["systemInstruction"] = {"parts": [{"text": req.system}]}
        if req.declarations:
            body["tools"] = [{"functionDeclarations": req.declarations}]
    if req.declarations and not req.allow_tools:
        body["toolConfig"] = {"functionCallingConfig": {"mode": "NONE"}}
    return body


def parse_sse(lines: Iterable[bytes]) -> Iterator[ModelEvent]:
    """Gemini's `alt=sse` stream: one JSON object per `data:` line."""
    usage = None
    finish = ""
    for raw in lines:
        line = raw.decode("utf-8", "replace").strip() if isinstance(raw, bytes) else raw.strip()
        if not line.startswith("data:"):
            continue
        try:
            ev = json.loads(line[5:].strip())
        except ValueError:
            continue
        if isinstance(ev.get("error"), dict):
            raise ModelFailure(f"Gemini answered an error ({ev['error'].get('code', '?')})")
        if isinstance(ev.get("usageMetadata"), dict):
            usage = ev["usageMetadata"]
        block = (ev.get("promptFeedback") or {}).get("blockReason")
        if block:
            raise ModelFailure(f"Gemini blocked the prompt ({block})")
        cands = ev.get("candidates") or []
        cand = cands[0] if cands and isinstance(cands[0], dict) else {}
        if cand.get("finishReason"):
            finish = cand["finishReason"]
        for part in (cand.get("content") or {}).get("parts") or []:
            if not isinstance(part, dict):
                continue
            yield PartEvent(part)
            call = part.get("functionCall")
            if isinstance(call, dict) and call.get("name"):
                yield ToolCallEvent(str(call.get("id") or ""), call["name"], call.get("args") or {})
            elif isinstance(part.get("text"), str) and not part.get("thought") and part["text"]:
                yield TextDelta(part["text"])
    if usage is not None:
        output = int(usage.get("candidatesTokenCount") or 0) + int(
            usage.get("thoughtsTokenCount") or 0
        )
        yield Usage(
            prompt_tokens=int(usage.get("promptTokenCount") or 0)
            + int(usage.get("toolUsePromptTokenCount") or 0),
            cached_tokens=int(usage.get("cachedContentTokenCount") or 0),
            output_tokens=output,
            total_tokens=int(usage.get("totalTokenCount") or 0),
        )
    yield Finish(finish or "STOP")


class GeminiClient:
    def __init__(self, key: Callable[[], str | None], transport=None, api: str = API):
        self._key = key
        self._transport = transport or UrllibTransport()
        self._api = api

    def _headers(self) -> dict:
        key = self._key()
        if not key:
            raise ModelFailure("no Gemini key is configured")
        return {"Content-Type": "application/json", "x-goog-api-key": key}

    def stream(self, req: ModelRequest) -> Iterator[ModelEvent]:
        url = f"{self._api}/models/{req.model}:streamGenerateContent?alt=sse"
        body = json.dumps(request_body(req)).encode()
        yield from parse_sse(self._transport.lines(url, self._headers(), body, req.timeout_s))

    def create_cache(
        self, model: str, system: str, declarations: list[dict], ttl_s: int = 300
    ) -> str:
        """An explicit cache of `system` + `declarations`; returns its name."""
        body = {
            "model": f"models/{model}",
            "displayName": "legion-agents",
            "systemInstruction": {"parts": [{"text": system}]},
            "ttl": f"{ttl_s}s",
        }
        if declarations:
            body["tools"] = [{"functionDeclarations": declarations}]
        status, raw = self._transport.request(
            "POST", f"{self._api}/cachedContents", self._headers(), json.dumps(body).encode(), 20
        )
        if status != 200:
            raise ModelFailure(f"Gemini refused the cache (HTTP {status})")
        return json.loads(raw)["name"]

    def delete_cache(self, name: str) -> int:
        status, _ = self._transport.request(
            "DELETE", f"{self._api}/{name}", self._headers(), None, 20
        )
        return status


# =============================================================================
# Fakes, recording and replay (no network)
# =============================================================================


class FakeModel:
    """Scripted rounds: each `stream` call serves the next list of events. Records the requests."""

    def __init__(self, rounds: list[list[ModelEvent]]):
        self.rounds = list(rounds)
        self.requests: list[ModelRequest] = []

    def stream(self, req: ModelRequest) -> Iterator[ModelEvent]:
        self.requests.append(req)
        if not self.rounds:
            raise ModelFailure("the fake model has no more rounds")
        yield from self.rounds.pop(0)


def say(text: str, prompt_tokens: int = 100, output_tokens: int = 10) -> list[ModelEvent]:
    """A fake round that answers `text` with no tool call."""
    return [
        PartEvent({"text": text}),
        TextDelta(text),
        Usage(prompt_tokens, 0, output_tokens, prompt_tokens + output_tokens),
        Finish("STOP"),
    ]


def call(name: str, args: dict | None = None, call_id: str = "") -> list[ModelEvent]:
    """A fake round that calls one tool."""
    args = args or {}
    return [
        PartEvent({"functionCall": {"name": name, "args": args}}),
        ToolCallEvent(call_id, name, args),
        Usage(100, 0, 5, 105),
        Finish("STOP"),
    ]


class RecordingModel:
    """Wraps a real client; every round's events land in `rounds` (JSON) for a cassette."""

    def __init__(self, inner: ModelClient):
        self.inner = inner
        self.rounds: list[list[dict]] = []

    def stream(self, req: ModelRequest) -> Iterator[ModelEvent]:
        kept: list[dict] = []
        self.rounds.append(kept)
        for ev in self.inner.stream(req):
            kept.append(event_to_json(ev))
            yield ev


class CassetteModel:
    """Replays recorded rounds in order. A round past the recording fails as not recorded."""

    def __init__(self, rounds: list[list[dict]]):
        self.rounds = [list(r) for r in rounds]
        self.requests: list[ModelRequest] = []

    def stream(self, req: ModelRequest) -> Iterator[ModelEvent]:
        self.requests.append(req)
        if not self.rounds:
            raise ModelFailure("this round is not in the recording")
        for raw in self.rounds.pop(0):
            yield event_from_json(raw)
