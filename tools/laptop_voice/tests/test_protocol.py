"""Protocol tests against the real server code with fake engines (no GPU, no models).

Run from tools/:  python -m pytest laptop_voice/tests -q
"""

from __future__ import annotations

import asyncio
import json
import sys
from pathlib import Path

import aiohttp
import numpy as np
import pytest
from aiohttp.test_utils import TestServer

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from laptop_voice import engines as E  # noqa: E402
from laptop_voice import server as S  # noqa: E402
from laptop_voice.client import VoiceClient  # noqa: E402

TOKEN = "test-token-0123456789abcdef"


async def start(stt=None, tts=None, **svc_kw):
    svc = S.Service(stt=stt or E.FakeStt(), tts=tts or E.FakeTts(), token=TOKEN, sample=False, **svc_kw)
    server = TestServer(S.make_app(svc), host="127.0.0.1")
    await server.start_server()
    return svc, server, f"http://127.0.0.1:{server.port}"


def tone(seconds: float) -> np.ndarray:
    t = np.arange(int(16000 * seconds)) / 16000
    return (np.sin(2 * np.pi * 300 * t) * 5000).astype(np.int16)


@pytest.mark.asyncio
async def test_health_needs_token_and_reports_ready():
    svc, server, url = await start()
    try:
        status, body = await VoiceClient(url, "wrong").health()
        assert status == 401 and body is None
        status, body = await VoiceClient(url, TOKEN).health()
        assert status == 200
        assert body["status"] == "ready" and body["protocol"] == 1
        assert body["stt"]["sample_rate"] == 16000 and body["tts"]["sample_rate"] == 24000
    finally:
        await server.close()


@pytest.mark.asyncio
async def test_websocket_refuses_bad_token_before_upgrade():
    svc, server, url = await start()
    try:
        async with aiohttp.ClientSession() as s:
            with pytest.raises(aiohttp.WSServerHandshakeError) as e:
                await s.ws_connect(url.replace("http", "ws") + "/v1/conversation",
                                   headers={"Authorization": "Bearer nope"})
            assert e.value.status == 401
    finally:
        await server.close()


@pytest.mark.asyncio
async def test_speech_in_text_out():
    svc, server, url = await start(stt=E.FakeStt(text="set a timer for twelve minutes"))
    c = VoiceClient(url, TOKEN)
    try:
        ready = await c.connect()
        assert ready["type"] == "ready" and ready["limits"]["max_tts_chars"] == 1000
        await c.stream_utterance(7, tone(2.0))
        ev = await c.next_event("stt.final")
        assert ev["id"] == 7 and ev["text"] == "set a timer for twelve minutes"
        assert ev["audio_ms"] == 2000 and ev["lang"] == "en" and ev["reason"] == "client_end"
        assert ready["capabilities"] == {"end_of_turn": False, "partials": False, "text_streaming": False}
        assert ready["stt"]["end_of_turn"] is False
    finally:
        await c.close(); await server.close()


@pytest.mark.asyncio
async def test_text_in_audio_out_with_header_and_rate():
    svc, server, url = await start()
    c = VoiceClient(url, TOKEN)
    try:
        await c.connect()
        await c.speak(3, "Twelve minutes, running.")
        done = await c.next_event("tts.done")
        assert done["id"] == 3 and not done.get("cancelled")
        pcm = np.frombuffer(bytes(c.tts[3].pcm), dtype="<i2")
        assert len(pcm) * 1000 // 24000 == done["audio_ms"] > 0
        assert done["first_audio_ms"] is not None
    finally:
        await c.close(); await server.close()


