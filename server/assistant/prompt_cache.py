"""The explicit Gemini context cache for the web cascade's prefix (ADR 0102), the phone's
`ContextCacheStore` on the engine.

Why: the cascade's prefix (the system prompt's stable body plus the tool declarations) is about
nine thousand tokens, sent again on every model round. Uncached, a cascade turn costs about what a
Live answer does; the phone measured an explicit cache hitting on every call (107 of 107) where the
implicit cache never hit a new conversation (0 of 9). So the saving is here.

How, copied from the phone (`ContextCacheStore.kt`, `GeminiStream.kt` `CascadeRequests`):
- **Keyed** by a hash of the model, the body and the declarations. The clock and who is speaking
  are NOT in the body (`assistant.prompt.build_system_prompt_parts`); they go with each turn.
- **Never blocks a turn.** With no usable cache the round goes inline and creation starts in the
  background; the next turn uses it. Created with the phone's TTL (1,200 s) and treated as gone
  `MARGIN_S` (120 s) before it expires. A failed create backs off (60 s, doubling to 30 min).
- **Only `cachedContent`, `contents` and `generationConfig` travel with a cache** (a request that
  also sets `systemInstruction`, `tools` or `toolConfig` is a 400, the phone's measured rule). So
  the runtime's last round, which turns calling off with `toolConfig`, goes inline: it is the rare
  fourth round.
- **A cache Google reports broken** (400, 403 or 404 before any output) is dropped and that round
  is sent again inline, once.

Per process: each engine instance keeps its own names, as each phone does. A cache made by one
instance and never used again expires on its own TTL. The key goes into one header per request
(`agents.runtime.model.GeminiClient`), never here.
"""

from __future__ import annotations

import dataclasses
import hashlib
import json
import logging
import threading
import time
from collections.abc import Callable, Iterator
from dataclasses import dataclass

from agents.runtime.model import ModelFailure, ModelRequest

log = logging.getLogger(__name__)

TTL_S = 1_200
MARGIN_S = 120
BASE_BACKOFF_S = 60
MAX_BACKOFF_S = 30 * 60
MAX_ENTRIES = 64
_BROKEN = ("HTTP 400", "HTTP 403", "HTTP 404")


def key_of(model: str, system: str, declarations: list[dict]) -> str:
    raw = json.dumps([model, system, declarations], sort_keys=True, ensure_ascii=False)
    return hashlib.sha256(raw.encode()).hexdigest()


@dataclass
class _Entry:
    name: str
    expires_at: float


def _thread(work: Callable[[], None]) -> None:
    threading.Thread(target=work, name="legion-prompt-cache", daemon=True).start()


class CacheStore:
    def __init__(
        self,
        clock: Callable[[], float] = time.time,
        spawn: Callable[[Callable[[], None]], None] = _thread,
    ):
        self._clock = clock
        self._spawn = spawn
        self._lock = threading.Lock()
        self._entries: dict[str, _Entry] = {}
        self._creating: set[str] = set()
        self._failures: dict[str, tuple[int, float]] = {}

    def peek(
        self, client, model: str, system: str, declarations: list[dict], create: bool = True
    ) -> str | None:
        """A usable cache name for this prefix, or None (and creation started, unless `create` is
        False: a conversation's first turn may use a cache that exists but never makes one)."""
        key = key_of(model, system, declarations)
        now = self._clock()
        with self._lock:
            entry = self._entries.get(key)
            if entry is not None and entry.expires_at - now > MARGIN_S:
                return entry.name
            if not create or key in self._creating:
                return None
            failures, at = self._failures.get(key, (0, 0.0))
            if failures and now - at < min(BASE_BACKOFF_S * 2 ** (failures - 1), MAX_BACKOFF_S):
                return None
            self._creating.add(key)

        def create() -> None:
            started = self._clock()
            try:
                name = client.create_cache(model, system, declarations, ttl_s=TTL_S)
            except (ModelFailure, OSError, ValueError, KeyError) as exc:
                with self._lock:
                    count, _ = self._failures.get(key, (0, 0.0))
                    self._failures[key] = (count + 1, self._clock())
                    self._creating.discard(key)
                log.warning("assistant prompt cache: create failed (%s)", type(exc).__name__)
                return
            with self._lock:
                self._failures.pop(key, None)
                self._creating.discard(key)
                self._entries[key] = _Entry(name, started + TTL_S)
                if len(self._entries) > MAX_ENTRIES:
                    oldest = min(self._entries, key=lambda k: self._entries[k].expires_at)
                    self._entries.pop(oldest, None)
            log.warning("assistant prompt cache: created ttl_s=%d", TTL_S)

        self._spawn(create)
        return None

    def invalidate(self, model: str, system: str, declarations: list[dict]) -> None:
        with self._lock:
            self._entries.pop(key_of(model, system, declarations), None)


STORE = CacheStore()


class CachingModel:
    """A `ModelClient` that sends a round through the cache when it can. `inner` is the runtime's
    `GeminiClient` (or a test's fake with `stream` and `create_cache`). `used` counts the rounds
    that went through a cache, for the timing line. `allow_create` is False on a conversation's
    first turn (cost cut 4, 2026-11-26): a one-turn conversation then never pays a cache create;
    the second turn makes it, and a cache an earlier conversation made is used from turn one."""

    def __init__(self, inner, store: CacheStore | None = None):
        self.inner = inner
        self.store = store or STORE
        self.used = 0
        self.allow_create = True

    def stream(self, req: ModelRequest) -> Iterator:
        name = None
        if req.allow_tools and req.declarations and not req.cached_content:
            name = self.store.peek(
                self.inner, req.model, req.system, req.declarations, create=self.allow_create
            )
        if name is None:
            yield from self.inner.stream(req)
            return
        started = False
        try:
            for item in self.inner.stream(dataclasses.replace(req, cached_content=name)):
                if not started:
                    started = True
                    self.used += 1
                yield item
        except ModelFailure as exc:
            if started or not any(code in str(exc) for code in _BROKEN):
                raise
            self.store.invalidate(req.model, req.system, req.declarations)
            log.warning("assistant prompt cache: dropped a broken cache, round sent inline")
            yield from self.inner.stream(req)
