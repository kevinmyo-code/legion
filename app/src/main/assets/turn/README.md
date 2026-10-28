# Local end-of-turn models (2026-10-27)

Bundled, never fetched at runtime (CLAUDE.md sec 7). Loaded from assets into ONNX Runtime by
`service/turn/TurnModels.kt`.

| File | Bytes | SHA-256 | Source | Licence |
|---|---|---|---|---|
| `silero_vad_v6_2.onnx` | 2,327,524 | `1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3` | Silero VAD v6.2 (`src/silero_vad/data/silero_vad.onnx`, github.com/snakers4/silero-vad, release v6.2.3) | MIT |
| `smart-turn-v3.2-cpu.onnx` | 8,679,182 | `2bb026316b14a660486a75b1733cd3fbab8c2fd0314dc9af7be49f8cca967e4f` | Pipecat Smart Turn v3.2, cpu int8 (huggingface.co/pipecat-ai/smart-turn-v3) | BSD-2-Clause |

Runtime: `com.microsoft.onnxruntime:onnxruntime-android` (MIT), resolved by Gradle, not vendored.
It is a second ONNX Runtime beside sherpa-onnx's statically linked one; it adds about 34 MB of
uncompressed arm64 native code to the APK.

The older `wake-kws/silero_vad.onnx` (643,854 bytes) stays for the wake word and is not this file.
