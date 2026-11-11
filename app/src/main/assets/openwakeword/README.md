# openWakeWord models (wake word engine)

Three files go in this directory before building, and none is committed (see `.gitignore`):

| File | What | Source |
|---|---|---|
| `melspectrogram.onnx` | openWakeWord's mel front end | github.com/dscripka/openWakeWord releases, v0.5.1 |
| `embedding_model.onnx` | the shared speech embedding | same release |
| `hey_<name>.onnx` | the head trained for "hey <companion name>", e.g. `hey_alfred.onnx` | trained locally with openWakeWord's `train.py` |

Why they stay out of git: openWakeWord's README licenses its pre-trained models CC BY-NC-SA 4.0,
and a custom head is trained on negative features published under the same terms. Fine for a
personal build; not something to redistribute from a public repo.

Without them the app still builds and runs: choosing "openWakeWord" under Settings, Wake word engine
says the model is missing and the current keyword spotter keeps listening.

A head retrained later can be dropped on the phone without a rebuild: push all three files to
`/sdcard/Android/data/com.kevin.legion/files/openwakeword/`, which `OwwModelFiles` checks first.

The head is per phrase: a new companion name needs a new head (`hey_<newname>.onnx`), trained the
same way with `target_phrase` changed.
