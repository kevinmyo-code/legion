"""Render Alfred's scripted opening lines to WAV clips (voice cost audit 2026-11-10, row F2).

A tap or the wake word used to cost a full billed Live generation (a spoken greeting). The phone
now plays one of these clips instead and asks the model for nothing.

ONE list, any voice:
- The lines live in `app/src/main/assets/listen_cues/lines.json`; the phone's picker
  (`service/ListenCue.kt`) reads the same file.
- Each line becomes `app/src/main/assets/listen_cues/<id>.wav`, 24 kHz mono PCM16 (the format the
  phone's voice track plays). Whatever a renderer returns is converted to that.
- lines.json `voice` names the voice the clips are in. The phone plays them only when it matches
  the runtime voice, else a short earcon. Swapping voices is data: new WAVs plus that field.
- Renderers are pluggable (`RENDERERS`):
    gemini   Gemini TTS, a prebuilt voice (Puck today, while Gemini Live is the runtime). PAID.
    command  any local TTS: a shell command that writes a WAV, e.g. for the planned cascade's
             local voice:  --renderer command --voice my-local-voice \
                           --cmd "piper --model voice.onnx --output_file {out}"   (text on stdin)

    GEMINI_API_KEY=... python tools/render_listen_cues.py                 # Puck, missing clips only
    python tools/render_listen_cues.py --key-file <file holding only the key> --force
    python tools/render_listen_cues.py --renderer command --voice <name> --cmd "<...{out}...>" --force
    python tools/render_listen_cues.py --list-models                      # free: Gemini TTS model ids
    python tools/render_listen_cues.py --dry-run

One pass, no retries: a line that fails is reported and left to the earcon. Lines that already
have a clip are skipped unless --force. Rendering in a voice other than lines.json's needs
--force (every clip must be in one voice), and then rewrites that field. The key comes from
GEMINI_API_KEY or --key-file and is never printed.
"""

from __future__ import annotations

import argparse
import base64
import io
import json
import os
import shlex
import struct
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
import wave
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
CUES = REPO / "app" / "src" / "main" / "assets" / "listen_cues"
LINES = CUES / "lines.json"
API = "https://generativelanguage.googleapis.com/v1beta"
RATE = 24_000
# Alfred: calm, fast, dry; never "sir". Only the line itself is sent: a style instruction in front of
# it (tried 2026-11-10) was read aloud with the line, giving 7 to 9 s clips. MAX_CLIP_S guards that.
MAX_CLIP_S = 2.5


# ---- renderers: text in, (pcm16 mono bytes, sample rate) out ------------------------------------


class GeminiRenderer:
    """Gemini TTS with a prebuilt voice. Paid per call."""

    def __init__(self, voice: str, key: str, model: str):
        self.voice, self.key, self.model = voice, key, model

    def render(self, text: str) -> tuple[bytes, int]:
        body = {
            "contents": [{"parts": [{"text": text}]}],
            "generationConfig": {
                "responseModalities": ["AUDIO"],
                "speechConfig": {"voiceConfig": {"prebuiltVoiceConfig": {"voiceName": self.voice}}},
            },
        }
        resp = _call(f"{API}/models/{self.model}:generateContent", self.key, body)
        part = resp["candidates"][0]["content"]["parts"][0]["inlineData"]
        mime = part.get("mimeType", "")
        rate = int(mime.split("rate=")[1].split(";")[0]) if "rate=" in mime else RATE
        return base64.b64decode(part["data"]), rate


class CommandRenderer:
    """Any local TTS: runs --cmd with the text on stdin; {text} and {out} are substituted."""

    def __init__(self, cmd: str):
        self.cmd = cmd

    def render(self, text: str) -> tuple[bytes, int]:
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "clip.wav"
            argv = [a.replace("{text}", text).replace("{out}", str(out)) for a in shlex.split(self.cmd)]
            subprocess.run(argv, input=text.encode("utf-8"), check=True, timeout=120)
            return wav_to_mono16(out.read_bytes())


RENDERERS = ("gemini", "command")


# ---- audio helpers ------------------------------------------------------------------------------