@pytest.mark.asyncio
async def test_cancel_mid_sentence_stops_audio():
    # 0.3 s per piece and four pieces: cancel after the first piece arrives
    svc, server, url = await start(tts=E.FakeTts(delay_s=0.3))
    c = VoiceClient(url, TOKEN)
    try:
        await c.connect()
        text = "First piece here. Second piece here. Third piece here. Fourth piece here."
        await c.speak(9, text)
        for _ in range(100):
            if c.tts[9].pcm:
                break
            await asyncio.sleep(0.02)
        await c.send({"type": "tts.cancel", "id": 9})
        done = await c.next_event("tts.done")
        assert done["cancelled"] is True
        full = sum(int(24000 * 0.06 * len(p)) for p in E.split_for_speech(text))
        assert len(c.tts[9].pcm) // 2 < full / 2
    finally:
        await c.close(); await server.close()


@pytest.mark.asyncio
async def test_stt_cancel_answers_cancelled():
    svc, server, url = await start(stt=E.FakeStt(delay_s=0.5))
    c = VoiceClient(url, TOKEN)
    try:
        await c.connect()
        await c.stream_utterance(1, tone(1.0))
        await c.send({"type": "stt.cancel", "id": 1})
        ev = await c.next_event("stt.final")
        assert ev["cancelled"] is True and ev["text"] == "" and ev["reason"] == "cancelled"
        # nothing else arrives for id 1
        with pytest.raises(asyncio.TimeoutError):
            await c.next_event("stt.final", timeout=1.0)
    finally:
        await c.close(); await server.close()


@pytest.mark.asyncio
async def test_busy_at_connect_closes_1013():
    svc, server, url = await start()
    svc.ram_pct = 99.5
    try:
        status, body = await VoiceClient(url, TOKEN).health()
        assert body["status"] == "busy"
        async with aiohttp.ClientSession() as s:
            ws = await s.ws_connect(url.replace("http", "ws") + "/v1/conversation",
                                    headers={"Authorization": f"Bearer {TOKEN}"})
            msg = await ws.receive()
            assert msg.type == aiohttp.WSMsgType.CLOSE and ws.close_code == 1013
    finally:
        await server.close()


@pytest.mark.asyncio
async def test_slow_strikes_make_busy_then_expire(monkeypatch):
    monkeypatch.setattr(S, "STRIKE_FORGET_S", 0.3)
    svc, server, url = await start()
    try:
        for _ in range(3):
            svc.strike()
        assert (await VoiceClient(url, TOKEN).health())[1]["status"] == "busy"
        await asyncio.sleep(0.4)
        assert (await VoiceClient(url, TOKEN).health())[1]["status"] == "ready"
    finally:
        await server.close()


@pytest.mark.asyncio
async def test_stt_timeout_reports_error(monkeypatch):
    monkeypatch.setattr(S, "STT_BUDGET_S", 0.2)
    svc, server, url = await start(stt=E.FakeStt(delay_s=1.0))
    c = VoiceClient(url, TOKEN)
    try:
        await c.connect()
        await c.stream_utterance(2, tone(0.5))
        ev = await c.next_event("stt.final")
        assert ev["type"] == "error" and ev["code"] == "timeout" and ev["id"] == 2
    finally:
        await c.close(); await server.close()


@pytest.mark.asyncio
async def test_hello_required_and_bad_frames_rejected():
    svc, server, url = await start()
    try:
        async with aiohttp.ClientSession() as s:
            ws = await s.ws_connect(url.replace("http", "ws") + "/v1/conversation",
                                    headers={"Authorization": f"Bearer {TOKEN}"})
            await ws.send_str(json.dumps({"type": "tts.speak", "id": 1, "text": "hi"}))
            err = json.loads((await ws.receive()).data)
            assert err["code"] == "protocol"
            msg = await ws.receive()
            assert msg.type == aiohttp.WSMsgType.CLOSE and ws.close_code == 4400
        c = VoiceClient(url, TOKEN)
        await c.connect()
        await c.ws.send_bytes(S.HEADER.pack(S.KIND_MIC, 99) + b"\x00\x00")
        err = await c.next_event("error")
        assert err["code"] == "bad_request" and err["id"] == 99
        await c.speak(5, "x" * 1001)
        err = await c.next_event("error")
        assert err["code"] == "bad_request" and err["id"] == 5
        await c.close()
    finally:
        await server.close()


