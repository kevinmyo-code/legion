"""End-to-end check of a RUNNING service with real engines, over the wire as the phone sees it
(minus the network). Checks behaviour and measures latency. Writes nothing unless --samples-out.

    python -m laptop_voice.live_check --wavs <dir of 16 kHz WAVs> --gt <ground_truth.json> [--n 12]
    python -m laptop_voice.live_check --samples-out <dir> --voices dev vctk_p226 ...   (renders lines)

Mic audio is paced in real time: the 100 ms chunk holding samples [k*100, (k+1)*100) ms is sent when
that audio would have finished being captured, (k+1)*100 ms after the start, so "after speech ends"
is measured from the true end of speech in the file. Token from the service's token file (never printed).
"""

from __future__ import annotations

import argparse
import asyncio
import glob
import json
import os
import re
import statistics
import time
import wave

import aiohttp
import numpy as np

from .client import HEADER, KIND_MIC, TtsResult, VoiceClient, read_token

TTS_LINES = [
    "Done.",
    "Twelve minutes, running.",
    "It's four forty-five. You've got the dentist at five thirty, so leave in twenty minutes.",
    "Checking balance is one thousand two hundred eighty-four dollars and seventeen cents.",
    "Still checking the flight. Give me a second.",
]

# Alfred-style sample lines: calm, brief, the number first, never "sir". Numbers in words (the phone
# normalises before sending). A7 and A8 need feeling.
SAMPLE_LINES = {
    "A1": "Twelve minutes, running. I'll tell you when it's done.",
    "A2": "Sixty-one degrees, light rain after three. Take the jacket.",
    "A3": "One thousand two hundred eighty-four in checking. Rent clears Friday, so you're fine.",
    "A4": "Four forty-five now. Dentist at five thirty, so leave in twenty minutes.",
    "A5": "Ninety-two thousand miles on the car. Oil change is due in about six hundred.",
    "A6": "Three things on the list: milk, eggs and chicken thighs. Added.",
    "A7": "They said yes! The offer came through this morning, and it's better than you asked for.",
    "A8": "I'm sorry. That's a hard call to get. Take a minute, I'll hold everything else.",
}


def pct(xs, q):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(q * (len(xs) - 1) + 0.5))]


def summary(name, xs, unit="ms"):
    if not xs:
        return f"{name}: no data"
    return (f"{name}: median {statistics.median(xs):.0f} {unit}, p90 {pct(xs, 0.9):.0f}, "
            f"min {min(xs):.0f}, max {max(xs):.0f}, n={len(xs)}")


def read_wav16(path):
    with wave.open(path, "rb") as w:
        assert w.getframerate() == 16000 and w.getnchannels() == 1 and w.getsampwidth() == 2, path
        return np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").copy()


def speech_end(x, thr=600):
    """Last sample of speech: end of the last 10 ms window whose mean |x| is above thr."""
    win = 160
    n = len(x) // win
    if n == 0:
        return len(x)
    e = np.abs(x[: n * win].astype(np.int32)).reshape(n, win).mean(axis=1)
    loud = np.nonzero(e > thr)[0]
    return int((loud[-1] + 1) * win) if len(loud) else len(x)


def words(s):
    return re.sub(r"[^a-z0-9' ]+", " ", s.lower().replace("-", " ")).split()


def wer(ref, hyp):
    r, h = words(ref), words(hyp)
    d = list(range(len(h) + 1))
    for i in range(1, len(r) + 1):
        prev, d[0] = d[0], i
        for j in range(1, len(h) + 1):
            cur = min(d[j] + 1, d[j - 1] + 1, prev + (r[i - 1] != h[j - 1]))
            prev, d[j] = d[j], cur
    return d[len(h)] / max(1, len(r)), len(r)


def ends_complete(ref: dict, hyp: str) -> bool:
    """True when the transcript reaches the end of the sentence: the last word of the spoken or
    written reference is among the transcript's last two words (or, for a number, its digits end
    the transcript, so "4417" passes and "44" fails)."""
    h = words(hyp)
    if not h:
        return False
    tail = h[-2:]
    for k in ("spoken", "written"):
        r = words(ref.get(k, ""))
        if r and r[-1] in tail:
            return True
    last_w = words(ref.get("written", ""))[-1:] or [""]
    return bool(last_w[0]) and "".join(tail).endswith(last_w[0])