def wav_to_mono16(data: bytes) -> tuple[bytes, int]:
    with wave.open(io.BytesIO(data)) as w:
        if w.getsampwidth() != 2:
            raise ValueError(f"need 16-bit WAV, got {8 * w.getsampwidth()}-bit")
        frames, ch, rate = w.readframes(w.getnframes()), w.getnchannels(), w.getframerate()
    samples = struct.unpack(f"<{len(frames) // 2}h", frames)
    if ch > 1:
        samples = tuple(sum(samples[i:i + ch]) // ch for i in range(0, len(samples), ch))
    return struct.pack(f"<{len(samples)}h", *samples), rate


def resample(pcm: bytes, rate: int) -> bytes:
    """Linear resample to RATE; a no-op at RATE."""
    if rate == RATE:
        return pcm
    src = struct.unpack(f"<{len(pcm) // 2}h", pcm)
    n = int(len(src) * RATE / rate)
    out = []
    for i in range(n):
        x = i * rate / RATE
        j = int(x)
        f = x - j
        a = src[min(j, len(src) - 1)]
        b = src[min(j + 1, len(src) - 1)]
        out.append(int(a + (b - a) * f))
    return struct.pack(f"<{n}h", *out)


def trim(pcm: bytes, threshold: int = 300, pad_ms: int = 30) -> bytes:
    """Cuts leading and trailing near-silence so the clip starts at once, keeping a short pad."""
    n = len(pcm) // 2
    samples = struct.unpack(f"<{n}h", pcm[: n * 2])
    loud = [i for i, s in enumerate(samples) if abs(s) > threshold]
    if not loud:
        return pcm
    pad = RATE * pad_ms // 1000
    start, end = max(0, loud[0] - pad), min(n, loud[-1] + pad)
    return struct.pack(f"<{end - start}h", *samples[start:end])


def to_wav(pcm: bytes) -> bytes:
    header = b"RIFF" + struct.pack("<I", 36 + len(pcm)) + b"WAVE"
    fmt = b"fmt " + struct.pack("<IHHIIHH", 16, 1, 1, RATE, RATE * 2, 2, 16)
    return header + fmt + b"data" + struct.pack("<I", len(pcm)) + pcm


# ---- Gemini plumbing ----------------------------------------------------------------------------


def _call(url: str, key: str, body: dict | None = None) -> dict:
    req = urllib.request.Request(
        url,
        data=None if body is None else json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json", "x-goog-api-key": key},
        method="GET" if body is None else "POST",
    )
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.loads(resp.read().decode("utf-8"))


def tts_models(key: str) -> list[str]:
    """Model ids whose name says TTS and that serve generateContent. Listing models is free."""
    out, token = [], None
    while True:
        page = _call(f"{API}/models?pageSize=200" + (f"&pageToken={token}" if token else ""), key)
        for m in page.get("models", []):
            name = m.get("name", "").removeprefix("models/")
            if "tts" in name and "generateContent" in m.get("supportedGenerationMethods", []):
                out.append(name)
        token = page.get("nextPageToken")
        if not token:
            return out


def pick_model(models: list[str]) -> str:
    """The newest flash TTS model (not lite), else any TTS model."""
    flash = [m for m in models if "flash" in m and "lite" not in m] or models
    if not flash:
        raise SystemExit("No TTS model is served to this key; pass --model.")
    return sorted(flash)[-1]


def read_key(key_file: str | None) -> str:
    key = (Path(key_file).read_text(encoding="utf-8").strip() if key_file
           else os.environ.get("GEMINI_API_KEY", "").strip())
    if not key:
        raise SystemExit("No key: set GEMINI_API_KEY or pass --key-file. (It is never printed.)")
    return key


# ---- main ---------------------------------------------------------------------------------------


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--renderer", choices=RENDERERS, default="gemini")
    ap.add_argument("--voice", help="voice name; default: lines.json's voice")
    ap.add_argument("--cmd", help="command renderer: argv template with {out} (and optional {text})")
    ap.add_argument("--key-file")
    ap.add_argument("--model", default="auto", help="Gemini TTS model id, or auto (newest flash TTS)")
    ap.add_argument("--force", action="store_true", help="re-render lines that already have a clip")
    ap.add_argument("--only", nargs="*", help="render just these ids")
    ap.add_argument("--list-models", action="store_true")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    data = json.loads(LINES.read_text(encoding="utf-8"))
    lines = data["lines"]
    if len({ln["id"] for ln in lines}) != len(lines):
        raise SystemExit("lines.json has a duplicate id")
    current_voice = data.get("voice", "Puck")
    voice = args.voice or current_voice
    if voice != current_voice and not (args.force and args.only is None):
        raise SystemExit(f"clips are in {current_voice}; a new voice ({voice}) needs --force over every line")

    todo = [ln for ln in lines if (args.only is None or ln["id"] in args.only)
            and (args.force or not (CUES / f"{ln['id']}.wav").exists())]
    if args.dry_run:
        print(f"{len(todo)} of {len(lines)} lines to render, renderer {args.renderer}, voice {voice}:")
        for ln in todo:
            print(f"  {ln['id']}: {ln['text']}")
        return 0

    if args.renderer == "gemini":
        key = read_key(args.key_file)
        if args.list_models:
            print("\n".join(tts_models(key)))
            return 0
        model = pick_model(tts_models(key)) if args.model == "auto" else args.model
        renderer = GeminiRenderer(voice, key, model)
        print(f"renderer gemini, model {model}, voice {voice}, {len(todo)} line(s)")
    else:
        if not args.cmd:
            raise SystemExit("--renderer command needs --cmd")
        renderer = CommandRenderer(args.cmd)
        print(f"renderer command, voice {voice}, {len(todo)} line(s)")

    failed = []
    for ln in todo:  # one pass, no retries
        try:
            pcm, rate = renderer.render(ln["text"])
            pcm = trim(resample(pcm, rate))
            secs = len(pcm) / 2 / RATE
            if secs > MAX_CLIP_S:
                raise ValueError(f"clip is {secs:.2f} s, over {MAX_CLIP_S} s: the voice said more than the line")
            (CUES / f"{ln['id']}.wav").write_bytes(to_wav(pcm))
            print(f"  ok   {ln['id']}  {len(pcm) / 2 / RATE:.2f}s")
        except (urllib.error.URLError, subprocess.SubprocessError, OSError, KeyError, IndexError,
                ValueError, wave.Error) as e:
            detail = e.code if isinstance(e, urllib.error.HTTPError) else type(e).__name__
            failed.append(ln["id"])
            print(f"  FAIL {ln['id']}  ({detail}); the phone plays the earcon for it")
    if voice != current_voice:
        data["voice"] = voice
        LINES.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        print(f"lines.json voice is now {voice}")
    print(f"{len(todo) - len(failed)} rendered, {len(failed)} failed" + (f": {', '.join(failed)}" if failed else ""))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
