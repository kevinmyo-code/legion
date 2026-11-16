# "Only my voice" speaker model

One file goes in this directory before building, and it is not committed (see `.gitignore`):

| File | Bytes | SHA-256 | Source |
|---|---|---|---|
| `3dspeaker_speech_eres2net_sv_en_voxceleb_16k.onnx` | 26,485,263 | `c59158379255ad66e161679cca6af8d52d51e389e3224ab7d7a7baae295c2db5` | sherpa-onnx release `speaker-recongition-models` (github.com/k2-fsa/sherpa-onnx/releases), exported from 3D-Speaker's ERes2Net, English, VoxCeleb, 16 kHz |

Loaded by `service/speaker/SherpaSpeakerEmbedder.kt` through sherpa-onnx's
`SpeakerEmbeddingExtractor`, on the sherpa-onnx the wake word already ships. No new runtime.

Why it stays out of git: the 3D-Speaker code is Apache-2.0, but no licence for the model weights
themselves was confirmed (the ModelScope card could not be read). Bundle it only once that is
settled.

Without it the app still builds and runs: every wake hit logs `speaker_check ... result=model_missing`
and the wake word opens as it always did, "Only my voice" switch or not.

It can be dropped on the phone without a rebuild: push the file to
`/sdcard/Android/data/com.kevin.legion/files/speaker/`, which is checked first.
