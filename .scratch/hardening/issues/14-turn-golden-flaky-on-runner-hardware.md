---
type: build
status: open
blocked_by: []
map: hardening
---

# TurnModelsGoldenTest is flaky on some CI runners

**Found 2026-11-12.** Android CI run 43308786505 failed one test,
`TurnModelsGoldenTest > smart turn on kotlin features matches python` (`app/src/test/java/com/kevin/legion/service/turn/TurnModelsGoldenTest.kt:26`),
on a runner whose ONNX Runtime log showed different hardware; the rerun of the same commit passed.
The test compares Smart Turn's probability on Kotlin features against Python's with a tolerance of
0.01. Same code passed on `dev` the same day.

**Why it matters:** a red build that a rerun turns green teaches everyone to rerun instead of read,
which is ticket 13's failure in a new place.

## Build

1. Log the expected and actual probabilities in the assertion message, so the next failure says by
   how much.
2. Pin ONNX Runtime's CPU behaviour for the test (one intra-op thread is already set; check
   `SessionOptions` for deterministic or disabled-optimisation settings), or widen the tolerance to
   what the measured spread justifies and write the figure beside it. Not both blindly.
3. The Silero test beside it (1e-3) has not failed; leave it unless the same cause shows there.

## Verification

Twenty CI reruns, or a matrix over runner images, with the probabilities logged; the tolerance or
pin is justified by the spread seen.
