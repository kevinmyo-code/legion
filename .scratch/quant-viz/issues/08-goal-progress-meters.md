# 08 - Goal progress meters

Status: OPEN. Lane C. Depends on 01.

## What

`GoalsPanel.kt:160-163` prints `Goal.targetValue` as text; the ONLY computed goal progress in the
app lives inside an LLM digest (`advisor/digest/CredDigestBuilder.kt:195-211`, savings). Surface
progress on screen, conservatively.

## Spec

### Extraction

- Move the savings progress computation out of `CredDigestBuilder` into a pure function in a
  non-UI module (e.g. `goals/GoalProgress.kt` or wherever goal logic lives - follow the package
  the `Goal` controller uses):

```kotlin
/** Progress toward an accumulation goal: current/target, null when target missing or <= 0. */
fun accumulationProgress(currentValue: Double, targetValue: Double?): Float?
```

- `CredDigestBuilder` calls the extracted function - one definition, digest and screen can never
  disagree (same posture as map taste call 6).

### UI (`ui/goals/GoalsPanel.kt`)

- For each ACTIVE goal with `metricKey != null && targetValue != null`:
  - Resolve the current value per metricKey, reusing the exact reads `CredDigestBuilder` (or the
    relevant controller) already performs:
    - `savings_balance_cents` -> the same balance read the digest uses.
    - `bodyweight_kg` -> latest `BodyweightLog` value.
    - Any other key present in the DB but not resolvable -> fall through to prose (below), never
      guess.
  - Render, under the goal's existing statement text: a words line `"NOW <current> -> TARGET
    <target> <unit>"` (`LegionType.stamp`), then `DeckMeter(fraction)` ONLY for
    `savings_balance_cents` (accumulation toward a ceiling - a fill fraction is truthful).
  - `bodyweight_kg` and other direction-ambiguous metrics get the words line but NO meter: with
    no recorded baseline, a loss-goal's "fraction complete" is not computable, and a meter that
    fills as weight rises would celebrate the wrong direction. Words only. (If a baseline field
    ever lands on Goal, revisit.)
- Goals with no metricKey: untouched, prose as today.
- Money values format via `formatCents` from Long; do not route cents through Double for LABELS
  (the stored `targetValue` is Double by schema - convert to Long cents at the boundary,
  document the one cast).

### Verification

- [ ] Unit tests: `accumulationProgress` (null/zero/negative target -> null, exact fraction),
      and that `CredDigestBuilder`'s output is unchanged for the same inputs (existing digest
      tests still green).
- [ ] `compileDebugKotlin -Pnokey` + `testDebugUnitTest` green.
- [ ] No meter rendered for bodyweight goals (grep the diff).
- [ ] On-device (map-level): a savings goal shows meter + words; a prose goal is unchanged.
