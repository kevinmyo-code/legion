r"""Drive the phone's voice assistant from the laptop over adb, then score it like the laptop eval.

    python tools/phone_voice_test.py status                  is the phone reachable, is the switch on, is a conversation open
    python tools/phone_voice_test.py run                     every case in cases.json + private/*.json, typed
    python tools/phone_voice_test.py run --only due_open,due_today --audio
    python tools/phone_voice_test.py run --allow-writes write_todo     that case's writes really happen (real data!)
    python tools/phone_voice_test.py end                     close a test conversation left open
    python tools/phone_voice_test.py score RUN.log           re-score a saved run

What it does, per case: opens a TEST conversation on the phone (the app's adb-only door,
`service/testturn/`), sends each turn as Kevin's own words (typed) or as a WAV fed in place of the
microphone (`--audio`, cases marked "audio": true), waits for the reply to finish, then ends the
conversation. The phone's `LIVE_AUDIT` / `LIVE_LATENCY` lines are read from logcat and scored by
`tools/voice_eval.py` (rules; `--grade` adds the LLM column), with open time and turn latency.

**Safety, on the phone (not here):** the Settings switch "Allow test turns from a computer" must be
on; only adb's shell uid is answered; in a test conversation every tool that would change data or act
(add, edit, delete, send, call, control, open a screen) is refused in words with `test_mode`, unless
`--allow-writes <case>` names that case. Reads use his real data. The phone closes a test conversation
itself after 90 s with no command and after 15 minutes in all, and a real tap ends it at once (the
wake word is not listening while any conversation is open). **Here:** a conversation is ended after every case and in `finally`; if a real (non-test)
conversation is open, the run waits up to `--wait-real` seconds and otherwise stops.

**Audio cases** are made from the case text by the laptop voice service (Kyutai) ONLY when its
`/health` says ready with no connection and an empty queue, one short request at a time, cached under
`~/.legion/voice-eval/clips/`. Otherwise they are skipped and the run says so. The service is never
started, stopped or restarted from here.

Output: `~/.legion/voice-eval/phone-<stamp>.log` (real data: kept outside the repo). adb is found
like `tools/phone/adb.py` does ($ADB, %LOCALAPPDATA% SDK, PATH); with several devices, `--serial` or
$ANDROID_SERIAL, else the one wireless device. Stdlib (+ numpy/aiohttp only for audio).
Tests: python -m pytest tools/tests/test_phone_voice_test.py -p no:cacheprovider
"""

from __future__ import annotations

import argparse
import asyncio
import datetime
import hashlib
import importlib.util
import io
import json
import os
import re
import shlex
import subprocess
import sys
import threading
import time
import wave
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
PACKAGE = "com.kevin.legion"
AUTHORITY = f"content://{PACKAGE}.testturn"
REMOTE_DIR = f"/sdcard/Android/data/{PACKAGE}/files/voicetest"
LOG_TAGS = ("LIVE_AUDIT", "LIVE_LATENCY", "LIVE_TEST", "LIVE_TURN")
CLIPS = Path.home() / ".legion" / "voice-eval" / "clips"
VOICE_URL = os.environ.get("LEGION_VOICE_URL", "http://127.0.0.1:8765")


def _load(name: str, file: str):
    spec = importlib.util.spec_from_file_location(name, TOOLS / file)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


voice_eval = _load("voice_eval_for_phone", "voice_eval.py")
adbmod = _load("phone_adb_for_voice", "phone/adb.py")


# --------------------------------------------------------------------------- parsers (pure)


def parse_bundle(out: str) -> dict:
    """`content call` prints `Result: Bundle[{ok=true, error=disabled: ..., turns_answered=2}]`.
    Values with commas cannot be split reliably, so a key is recognised only as `, word=`."""
    m = re.search(r"Bundle\[\{(.*)\}\]", out, re.S)
    if not m:
        if "Could not find provider" in out or "Unknown authority" in out:
            return {"ok": False, "error": "no_door: this build has no test-turn door (install a build with service/testturn)"}
        return {"ok": False, "error": f"no_result: {out.strip()[:200]}"}
    body = m.group(1)
    parts = re.split(r", (?=[A-Za-z_]+=)", body)
    result: dict = {}
    for p in parts:
        if "=" not in p:
            continue
        k, v = p.split("=", 1)
        if v in ("true", "false"):
            result[k] = v == "true"
        elif re.fullmatch(r"-?\d+", v):
            result[k] = int(v)
        else:
            result[k] = v
    return result


