# "Only my voice" speaker model

One file goes in this directory before an APK build. It is not committed (see `.gitignore`).

| File | Bytes | SHA-256 | Source |
|---|---|---|---|
| `3dspeaker_speech_eres2net_sv_en_voxceleb_16k.onnx` (official) | 26,485,264 | `c4947caf150b8502fdd75bbe4089b971ab1ec42e90ee3ba547faca2cde44d9ee` | github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-recongition-models: sherpa-onnx's ONNX export of 3D-Speaker's ERes2Net (English, VoxCeleb, 16 kHz). Size and sha256 as GitHub reports them for the release asset |
| the same name (laptop copy, in today's phone builds) | 26,485,263 | `c59158379255ad66e161679cca6af8d52d51e389e3224ab7d7a7baae295c2db5` | unproven: one byte shorter than the official file. Never committed |

`app/speaker-model.pin` holds the official URL, size and sha256. A cloud build fetches the
official file with `.github/scripts/fetch_speaker_model.sh` and checks it; it refuses to build an
APK until the pin says `release_ok=yes`, which waits on a hand test of the official file on the
phone with the enrolled voiceprint. Laptop builds still use the laptop copy until then.

Loaded by `service/speaker/SherpaSpeakerEmbedder.kt` through sherpa-onnx's
`SpeakerEmbeddingExtractor`, on the sherpa-onnx the wake word already ships. No new runtime.

## Licence (Apache-2.0)

The model comes from 3D-Speaker (github.com/modelscope/3D-Speaker, Apache License 2.0; ModelScope
lists the model `iic/speech_eres2net_sv_en_voxceleb_16k` as Apache License 2.0), exported to ONNX by
the sherpa-onnx project (github.com/k2-fsa/sherpa-onnx, Apache License 2.0). This repo does not
change the file. 3D-Speaker ships no NOTICE file.

Notice: the ERes2Net speaker model, copyright the 3D-Speaker authors, is licensed under the Apache
License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0). It is distributed on an "AS IS"
basis, without warranties or conditions of any kind. A cloud build puts the full licence text next
to the other notices in the APK, at `assets/licenses/3dspeaker-eres2net-Apache-2.0.txt`.

The model was trained on VoxCeleb; its data terms were not checked here.

## Without it

The app still builds and runs: no voiceprint can be built (the screen says the model is not on
the phone), so every wake hit logs `speaker_check ... result=no_voiceprint` and the wake word opens
as it always did. If a voiceprint exists but the model is gone, the log says `model_missing` and
the wake word still opens.

It can be dropped on the phone without a rebuild: push the file to
`/sdcard/Android/data/com.kevin.legion/files/speaker/`, which is checked first. Pressing "Build
voiceprint" retries the load; a wake check picks up a newly pushed model after the app restarts.
