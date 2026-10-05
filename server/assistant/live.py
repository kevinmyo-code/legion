"""Gemini Live for a browser: the locked setup, and the ephemeral token that carries it.

web-assistant tickets 01 and 07, ADR 0056: the household's key stays on the
engine; the engine mints a short-lived token per conversation and the browser
connects straight to Google with it. Sourced in
`.scratch/web-assistant/research/01-live-from-an-iphone-pwa.md` section 1.

## The lock

The token carries the WHOLE setup - model, voice, system prompt, tool
declarations, transcription, turn-taking - and no `fieldMask`. Per the API
reference (`AuthToken.fieldMask`): with a setup present and no mask, "the
effective setup is taken entirely from `bidiGenerateContentSetup` in this
request. The setup message from the Live API connection is ignored." So a
browser cannot add, drop or reword a tool, unstamp `BLOCKING`, or edit the
prompt (ticket 02).

**What that costs, chosen with open eyes.** Session resumption lives inside
the setup, so with a full lock the browser cannot hand back a resumption
handle. Kevin ruled (ticket 02, 2026-10-04) that a screen lock or app switch
ENDS the conversation, so resumption is not needed for that. It also means a
conversation ends at Google's per-connection limit (about 10 minutes,
announced by `goAway`): the browser starts a fresh one with a fresh mint. The
research's alternative - a field mask that leaves `sessionResumption`
client-set - is `reasoned`, never tested; switching to it is one constant
here (`FIELD_MASK`) plus a test, the day a longer conversation matters.

## The phone, mirrored

`app/.../service/GeminiLiveSession.kt` `buildSetupJson`: the same model id,
audio out, input transcription on, compression 32,000 / 16,000, VAD timings
and sensitivities, and `behavior: BLOCKING` on every declaration (CLAUDE.md
section 7: an outcome verb may follow only a tool result the model has SEEN,
and Live 3.8 defaults to NON_BLOCKING). Output transcription is ON here, always:
the web shows the assistant's words as text (ticket 05). No `googleSearch`:
ticket 04's web surface is the engine's tools, and the prompt says there is
no search here.
"""

from __future__ import annotations

import json
import urllib.error
import urllib.request
from collections.abc import Callable
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta

from ingest.drive import _OPENER, Response

MODEL = "models/gemini-3.8-live"
AUTH_TOKENS_URL = "https://generativelanguage.googleapis.com/v1beta/auth_tokens"
WS_URL = (
    "wss://generativelanguage.googleapis.com/ws/"
    "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained"
)
INPUT_AUDIO_MIME = "audio/pcm;rate=16000"
OUTPUT_AUDIO_RATE = 24000

# One conversation per mint; resuming is not a new use (and is not used, see
# the module doc). `expireTime` mirrors the phone's 30-minute conversation
# backstop; `newSessionExpireTime` is how long the browser has to connect.
USES = 1
TOKEN_LIFETIME = timedelta(minutes=30)
CONNECT_WINDOW = timedelta(seconds=60)
# None = lock everything (no mask sent). See the module doc.
FIELD_MASK: str | None = None

COMPRESSION_TRIGGER_TOKENS = 32_000
COMPRESSION_TARGET_TOKENS = 16_000
VAD_SILENCE_MS = 900
VAD_PREFIX_PADDING_MS = 300
VAD_START_SENSITIVITY = "START_SENSITIVITY_LOW"
VAD_END_SENSITIVITY = "END_SENSITIVITY_HIGH"

MINT_TIMEOUT_SECONDS = 20

# =============================================================================
# Tool declarations
# =============================================================================

# The Gemini `Schema` subset the phone's own declarations use (`LiveToolbox.fn`,
# `EngineToolbox`): type, description, enum, properties, required, items. The
# registry's JSON Schemas also carry `additionalProperties`, `format`, and
# numeric and length bounds; those are DROPPED from what Gemini is told, never
# from what the engine accepts - `run_tool` validates every call against the
# full JSON Schema before anything runs, and refuses in words.
_KEPT_KEYS = ("type", "description", "enum", "required")


def gemini_schema(schema: dict) -> dict:
    out: dict = {key: schema[key] for key in _KEPT_KEYS if key in schema}
    if "properties" in schema:
        out["properties"] = {
            name: gemini_schema(prop) for name, prop in schema["properties"].items()
        }
    if "items" in schema:
        out["items"] = gemini_schema(schema["items"])
    if schema.get("format") == "uuid":
        out["description"] = (out.get("description", "") + " An id (a uuid).").strip()
    return out


def function_declarations(tools, describe=lambda tool: tool.description) -> list[dict]:
    return [
        {
            "name": tool.name,
            "description": describe(tool),
            "parameters": gemini_schema(tool.input_schema),
            "behavior": "BLOCKING",
        }
        for tool in tools
    ]