def pick_device(devices: list, wanted: str | None) -> str:
    """The serial to use: [wanted] if attached, else the only device, else the only wireless one."""
    ready = [d for d in devices if d.state == "device"]
    if wanted:
        if any(d.serial == wanted for d in ready):
            return wanted
        raise SystemExit(f"device {wanted} is not attached (adb devices: {[d.serial for d in devices]})")
    if len(ready) == 1:
        return ready[0].serial
    wireless = [d for d in ready if ":" in d.serial or "adb-tls-connect" in d.serial]
    if len(wireless) == 1:
        return wireless[0].serial
    # One phone seen twice (its IP:port and its mDNS name): the same model on every wireless entry.
    if wireless and len(wireless) == len(ready) and len({d.model for d in wireless}) == 1 and wireless[0].model:
        return wireless[0].serial
    if not ready:
        raise SystemExit("no phone over adb: pair wireless debugging (adb pair / adb connect) or plug in USB")
    raise SystemExit(f"several devices: pass --serial ({[d.serial for d in ready]})")


def frame(md_time: str, tag: str, msg: str) -> str:
    """A threadtime line in the shape the scorer reads."""
    return f"{md_time}  9999  9999 I {tag}: {msg}"


def phone_stamp(line: str) -> str | None:
    m = adbmod.THREADTIME_RE.match(line)
    return f"{m.group('date')} {m.group('time')}" if m else None


def case_block(cid: str, lines: list[str], ms: int, note: str | None, fallback_stamp: str) -> list[str]:
    """One case's slice of logcat between synthetic LIVE_TEST markers, as the laptop harness writes."""
    first = next((phone_stamp(x) for x in lines if phone_stamp(x)), None) or fallback_stamp
    last = next((phone_stamp(x) for x in reversed(lines) if phone_stamp(x)), None) or first
    end = f"case_end id={cid} ms={ms}" + (f" note={note.replace(' ', '_')}" if note else "")
    return [frame(first, "LIVE_TEST", f"case_begin id={cid}")] + lines + [frame(last, "LIVE_TEST", end)]


def heard(before: dict, now: dict) -> bool:
    """The phone took the turn as his words (a typed turn after the open, a clip after it was fed)."""
    return now.get("user_turns", 0) > before.get("user_turns", 0)


def turn_finished(before: dict, now: dict, heard_at: float | None, clock: float, saw_busy: bool) -> str | None:
    """Why a turn is over, or None to keep waiting. Over when the phone heard the turn, answered one
    more and is quiet with no clip still waiting; or the conversation closed; or a real one took
    over. The greeting's own busy spell never counts: nothing is over before the turn was heard."""
    conv = now.get("conversation")
    if conv == "real":
        return "real_conversation"
    if conv == "none":
        return "closed"
    if now.get("audio_pending") or not heard(before, now):
        return None
    quiet = not now.get("busy") and not now.get("speaking")
    if now.get("turns_answered", 0) > before.get("turns_answered", 0) and quiet:
        return "answered"
    # Answered with a failure (no answer counted): busy after it was heard, quiet again for a while.
    if saw_busy and quiet and heard_at is not None and clock - heard_at > 3:
        return "quiet"
    return None


def wav_16k_mono(pcm: bytes, rate: int) -> bytes:
    """PCM16 mono at [rate] to a 16 kHz WAV file's bytes (linear resampling, stdlib only)."""
    import array

    src = array.array("h")
    src.frombytes(pcm)
    if rate != 16000 and len(src) > 1:
        n = int(len(src) * 16000 / rate)
        out = array.array("h", [0]) * n
        step = rate / 16000
        for i in range(n):
            x = i * step
            a = int(x)
            b = min(a + 1, len(src) - 1)
            f = x - a
            out[i] = int(src[a] * (1 - f) + src[b] * f)
        src = out
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(16000)
        w.writeframes(src.tobytes())
    return buf.getvalue()


