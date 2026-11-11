"""Latency check against a RUNNING service: final-transcript latency after stt.end, and TTS first
audio, over the wire exactly as the phone sees it (minus the network).

    python -m laptop_voice.measure --wavs <dir of 16 kHz mono WAVs> [--n 10] [--realtime]

Prints medians and p90s; writes nothing. Token from LEGION_VOICE_TOKEN.
"""

from __future__ import annotations

import argparse
import asyncio
import glob
import os
import statistics
import time

import numpy as np
import soundfile as sf

from .client import VoiceClient, read_token

LINES = [
    "Done.",
    "Twelve minutes, running.",
    "It's four forty-five. You've got the dentist at five thirty, so leave in twenty minutes.",
    "Checking balance is one thousand two hundred eighty-four dollars and seventeen cents.",
    "Still checking the flight. Give me a second.",
]


def pct(xs, q):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(q * (len(xs) - 1) + 0.5))]


async def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--url", default="http://127.0.0.1:8765")
    p.add_argument("--wavs", required=True)
    p.add_argument("--n", type=int, default=10)
    p.add_argument("--realtime", action="store_true", help="send mic frames at real-time pace")
    p.add_argument("--lang", default="auto", help="hello lang: auto or an ISO 639-1 code")
    a = p.parse_args()
    c = VoiceClient(a.url, read_token())
    t = time.perf_counter()
    st, body = await c.health()
    print(f"health {st} {body and body['status']} in {(time.perf_counter() - t) * 1000:.0f} ms")
    t = time.perf_counter()
    ready = await c.connect(lang=a.lang)
    print(f"connect+hello {(time.perf_counter() - t) * 1000:.0f} ms; stt={ready['stt']['model']} tts={ready['tts']['model']}")
    files = sorted(f for f in glob.glob(os.path.join(a.wavs, "*.wav")) if "noisy" not in f)[: a.n]
    stt_ms, dec_ms = [], []
    for i, f in enumerate(files, start=1):
        x, sr = sf.read(f, dtype="int16")
        assert sr == 16000
        await c.stream_utterance(i, x, realtime=a.realtime)
        t_end = time.perf_counter()
        ev = await c.next_event("stt.final")
        stt_ms.append((time.perf_counter() - t_end) * 1000)
        dec_ms.append(ev.get("decode_ms", 0))
        print(f"  stt {os.path.basename(f)} {len(x) / 16000:.1f}s -> {stt_ms[-1]:.0f} ms: {ev.get('text', ev)[:70]!r}")
    first_ms, srv_first = [], []
    for j in range(3):
        for k, line in enumerate(LINES):
            tid = 100 + j * 10 + k
            t0 = await c.speak(tid, line)
            done = await c.next_event("tts.done")
            first_ms.append((c.tts[tid].first_frame_s - t0) * 1000)
            srv_first.append(done["first_audio_ms"])
    print(f"STT final after stt.end: median {statistics.median(stt_ms):.0f} ms, p90 {pct(stt_ms, 0.9):.0f} ms "
          f"(server decode median {statistics.median(dec_ms):.0f} ms), n={len(stt_ms)}")
    print(f"TTS first audio frame: median {statistics.median(first_ms):.0f} ms, p90 {pct(first_ms, 0.9):.0f} ms, "
          f"n={len(first_ms)} (server-side median {statistics.median(srv_first):.0f} ms)")
    await c.close()


if __name__ == "__main__":
    asyncio.run(main())