# =============================================================================
# The setup and the token request
# =============================================================================


def build_setup(*, system_prompt: str, voice_name: str, declarations: list[dict]) -> dict:
    """A `BidiGenerateContentSetup`, the shape the phone's `buildSetupJson`
    sends inside `{"setup": ...}`."""
    return {
        "model": MODEL,
        "generationConfig": {
            "responseModalities": ["AUDIO"],
            "speechConfig": {"voiceConfig": {"prebuiltVoiceConfig": {"voiceName": voice_name}}},
        },
        "systemInstruction": {"parts": [{"text": system_prompt}]},
        "tools": [{"functionDeclarations": declarations}],
        "realtimeInputConfig": {
            "automaticActivityDetection": {
                "disabled": False,
                "silenceDurationMs": VAD_SILENCE_MS,
                "prefixPaddingMs": VAD_PREFIX_PADDING_MS,
                "startOfSpeechSensitivity": VAD_START_SENSITIVITY,
                "endOfSpeechSensitivity": VAD_END_SENSITIVITY,
            }
        },
        "contextWindowCompression": {
            "triggerTokens": COMPRESSION_TRIGGER_TOKENS,
            "slidingWindow": {"targetTokens": COMPRESSION_TARGET_TOKENS},
        },
        "inputAudioTranscription": {},
        "outputAudioTranscription": {},
    }


def _rfc3339(when: datetime) -> str:
    return when.astimezone(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


def build_token_request(setup: dict, now: datetime) -> dict:
    """The body of `POST v1beta/auth_tokens`. The key is a header, never here."""
    body = {
        "uses": USES,
        "expireTime": _rfc3339(now + TOKEN_LIFETIME),
        "newSessionExpireTime": _rfc3339(now + CONNECT_WINDOW),
        "bidiGenerateContentSetup": setup,
    }
    if FIELD_MASK is not None:
        body["fieldMask"] = FIELD_MASK
    return body


# =============================================================================
# Minting
# =============================================================================


@dataclass(frozen=True)
class MintedToken:
    # The whole `auth_tokens/...` name: the browser passes it as `access_token`.
    token: str
    expires_at: str
    connect_by: str


class MintFailed(Exception):
    """Google did not hand back a token. `message` is for the member, in
    words, and never carries the key; `status` is the engine's answer."""

    def __init__(self, message: str, status: int):
        super().__init__(message)
        self.message = message
        self.status = status


Transport = Callable[[str, str, dict[str, str], bytes | None], Response]


def urllib_transport(method: str, url: str, headers: dict[str, str], body: bytes | None):
    request = urllib.request.Request(url, data=body, method=method, headers=headers)
    with _OPENER.open(request, timeout=MINT_TIMEOUT_SECONDS) as reply:
        return Response(reply.status, dict(reply.headers.items()), reply.read())


def _google_says(response: Response) -> str:
    """Google's own error status and message, for the operator. Never the key:
    the key travels in a header Google does not echo."""
    try:
        error = response.json().get("error") or {}
    except (ValueError, AttributeError):
        return ""
    status = error.get("status") or ""
    message = str(error.get("message") or "")[:300]
    said = ": ".join(part for part in (status, message) if part)
    return f" Google said: {said}" if said else ""


def mint(key: str, body: dict, *, transport: Transport | None = None) -> MintedToken:
    send = transport or urllib_transport
    try:
        response = send(
            "POST",
            AUTH_TOKENS_URL,
            {"Content-Type": "application/json", "x-goog-api-key": key},
            json.dumps(body).encode(),
        )
    except (TimeoutError, urllib.error.URLError, ConnectionError, OSError) as exc:
        raise MintFailed(
            f"The assistant could not start: Google's live voice service did not answer "
            f"({type(exc).__name__}). Nothing was started; try again in a moment.",
            503,
        ) from None
    status = response.status
    if status in (401, 403):
        raise MintFailed(
            "The assistant could not start: Google refused this server's Gemini key "
            f"(HTTP {status}). The household's operator needs to check LEGION_GEMINI_KEY."
            + _google_says(response),
            502,
        )
    if status == 429 or status >= 500:
        raise MintFailed(
            f"The assistant could not start: Google's live voice service is busy or down "
            f"(HTTP {status}). Nothing was started; try again in a moment.",
            503,
        )
    if status != 200:
        raise MintFailed(
            f"The assistant could not start: Google refused the session this server asked "
            f"for (HTTP {status}).{_google_says(response)}",
            502,
        )
    try:
        token = response.json().get("name")
    except (ValueError, AttributeError):
        token = None
    if not isinstance(token, str) or not token:
        raise MintFailed(
            "The assistant could not start: Google answered without a token. Nothing was "
            "started.",
            502,
        )
    return MintedToken(
        token=token, expires_at=body["expireTime"], connect_by=body["newSessionExpireTime"]
    )