# --------------------------------------------------------------------------- the phone


class Phone:
    def __init__(self, adb):
        self.adb = adb

    def call(self, method: str, arg: str | None = None, **extras) -> dict:
        cmd = f"content call --uri {AUTHORITY} --method {method}"
        if arg is not None:
            cmd += f" --arg {shlex.quote(arg)}"
        for k, v in extras.items():
            kind = "b" if isinstance(v, bool) else "s"
            val = str(v).lower() if isinstance(v, bool) else str(v)
            cmd += f" --extra {k}:{kind}:{shlex.quote(val)}"
        _, out = self.adb.run(["shell", cmd], timeout=20)
        return parse_bundle(out)

    def status(self) -> dict:
        return self.call("status")

    def now_stamp(self) -> str:
        _, out = self.adb.run(["shell", "date +'%m-%d %H:%M:%S.000'"], timeout=10)
        return out.strip().strip("'")

    def push(self, local: Path, name: str) -> bool:
        os.environ.setdefault("MSYS_NO_PATHCONV", "1")  # Git Bash would rewrite /sdcard
        self.adb.run(["shell", f"mkdir -p {REMOTE_DIR}"], timeout=10)
        rc, out = self.adb.run(["push", str(local), f"{REMOTE_DIR}/{name}"], timeout=60)
        return rc == 0


class Logcat:
    """`adb logcat` from a phone timestamp on, the conversation tags only, collected in a thread."""

    def __init__(self, adb, since: str):
        cmd = [adb.exe] + (["-s", adb.serial] if adb.serial else []) + [
            "logcat", "-v", "threadtime", "-T", since, "-s"] + [f"{t}:I" for t in LOG_TAGS]
        self.proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        self.lines: list[str] = []
        self.lock = threading.Lock()
        self.thread = threading.Thread(target=self._read, daemon=True)
        self.thread.start()

    def _read(self):
        for raw in self.proc.stdout:
            line = raw.decode("utf-8", "replace").rstrip("\r\n")
            with self.lock:
                self.lines.append(line)

    def mark(self) -> int:
        with self.lock:
            return len(self.lines)

    def since(self, mark: int) -> list[str]:
        with self.lock:
            return [x for x in self.lines[mark:] if any(f" {t}" in x for t in LOG_TAGS)]

    def stop(self):
        self.proc.terminate()


# --------------------------------------------------------------------------- audio clips (laptop voice)


def make_clip(text: str) -> tuple[Path | None, str]:
    """A 16 kHz WAV of [text] from the laptop voice service, or (None, why). Only when idle."""
    CLIPS.mkdir(parents=True, exist_ok=True)
    out = CLIPS / (hashlib.sha256(text.encode()).hexdigest()[:16] + ".wav")
    if out.exists():
        return out, "cached"
    try:
        sys.path.insert(0, str(TOOLS))
        from laptop_voice.client import VoiceClient, read_token  # aiohttp, numpy
    except Exception as e:  # noqa: BLE001
        return None, f"laptop voice client unavailable ({type(e).__name__})"
    try:
        token = read_token()
    except OSError:
        return None, "no laptop voice token file"

    async def go():
        c = VoiceClient(VOICE_URL, token)
        status, health = await c.health()
        if status != 200 or not health:
            return None, f"laptop voice /health {status}"
        if health.get("status") != "ready" or health.get("connections", 0) or health.get("queue", 0):
            return None, (f"laptop voice not idle (status={health.get('status')} "
                          f"connections={health.get('connections')} queue={health.get('queue')})")
        ready = await c.connect()
        rate = int(ready.get("tts", {}).get("sample_rate") or 24000)
        try:
            await c.speak(1, text)
            ev = await c.next_event("tts.done", timeout=30)
            if ev.get("type") != "tts.done":
                detail = " ".join(str(ev.get(k)) for k in ("code", "message") if ev.get(k))
                return None, f"laptop voice said {ev.get('type')} {detail}".strip()
        finally:
            await c.close()
        out.write_bytes(wav_16k_mono(bytes(c.tts[1].pcm), rate))
        return out, "made"

    try:
        return asyncio.run(go())
    except Exception as e:  # noqa: BLE001
        return None, f"laptop voice failed ({type(e).__name__})"


