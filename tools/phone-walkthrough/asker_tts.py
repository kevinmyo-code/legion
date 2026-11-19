"""The asker's lines for the phone video, in a STOCK text-to-speech voice (never a person's own).

    LEGION_GEMINI_KEY_FILE=<file holding the key> python tools/phone-walkthrough/asker_tts.py \
        --spec <spec.json> --out <dir> [--voice Kore] [--budget 0.20]

Each `spec["lines"][key]` becomes `<out>/<key>.wav` (24 kHz mono, 16-bit) through the engine's
own speech client (`server/assistant/speech.py`, the way `server/tests/realrun_web_cascade.py`
makes its question clips). A WAV already there is kept, so a re-run costs only new lines. The
key is read from the file named by LEGION_GEMINI_KEY_FILE and is never printed. A line spoken in
any voice but the one asked for (the client falls back to Alfred's fallback voice on a refusal)
is not written: the asker must never sound like Alfred.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import wave
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "server"))

from assistant.speech import GeminiSpeech, Spoken  # noqa: E402

# USD per million tokens, the same estimates as realrun_web_cascade.py (tools/voice_prices.json).
TTS_IN, TTS_OUT = 0.50, 9.00


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--spec", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--voice", default="Kore")
    parser.add_argument("--budget", type=float, default=0.20)
    opt = parser.parse_args()

    key_file = os.environ.get("LEGION_GEMINI_KEY_FILE", "")
    if not key_file or not Path(key_file).is_file():
        print("LEGION_GEMINI_KEY_FILE names no file; nothing was made.")
        return 2
    speech = GeminiSpeech(lambda: Path(key_file).read_text(encoding="utf-8").strip(), stt_model="unused")
    lines = json.loads(Path(opt.spec).read_text(encoding="utf-8"))["lines"]
    out = Path(opt.out)
    out.mkdir(parents=True, exist_ok=True)
    spent = 0.0
    for name, text in lines.items():
        target = out / f"{name}.wav"
        if target.exists():
            continue
        if spent > opt.budget:
            print(f"budget reached (${spent:.4f}); {name} and later lines not made")
            return 1
        spoken = Spoken()
        pcm = b"".join(speech.speak(text, opt.voice, spoken))
        spent += (spoken.prompt_tokens * TTS_IN + spoken.output_tokens * TTS_OUT) / 1e6
        if spoken.voice != opt.voice:
            print(f"{name}: spoken in {spoken.voice}, not {opt.voice}; not written")
            continue
        with wave.open(str(target), "wb") as wav:
            wav.setnchannels(1)
            wav.setsampwidth(2)
            wav.setframerate(24000)
            wav.writeframes(pcm)
        print(f"{name}: {len(pcm) / 48000:.2f} s")
    print(f"spent about ${spent:.4f}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
