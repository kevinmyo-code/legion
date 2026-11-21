"""Keep-warm: while no conversation is open, a tiny pass through both models every few seconds, so
the first request after a long idle does not pay to bring the models back. Fake engines only (no GPU,
no models).

Run from tools/:  python -m pytest laptop_voice/tests -q
"""

from __future__ import annotations

import asyncio
import logging
import sys
import threading
import time
from pathlib import Path

import pytest
from aiohttp.test_utils import TestServer

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from laptop_voice import engines as E  # noqa: E402
from laptop_voice import keep_warm as W  # noqa: E402
from laptop_voice import server as S  # noqa: E402
from laptop_voice.client import VoiceClient  # noqa: E402

TOKEN = "test-token-0123456789abcdef"


class CountingStt(E.FakeStreamingStt):
    """Counts the streams it opens and the audio it is fed; can fail or be slow on demand."""

    def __init__(self, fail: int = 0, delay_s: float = 0.0):
        super().__init__()
        self.opened, self.fed_ms, self.fail, self.delay_s = 0, 0, fail, delay_s
        self.lock = threading.Lock()  # like the Kyutai engine's own lock

    def open_stream(self, lang):
        if self.fail:
            self.fail -= 1
            raise RuntimeError("model step failed")
        time.sleep(self.delay_s)
        self.opened += 1
        sess, outer = super().open_stream(lang), self
        feed = sess.feed

        def counted(pcm):
            outer.fed_ms += len(pcm) * 1000 // E.STT_RATE
            return feed(pcm)

        sess.feed = counted
        return sess


class CountingTts(E.FakeTts):
    def __init__(self):
        super().__init__()
        self.calls: list[tuple[str, str]] = []

    def synth(self, text, voice, speed):
        self.calls.append((text, voice))
        yield from super().synth(text, voice, speed)


def service(stt=None, tts=None, lock=None, **kw) -> S.Service:
    stt, tts = stt or CountingStt(), tts or CountingTts()
    if lock is not None:
        stt.gpu_lock = tts.gpu_lock = lock
    return S.Service(stt=stt, tts=tts, token=TOKEN, sample=False, **kw)


def hold_in_other_thread(lock) -> threading.Event:
    """Take the lock in a different thread (an RLock re-enters on its own thread, which would make a
    busy-lock test pass for the wrong reason). Set the returned event to let go."""
    taken, release = threading.Event(), threading.Event()

    def hold():
        with lock:
            taken.set()
            release.wait(5)

    threading.Thread(target=hold, daemon=True).start()
    assert taken.wait(2)
    return release


@pytest.mark.asyncio
async def test_pass_runs_both_models_when_idle():
    svc = service(lock=threading.RLock())
    done = await W.warm_once(svc)
    assert done is not None and done.stt_ms >= 0 and done.tts_ms >= 0
    assert svc.stt.opened == 1 and 0 < svc.stt.fed_ms <= 1000  # a short burst, not an utterance
    assert svc.tts.calls == [(W.TTS_WARM_TEXT, svc.tts.default_voice)]


@pytest.mark.asyncio
async def test_skipped_while_a_conversation_is_open():
    svc = service()
    svc.connections = 1
    assert await W.warm_once(svc) is None
    assert svc.stt.opened == 0 and svc.tts.calls == []


@pytest.mark.asyncio
async def test_skipped_while_a_conversation_is_starting():
    svc = service()
    svc.last_conversation = time.monotonic()  # a socket just passed the token check
    assert await W.warm_once(svc) is None
    assert svc.stt.opened == 0 and svc.tts.calls == []


@pytest.mark.asyncio
async def test_skipped_while_loading():
    svc = service()
    svc.loading = True
    assert await W.warm_once(svc) is None
    assert svc.stt.opened == 0


@pytest.mark.asyncio
async def test_skipped_without_waiting_when_the_gpu_lock_is_busy():
    lock = threading.RLock()
    svc = service(lock=lock)
    release = hold_in_other_thread(lock)
    try:
        t0 = time.perf_counter()
        assert await W.warm_once(svc) is None
        assert time.perf_counter() - t0 < 1.0, "try-acquire: never waits for the lock"
        assert svc.stt.opened == 0 and svc.tts.calls == []
    finally:
        release.set()


@pytest.mark.asyncio
async def test_skipped_when_an_engine_is_inside_a_call():
    """The real paths take the engine's own lock before the GPU lock: a pass that held the GPU lock and
    then waited on the engine lock could deadlock, so a held engine lock means skip."""
    svc = service(lock=threading.RLock())
    release = hold_in_other_thread(svc.stt.lock)
    try:
        assert await W.warm_once(svc) is None
        assert svc.stt.opened == 0 and svc.tts.calls == []
    finally:
        release.set()


