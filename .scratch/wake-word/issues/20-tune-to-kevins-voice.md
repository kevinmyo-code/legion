---
map: wake-word
ticket: 20
title: "Tune the wake word to Kevin's own voice"
type: build
status: open
status-detail: "Steps 1-3 built 2026-10-30. Sweep run 2026-11-03 on 20 positives, 0 negatives (research/20-sweep-2026-11-03.md). INTERIM point shipped 2026-11-03 (Kevin: agreed on all 3): gain 4, keywordsThreshold 0.10, keywordsScore 5.0; chosen without negatives, false accepts unmeasured. Owed: Kevin records the 2-minute negative (neg_ambient_01) on the A25, then the sweep re-runs with it and the point is confirmed or moved."
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

2026-11-03, `research/20-sweep-2026-11-03.md` (from `tools/wake_eval/` on enrollment
`enroll-20261103-103441-214`): 20 positives, **0 negatives**, so every figure is recall only.
Capture arrives about 30 dB low (speech RMS -51 to -74 dBFS). Shipped point 4/20; best 14/20
(gain 8, thr 0.05, score 5.0). No point meets the target; false accepts not measured.

## Chosen values

**INTERIM, chosen without negatives** (Kevin 2026-11-03, "agreed on all 3", the sweep's middle
row, 13/20 recall):

| Setting | Was (ticket 18) | Now | Where |
|---|---|---|---|
| Input gain ahead of VAD/KWS/Vosk | 1 | **4** (saturating to Short) | `WakeInputGain.kt` `WAKE_INPUT_GAIN`, applied in `WakeWordEngine.handleChunk` |
| `keywordsThreshold` | 0.25 | **0.10** | `WakeTwoStageDetector` `KWS_KEYWORDS_THRESHOLD` |
| `keywordsScore` | 1.0 | **5.0** | `WakeTwoStageDetector` `KWS_KEYWORDS_SCORE` |
| `numTrailingBlanks` / `maxActivePaths` | 1 / 4 | unchanged | |

- The peak meter and `WakeSampleRecorder` stay raw (gain applies after both), so recordings can be
  re-swept at any gain. The Vosk-only fallback hears the gained audio too.
- Mirrored in `tools/wake_eval/core.py` (`APP_KEYWORDS_SCORE`, `APP_KEYWORDS_THRESHOLD`,
  `APP_INPUT_GAIN`); the CLI replay applies the gain to the raw takes (`--gain N`, default 4; pass
  `--gain 1` to re-check replay fidelity at the old point).
- The detector's `wake_stage: utterance ... peak=` figure is now POST-gain (about 4x, +12 dB). Do
  not compare it with the sweep's raw dBFS numbers; the Settings peak meter and recordings are raw.
- **False accepts are unmeasured.** This is not "tuned" under the ticket's rule. It stands until
  Kevin records the 2-minute negative and the sweep re-runs with it; then the point is confirmed or
  moved. Watch for spurious opens on the phone meanwhile.

## Verification

- Unit: `WakeSamplesTest` (WAV header, live segment pre-roll and verdicts, continuous takes, flush,
  enrollment script shape). Built.
- Unit: `tools/tests/test_wake_eval.py` (replay stage rules with fakes, labels, metrics, ranking).
  Built.
- On the A25: enrollment files appear under the path above and `adb pull` reads them. Owed to Kevin's
  run.
- Eval numbers against the target above. Owed.