def test_non_loopback_bind_refused(monkeypatch):
    monkeypatch.setenv("LEGION_VOICE_TOKEN", TOKEN)
    monkeypatch.setattr(sys, "argv", ["server", "--host", "0.0.0.0", "--stt", "fake", "--tts", "fake"])
    with pytest.raises(SystemExit) as e:
        S.main()
    assert "all-interfaces" in str(e.value)


def test_split_for_speech_keeps_first_piece_short():
    pieces = E.split_for_speech("Done. Milk, eggs and two pounds of chicken thighs are on the grocery list.")
    assert pieces[0] == "Done."
    assert all(len(p.split()) <= 14 for p in pieces)
    first = E.split_for_speech("Checking balance is one thousand two hundred eighty-four dollars and seventeen cents.")[0]
    assert len(first.split()) <= 8


@pytest.mark.asyncio
async def test_livez_needs_no_token_and_says_nothing():
    svc, server, url = await start()
    try:
        async with aiohttp.ClientSession() as s:
            async with s.get(url + "/livez") as r:
                assert r.status == 204 and await r.read() == b""
            async with s.get(url + "/") as r:
                assert r.status == 404
    finally:
        await server.close()


@pytest.mark.asyncio
async def test_repeated_bad_tokens_lock_out(monkeypatch):
    monkeypatch.setattr(S, "AUTH_FAIL_DELAY_S", 0.0)
    monkeypatch.setattr(S, "AUTH_FAIL_MAX", 3)
    svc, server, url = await start()
    try:
        bad = VoiceClient(url, "wrong")
        for _ in range(4):
            assert (await bad.health())[0] == 401
        assert (await VoiceClient(url, TOKEN).health())[0] == 429
        assert (await VoiceClient(url, "wrong").health())[0] == 429
    finally:
        await server.close()


@pytest.mark.asyncio
async def test_third_conversation_refused():
    svc, server, url = await start()
    a, b = VoiceClient(url, TOKEN), VoiceClient(url, TOKEN)
    try:
        await a.connect(); await b.connect()
        async with aiohttp.ClientSession() as s:
            ws = await s.ws_connect(url.replace("http", "ws") + "/v1/conversation",
                                    headers={"Authorization": f"Bearer {TOKEN}"})
            msg = await ws.receive()
            assert msg.type == aiohttp.WSMsgType.CLOSE and ws.close_code == 1013
    finally:
        await a.close(); await b.close(); await server.close()


@pytest.mark.asyncio
async def test_voice_outside_allowlist_and_oversize_frame_rejected():
    svc, server, url = await start()
    c = VoiceClient(url, TOKEN)
    try:
        await c.connect()
        await c.speak(1, "hello", voice="../../windows/win.ini")
        err = await c.next_event("error")
        assert err["code"] == "bad_request" and err["id"] == 1
        await c.ws.send_bytes(S.HEADER.pack(S.KIND_MIC, 1) + b"\x00" * (S.MAX_FRAME_BYTES + 2))
        ev = await c.next_event("_closed", timeout=5)
        assert ev["type"] == "_closed" and ev["code"] == 1009
    finally:
        await c.close(); await server.close()


@pytest.mark.asyncio
async def test_laptop_end_of_turn_streams_partials_pause_and_final():
    svc, server, url = await start(stt=E.FakeStreamingStt())
    c = VoiceClient(url, TOKEN)
    try:
        ready = await c.connect()
        assert ready["capabilities"]["end_of_turn"] is True and ready["stt"]["end_of_turn"] is True
        speech = np.concatenate([tone(1.2), np.zeros(int(16000 * 0.8), dtype=np.int16)])
        await c.stream_utterance(4, speech, end=False)
        seen = []
        while True:
            ev = await c.next_event()
            seen.append(ev["type"])
            if ev["type"] == "stt.final":
                break
        assert "stt.partial" in seen and "stt.pause" in seen
        assert ev["reason"] == "end_of_turn" and ev["text"] == "set a timer for twelve minutes"
        await c.ws.send_bytes(S.HEADER.pack(S.KIND_MIC, 4) + b"\x00\x00")
        err = await c.next_event("error")
        assert err["code"] == "bad_request" and err["id"] == 4
    finally:
        await c.close(); await server.close()


