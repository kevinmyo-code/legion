"""The prompt cache is made at a conversation's second turn, never its first (cost cut 4,
2026-11-26). Google is never called: a scripted model with a fake `create_cache`. Fixtures and
helpers come from `test_assistant_cascade`."""

# ruff: noqa: F811  (the fixtures are imported, so the tests name them as arguments)

from __future__ import annotations

import pytest

from agents.runtime.model import FakeModel, say
from tests.test_assistant_cascade import (  # noqa: F401  (fixtures)
    _clean,
    _events,
    _of,
    _post,
    engine,
    member,
)

pytestmark = pytest.mark.django_db


# ---- the prompt cache is made at the second turn (cost cut 4) ----------------------------------


class _CachingFake(FakeModel):
    """A scripted model with the cache call the real client has."""

    def __init__(self, rounds):
        super().__init__(rounds)
        self.created = 0

    def create_cache(self, model, system, declarations, ttl_s=300):
        self.created += 1
        return f"cachedContents/{self.created}"


def _caching_engine(engine, rounds):
    from assistant.prompt_cache import CacheStore, CachingModel

    inner = _CachingFake(rounds)
    store = CacheStore(spawn=lambda work: work())
    engine.model = CachingModel(inner, store)
    return inner


def test_a_one_turn_conversation_creates_no_prompt_cache(member, engine):
    inner = _caching_engine(engine, [say("Nothing is on today.")])
    _events(_post(member, text="What is on today?"))
    assert inner.created == 0
    assert [r.cached_content for r in inner.requests] == [None]


def test_the_second_turn_creates_the_cache_and_the_third_reuses_it(member, engine):
    from assistant.prompt_cache import CacheStore, CachingModel

    inner = _CachingFake([say("One."), say("Two."), say("Three.")])
    store = CacheStore(spawn=lambda work: work())
    engine.model = CachingModel(inner, store)
    first = _events(_post(member, text="First?"))
    conversation = _of(first, "turn")[0]["conversation_id"]
    assert inner.created == 0
    _events(_post(member, text="Second?", conversation_id=conversation))
    assert inner.created == 1
    _events(_post(member, text="Third?", conversation_id=conversation))
    assert inner.created == 1
    assert inner.requests[-1].cached_content == "cachedContents/1"
