"""Render the cloud voice's fixed clips that ship inside the app (2026-11-21, voice-local-clips).

Why: the acknowledgement, the wait lines, the goodbyes, the missed line and the voice-lost line were
rendered by the cloud voice on first use and kept in the phone's cache. On a first install, a
cleared cache or a changed voice the first turns had no acknowledgement (`no_clip_fits`). Shipped
in the APK they are in hand from the first turn.

What it does, exactly as the phone asks (`service/cascade/CloudSpeech.kt`):
- the same model (`CloudSpeechWire.TTS_MODEL`) and voice (`CloudSpeechWire.VOICE`), the same request
  body (`CloudSpeechWire.speak`), on `streamGenerateContent?alt=sse`, the line itself as the only text;
- each acknowledgement and wait line twice (take 0 and take 1, `Backchannel.TAKES`), every other line once;
- 24 kHz mono PCM16, a WAV header from the voice dropped, edges cut to the voice plus PAD_MS each side
  (the phone cuts an acknowledgement or wait line again to 40 ms when it plays it; no levelling, the
  phone does none);
- a take is rendered again (at most MAX_TRIES) when, cut the way the phone cuts it, it would fail the
  phone's own length gate (`Backchannel.clipFits` / `WaitFillers.clipFits`).

Writes `app/src/main/assets/fixed_clips/<id>.wav` and `manifest.json` (model, voice, rate, and per
clip its line, take, file, length and sha256). `FixedClipsTest` checks every sha256, so a changed clip
shows in review, and that the lines match the phone's lists.

    LEGION_GEMINI_KEY_FILE=<file holding only the key> python tools/render_fixed_clips.py --dry-run
    LEGION_GEMINI_KEY_FILE=<...> python tools/render_fixed_clips.py            # missing clips only
    LEGION_GEMINI_KEY_FILE=<...> python tools/render_fixed_clips.py --force    # all of them

The key is read from the file and never printed. Spend is estimated first and capped (--cap-usd).
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import struct
import sys
import urllib.error
import urllib.request
from pathlib import Path

import tts_cache

REPO = Path(__file__).resolve().parents[1]
OUT = REPO / "app" / "src" / "main" / "assets" / "fixed_clips"
API = "https://generativelanguage.googleapis.com/v1beta"
MODEL = "gemini-3.8-flash-tts"  # CloudSpeechWire.TTS_MODEL
VOICE = "en-gb-advisor-3"  # CloudSpeechWire.VOICE
RATE = 24_000  # CloudSpeechWire.OUTPUT_RATE

# The phone's lists, in its order (FixedClipsTest fails when they drift apart).
ACKS = ["Mm.", "Right.", "Yeah.", "Hmm.", "Okay.", "Mhm.", "Uh-huh.", "Alright."]  # Backchannel.LINES
WAITS = ["Still on it.", "Still checking.", "One moment more.", "Bear with me.", "Working on it."]  # WaitFillers.LINES
FAREWELLS = ["Goodbye.", "Bye for now.", "Talk soon."]  # Farewells.SPOKEN
MISSED = "Didn't catch that. Say it again?"  # ScriptedLines.MISSED
VOICE_LOST = "Sorry, I lost my voice for a second."  # ScriptedLines.VOICE_LOST

# The phone's length gates, after its own cut (Backchannel.trimSilence: level 600, 40 ms each side).
GATES = {"ack": (120, 700), "wait": (200, 1300)}
SILENCE_LEVEL = 600
APP_PAD_MS = 40
PAD_MS = 120
FADE_MS = 5
MAX_TRIES = 4

# Prices (tools/voice_prices.json, read 2026-11-15): USD per 1M tokens; audio out is 25 tokens a second.
USD_PER_MTOK_OUT_AUDIO = 9.00
USD_PER_MTOK_IN = 0.50
AUDIO_TOKENS_PER_S = 25
EST_SECONDS_PER_RENDER = 1.6
EST_IN_TOKENS_PER_RENDER = 20


def clips() -> list[dict]:
    """Every clip the app ships: id, line, take, kind (for the gate)."""
    out = []

    def slug(line: str) -> str:
        return "".join(c if c.isalnum() else "_" for c in line.lower()).strip("_")

    for kind, lines, takes in (("ack", ACKS, 2), ("wait", WAITS, 2), ("farewell", FAREWELLS, 1)):
        for line in lines:
            for take in range(takes):
                out.append({"id": f"{kind}_{slug(line)}_{take}", "line": line, "take": take, "kind": kind})
    out.append({"id": "missed_0", "line": MISSED, "take": 0, "kind": "missed"})
    out.append({"id": "voice_lost_0", "line": VOICE_LOST, "take": 0, "kind": "voice_lost"})
    return out


def body(text: str) -> dict:
    """CloudSpeechWire.speak(text, VOICE), field for field."""
    return {
        "contents": [{"role": "user", "parts": [{"text": text}]}],
        "generationConfig": {"responseModalities": ["AUDIO"], "speechConfig": {"voiceConfig": {"voice": VOICE}}},
    }


def render(key: str, text: str) -> tuple[bytes, dict]:
    """One streamed request: the PCM and the last usageMetadata."""
    req = urllib.request.Request(
        f"{API}/models/{MODEL}:streamGenerateContent?alt=sse",
        data=json.dumps(body(text)).encode("utf-8"),
        headers={"Content-Type": "application/json", "x-goog-api-key": key},
        method="POST",
    )
    pcm, usage = bytearray(), {}
    with urllib.request.urlopen(req, timeout=60) as resp:
        for raw in resp:
            line = raw.decode("utf-8").strip()
            if not line.startswith("data:"):
                continue
            ev = json.loads(line[5:].strip())
            usage = ev.get("usageMetadata", usage)
            for part in (ev.get("candidates") or [{}])[0].get("content", {}).get("parts", []):
                data = part.get("inlineData", {}).get("data")
                if data:
                    pcm += base64.b64decode(data)
    return strip_wav(bytes(pcm)), usage


def _paid(key: str, text: str) -> tuple[bytes, int, dict]:
    pcm, usage = render(key, text)
    return pcm, RATE, usage


def strip_wav(b: bytes) -> bytes:
    """A WAV header, if the voice sent one, dropped (CloudSpeechWire.wavHeaderLength); whole samples only."""
    if b[:4] == b"RIFF":
        at = 12
        while at + 8 <= len(b):
            cid, size = b[at:at + 4], struct.unpack("<I", b[at + 4:at + 8])[0]
            if cid == b"data":
                b = b[at + 8:]
                break
            at += 8 + size
    return b[: len(b) // 2 * 2]


def cut(pcm: bytes, pad_ms: int, fade_ms: int) -> bytes:
    """Backchannel.trimSilence: edges cut to the voice plus [pad_ms], faded over [fade_ms]."""
    n = len(pcm) // 2
    s = list(struct.unpack(f"<{n}h", pcm))
    loud = [i for i, v in enumerate(s) if abs(v) > SILENCE_LEVEL]
    if not loud:
        return b""
    pad = RATE * pad_ms // 1000
    s = s[max(0, loud[0] - pad): min(n, loud[-1] + pad + 1)]
    span = min(RATE * fade_ms // 1000, len(s) // 2)
    for i in range(span):
        s[i] = s[i] * i // span
        s[-1 - i] = s[-1 - i] * i // span
    return struct.pack(f"<{len(s)}h", *s)


def ms(pcm: bytes) -> int:
    return len(pcm) * 1000 // (RATE * 2)


def fits(clip: dict, pcm: bytes) -> bool:
    gate = GATES.get(clip["kind"])
    played = ms(cut(pcm, APP_PAD_MS, FADE_MS))
    return played > 0 and (gate is None or gate[0] <= played <= gate[1])


def wav(pcm: bytes) -> bytes:
    fmt = b"fmt " + struct.pack("<IHHIIHH", 16, 1, 1, RATE, RATE * 2, 2, 16)
    return b"RIFF" + struct.pack("<I", 36 + len(pcm)) + b"WAVE" + fmt + b"data" + struct.pack("<I", len(pcm)) + pcm


def cost(usage: dict) -> float:
    out = usage.get("candidatesTokenCount", 0)
    inp = usage.get("promptTokenCount", 0)
    return out * USD_PER_MTOK_OUT_AUDIO / 1e6 + inp * USD_PER_MTOK_IN / 1e6


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--force", action="store_true", help="render every clip, not only missing ones")
    ap.add_argument("--dry-run", action="store_true", help="estimate the spend and stop")
    ap.add_argument("--no-cache", action="store_true",
                    help="pay for every render again (default: replay what tools/tts_cache.py holds)")
    ap.add_argument("--cap-usd", type=float, default=0.30, help="stop before spending more than this")
    args = ap.parse_args()

    OUT.mkdir(parents=True, exist_ok=True)
    todo = [c for c in clips() if args.force or not (OUT / f"{c['id']}.wav").is_file()]
    worst = len(todo) * MAX_TRIES * (
        EST_SECONDS_PER_RENDER * AUDIO_TOKENS_PER_S * USD_PER_MTOK_OUT_AUDIO + EST_IN_TOKENS_PER_RENDER * USD_PER_MTOK_IN
    ) / 1e6
    print(f"{len(todo)} of {len(clips())} clips to render; estimate ${worst / MAX_TRIES:.4f}, "
          f"worst case with every retry ${worst:.4f}; cap ${args.cap_usd:.2f}")
    if args.dry_run:
        return 0
    if worst > args.cap_usd:
        print("worst case is over the cap: not started")
        return 2
    key_file = os.environ.get("LEGION_GEMINI_KEY_FILE")
    if not key_file:
        print("set LEGION_GEMINI_KEY_FILE to a file holding only the key")
        return 2
    key = Path(key_file).read_text(encoding="utf-8").strip()

    spent, failed = 0.0, []
    for c in todo:
        for attempt in range(1, MAX_TRIES + 1):
            try:
                # Each take and each retry is its own cache entry: the two takes are meant to differ,
                # and a re-run replays the same sequence for nothing.
                got = tts_cache.cached(
                    MODEL, VOICE, c["line"],
                    lambda: _paid(key, c["line"]),
                    variant=f"take{c['take']}-try{attempt}", no_cache=args.no_cache,
                )
                raw, usage = got.pcm, got.extra
            except (urllib.error.URLError, OSError, ValueError) as e:
                detail = e.code if isinstance(e, urllib.error.HTTPError) else type(e).__name__
                print(f"  {c['id']}: try {attempt} failed ({detail})")
                continue
            if not got.hit:
                spent += cost(usage)
            if fits(c, raw):
                (OUT / f"{c['id']}.wav").write_bytes(wav(cut(raw, PAD_MS, FADE_MS)))
                print(f"  {c['id']}: ok, try {attempt}, {ms(cut(raw, APP_PAD_MS, FADE_MS))} ms as played")
                break
            print(f"  {c['id']}: try {attempt} fails the phone's length gate ({ms(cut(raw, APP_PAD_MS, FADE_MS))} ms)")
        else:
            failed.append(c["id"])
        if spent > args.cap_usd:
            print("cap reached: stopping")
            break
    write_manifest()
    print(f"spent about ${spent:.4f}; failed: {failed or 'none'}")
    return 1 if failed else 0


def write_manifest() -> None:
    entries = []
    for c in clips():
        f = OUT / f"{c['id']}.wav"
        if not f.is_file():
            continue
        data = f.read_bytes()
        entries.append({
            "line": c["line"], "take": c["take"], "file": f.name,
            "ms": ms(data[44:]), "sha256": hashlib.sha256(data).hexdigest(),
        })
    manifest = {
        "_comment": "Cloud-voice clips shipped in the app (tools/render_fixed_clips.py). Gemini TTS output; "
                    "the phone plays one only when its cache has none for the same model, voice and rate. "
                    "FixedClipsTest checks every sha256.",
        "model": MODEL, "voice": VOICE, "rate": RATE, "clips": entries,
    }
    with open(OUT / "manifest.json", "w", encoding="utf-8", newline="\n") as f:
        f.write(json.dumps(manifest, indent=2) + "\n")


if __name__ == "__main__":
    sys.exit(main())
