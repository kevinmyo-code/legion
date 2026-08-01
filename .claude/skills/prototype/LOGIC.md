# Logic Prototype (ADAPTED for LEGION)

Push a state model through the cases that are hard to reason about on paper, and print what happened
at every step. The point is to *see* the transitions, not to assert them.

If the question is about what something looks like - wrong branch. Use [UI.md](UI.md).

Upstream's version of this branch builds a tiny interactive terminal app (`pnpm`/`bun`/`python`).
This project has no such runner: it's Kotlin behind Gradle, and its one fast, no-hardware seam is the
JVM unit test source set (`app/src/test/`), where the 19 ledger and pantry tests already live and run
in seconds. That is the terminal app here.

## When this is the right shape

- "Does the batch ingestion state machine resume sanely after process death?"
- "What actually happens when two overlapping statements restate the same transaction?"
- "If two devices ingest the same Drive folder, which rows survive the merge?"
- "Does the reconciliation gate quarantine correctly when a discount line sits outside the total?"
- Anything where the failure mode is a transition you didn't think of.

## Shape

A single throwaway test class at `app/src/test/java/com/kevin/legion/proto/Proto<Thing>Test.kt`.

- **One `@Test` per scenario**, named for the scenario in plain words, not `test1`.
- **`println` the full relevant state after every action.** Gradle shows stdout for JVM tests. This
  is the entire value: a readable trace Kevin can scan. Assertions are optional and usually beside
  the point - you're looking, not proving.
- **Drive it with fakes, never real hardware or real network.** No ELM327, no Gemini, no Drive. If
  the thing under test reaches for `ObdBluetoothManager` or `SubAgent`, that is the signal the seam
  is in the wrong place - surface that as the finding, because it is a more valuable answer than the
  prototype was going to give.
- **In-memory only.** No Room. If the model genuinely needs persistence, fake the DAO behind an
  interface.
- **Money is `Long` cents even in a prototype.** A prototype that reaches for `Double` will give you
  a wrong answer about the reconciliation gate specifically, since the gate is an exact-equality
  check and `Double` breaks it by binary rounding. This is the one shortcut that corrupts the result.

## Run it

```
gradlew testDebugUnitTest --tests "com.kevin.legion.proto.Proto<Thing>Test"
```

Give Kevin that exact line. He runs this project from Android Studio's play button, so also mention
the gutter Run arrow on the test class, which is usually faster for him than the CLI.

## A real gotcha, already paid for

**PdfBox-Android ships its fonts and glyphlists as Android assets**, unreachable from a plain JVM
unit test - it fails with a `GlyphList not found` error. Anything prototyping the ledger parsers
needs Robolectric (already a test-only dependency) to shadow `AssetManager`. This was found by
running a spike before porting the rest of the parser, which is exactly what this skill is for.

## Prior art worth copying

`SyncMergeTest` / `SyncCodecTest` are the model: the merge logic was deliberately extracted into pure
`SyncMerge` + `SyncCodec` so it could be reasoned about on the JVM, away from Drive. The ledger
parsers follow the same shape - `PdfWords`/`PdfText` isolate the PDF I/O so the column-classification
logic stays testable. If a logic prototype is hard to write because the logic is tangled with I/O,
the prototype has already told you something: extract the pure part first.

## Capture

Fold the validated decision into the real code, then delete or branch off the prototype. Record the
verdict and the question it settled on the ticket. `proto/` never ships.
