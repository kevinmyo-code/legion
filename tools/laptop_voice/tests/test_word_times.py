"""`tts.word`: when each word starts in the audio of its id (CONTRACT.md section 4d, 2026-11-21).

The phone keeps in its history only the words whose start has played, so a barge-in no longer leaves
Alfred assuming the owner heard a whole clip. These tests run the real server code on fake engines
(no GPU, no models). The fakes make a tone of 60 ms per character, so every word's true start in the
PCM is known exactly and the event's `start_ms` is checked against it.

Run from tools/:  python -m pytest laptop_voice/tests -q
"""

from __future__ import annotations

import asyncio
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from laptop_voice import engines as E  # noqa: E402
from laptop_voice.client import TtsResult, VoiceClient  # noqa: E402
from laptop_voice.tests.test_protocol import TOKEN, start  # noqa: E402

MS_PER_CHAR = 60  # the fakes' tone length per character


async def events_until_done(c: VoiceClient, tid: int, timeout: float = 15.0) -> list[dict]:
    """Every event for `tid` up to and including its tts.done (or an error naming it)."""
    out = []
    while True:
        ev = await asyncio.wait_for(c.events.get(), timeout)
        if ev.get("id") != tid:
            continue
        out.append(ev)
        if ev["type"] in ("tts.done", "error"):
            return out


def words_of(events: list[dict]) -> list[tuple[str, int]]:
    return [(e["text"], e["start_ms"]) for e in events if e["type"] == "tts.word"]


def expected_streamed(text: str) -> list[tuple[str, int]]:
    """FakeStreamingTts speaks each word as its own tone, back to back."""
    out, at = [], 0
    for w in text.split():
        out.append((w, at))
        at += MS_PER_CHAR * len(w)
    return out


def expected_spoken(text: str) -> list[tuple[str, int]]:
    """FakeTts speaks each clause piece as one tone of 60 ms per character, spaces included."""
    out, piece_at = [], 0
    for piece in E.split_for_speech(text):
        col = 0
        for w in piece.split(" "):
            out.append((w, piece_at + MS_PER_CHAR * col))
            col += len(w) + 1
        piece_at += MS_PER_CHAR * len(piece)
    return out


@pytest.mark.asyncio
async def test_capability_says_whether_word_times_come():
    for tts, want in ((E.FakeTts(word_times=True), True), (E.FakeTts(), False),
                      (E.FakeStreamingTts(word_times=True), True)):
        svc, server, url = await start(tts=tts)
        c = VoiceClient(url, TOKEN)
        try:
            ready = await c.connect()
            assert ready["capabilities"]["word_times"] is want
            status, body = await c.health()
            assert body["capabilities"]["word_times"] is want
        finally:
            await c.close(); await server.close()


@pytest.mark.asyncio
async def test_streamed_line_sends_every_word_with_its_start_in_that_ids_audio():
    svc, server, url = await start(tts=E.FakeStreamingTts(word_times=True))
    c = VoiceClient(url, TOKEN)
    text = "Your train leaves at nine from platform four."
    try:
        await c.connect()
        c.tts[5] = TtsResult()
        await c.send({"type": "tts.stream.start", "id": 5})
        await c.send({"type": "tts.stream.text", "id": 5, "text": "Your train leaves "})
        await c.send({"type": "tts.stream.text", "id": 5, "text": "at nine from platform four."})
        await c.send({"type": "tts.stream.end", "id": 5})
        evs = await events_until_done(c, 5)
        done = evs[-1]
        assert done["type"] == "tts.done" and not done.get("cancelled")
        assert words_of(evs) == expected_streamed(text)
        assert all(ms < done["audio_ms"] for _, ms in words_of(evs))
    finally:
        await c.close(); await server.close()


@pytest.mark.asyncio
async def test_whole_line_sends_every_word_with_its_start_in_that_ids_audio():
    svc, server, url = await start(tts=E.FakeTts(word_times=True))
    c = VoiceClient(url, TOKEN)
    text = "Twelve minutes, running. The oven is still on."
    try:
        await c.connect()
        await c.speak(3, text)
        evs = await events_until_done(c, 3)
        assert evs[-1]["type"] == "tts.done"
        assert words_of(evs) == expected_spoken(text)
        starts = [ms for _, ms in words_of(evs)]
        assert starts == sorted(starts) and starts[-1] < evs[-1]["audio_ms"]
    finally:
        await c.close(); await server.close()


@pytest.mark.asyncio
async def test_each_id_counts_from_its_own_first_sample():
    svc, server, url = await start(tts=E.FakeTts(word_times=True))
    c = VoiceClient(url, TOKEN)
    try:
        await c.connect()
        await c.speak(1, "First line here.")
        await c.speak(2, "Second line.")
        one = await events_until_done(c, 1)
        two = await events_until_done(c, 2)
        assert words_of(one)[0] == ("First", 0)
        assert words_of(two) == expected_spoken("Second line.")
    finally:
        await c.close(); await server.close()


@pytest.mark.asyncio
async def test_no_word_after_a_cancel_and_none_after_done():
    # each word takes 0.15 s to generate: the cancel lands while words are still coming
    svc, server, url = await start(tts=E.FakeStreamingTts(word_s=0.15, word_times=True))
    c = VoiceClient(url, TOKEN)
    text = "one two three four five six seven eight nine ten"
    try:
        await c.connect()
        c.tts[7] = TtsResult()
        await c.send({"type": "tts.stream.start", "id": 7})
        await c.send({"type": "tts.stream.text", "id": 7, "text": text})
        await c.send({"type": "tts.stream.end", "id": 7})
        for _ in range(200):
            if c.tts[7].pcm:
                break
            await asyncio.sleep(0.01)
        await c.send({"type": "tts.cancel", "id": 7})
        evs = await events_until_done(c, 7)
        assert evs[-1]["type"] == "tts.done" and evs[-1]["cancelled"] is True
        got = words_of(evs)
        assert 0 < len(got) < len(text.split()), "some words went out, the cut ones did not"
        assert got == expected_streamed(text)[:len(got)]
        await asyncio.sleep(0.4)  # nothing more for that id once tts.done is out
        late = []
        while not c.events.empty():
            ev = c.events.get_nowait()
            if ev.get("id") == 7:
                late.append(ev)
        assert late == []
    finally:
        await c.close(); await server.close()


@pytest.mark.asyncio
async def test_an_engine_without_word_times_sends_none():
    svc, server, url = await start(tts=E.FakeStreamingTts())
    c = VoiceClient(url, TOKEN)
    try:
        await c.connect()
        await c.speak(4, "Nothing to see.")
        evs = await events_until_done(c, 4)
        c.tts[6] = TtsResult()
        await c.send({"type": "tts.stream.start", "id": 6})
        await c.send({"type": "tts.stream.text", "id": 6, "text": "Still nothing here."})
        await c.send({"type": "tts.stream.end", "id": 6})
        evs += await events_until_done(c, 6)
        assert [e for e in evs if e["type"] == "tts.word"] == []
    finally:
        await c.close(); await server.close()