@pytest.mark.asyncio
async def test_laptop_end_of_turn_can_be_forced_by_phone():
    svc, server, url = await start(stt=E.FakeStreamingStt())
    c = VoiceClient(url, TOKEN)
    try:
        await c.connect()
        await c.stream_utterance(5, tone(1.0), end=True)
        ev = await c.next_event("stt.final")
        assert ev["reason"] == "client_end"
    finally:
        await c.close(); await server.close()


@pytest.mark.asyncio
async def test_streamed_text_speaks_before_text_ends_and_cancels():
    svc, server, url = await start(tts=E.FakeStreamingTts())
    c = VoiceClient(url, TOKEN)
    try:
        ready = await c.connect()
        assert ready["capabilities"]["text_streaming"] is True
        c.tts[8] = __import__("laptop_voice.client", fromlist=["TtsResult"]).TtsResult()
        await c.send({"type": "tts.stream.start", "id": 8})
        await c.send({"type": "tts.stream.text", "id": 8, "text": "Twelve "})
        for _ in range(100):
            if c.tts[8].pcm:
                break
            await asyncio.sleep(0.02)
        assert c.tts[8].pcm, "audio must start before the text ends"
        await c.send({"type": "tts.stream.text", "id": 8, "text": "minutes, running."})
        await c.send({"type": "tts.stream.end", "id": 8})
        done = await c.next_event("tts.done")
        assert done["id"] == 8 and not done.get("cancelled") and done["audio_ms"] > 0
        await c.send({"type": "tts.stream.start", "id": 9})
        await c.send({"type": "tts.stream.text", "id": 9, "text": "This will be cut "})
        await c.send({"type": "tts.cancel", "id": 9})
        done = await c.next_event("tts.done")
        assert done["id"] == 9 and done["cancelled"] is True
    finally:
        await c.close(); await server.close()


@pytest.mark.asyncio
async def test_streamed_text_with_slow_model_streams_and_cancels_after_end():
    # each word takes 0.15 s, longer than the server's 50 ms pull: audio must still flow word by word
    # after tts.stream.end (no burst at the end), and a cancel after the end must still cut it
    svc, server, url = await start(tts=E.FakeStreamingTts(word_s=0.15))
    c = VoiceClient(url, TOKEN)
    words = "one two three four five six seven eight"
    try:
        await c.connect()
        from laptop_voice.client import TtsResult
        c.tts[1] = TtsResult()
        t0 = asyncio.get_running_loop().time()
        await c.send({"type": "tts.stream.start", "id": 1})
        await c.send({"type": "tts.stream.text", "id": 1, "text": words})
        await c.send({"type": "tts.stream.end", "id": 1})
        for _ in range(200):
            if c.tts[1].pcm:
                break
            await asyncio.sleep(0.01)
        t_first = asyncio.get_running_loop().time() - t0
        done = await c.next_event("tts.done")
        t_done = asyncio.get_running_loop().time() - t0
        assert not done.get("cancelled") and t_first < 0.6 and t_done > 1.0
        c.tts[2] = TtsResult()
        await c.send({"type": "tts.stream.start", "id": 2})
        await c.send({"type": "tts.stream.text", "id": 2, "text": words})
        await c.send({"type": "tts.stream.end", "id": 2})
        for _ in range(200):
            if c.tts[2].pcm:
                break
            await asyncio.sleep(0.01)
        await c.send({"type": "tts.cancel", "id": 2})
        done = await c.next_event("tts.done")
        assert done["id"] == 2 and done["cancelled"] is True
        assert len(c.tts[2].pcm) < len(c.tts[1].pcm) / 2
    finally:
        await c.close(); await server.close()