# --------------------------------------------------------------------------- the run


def wait_turn(phone, before: dict, timeout: float, poll: float = 0.5) -> tuple[str, dict]:
    sent = time.monotonic()
    saw_busy = False
    heard_at = None
    now = before
    while time.monotonic() - sent < timeout:
        time.sleep(poll)
        now = phone.status()
        if not now.get("ok", True):
            return "status_failed", now
        if heard_at is None and heard(before, now):
            heard_at = time.monotonic()
        if heard_at is not None:
            saw_busy |= bool(now.get("busy") or now.get("speaking"))
        why = turn_finished(before, now, heard_at, time.monotonic(), saw_busy)
        if why:
            return why, now
    return "timeout", now


def wait_speaking(phone, before: dict, timeout: float, poll: float = 0.25) -> str:
    """Until the reply to the turn just sent is audible (never the greeting: the turn must be heard
    first, and no clip may still be waiting)."""
    t0 = time.monotonic()
    while time.monotonic() - t0 < timeout:
        st = phone.status()
        if st.get("conversation") != "test":
            return f"conversation {st.get('conversation')}"
        if heard(before, st) and not st.get("audio_pending") and st.get("speaking"):
            return "speaking"
        time.sleep(poll)
    return "timeout"


def wait_closed(phone: Phone, timeout: float = 15) -> bool:
    t0 = time.monotonic()
    while time.monotonic() - t0 < timeout:
        if phone.status().get("conversation") in ("none", None):
            return True
        time.sleep(0.5)
    return False


def preflight(phone: Phone, wait_real: float) -> dict:
    st = phone.status()
    if not st.get("ok"):
        raise SystemExit(f"phone: {st.get('error')}")
    if not st.get("enabled"):
        raise SystemExit("phone: the switch is off. Settings, Assistant, Testing: Allow test turns from a computer.")
    if not st.get("service"):
        raise SystemExit("phone: LEGION's assistant service is not running (open LEGION once).")
    t0 = time.monotonic()
    while st.get("conversation") == "real":
        if time.monotonic() - t0 > wait_real:
            raise SystemExit("phone: a real conversation is open; not testing over it.")
        print("a real conversation is open; waiting...")
        time.sleep(5)
        st = phone.status()
    return st


