# openWakeWord models (wake word engine): sources and licence

These three files are committed here and ship inside the phone APK. `app/models.sha256` pins each
one; the build's `verifyModels` task (runs before every build of `app/`) fails when a file is
missing or its sha256 differs.

| File | Bytes | SHA-256 | Source | Changed? |
|---|---|---|---|---|
| `melspectrogram.onnx` | 1,087,958 | `ba2b0e0f8b7b875369a2c89cb13360ff53bac436f2895cced9f479fa65eb176f` | openWakeWord release v0.5.1, github.com/dscripka/openWakeWord | No, byte for byte as released |
| `embedding_model.onnx` | 1,326,578 | `70d164290c1d095d1d4ee149bc5e00543250a7316b59f31d056cff7bd3075c1f` | same release | No, byte for byte as released |
| `hey_alfred.onnx` | 205,430 | `07084c22a8b9ec148f92faa1f45bbdb669bebbfd9e3848d40129d99abbcfdef4` | trained for this project with openWakeWord's `train.py` | New work: a head trained for the phrase "hey alfred" |

## Licence

Creator of openWakeWord and its pre-trained models: David Scripka (github.com/dscripka/openWakeWord).

openWakeWord's README licenses all of its included pre-trained models under the Creative Commons
Attribution-NonCommercial-ShareAlike 4.0 International licence (CC BY-NC-SA 4.0):
https://creativecommons.org/licenses/by-nc-sa/4.0/

- `melspectrogram.onnx` and `embedding_model.onnx` are those models, unmodified, under CC BY-NC-SA
  4.0. (The README says the embedding re-implements a Google TFHub speech embedding; this repo
  follows the README's blanket licence statement for it.)
- `hey_alfred.onnx` was trained with openWakeWord's tooling on openWakeWord's precomputed negative
  features, which are published under CC BY-NC-SA 4.0
  (huggingface.co/datasets/davidscripka/openwakeword_features). It is therefore treated as an
  adaptation and is licensed under CC BY-NC-SA 4.0 too. It is the repo owner's own wake word model,
  published here by his choice. The audio clips used to train it are not in this repo.

What that means for this repo, in plain words:

- **Credit and link:** this file is the credit and the licence link. Keep it next to the models,
  and keep it in any copy of them.
- **NonCommercial:** nobody may use these three files for commercial purposes. This repo has no
  LICENSE file of its own; if one is ever added, these files keep CC BY-NC-SA 4.0 regardless. Any
  paid or commercial build of the app must remove them and use another wake word engine (the
  Apache-2.0 keyword spotter in `../wake-kws/` is one).
- **ShareAlike:** anyone who changes or retrains these models and shares the result must share it
  under CC BY-NC-SA 4.0 as well.
- No warranty: the licence gives the models as they are.

## Replacing a model

A head retrained later can be dropped on the phone without a rebuild: push all three files to
`/sdcard/Android/data/com.kevin.legion/files/openwakeword/`, which `OwwModelFiles` checks first.
Changing a committed file means changing its line in `app/models.sha256` and this table in the
same commit, and allowing its exact name in `.gitignore` (the folder is ignored except for the
names listed there, so nothing else can be committed here by accident).

The head is per phrase: a new companion name needs a new head (`hey_<newname>.onnx`), trained the
same way with `target_phrase` changed.
