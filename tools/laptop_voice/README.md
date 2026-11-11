# Laptop voice service

Speech-to-text and text-to-speech for the phone's cascade voice, served from the household's own
laptop GPU. Speech only: it never holds the Gemini key, never runs tools, and writes nothing to disk.
The phone keeps working without it (its own on-device speech path).

- Wire contract: [`CONTRACT.md`](CONTRACT.md) (protocol 1). The phone builds against it. It is a copy
  of the owner's master contract with the laptop's machine name replaced by `<laptop>`.
- Code: `server.py` (aiohttp: `GET /livez`, `GET /health`, `WS /v1/conversation`), `run.py` (hardened
  launcher: token file, allowlisted environment, Job Object memory cap), `engines.py` (Whisper,
  Parakeet, Kokoro, fakes), `kyutai.py` + `kyutai_core.py` (Kyutai streaming STT with its own end of
  turn, Kyutai streaming TTS), `client.py` (reference client), `live_check.py` (checks and latency
  against a running service), `tests/` (protocol tests with fake engines, no GPU needed).
- Models and caches live outside the repo. Nothing model-sized is committed.
- Binds `127.0.0.1` by default and refuses all-interfaces. Tailscale Serve is the way in from the phone.
- Every request except `/livez` needs `Authorization: Bearer <token>`; the token lives in
  `%LOCALAPPDATA%\legion-voice\token` (created on first start, readable by the owner only, never printed).

## Backends

| Flag | STT | TTS | End of turn | Venv |
|---|---|---|---|---|
| `--stt kyutai --tts kyutai` | `kyutai/stt-1b-en_fr-candle` (en, fr), streaming, pause head | `kyutai/tts-1.6b-en_fr`, streamed text in | laptop (`end_of_turn: true`) | `venv-kyutai` |
| `--stt whisper --tts kokoro` (fallback) | faster-whisper large-v3-turbo | Kokoro v1.0 | phone (`end_of_turn: false`) | `venv` |

Kyutai runs on native Windows + CUDA through the PyTorch `moshi` package (`NO_TORCH_COMPILE=1`, set
by the launcher). Both models in one process: every CUDA call goes through one lock, because moshi
captures CUDA graphs lazily and a capture in one thread fails if another thread launches work.

## Setup (once)

```
uv venv -p 3.12 %USERPROFILE%\.legion\laptop-voice\venv-kyutai
uv pip install --python %USERPROFILE%\.legion\laptop-voice\venv-kyutai\Scripts\python.exe torch==2.9.1 torchaudio==2.9.1 --index-url https://download.pytorch.org/whl/cu128
uv pip install --python %USERPROFILE%\.legion\laptop-voice\venv-kyutai\Scripts\python.exe -r tools\laptop_voice\requirements-kyutai.txt
```

First start with `--allow-download` (and an `--hf-home` of your choice) to fetch the weights; after
that the service runs with Hugging Face offline.

## Run (by hand, owner's account)

```
cd tools
%USERPROFILE%\.legion\laptop-voice\venv-kyutai\Scripts\python.exe -m laptop_voice.run --stt kyutai --tts kyutai --hf-home <cache>
```

Kyutai holds about 6.7 GB of VRAM and about 10 GB of Windows commit (VRAM is charged to commit
on WDDM; peak 12.6 GB while loading). The launcher's default cap for Kyutai is 16 GB.
`--eot-threshold` (default 0.6) sets the pause probability that ends a turn: higher means fewer
mid-sentence splits and a later end of turn.

## Test

```
cd tools
%USERPROFILE%\.legion\laptop-voice\venv-kyutai\Scripts\python.exe -m pytest laptop_voice/tests -q
%USERPROFILE%\.legion\laptop-voice\venv-kyutai\Scripts\python.exe -m laptop_voice.live_check --wavs <16 kHz wav dir> --gt <ground_truth.json>
```
