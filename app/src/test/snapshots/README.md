# Screenshot baselines (Roborazzi)

Hardening ticket 01 (`.scratch/hardening/issues/01-ui-screenshot-tests.md`). Robolectric-native
screenshot tests, running inside `testDebugUnitTest` like every other JVM unit test in this module
- no emulator, no device, no `androidTest` source set. The test classes live in
`app/src/test/java/com/kevin/legion/screenshot/`; every PNG this folder holds is a committed
baseline one of those tests compares its render against.

## What's baselined here

| File | Test | What it proves |
|---|---|---|
| `pager-home-seeded-arrangement.png` | `WidgetPagerHomeScreenshotTest` | The widget pager's HOME page with the seeded default arrangement (agenda / next-due / two stat tiles / quick-add), over a bare `ComponentActivity` with `WidgetPagerRoot` mounted directly (the former `WidgetPagerActivity` was deleted when the pager became the home route). |
| `deckgrid-edit-mode.png` | `DeckGridEditModeScreenshotTest` | `DeckGrid` in edit mode: the dotted cell-boundary grid, per-card remove/size chips, four fixed `GridItem` fixtures across every `GridPreset` shape. |
| `generated-list.png` | `GeneratedScreensScreenshotTest` | The generated list screen (`ui/generated/GeneratedListScreen.kt`) over one seeded Dates "Event" record. |
| `generated-detail.png` | `GeneratedScreensScreenshotTest` | The generated detail screen - every field rendered, provenance in words. |
| `generated-form-add.png` | `GeneratedScreensScreenshotTest` | The generated add form - one editor per `FieldDef.type` on the Dates aspect's field set. |
| `stat-tile-not-configured.png` | `EngineWidgetStatesScreenshotTest` | `StatTileWidget`, `recordTypeId = null` - the "not configured yet" state. |
| `stat-tile-error-deleted-field.png` | `EngineWidgetStatesScreenshotTest` | `StatTileWidget` pointed at a `fieldId` that no longer exists in `field_defs` - the "the configured field was deleted" error state. |
| `stat-tile-data-count.png` | `EngineWidgetStatesScreenshotTest` | `StatTileWidget` with one active record - a real `Count(1)`. |
| `record-list-empty-in-words.png` | `EngineWidgetStatesScreenshotTest` | `RecordListWidget` against a real, zero-record record type - "NO RECORDS YET", worded emptiness rather than a blank list. |
| `mirror-sync-no-folder.png` | `MirrorSyncNoFolderScreenshotTest` | `MirrorSyncActivity` before any mirror folder has ever been connected. |

No single `WidgetKind` carries all four states the ticket asks for (data / empty-in-words / error /
not-configured) - see `EngineWidgetStatesScreenshotTest`'s own class doc for exactly which widget
covers which state and why that split was chosen over inventing a new production seam.

**Device profile**: every test renders at `w384dp-h832dp` (`ScreenshotDeviceConfig.QUALIFIERS`) -
the real phone this app ships on (Galaxy A25, `memory/MEMORY.md`). **Dark only**: `ui/theme/Theme.kt`
is a stated, deliberate dark-only design (VACUUM/SENTRY, no `LegionThemeFollowingSystem`, no light
`ColorScheme` exists anywhere in the app) - there is no light baseline to render because there is no
light theme to render it against.

## Recording vs. verifying

- **Verify** (what `testDebugUnitTest` runs, every time, including CI-shaped local runs): compares
  each test's live render against its committed PNG here and fails the test on any pixel diff.
  This is the default - no flag, no property, nothing to opt into.

  ```
  ./gradlew :app:testDebugUnitTest
  ```

- **Record** (regenerates every baseline in this folder from the CURRENT code - an intentional,
  human-reviewed action, never run automatically):

  ```
  ./gradlew :app:recordRoborazziDebug
  ```

  Look at the diff (`git diff` on the PNGs, or open them) before committing. A baseline that
  changed because you deliberately changed a screen's layout/copy/theme is expected and fine to
  commit. A baseline that changed and you don't know why is a regression - do not commit it, go
  find out what moved first.

- **Compare only** (writes diff images under `build/outputs/roborazzi` without touching the
  committed baselines or failing the build - useful while iterating on a screen before deciding
  whether to record):

  ```
  ./gradlew :app:compareRoborazziDebug
  ```

## What a surprise diff means

If `verifyRoborazziDebug` (or plain `testDebugUnitTest`) fails on a screenshot test you did not
expect to touch, that is a real signal, not a flake to re-run past - something in the render path
changed: a theme token, a `DeckPane`/`DeckRow` layout change, a Compose or Robolectric version bump
that shifted font metrics, or (less happily) an actual regression in the screen under test. Diffs
are **review artifacts**. Never auto-accept a diff by blindly running `recordRoborazziDebug` and
committing without opening the images - that defeats the entire point of a baseline.

## Never run the record task from CI or a git hook

Same posture this repo already holds for the LLM evals: recording is on-demand, run by a person who
is looking at the result, never wired into `testDebugUnitTest`, a pre-commit hook, or a CI job. A
git hook that silently re-recorded on every commit would make this suite unable to ever catch a
regression - every "failure" would just quietly become the new truth.

## Determinism

- No `System.currentTimeMillis()`/`Date()` in any fixture. Every seeded `EngineRecord` is written
  through `RecordStore.create(..., now = <fixed literal>)`; `DefaultArrangementSeeder` has no `now`
  parameter reaching anything user-visible, since every seeded widget starts deliberately
  unconfigured (see that class's own doc comment).
- Animations are frozen. `DeckGrid`'s edit-mode jiggle is an infinite Compose animation -
  `composeTestRule.mainClock.autoAdvance = false`, set **before** `setContent` (per the vendored
  `android-testing` skill's own rule), freezes every card at its animation's `initialValue` rather
  than a random mid-animation phase.
- Fixed locale/timezone: every date/money format call in the screens under test
  (`SimpleDateFormat(..., Locale.US)`) already hardcodes `Locale.US` in production code - nothing
  extra was needed here. No screen under test formats a `TimeZone`-sensitive value.
- Fixed device config: `@Config(qualifiers = "w384dp-h832dp")` on every test class, via
  `ScreenshotDeviceConfig.QUALIFIERS`.
- Fixed graphics backend: `app/src/test/resources/robolectric.properties` pins
  `graphicsMode=NATIVE` module-wide, so a future Robolectric bump cannot silently change what a
  committed baseline was captured against.