def run(a) -> int:
    files = [Path(p) for p in a.cases] if a.cases else voice_eval.default_case_files()
    cases = voice_eval.load_cases(files)
    if a.only:
        wanted = {x.strip() for x in a.only.split(",")}
        cases = [c for c in cases if c["id"] in wanted]
    allow = {x.strip() for x in (a.allow_writes or "").split(",") if x.strip()}
    adb = adbmod.Adb(serial=None)
    _, out = adb.run(["devices", "-l"], device=False)
    adb.serial = pick_device(adbmod.parse_devices(out), a.serial or os.environ.get("ANDROID_SERIAL"))
    phone = Phone(adb)
    preflight(phone, a.wait_real)
    if phone.status().get("conversation") == "test":
        phone.call("end")
        wait_closed(phone)

    voice_eval.RUNS.mkdir(parents=True, exist_ok=True)
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    log_path = Path(a.out) if a.out else voice_eval.RUNS / f"phone-{stamp}.log"
    since = phone.now_stamp()
    logcat = Logcat(adb, since)
    blocks: list[str] = [frame(since, "LIVE_TEST", f"run_begin device={adb.serial} cases={len(cases)}")]
    stopped = None
    try:
        for case in cases:
            st = phone.status()
            if st.get("conversation") == "real":
                stopped = "a real conversation started"
                break
            cid = case["id"]
            writes = cid in allow
            mark = logcat.mark()
            t0 = time.monotonic()
            note = None
            turns = voice_eval.case_turns(case)
            for i, turn in enumerate(turns):
                before = phone.status()
                barge = bool(turn.get("barge")) and a.audio and bool(case.get("audio"))
                clip = None
                if a.audio and case.get("audio"):
                    clip, why = make_clip(turn["say"])
                    if clip is None:
                        note = f"audio skipped: {why}; sent typed"
                    elif not phone.push(clip, f"{cid}.wav"):
                        note, clip = "audio push failed; sent typed", None
                r = (phone.call("audio", f"{cid}.wav", id=cid, writes=writes, barge=barge) if clip
                     else phone.call("turn", turn["say"], id=cid, writes=writes))
                if not r.get("ok"):
                    note = f"phone refused the turn: {r.get('error')}"
                    break
                # The next turn talks over this reply: send it as soon as the reply is audible.
                nxt = turns[i + 1] if i + 1 < len(turns) else {}
                if nxt.get("barge") and a.audio and case.get("audio"):
                    why = wait_speaking(phone, before, a.turn_timeout)
                    if why != "speaking":
                        note = f"barge-in: the reply never became audible ({why})"
                        break
                    continue
                why, _ = wait_turn(phone, before, a.turn_timeout)
                if why not in ("answered", "quiet"):
                    note = f"turn ended: {why}"
                    break
            phone.call("end")
            wait_closed(phone)
            time.sleep(1.0)  # the last lines reach logcat
            ms = int((time.monotonic() - t0) * 1000)
            blocks += case_block(cid, logcat.since(mark), ms, note, since)
            print(f"{cid}: {'done' if not note else note}")
            if note and "real_conversation" in note:
                stopped = "a real conversation started"
                break
    except KeyboardInterrupt:
        stopped = "interrupted"
    finally:
        try:
            phone.call("end")
        finally:
            logcat.stop()
    if stopped:
        blocks.append(frame(since, "LIVE_TEST", f"note stopped: {stopped}"))
        print(f"STOPPED: {stopped}")
    log_path.write_text("\n".join(blocks) + "\n", encoding="utf-8")
    print(f"run log (real data, outside the repo): {log_path}")
    return score_file(log_path, a)


def score_file(path: Path, a) -> int:
    argv = ["score", str(path), "--phone"]
    for c in a.cases or []:
        argv += ["--cases", c]
    if a.only:
        argv += ["--only", a.only]
    if a.grade:
        argv.append("--grade")
    return voice_eval.main(argv)


def main(argv: list[str] | None = None) -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--serial")
    sub = ap.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("run")
    r.add_argument("--cases", action="append")
    r.add_argument("--only")
    r.add_argument("--audio", action="store_true", help='send cases marked "audio": true as WAV (laptop voice, only if idle)')
    r.add_argument("--allow-writes", help="comma list of case ids whose writes really happen (real data)")
    r.add_argument("--turn-timeout", type=float, default=60)
    r.add_argument("--wait-real", type=float, default=60, help="seconds to wait for a real conversation to end")
    r.add_argument("--out")
    r.add_argument("--grade", action="store_true")
    sub.add_parser("status")
    sub.add_parser("end")
    s = sub.add_parser("score")
    s.add_argument("log")
    s.add_argument("--cases", action="append")
    s.add_argument("--only")
    s.add_argument("--grade", action="store_true")
    a = ap.parse_args(argv)
    if a.cmd == "score":
        return score_file(Path(a.log), a)
    if a.cmd == "run":
        return run(a)
    adb = adbmod.Adb(serial=None)
    _, out = adb.run(["devices", "-l"], device=False)
    adb.serial = pick_device(adbmod.parse_devices(out), a.serial or os.environ.get("ANDROID_SERIAL"))
    phone = Phone(adb)
    result = phone.call("end") if a.cmd == "end" else phone.status()
    print(json.dumps(result, indent=2))
    return 0 if result.get("ok") else 1


if __name__ == "__main__":
    sys.exit(main())
