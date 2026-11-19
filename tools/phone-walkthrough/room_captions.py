"""Captions for Alfred's words in each take, from its room recording (the 4:5 cut plays muted).

    LEGION_GEMINI_KEY_FILE=<file holding the key> python tools/phone-walkthrough/room_captions.py \
        --clips <footage dir> [--ffmpeg <dir>] [--budget 0.05]

For every `<slot>.json` with a `room_wav` and no `<slot>.vtt` yet: ffmpeg's silencedetect splits
the room audio into stretches of speech; each stretch that does not overlap an asker line (the
asker's words come from the spec, not from the room) is transcribed once by the engine's own
speech client (`server/assistant/speech.py`) and written as a cue on the mp4's clock. A `.vtt`
already there is kept, so a hand-corrected one is never overwritten. The key is never printed.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import wave
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "server"))

from assistant.speech import GeminiSpeech  # noqa: E402

STT_MODEL = "gemini-3.5-flash-lite"  # the phone's own STT model (CloudSpeech.kt)
TEXT_IN, TEXT_OUT = 0.30, 2.50  # USD per million tokens, tools/voice_prices.json estimates


def stamp(t: float) -> str:
    ms = max(int(round(t * 1000)), 0)
    return f"{ms // 3600000:02d}:{ms // 60000 % 60:02d}:{ms // 1000 % 60:02d}.{ms % 1000:03d}"


def speech_spans(ffmpeg: str, wav: Path) -> list[tuple[float, float]]:
    """Stretches of sound between silences of 0.45 s or more under -38 dB."""
    out = subprocess.run(
        [ffmpeg, "-hide_banner", "-i", str(wav), "-af", "highpass=f=90,silencedetect=noise=-38dB:d=0.45", "-f", "null", "-"],
        capture_output=True, text=True,
    ).stderr
    h, m, s = re.search(r"Duration: (\d+):(\d+):([\d.]+)", out).groups()
    total = int(h) * 3600 + int(m) * 60 + float(s)
    starts = [float(x) for x in re.findall(r"silence_start: ([\d.]+)", out)]
    ends = [float(x) for x in re.findall(r"silence_end: ([\d.]+)", out)]
    spans, cursor = [], 0.0
    for s, e in zip(starts, ends + [total] * (len(starts) - len(ends))):
        if s - cursor > 0.4:
            spans.append((cursor, s))
        cursor = e
    if total - cursor > 0.4:
        spans.append((cursor, total))
    return spans


def pcm16k(ffmpeg: str, wav: Path, a: float, b: float) -> bytes:
    return subprocess.run(
        [ffmpeg, "-v", "error", "-ss", f"{a:.3f}", "-to", f"{b:.3f}", "-i", str(wav), "-ac", "1", "-ar", "16000", "-f", "s16le", "-"],
        capture_output=True, check=True,
    ).stdout


def wav_seconds(path: Path) -> float:
    with wave.open(str(path)) as w:
        return w.getnframes() / w.getframerate()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--clips", required=True)
    parser.add_argument("--asker")
    parser.add_argument("--ffmpeg", default="")
    parser.add_argument("--budget", type=float, default=0.05)
    opt = parser.parse_args()
    ffmpeg = str(Path(opt.ffmpeg) / "ffmpeg") if opt.ffmpeg else "ffmpeg"
    key_file = os.environ.get("LEGION_GEMINI_KEY_FILE", "")
    if not key_file or not Path(key_file).is_file():
        print("LEGION_GEMINI_KEY_FILE names no file; nothing was made.")
        return 2
    speech = GeminiSpeech(lambda: Path(key_file).read_text(encoding="utf-8").strip(), stt_model=STT_MODEL)
    clips = Path(opt.clips)
    asker = Path(opt.asker) if opt.asker else clips / "asker"
    spent = 0.0
    for meta_file in sorted(clips.glob("*.json")):
        slot = meta_file.stem
        meta = json.loads(meta_file.read_text(encoding="utf-8-sig"))  # PowerShell writes a BOM
        room = clips / meta.get("room_wav", "")
        target = clips / f"{slot}.vtt"
        if not meta.get("room_wav") or not room.is_file() or target.exists():
            continue
        offset = float(meta.get("room_starts_before_video_s", 0))
        # Asker lines on the room's clock, padded: a stretch inside one is the asker's echo.
        asks = []
        for ask in meta.get("asks", []):
            wav = asker / f"{ask['line']}.wav"
            if wav.is_file():
                asks.append((ask["at"] + offset - 0.3, ask["at"] + offset + wav_seconds(wav) + 0.3))
        cues = []
        for a, b in speech_spans(ffmpeg, room):
            if any(a >= x and b <= y for x, y in asks):
                continue
            if spent > opt.budget:
                print(f"budget reached (${spent:.4f}); {slot} left unfinished")
                return 1
            heard = speech.transcribe(pcm16k(ffmpeg, room, a, b))
            spent += (heard.prompt_tokens * TEXT_IN + heard.output_tokens * TEXT_OUT) / 1e6
            if heard.text.strip():
                cues.append((a - offset, b - offset, heard.text.strip()))
        lines = ["WEBVTT", ""]
        for a, b, text in cues:
            lines += [f"{stamp(a)} --> {stamp(b)}", f"Alfred: {text}", ""]
        target.write_text("\n".join(lines), encoding="utf-8")
        print(f"{slot}: {len(cues)} cues")
    print(f"spent about ${spent:.4f}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
