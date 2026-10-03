# Wake-word stage 0/1 models (ticket 18, 2026-10-03)

Bundled, never fetched at runtime (CLAUDE.md sec 7). Copied to `filesDir/wake-kws/` on first use
because sherpa-onnx reads real file paths.

| File | Bytes | Source | Licence |
|---|---|---|---|
| `encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx` | 4,807,159 | sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01 (NOT the -mobile build) | Apache-2.0 |
| `decoder-epoch-12-avg-2-chunk-16-left-64.onnx` | 1,063,189 | same archive | Apache-2.0 |
| `joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx` | 163,380 | same archive | Apache-2.0 |
| `tokens.txt` | 5,006 | same archive | Apache-2.0 |
| `bpe.model` | 244,837 | same (SentencePiece model, read by `BpeKeywordTokenizer`) | Apache-2.0 |
| `silero_vad.onnx` | 643,854 | Silero VAD as published by k2-fsa | MIT |

KWS archive:
`https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2`
(the standard archive: its int8 encoder, fp32 decoder and int8 joiner are used; the other variants are left out).
VAD: `https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx`.

The runtime library is the official `sherpa-onnx-static-link-onnxruntime-1.13.8.aar` (Apache-2.0, 38,691,998 bytes, arm64 JNI lib 24,169,352) from
`https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/`, resolved by Gradle, not vendored.

**Do not use the `-mobile` archive's encoder.** It aborts natively on its first decode with
sherpa-onnx 1.13.8 (Reshape `/downsample/Reshape_1`, input {17,1,128} vs {8,2,1,128}), reproduced
off-device in Python with both the int8 and fp32 mobile encoders; the standard archive's encoder
(`https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2`)
runs the same harness cleanly.
