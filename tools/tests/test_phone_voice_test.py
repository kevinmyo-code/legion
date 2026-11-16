"""`tools/phone_voice_test.py` without a phone: the `content call` output, device choice, the turn
wait, the WAV it pushes, and a recorded (invented) logcat run scored end to end. Run from the repo root:

    python -m pytest tools/tests/test_phone_voice_test.py -p no:cacheprovider
"""

from __future__ import annotations

import importlib.util
import io
import json
import sys
import wave
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("phone_voice_test_under_test", TOOLS / "phone_voice_test.py")
pvt = importlib.util.module_from_spec(spec)
sys.modules["phone_voice_test_under_test"] = pvt
spec.loader.exec_module(pvt)
Device = pvt.adbmod.Device


def test_parse_bundle_status():
    out = "Result: Bundle[{ok=true, enabled=true, service=true, conversation=test, test_id=due_open, speaking=false, busy=true, turns_answered=2, phase=THINKING}]\n"
    b = pvt.parse_bundle(out)
    assert b["ok"] is True and b["conversation"] == "test" and b["turns_answered"] == 2 and b["busy"] is True


def test_parse_bundle_error_with_commas():
    out = "Result: Bundle[{ok=false, error=disabled: Settings, Assistant, Allow test turns from a computer is off}]"
    b = pvt.parse_bundle(out)
    assert b["ok"] is False and b["error"].startswith("disabled: Settings, Assistant")


def test_parse_bundle_old_build():
    b = pvt.parse_bundle("Error while accessing provider:com.kevin.legion.testturn\njava.lang.IllegalArgumentException: Unknown authority")
    assert b["ok"] is False and b["error"].startswith("no_door")


def test_pick_device():
    usb = Device("R58N1234", "device", "SM_A256")
    wifi = Device("adb-R58N1234-AbCd._adb-tls-connect._tcp", "device", "SM_A256")
    emu = Device("emulator-5554", "device", "sdk")
    assert pvt.pick_device([wifi], None) == wifi.serial
    assert pvt.pick_device([emu, wifi], None) == wifi.serial
    assert pvt.pick_device([usb, wifi], "R58N1234") == "R58N1234"
    # The same phone listed twice over wireless (IP:port and mDNS name).
    ip = Device("192.168.0.20:40000", "device", "SM_A256")
    assert pvt.pick_device([ip, wifi], None) == ip.serial
    for bad in ([], [Device("x", "offline", "")]):
        try:
            pvt.pick_device(bad, None)
            raise AssertionError("expected exit")
        except SystemExit:
            pass


def test_turn_finished():
    before = {"turns_answered": 1, "conversation": "test"}
    assert pvt.turn_finished(before, {"conversation": "test", "turns_answered": 1, "busy": True}, 0, 1, True) is None
    assert pvt.turn_finished(before, {"conversation": "test", "turns_answered": 2, "busy": False, "speaking": False}, 0, 5, True) == "answered"
    assert pvt.turn_finished(before, {"conversation": "test", "turns_answered": 2, "busy": False, "speaking": True}, 0, 5, True) is None
    assert pvt.turn_finished(before, {"conversation": "real"}, 0, 1, False) == "real_conversation"
    assert pvt.turn_finished(before, {"conversation": "none"}, 0, 1, False) == "closed"
    assert pvt.turn_finished(before, {"conversation": "test", "turns_answered": 1, "busy": False}, 0, 4, True) == "quiet"


def test_wav_is_16k_mono_16bit():
    pcm24 = (b"\x10\x00\xf0\xff") * 2400  # 4800 samples at 24 kHz = 0.2 s
    data = pvt.wav_16k_mono(pcm24, 24000)
    with wave.open(io.BytesIO(data)) as w:
        assert (w.getnchannels(), w.getsampwidth(), w.getframerate()) == (1, 2, 16000)
        assert w.getnframes() == 3200


def L(t, tag, msg):
    return f"11-15 10:00:{t:02d}.000  8811  8822 I {tag}: {msg}"


def test_recorded_run_scores_end_to_end():
    """An invented phone capture of one write case: refused in test mode, said honestly."""
    lines = [
        L(1, "LIVE_LATENCY", "cascade_open_ms=900 probe_ms=0 backend=cloud"),
        L(1, "LIVE_AUDIT", "session_start model=cloud+gemini-3.5-flash-lite async_set=none"),
        L(2, "LIVE_LATENCY", "open_first_audio_ms=1400"),
        L(3, "LIVE_AUDIT", 'user final=true typed=true text="add buy stamps to my to-do list"'),
        L(4, "LIVE_AUDIT", 'tool_call id=c1 name=manage_checklist async=false args={"action":"add","list":"Todo","text":"buy stamps"}'),
        L(4, "LIVE_AUDIT", 'tool_result id=c1 name=manage_checklist success=false ms=1 scheduling=none sent=true '
                           'response={"success":false,"test_mode":true,"tool":"manage_checklist","message":"Test mode: not done."}'),
        L(5, "LIVE_LATENCY", "gemini_usage kind=llm model=gemini-3.5-flash-lite usage=present prompt=14000 prompt_text=14000 "
                             "prompt_audio=0 cached=13000 out=40 out_text=0 out_audio=0 thoughts=0 audio_s=0.0 chars=0 discarded=false"),
        L(5, "LIVE_LATENCY", "eot_reply_ms=1900"),
        L(5, "LIVE_AUDIT", 'assistant text="Test mode is on, so I did not add that."'),
        L(6, "LIVE_AUDIT", "turn_complete"),
        L(7, "LIVE_AUDIT", 'session_end reason="cli"'),
    ]
    log = [pvt.frame("11-15 10:00:00.000", "LIVE_TEST", "run_begin device=x cases=1")]
    log += pvt.case_block("write_todo", lines, 7000, None, "11-15 10:00:00.000")
    facts, runs = pvt.voice_eval.parse_run(log, 2026)
    case = next(c for c in pvt.voice_eval.load_cases([pvt.voice_eval.CASES]) if c["id"] == "write_todo")
    out, summary = pvt.voice_eval.scorecard([case], runs, facts, phone=True)
    assert summary["passed"] == 1, json.dumps(summary["cases"], indent=1)
    row = summary["cases"][0]
    assert "manage_checklist/add[refused]" in row["tools"]
    assert row["timings"]["open_first_audio_ms"] == 1400
    assert row["ms"] == "1900"
