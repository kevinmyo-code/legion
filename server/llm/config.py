"""The model ids the clients should use (architecture review 2026-11-10, item 15).

Model ids were string constants in seven phone files, the head unit and the engine, so a
model retirement was an APK release. `GET /api/config/models` hands out this table; each
client keeps its own bundled constants as the fallback when the engine cannot be reached
or names no model for a key, so an old engine or an offline phone behaves exactly as today.

The engine's own defaults are the constants its code already uses (`assistant/live.py`,
`ingest/statements.py`) plus the phone's current ids. `LEGION_MODELS` overrides any key,
`name=model` comma-separated, e.g. `LEGION_MODELS=chat=gemini-3.8-flash,live=models/x`.
An override changes what clients are TOLD; the engine's own calls still use their
constants until each is moved onto this table (not done here: one change at a time).
"""

from __future__ import annotations

import os

from assistant import live
from ingest import statements

# key -> (default model id, what it is for). Keys are a contract: add, never rename.
MODELS: dict[str, tuple[str, str]] = {
    "live": (live.MODEL, "Gemini Live: the voice conversation (phone, head unit, web)."),
    "text": (statements.GEMINI_MODEL, "Cheap one-shot calls: sub-agents, the engine's jobs."),
    "chat": ("gemini-3.8-flash", "Typed chat, voice-note summaries, the sub-agent fallback."),
    "schema_generator": ("gemini-3.1-pro-preview", "Rare, heavy structured generation."),
}


def _overrides() -> dict[str, str]:
    raw = os.environ.get("LEGION_MODELS", "")
    found: dict[str, str] = {}
    for part in raw.split(","):
        name, sep, value = part.partition("=")
        if sep and name.strip() in MODELS and value.strip():
            found[name.strip()] = value.strip()
    return found


def model_config() -> dict[str, str]:
    overrides = _overrides()
    return {name: overrides.get(name, default) for name, (default, _) in MODELS.items()}