@pytest.mark.asyncio
async def test_pass_touches_no_client_visible_counter():
    svc = service(lock=threading.RLock())
    svc.slow_strikes, svc.last_strike = 1, time.monotonic()
    await W.warm_once(svc)
    assert svc.gpu.queued == 0 and svc.tts_gpu.queued == 0
    assert svc.slow_strikes == 1 and svc.connections == 0
    assert svc.tts.default_voice == "am_puck"


@pytest.mark.asyncio
async def test_failing_pass_is_logged_and_the_loop_keeps_going(caplog):
    svc = service(stt=CountingStt(fail=1))
    kw = W.KeepWarm(svc, interval_s=0.02)
    with caplog.at_level(logging.INFO, logger="laptop_voice"):
        task = asyncio.create_task(kw.run())
        for _ in range(200):
            await asyncio.sleep(0.01)
            if svc.stt.opened:
                break
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)
    assert svc.stt.opened >= 1, "the pass after the failure ran"
    assert kw.failed == 1
    assert any("keep-warm pass failed: RuntimeError" in r.getMessage() for r in caplog.records)


@pytest.mark.asyncio
async def test_slow_pass_and_hourly_summary_are_logged(caplog):
    svc = service(stt=CountingStt(delay_s=0.05))
    kw = W.KeepWarm(svc, interval_s=0.01, slow_ms=10, summary_s=0.05)
    with caplog.at_level(logging.INFO, logger="laptop_voice"):
        task = asyncio.create_task(kw.run())
        for _ in range(300):
            await asyncio.sleep(0.01)
            if any("keep-warm summary" in r.getMessage() for r in caplog.records):
                break
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)
    msgs = [r.getMessage() for r in caplog.records]
    assert any(m.startswith("keep-warm on: every 0.01 s") for m in msgs)
    assert any(m.startswith("keep-warm slow: stt_ms=") for m in msgs)
    assert any(m.startswith("keep-warm summary: passes=") and "median_ms=" in m and "max_ms=" in m for m in msgs)


@pytest.mark.asyncio
async def test_quiet_pass_logs_nothing(caplog):
    svc = service()
    kw = W.KeepWarm(svc, interval_s=0.01)
    with caplog.at_level(logging.INFO, logger="laptop_voice"):
        for _ in range(3):
            await kw.tick()
    assert kw.passes == 3
    assert [r.getMessage() for r in caplog.records] == []


async def start(svc):
    server = TestServer(S.make_app(svc), host="127.0.0.1")
    await server.start_server()
    return server, f"http://127.0.0.1:{server.port}"


@pytest.mark.asyncio
async def test_service_runs_it_sends_no_frame_and_shutdown_cancels_it(monkeypatch):
    """In the running app: passes happen while idle, stop while a client is connected (so nothing can
    reach that client), leave /health as it was, and the task ends with the app."""
    monkeypatch.setattr(W, "QUIET_S", 0.05)
    svc = service(lock=threading.RLock(), keep_warm_s=0.02)
    server, url = await start(svc)
    c = VoiceClient(url, TOKEN)
    try:
        for _ in range(200):
            await asyncio.sleep(0.01)
            if svc.stt.opened >= 2:
                break
        assert svc.stt.opened >= 2
        _, before = await c.health()
        await c.connect()
        opened = svc.stt.opened
        await asyncio.sleep(0.3)
        assert svc.stt.opened == opened, "no pass while a conversation is open"
        assert c.events.empty() and c.tts == {}, "the client heard nothing"
        await c.close()
        _, after = await c.health()
        for k in ("status", "queue", "connections"):
            assert after[k] == before[k]
        assert after["tts"]["default_voice"] == before["tts"]["default_voice"]
        task = server.app[S.KEEP_WARM]
    finally:
        await server.close()
    assert task.done()


@pytest.mark.asyncio
async def test_interval_zero_turns_it_off():
    svc = service(keep_warm_s=0)
    server, url = await start(svc)
    try:
        await asyncio.sleep(0.1)
        assert S.KEEP_WARM not in server.app
        assert svc.stt.opened == 0 and svc.tts.calls == []
    finally:
        await server.close()


def test_one_warm_body_for_start_up_and_keep_warm():
    """Start-up warm and keep-warm call the same functions; a non-streaming engine is warmed through
    transcribe, and only the first TTS chunk is generated."""
    stt, tts = E.FakeStt(), CountingTts()
    W.stt_pass(stt, 1000)
    W.tts_pass(tts)
    s2 = CountingStt()
    W.stt_pass(s2, 480)
    assert s2.opened == 1 and s2.fed_ms == 480
    assert tts.calls == [(W.TTS_WARM_TEXT, "am_puck")]
