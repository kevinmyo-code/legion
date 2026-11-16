# "Only my voice" speaker model

One file goes in this directory before building, and it is not committed (see `.gitignore`):

| File | Bytes | SHA-256 | Source |
|---|---|---|---|
| `3dspeaker_speech_eres2net_sv_en_voxceleb_16k.onnx` | 26,485,263 | `c59158379255ad66e161679cca6af8d52d51e389e3224ab7d7a7baae295c2db5` | 3D-Speaker's ERes2Net (English, VoxCeleb, 16 kHz) in sherpa-onnx's ONNX export; believed to be the k2-fsa/sherpa-onnx `speaker-recongition-models` release asset, NOT verified. Check the SHA-256 |

Loaded by `service/speaker/SherpaSpeakerEmbedder.kt` through sherpa-onnx's
`SpeakerEmbeddingExtractor`, on the sherpa-onnx the wake word already ships. No new runtime.

Why it stays out of git: the 3D-Speaker code is Apache-2.0, but no licence for the model weights
themselves was confirmed (the ModelScope card could not be read). Bundle it only once that is
settled.

Without it the app still builds and runs: no voiceprint can be built (the screen says the model is
not on the phone), so every wake hit logs `speaker_check ... result=no_voiceprint` and the wake word
opens as it always did. If a voiceprint exists but the model is gone, the log says `model_missing`
and the wake word still opens.

It can be dropped on the phone without a rebuild: push the file to
`/sdcard/Android/data/com.kevin.legion/files/speaker/`, which is checked first. Pressing "Build voiceprint"
retries the load; a wake check picks up a newly pushed model after the app restarts.