class Stamped:
    """Drains the client's event queue continuously and stamps each event on arrival."""

    def __init__(self, c: VoiceClient):
        self.c, self.log = c, []
        self.task = asyncio.create_task(self._run())

    async def _run(self):
        while True:
            ev = await self.c.events.get()
            self.log.append((time.perf_counter(), ev))

    async def wait(self, pred, timeout=15.0, since=0):
        t_end = time.perf_counter() + timeout
        while time.perf_counter() < t_end:
            for i in range(since, len(self.log)):
                if pred(self.log[i][1]):
                    return self.log[i]
            await asyncio.sleep(0.005)
        raise asyncio.TimeoutError(f"no matching event in {timeout}s")

    def stop(self):
        self.task.cancel()


async def stt_turn(c: VoiceClient, ev: Stamped, uid: int, x: np.ndarray, mode: str = "eot", tail_s: float = 4.0,
                   s_end: int | None = None):
    """mode eot: stream speech then silence until the laptop ends the turn.
    mode client: send stt.end right after the chunk that holds the end of speech.
    s_end: end of speech in samples (default: found by energy; pass the clean twin's for noisy audio)."""
    s_end = speech_end(x) if s_end is None else s_end
    pcm = np.concatenate([x, np.zeros(int(16000 * tail_s), np.int16)])
    since = len(ev.log)
    await c.send({"type": "stt.start", "id": uid})
    t0 = time.perf_counter()
    t_speech_end = t0 + s_end / 16000
    end_chunk = (s_end + 1599) // 1600
    t_end_sent = None
    final = None
    for k in range(0, (len(pcm) + 1599) // 1600):
        delay = t0 + (k + 1) * 0.1 - time.perf_counter()
        if delay > 0:
            await asyncio.sleep(delay)
        if any(e.get("type") == "stt.final" and e.get("id") == uid for _, e in ev.log[since:]):
            break
        await c.ws.send_bytes(HEADER.pack(KIND_MIC, uid) + pcm[k * 1600:(k + 1) * 1600].astype("<i2").tobytes())
        if mode == "client" and k + 1 == end_chunk:
            t_end_sent = time.perf_counter()
            await c.send({"type": "stt.end", "id": uid})
            break
    final = await ev.wait(lambda e: e.get("type") in ("stt.final", "error") and e.get("id") == uid, 10, since)
    t_final, fe = final
    pauses = [(t, e) for t, e in ev.log[since:] if e.get("type") == "stt.pause" and e.get("id") == uid]
    first_hi = next((t for t, e in pauses if t >= t_speech_end and e["prob"] >= 0.6), None)
    partial_ts = [t for t, e in ev.log[since:] if e.get("type") == "stt.partial" and e.get("id") == uid]
    return {
        "final": fe,
        "after_speech_ms": (t_final - t_speech_end) * 1000,
        "after_end_ms": (t_final - t_end_sent) * 1000 if t_end_sent else None,
        "pause_hi_after_speech_ms": (first_hi - t_speech_end) * 1000 if first_hi else None,
        "n_pause": len(pauses), "n_partial": len(partial_ts),
        "first_partial_ms": (partial_ts[0] - t0) * 1000 if partial_ts else None,
    }


async def speak(c: VoiceClient, ev: Stamped, tid: int, text: str, voice: str | None = None):
    since = len(ev.log)
    t0 = await c.speak(tid, text, voice)
    t_done, done = await ev.wait(lambda e: e.get("type") in ("tts.done", "error") and e.get("id") == tid, 60, since)
    r = c.tts[tid]
    audio_s = len(r.pcm) / 2 / 24000
    return {"done": done, "first_ms": (r.first_frame_s - t0) * 1000 if r.first_frame_s else None,
            "total_s": t_done - t0, "audio_s": audio_s, "rtf": (t_done - t0) / audio_s if audio_s else None,
            "pcm": bytes(r.pcm)}


async def speak_streamed(c: VoiceClient, ev: Stamped, tid: int, text: str, words_per_delta=3, delta_s=0.12):
    """Text arrives like an LLM stream: a few words every delta_s."""
    since = len(ev.log)
    c.tts[tid] = TtsResult()
    t0 = time.perf_counter()
    await c.send({"type": "tts.stream.start", "id": tid})
    ws = text.split(" ")
    for i in range(0, len(ws), words_per_delta):
        await c.send({"type": "tts.stream.text", "id": tid, "text": " ".join(ws[i:i + words_per_delta]) + " "})
        await asyncio.sleep(delta_s)
    t_text_end = time.perf_counter()
    await c.send({"type": "tts.stream.end", "id": tid})
    t_done, done = await ev.wait(lambda e: e.get("type") in ("tts.done", "error") and e.get("id") == tid, 60, since)
    r = c.tts[tid]
    audio_s = len(r.pcm) / 2 / 24000
    return {"done": done, "first_ms": (r.first_frame_s - t0) * 1000 if r.first_frame_s else None,
            "text_s": t_text_end - t0, "total_s": t_done - t0, "audio_s": audio_s}


def median_f0(pcm_bytes, sr=24000):
    x = np.frombuffer(pcm_bytes, dtype="<i2").astype(np.float32)
    win, hop, out = int(0.04 * sr), int(0.02 * sr), []
    for i in range(0, len(x) - win, hop):
        f = x[i:i + win] - x[i:i + win].mean()
        if np.sqrt((f ** 2).mean()) < 800:
            continue
        ac = np.correlate(f, f, "full")[win - 1:]
        lo, hi = sr // 300, sr // 60
        lag = lo + int(np.argmax(ac[lo:hi]))
        if ac[lag] > 0.3 * ac[0]:
            out.append(sr / lag)
    return float(np.median(out)) if out else None


def _ltas(pcm_bytes):
    x = np.frombuffer(pcm_bytes, dtype="<i2").astype(np.float32)
    n = 1024
    fr = [x[i:i + n] for i in range(0, len(x) - n, n // 2)]
    fr = [f for f in fr if np.sqrt((f ** 2).mean()) > 500] or fr
    s = 20 * np.log10(np.mean([np.abs(np.fft.rfft(f * np.hanning(n))) for f in fr], axis=0) + 1e-6)
    return s - s.mean()


def ltas_dist(a, b):
    """RMS dB distance between long-term average spectra: 1.2 to 3.1 same voice, 4.5 to 9.3 another
    voice (measured on Kyutai renders)."""
    return float(np.sqrt(((_ltas(a) - _ltas(b)) ** 2).mean()))


def write_wav(path, pcm_bytes, sr=24000):
    with wave.open(path, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(sr); w.writeframes(pcm_bytes)


async def checks(url, token):
    """Behaviour checks that do not need audio files. Returns list of (name, ok, detail)."""
    out = []
    async with aiohttp.ClientSession() as s:
        async with s.get(url + "/livez") as r:
            out.append(("livez 204 no auth", r.status == 204 and await r.read() == b"", r.status))
        async with s.get(url + "/health") as r:
            out.append(("health without token 401", r.status == 401, r.status))
        async with s.get(url + "/admin") as r:
            out.append(("unknown route 404", r.status == 404, r.status))
        try:
            await s.ws_connect(url.replace("http", "ws", 1) + "/v1/conversation", headers={"Authorization": "Bearer x" * 3})
            out.append(("ws bad token refused before upgrade", False, "upgraded"))
        except aiohttp.WSServerHandshakeError as e:
            out.append(("ws bad token refused before upgrade", e.status == 401, e.status))
    st, body = await VoiceClient(url, token).health()
    out.append(("health with token ready", st == 200 and body["status"] == "ready", body and body["status"]))
    return out, body


async def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--url", default="http://127.0.0.1:8765")
    p.add_argument("--wavs", default=None)
    p.add_argument("--gt", default=None)
    p.add_argument("--n", type=int, default=12)
    p.add_argument("--noisy", action="store_true", help="use the *_noisy.wav files instead of the clean ones")
    p.add_argument("--keep-text", action="store_true", help="put transcripts in --json-out (synthetic audio only)")
    p.add_argument("--samples-out", default=None)
    p.add_argument("--voices", nargs="*", default=None)
    p.add_argument("--lines", nargs="*", default=None, help="sample line keys to render (default all)")
    p.add_argument("--json-out", default=None, help="write raw numbers here (outside the repo)")
    a = p.parse_args()
    token = read_token()
    raw: dict = {}

    if a.samples_out:
        c = VoiceClient(a.url, token)
        ready = await c.connect()
        ev = Stamped(c)
        voices = a.voices or ready["tts"]["voices"]
        tid = 1000
        for v in voices:
            d = os.path.join(a.samples_out, f"kyutai-tts-1.6b_{v}")
            os.makedirs(d, exist_ok=True)
            for name, line in SAMPLE_LINES.items():
                if a.lines and name not in a.lines:
                    continue
                tid += 1
                r = await speak(c, ev, tid, line, v)
                write_wav(os.path.join(d, f"{name}.wav"), r["pcm"])
                f0 = median_f0(r["pcm"])
                raw.setdefault("samples", []).append({"voice": v, "line": name, "first_ms": r["first_ms"],
                                                      "total_s": r["total_s"], "audio_s": r["audio_s"],
                                                      "rtf": r["rtf"], "f0": f0})
                print(f"{v} {name} first {r['first_ms']:.0f} ms, {r['audio_s']:.2f} s audio in {r['total_s']:.2f} s "
                      f"(RTF {r['rtf']:.2f}), median F0 {f0 and round(f0)} Hz", flush=True)
        ev.stop(); await c.close()
        if a.json_out:
            with open(a.json_out, "w", encoding="utf-8") as fh:
                json.dump(raw, fh, indent=1)
        return

    res, body = await checks(a.url, token)
    print("health:", json.dumps({k: body[k] for k in ("stt", "tts", "capabilities", "gpu_free_mb", "ram_pct")}) if body else None)
    c = VoiceClient(a.url, token)
    ready = await c.connect()
    ev = Stamped(c)
    res.append(("ready.stt.end_of_turn true", ready["stt"]["end_of_turn"] is True, ready["stt"]["end_of_turn"]))
    res.append(("capabilities all true", ready["capabilities"] == {"end_of_turn": True, "partials": True,
                                                                   "text_streaming": True}, ready["capabilities"]))
    # second conversation: Kyutai holds one (batch size 1)
    async with aiohttp.ClientSession() as s:
        ws2 = await s.ws_connect(a.url.replace("http", "ws", 1) + "/v1/conversation",
                                 headers={"Authorization": f"Bearer {token}"})
        msg = await ws2.receive()
        res.append(("second conversation closed 1013 (busy)", msg.type == aiohttp.WSMsgType.CLOSE and ws2.close_code == 1013,
                    ws2.close_code))

    # ---- STT ----
    gt = {}
    if a.gt:
        with open(a.gt, encoding="utf-8") as fh:
            gt = {s["idx"]: s for s in json.load(fh)["sentences"]}
    files = []
    if a.wavs:
        allf = sorted(f for f in glob.glob(os.path.join(a.wavs, "*.wav")) if ("noisy" in f) == a.noisy)
        # spread across speakers
        by_spk: dict = {}
        for f in allf:
            by_spk.setdefault(os.path.basename(f).split("_")[0], []).append(f)
        while len(files) < a.n and any(by_spk.values()):
            for k in list(by_spk):
                if by_spk[k] and len(files) < a.n:
                    files.append(by_spk[k].pop(0))
    eot, cli, eot_pause, wers, n_eot = [], [], [], [], 0
    cut, n_ref = [], 0
    uid = 1
    for f in files:
        x = read_wav16(f)
        idx = int(re.findall(r"_(\d+)(?:_noisy)?\.wav$", f)[0])
        twin = f.replace("_noisy.wav", ".wav")
        s_end = speech_end(read_wav16(twin)) if a.noisy and os.path.exists(twin) else None
        for mode in ("eot", "client"):
            uid += 1
            r = await stt_turn(c, ev, uid, x, mode, s_end=s_end)
            fe = r["final"]
            if fe.get("type") != "stt.final":
                print("  STT error", os.path.basename(f), fe)
                continue
            ref = gt.get(idx, {})
            w = min((wer(ref[k], fe["text"])[0] for k in ("spoken", "written") if k in ref), default=None)
            if ref:
                n_ref += 1
                if not ends_complete(ref, fe["text"]):
                    cut.append(f"{mode}:{os.path.basename(f)}")
            if mode == "eot":
                if fe["reason"] == "end_of_turn":
                    n_eot += 1
                    eot.append(r["after_speech_ms"])
                if r["pause_hi_after_speech_ms"] is not None:
                    eot_pause.append(r["pause_hi_after_speech_ms"])
                if w is not None:
                    wers.append(w)
            else:
                cli.append(r["after_end_ms"])
            raw.setdefault("stt", []).append({"file": os.path.basename(f), "mode": mode, "reason": fe["reason"],
                                              "after_speech_ms": r["after_speech_ms"], "after_end_ms": r["after_end_ms"],
                                              "pause_hi_ms": r["pause_hi_after_speech_ms"], "wer": w,
                                              "n_partial": r["n_partial"], "first_partial_ms": r["first_partial_ms"],
                                              "decode_ms": fe["decode_ms"]})
            if a.keep_text:
                raw["stt"][-1].update(text=fe["text"], ref=ref.get("written"))
            print(f"  {mode:6s} {os.path.basename(f):14s} reason={fe['reason']:11s} "
                  f"after speech {r['after_speech_ms']:5.0f} ms"
                  + (f", after stt.end {r['after_end_ms']:4.0f} ms" if r["after_end_ms"] else "")
                  + f", partials {r['n_partial']}, WER {w if w is None else round(w, 2)}", flush=True)
    if files:
        res.append(("laptop ended every turn itself (reason end_of_turn)", n_eot == len(files), f"{n_eot}/{len(files)}"))
        if n_ref:
            res.append(("no final cut short (last reference word present)", not cut,
                        f"{n_ref - len(cut)}/{n_ref} complete" + (f"; cut: {', '.join(cut)}" if cut else "")))

    # ---- TTS: whole lines ----
    first, rtf = [], []
    for j in range(2):
        for k, line in enumerate(TTS_LINES):
            r = await speak(c, ev, 100 + j * 10 + k, line)
            if r["done"].get("type") == "tts.done" and r["first_ms"] is not None:
                first.append(r["first_ms"]); rtf.append(r["rtf"])
                raw.setdefault("tts", []).append({k2: r[k2] for k2 in ("first_ms", "total_s", "audio_s", "rtf")})
            else:
                print("  TTS error", r["done"])
    # ---- TTS: streamed text ----
    sfirst = []
    for k, line in enumerate(TTS_LINES[2:]):
        r = await speak_streamed(c, ev, 200 + k, line)
        if r["done"].get("type") == "tts.done" and r["first_ms"] is not None:
            sfirst.append(r["first_ms"])
            raw.setdefault("tts_stream", []).append({k2: r[k2] for k2 in ("first_ms", "text_s", "total_s", "audio_s")})
            print(f"  streamed: first audio {r['first_ms']:.0f} ms after stream.start (text took {r['text_s']:.2f} s), "
                  f"{r['audio_s']:.2f} s audio, done at {r['total_s']:.2f} s", flush=True)
        else:
            print("  stream error", r["done"])
    res.append(("streamed text produced audio", len(sfirst) == 3, len(sfirst)))

    # ---- concurrency: listening while speaking (barge-in path) ----
    if files:
        x = read_wav16(files[0])
        uid += 1
        spk = asyncio.create_task(speak(c, ev, 300, TTS_LINES[3] + " " + TTS_LINES[2]))
        await asyncio.sleep(0.3)
        r = await stt_turn(c, ev, uid, x, "eot")
        s = await spk
        ok = r["final"].get("type") == "stt.final" and s["done"].get("type") == "tts.done"
        res.append(("STT and TTS at once: both complete", ok, f"stt {r['final'].get('reason')}, tts {s['done'].get('type')}"))
        raw["concurrent"] = {"stt_after_speech_ms": r["after_speech_ms"], "tts_rtf": s["rtf"], "tts_first_ms": s["first_ms"]}
        print(f"  concurrent: STT final {r['after_speech_ms']:.0f} ms after speech, TTS RTF {s['rtf']:.2f}, "
              f"first {s['first_ms']:.0f} ms", flush=True)

    # ---- the voice field really changes the voice (it once did not: the swap kept the first voice) ----
    vs = ready["tts"]["voices"]
    if len(vs) >= 2:
        probe = TTS_LINES[2]
        a1 = await speak(c, ev, 350, probe, vs[0])
        b1 = await speak(c, ev, 351, probe, vs[1])
        a2 = await speak(c, ev, 352, probe, vs[0])
        if all(r["pcm"] for r in (a1, b1, a2)):
            same, diff = ltas_dist(a1["pcm"], a2["pcm"]), ltas_dist(a1["pcm"], b1["pcm"])
            # measured: other voice 4.5 to 9.3, same voice and text twice 1.2 to 3.1 (sampling varies)
            res.append((f"voice {vs[1]} differs from {vs[0]}, and back", diff > 4.0 and same < diff / 2,
                        f"spectral distance {diff:.1f} between voices, {same:.1f} same voice twice"))

    # ---- cancel mid-sentence ----
    long = ("Here is the long version. " + TTS_LINES[2] + " " + TTS_LINES[3] + " " + TTS_LINES[4])
    since = len(ev.log)
    c.tts[400] = TtsResult()
    t0 = await c.speak(400, long)
    while c.tts[400].first_frame_s is None and time.perf_counter() - t0 < 10:
        await asyncio.sleep(0.01)
    await asyncio.sleep(0.4)
    t_cancel = time.perf_counter()
    await c.send({"type": "tts.cancel", "id": 400})
    t_done, done = await ev.wait(lambda e: e.get("type") in ("tts.done", "error") and e.get("id") == 400, 15, since)
    got_s = len(c.tts[400].pcm) / 2 / 24000
    res.append(("tts.cancel mid-sentence stops audio", done.get("cancelled") is True and got_s < 4.0,
                f"done {(t_done - t_cancel) * 1000:.0f} ms after cancel, {got_s:.2f} s audio sent"))
    # streamed cancel
    since = len(ev.log)
    c.tts[401] = TtsResult()
    await c.send({"type": "tts.stream.start", "id": 401})
    await c.send({"type": "tts.stream.text", "id": 401, "text": long})
    await c.send({"type": "tts.stream.end", "id": 401})
    t0 = time.perf_counter()
    while c.tts[401].first_frame_s is None and time.perf_counter() - t0 < 10:
        await asyncio.sleep(0.01)
    await asyncio.sleep(0.4)
    t_cancel = time.perf_counter()
    await c.send({"type": "tts.cancel", "id": 401})
    t_done, done = await ev.wait(lambda e: e.get("type") in ("tts.done", "error") and e.get("id") == 401, 15, since)
    got_s = len(c.tts[401].pcm) / 2 / 24000
    res.append(("streamed tts.cancel stops audio", done.get("cancelled") is True and got_s < 4.0,
                f"done {(t_done - t_cancel) * 1000:.0f} ms after cancel, {got_s:.2f} s audio sent"))

    # ---- limits ----
    since = len(ev.log)
    await c.speak(500, "x" * 1001)
    _, e = await ev.wait(lambda e: e.get("id") == 500, 5, since)
    res.append(("text over 1000 chars refused", e.get("code") == "bad_request", e.get("code")))
    await c.speak(501, "hello", voice="../../windows/win.ini")
    _, e = await ev.wait(lambda e: e.get("id") == 501, 5, since)
    res.append(("voice outside allowlist refused", e.get("code") == "bad_request", e.get("code")))
    await c.speak(502, "hello", voice="am_puck")
    _, e = await ev.wait(lambda e: e.get("id") == 502, 5, since)
    res.append(("Kokoro voice id refused on Kyutai", e.get("code") == "bad_request", e.get("code")))
    await c.ws.send_bytes(HEADER.pack(KIND_MIC, 9) + b"\x00" * (64 * 1024 + 2))
    _, e = await ev.wait(lambda e: e.get("type") == "_closed", 5, since)
    res.append(("frame over 64 KB closes 1009", e.get("code") == 1009, e.get("code")))
    ev.stop(); await c.close()

    print()
    print(summary("STT final after speech ends, laptop end of turn", eot))
    print(summary("STT: first stt.pause >= 0.6 after speech ends", eot_pause))
    print(summary("STT final after stt.end (phone ends turn)", cli))
    if wers:
        print(f"STT WER (min of spoken/written ref, {'noisy' if a.noisy else 'clean'} synthetic): mean {statistics.mean(wers) * 100:.1f}%, n={len(wers)}")
    print(summary("TTS first audio, tts.speak", first))
    print(summary("TTS RTF (request to done / audio), tts.speak", [x * 1000 for x in rtf], unit="x1000"))
    print(summary("TTS first audio, streamed text (from stream.start)", sfirst))
    print()
    for name, ok, detail in res:
        print(f"{'PASS' if ok else 'FAIL'}  {name}  ({detail})")
    raw["checks"] = [{"name": n, "ok": bool(o), "detail": str(d)} for n, o, d in res]
    if a.json_out:
        with open(a.json_out, "w", encoding="utf-8") as fh:
            json.dump(raw, fh, indent=1)


if __name__ == "__main__":
    asyncio.run(main())
