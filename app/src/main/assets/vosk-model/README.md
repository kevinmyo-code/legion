# Vosk model (custom wake word)

This directory lives under `app/src/main/assets/` so it ships in every build, including
release: the custom wake word ("hey <name>", `WakeWordEngine`) is a real, paid-tier,
opt-in shipping feature (memory/library/decisions.md 2026-07-19), not debug-only
scaffolding. This is where the Vosk small English model must be unzipped before building.
It is intentionally **not committed** (see `.gitignore`) - a driver who never opts in
never pays the size cost of a redownload, but anyone who does build the app needs it
present locally first.

## Download

```
curl -L -o vosk-model-small-en-us-0.15.zip https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip
unzip vosk-model-small-en-us-0.15.zip
```

That produces a `vosk-model-small-en-us-0.15/` folder containing `README`, `am/`, `conf/`,
`graph/`, `ivector/`. Copy its **contents** (not the wrapping folder) directly into this
directory, so the layout is:

```
app/src/main/assets/vosk-model/
├── README.md      (this file, tracked)
├── README         (vendor's, from the model zip, ignored)
├── am/
├── conf/
├── graph/
└── ivector/
```

i.e. `app/src/main/assets/vosk-model/am/final.mdl` should exist directly, not
`app/src/main/assets/vosk-model/vosk-model-small-en-us-0.15/am/final.mdl`.

## Why this model

`vosk-model-small-en-us-0.15` (~40-50MB) is Vosk's smallest published English model -
appropriate for an always-on background listener on a weak head-unit SoC. See
`.scratch/custom-wake-word/research-arbitrary-phrase-feasibility.md` for the full comparison
against Porcupine and the other candidates that were ruled out.

## Runtime behavior

`WakeWordEngine` (in `service/`) copies this asset directory to
`context.filesDir/vosk-model` on first activation (Vosk's `Model` loader needs a real
filesystem path, not an APK asset path) and loads it from there. If this directory is empty
or missing at build time, the app still compiles - the Setup toggle will just fail to start
the engine and Log a warning.
