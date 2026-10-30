---
map: wake-word
ticket: 20
title: "Tune the wake word to Kevin's own voice"
type: build
status: open
status-detail: "Steps 1-3 built 2026-10-30 (attempt logging, sample recorder, enrollment screen, offline eval harness in tools/wake_eval/). Owed: Kevin records the 20 takes and the 2-minute negative on the A25; then the eval runs on his recordings and the best operating point ships (phase 2). Thresholds unchanged."
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# Tune the wake word to Kevin's own voice

**Kevin, 2026-10-30:** *"lets tune the wake build and train it to be better for me".*

## Evidence (A25, 2026-10-30 11:45-11:46)

Kevin said "hey alfred" about five times, phone idle, normal audio mode, Spotify possibly playing.
From logcat (`MidnightEvents: wake_stage`):

```
11:45:54.948 VAD on      11:45:55.743 VAD off
11:46:00.939 VAD on      11:46:01.762 KWS hit
                         11:46:01.940 confirm reject - not opening
                         11:46:02.149 VAD off
11:46:04.948 VAD on      11:46:06.340 VAD off
11:46:09.149 VAD on      11:46:10.138 VAD off
```

- Stage 0 (Silero VAD) fired on every utterance (0.8-1.4 s). Not the bottleneck.
- Stage 1 (sherpa-onnx KWS) hit once in five. Stage 2 (Vosk grammar) rejected that one.
- Mic ownership was fine: MicArbiter granted and regained correctly.
- Capture is VOICE_COMMUNICATION with AEC, NS and AGC (since August). Wake worked on 10-03.
- Every threshold is a documented guess never calibrated on the A25 (ticket 18):
  `keywordsScore=1.0`, `keywordsThreshold=0.25`, `numTrailingBlanks=1`, keyword
  `▁HE Y ▁A L F RE D @hey_alfred`, Vosk grammar `["hey alfred","[unk]"]` on a 2 s ring.
- No log said what Vosk heard or what the KWS matched. That is step 1.

## Plan (Kevin's turn-taking pattern: open models first, offline eval on his recordings, personalise only if short)

1. **Diagnostics.** Built. Each utterance now logs under `wake_stage`:
   `utterance <ms> ms peak=<p> kws=hit|miss`; on a hit, `KWS detail: keyword=... tokens=... t=...`
   (sherpa-onnx 1.13.8's `KeywordSpotterResult` has keyword, tokens and timestamps, **no score**,
   checked with `javap`); and `confirm heard "<text>" accepted=<b> ringPeak=<p> result=<Vosk JSON
   with per-word conf>`.
2. **Sample recorder and enrollment.** Built. Settings > Assistant > "Tune the wake word to your
   voice". Twenty 4 s takes (6 normal, 4 quiet, 4 far, 3 fast, 3 over music), then a 2-minute
   negative (talk normally or play a podcast). Takes are continuous audio through the engine's own
   capture, so the eval can sweep the VAD too. A switch saves every live VAD segment (1 s pre-roll,
   newest 300). A second switch captures VOICE_RECOGNITION with no effects, for comparing sources.
   While the screen is open a wake hit is logged, never opened. Files:
   `/sdcard/Android/data/com.kevin.legion/files/wake-samples/` (`enroll-<stamp>/`, `live/`), a
   16 kHz mono WAV plus a JSON sidecar each (label, source, effects, peak, events with sample
   offsets, Vosk text, verdict).
3. **Offline eval** in `tools/wake_eval/` (Python, sherpa-onnx and vosk pip packages, the SAME model
   files as `app/src/main/assets/wake-kws` and `vosk-model`): replay the WAVs through VAD -> KWS ->
   confirm; sweep `keywordsThreshold`, `keywordsScore`, `numTrailingBlanks`, alternative token
   sequences (HAY/HEY, AL FRED splits), and confirm policies (strong KWS hit skips Vosk; Vosk accepts
   on "alfred" anywhere; window length). Report recall on positives and false accepts per hour on
   negatives. **Target: >= 95% recall on his positives, <= 1 false accept per hour.**
   **Skeleton built 2026-10-30, never run on real audio.** `core.py` (pure: the replay of
   `WakeTwoStageDetector`'s stage rules, labels, grids, metrics; `tools/tests/test_wake_eval.py`
   with fakes), `engines.py` (sherpa-onnx 1.13.8 and vosk adapters, API read off the 1.13.8 wheel),
   `wake_eval.py` (CLI; usage in its docstring). Grids `app` (shipped point only), `quick` (36),
   `full` (720). Swept today: threshold, score, trailing blanks, four token spellings (all four in
   `tokens.txt`; there is no `HEY` or `AL` word token), confirm policies `phrase` / `name_anywhere`
   / `kws_only`. Not swept yet: the VAD and the confirm window length. One open per utterance in the
   replay, because on the phone an open takes the mic. Smoke-run once on synthetic noise to prove
   the native path loads and runs; those numbers mean nothing and are not recorded. Live segments
   are skipped (no ground truth). `wake-samples/` and `.venv-wake/` are gitignored: the repo is
   public. Owed: his recordings.
4. **Ship the best operating point** as defaults, decision logic pure and unit tested, thresholds in
   one place with a comment citing the eval numbers and date. Owed.
5. If the open models cannot reach the target, write up personalisation here (custom small KWS or a
   speaker verifier trained on his samples). Not built without Kevin's say.

## Sweep results

None yet. Nothing in this section is to be filled in from anything but `tools/wake_eval/` run on
his recordings.

## Chosen values

Unchanged from ticket 18 until the sweep runs.

## Verification

- Unit: `WakeSamplesTest` (WAV header, live segment pre-roll and verdicts, continuous takes, flush,
  enrollment script shape). Built.
- Unit: `tools/tests/test_wake_eval.py` (replay stage rules with fakes, labels, metrics, ranking).
  Built.
- On the A25: enrollment files appear under the path above and `adb pull` reads them. Owed to Kevin's
  run.
- Eval numbers against the target above. Owed.