@pytest.mark.asyncio
async def test_streaming_stt_cap_sends_too_long_then_final(monkeypatch):
    monkeypatch.setattr(S, "MAX_UTTERANCE_MS", 1000)
    svc, server, url = await start(stt=E.FakeStreamingStt())
    c = VoiceClient(url, TOKEN)
    try:
        await c.connect()
        await c.stream_utterance(6, tone(1.5), end=False)  # speech only: the model never ends the turn
        err = await c.next_event("error", timeout=5)
        assert err["code"] == "too_long" and err["id"] == 6
        ev = await c.next_event("stt.final", timeout=5)
        assert ev["type"] == "stt.final" and ev["id"] == 6 and ev["audio_ms"] == 1000
    finally:
        await c.close(); await server.close()


@pytest.mark.asyncio
async def test_streaming_stt_step_timeout_answers_timeout_and_strikes(monkeypatch):
    monkeypatch.setattr(S, "STT_BUDGET_S", 0.2)
    svc, server, url = await start(stt=E.FakeStreamingStt(feed_delay_s=0.5))
    c = VoiceClient(url, TOKEN)
    try:
        await c.connect()
        await c.stream_utterance(7, tone(0.3), end=False)
        err = await c.next_event("error", timeout=5)
        assert err["code"] == "timeout" and err["id"] == 7
        assert svc.slow_strikes == 1
    finally:
        await c.close(); await server.close()


@pytest.mark.asyncio
async def test_streaming_stt_refuses_a_second_live_utterance():
    svc, server, url = await start(stt=E.FakeStreamingStt())
    c = VoiceClient(url, TOKEN)
    try:
        await c.connect()
        await c.stream_utterance(1, tone(0.3), end=False)
        await c.send({"type": "stt.start", "id": 2})
        err = await c.next_event("error")
        assert err["code"] == "bad_request" and err["id"] == 2
        await c.send({"type": "stt.cancel", "id": 1})
        ev = await c.next_event("stt.final")
        assert ev["reason"] == "cancelled"
        await c.stream_utterance(3, tone(0.3), end=True)  # after a cancel a new id is fine
        ev = await c.next_event("stt.final")
        assert ev["id"] == 3 and ev["reason"] == "client_end"
    finally:
        await c.close(); await server.close()


@pytest.mark.asyncio
async def test_streamed_text_over_cap_closes_the_id_and_frees_the_queue():
    svc, server, url = await start(tts=E.FakeStreamingTts())
    c = VoiceClient(url, TOKEN)
    try:
        await c.connect()
        from laptop_voice.client import TtsResult
        c.tts[5] = TtsResult()
        await c.send({"type": "tts.stream.start", "id": 5})
        await c.send({"type": "tts.stream.text", "id": 5, "text": "word " * 120})   # 600 chars
        await c.send({"type": "tts.stream.text", "id": 5, "text": "more " * 81})    # 405 more: over 1,000
        err = await c.next_event("error")
        assert err["code"] == "bad_request" and err["id"] == 5
        await c.speak(6, "Twelve minutes, running.")   # queued behind id 5: must still be spoken
        done = await c.next_event("tts.done", timeout=10)
        assert done["id"] == 6 and not done.get("cancelled")
        with pytest.raises(asyncio.TimeoutError):  # nothing more for the errored id
            await c.next_event("tts.done", timeout=0.5)
    finally:
        await c.close(); await server.close()


@pytest.mark.asyncio
async def test_stream_messages_refused_without_text_streaming():
    svc, server, url = await start()
    c = VoiceClient(url, TOKEN)
    try:
        await c.connect()
        await c.send({"type": "tts.stream.start", "id": 1})
        err = await c.next_event("error")
        assert err["code"] == "bad_request"
    finally:
        await c.close(); await server.close()
