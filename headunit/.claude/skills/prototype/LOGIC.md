# Logic Prototype (ADAPTED for MIDNIGHT AI)

Push a state model through the cases that are hard to reason about on paper, and print what happened
at every step. The point is to *see* the transitions, not to assert them.

If the question is about what something looks like — wrong branch. Use [UI.md](UI.md).

Upstream's version of this branch builds a tiny interactive terminal app (`pnpm`/`bun`/`python`).
This project has no such runner: it's Kotlin behind Gradle, and its one fast, no-hardware seam is the
JVM unit test source set (`app/src/test/`), where the 36 `ObdResponseParser` tests already live and
run in seconds. That is the terminal app here.

## When this is the right shape

- "Does this state machine handle the reconnect case sanely?"
- "What actually happens to `MusicSource` when a mixtape stops mid-track and Bluetooth is also connected?"
- "If two devices sync the same car task, which write wins?"
- Anything where the failure mode is a transition you didn't think of.

## Shape

A single throwaway test class at `app/src/test/java/com/kevin/midnightai/proto/Proto<Thing>Test.kt`.

- **One `@Test` per scenario**, named for the scenario in plain words, not `test1`.
- **`println` the full relevant state after every action.** Gradle shows stdout for JVM tests. This is the entire value: a readable trace Kevin can scan. Assertions are optional and usually beside the point — you're looking, not proving.
- **Drive it with fakes, never real hardware.** No ELM327, no Gemini, no Firebase, no Drive. If the thing under test reaches for `ObdBluetoothManager` or `SubAgent`, that is the signal the seam is in the wrong place — surface that as the finding, because it is a more valuable answer than the prototype was going to give.
- **In-memory only.** No Room. If the model genuinely needs persistence, fake the DAO behind an interface.

## Run it

```
gradlew testDebugUnitTest --tests "com.kevin.midnightai.proto.Proto<Thing>Test"
```

Give Kevin that exact line. He runs this project from Android Studio's play button, so also mention
the gutter Run arrow on the test class, which is usually faster for him than the CLI.

## Prior art worth copying

`SyncMergeTest` / `SyncCodecTest` (S1's merge brain) are the model: the merge logic was deliberately
extracted into pure `SyncMerge` + `SyncCodec` so it could be reasoned about on the JVM, away from
Drive. If a logic prototype is hard to write because the logic is tangled with I/O, the prototype has
already told you something: extract the pure part first.

## Capture

Fold the validated decision into the real code, then delete or branch off the prototype. Record the
verdict and the question it settled on the ticket. `proto/` never ships.
