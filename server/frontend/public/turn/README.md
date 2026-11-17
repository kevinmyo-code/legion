# The phone's turn models, served to the browser (2026-11-17)

The same files as the phone's `app/src/main/assets/turn/` (byte for byte, same SHA-256), served by
this site and fetched only after the first tap on the assistant (`src/assistant/turn/`). Never from
a third-party host. The openWakeWord models are not shipped here and must not be.

| File | Bytes | SHA-256 | Source | Licence |
|---|---|---|---|---|
| `silero_vad_v6_2.onnx` | 2,327,524 | `1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3` | Silero VAD v6.2 (github.com/snakers4/silero-vad, release v6.2.3) | MIT |
| `smart-turn-v3.2-cpu.onnx` | 8,679,182 | `2bb026316b14a660486a75b1733cd3fbab8c2fd0314dc9af7be49f8cca967e4f` | Pipecat Smart Turn v3.2, cpu int8 (huggingface.co/pipecat-ai/smart-turn-v3) | BSD-2-Clause |

Runtime: `onnxruntime-web` 1.30.0 (MIT), its WebAssembly bundled into the site by the build.
MIT and BSD-2-Clause both allow redistribution on a public page with the licence notice kept.
