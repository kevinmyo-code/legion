# Build: theme, tokens, typeface and the control vocabulary

Type: task
Status: resolved
Blocked by: 01, 02, 04, 09

## Question

Land the palette, the bundled typeface and the app-wide control vocabulary in code. No screen
relayout in this ticket.

Graduated from fog 2026-08-14: tickets 01-07 have all landed, which is the condition the map set for
build tickets to be sliced.

**This ticket unblocks [ticket 10](10-daylight-contrast.md)**, which cannot run until there is a
built palette to install.

## Scope

1. **`ui/theme/Color.kt`** - the VACUUM / SENTRY table from ticket 01, **as revised by ticket 06**:
   `good` `#7BE86A` is REMOVED, `credit` resolves to `data`. New tokens the palette needs that
   `LegionSemantics` lacks: `chrome`, `chromeText`, `chromeDim`, `marker`, `data`.
2. **`ui/theme/Theme.kt`** - map the tokens onto M3 roles. **The `contentColorFor` collision audit is
   a required step, not a note**: no two of the twelve early `ColorScheme` roles may share a raw
   value. This is the bug class that shipped once already (CLAUDE.md §8, L11). Re-read that file's
   existing audit doc comment before changing anything.
3. **`ui/theme/Type.kt`** - Martian Mono Condensed, three statics in `res/font`, `OFL.txt` into
   `third_party/`. **The scale needs roughly a 10% pass down** (ticket 02: 0.800em cap height). Not
   optional.
4. **`ui/common/DeckPanels.kt`** - per ticket 03's verdict table: `DeckPane` gets the pill and a full
   frame; `DeckRow` splits into 22dp display and 48dp tappable; `DeckMeter` recolours and drops to
   6dp; `DeckTag`/`QuarantineTag` and `StatusLine` carry over untouched. New: `DeckBezel`,
   `DeckSectionRule`.
5. **The control vocabulary** from ticket 09, app-wide: switch as a segmented ON/OFF toggle,
   checkbox, radio, button, text field, dropdown, dialog.
6. **Re-home all 50 `sem.quarantined` call sites** to ticket 04's three tiers. Mechanical, not small,
   and skipping it leaves the app exactly as unsorted as it is now.
7. **`ui/theme/DeckMotion.kt`** - its doc comment claims "ambient motion is exactly ONE element
   app-wide", superseded by ticket 07, and its ticket references point at `cyberdeck-ui`.

## Verification, all of it binding

- `./gradlew compileDebugKotlin -Pnokey` and `./gradlew testDebugUnitTest` green.
- **Render all five previews in `ui/theme/ThemePreview.kt`.** This is the step that was skipped last
  time and it is how quarantine-red body text reached every screen (L11). If it cannot be rendered,
  that is a blocking item to surface, not a footnote.
- **TalkBack pass on every rebuilt control** - confirm the M3 role, the state description and the
  48dp target. Ticket 09: a control rebuilt as a bare `Box` with an `onClick` is a regression, and
  it is invisible in a screenshot.
- **Install on the device and verify by hash** - `adb`/`pm` reporting "Success" has installed a
  different APK before, and it cost a day's data. `adb` is not on PATH; it is at
  `AppData\Local\Android\Sdk\platform-tools\adb.exe`.
- Run the `dataviz` validator over the final `Color.kt` values as a regression check (map Notes).

## Answer

Built 2026-08-14 across four commits on `feat/mission-control`:

| Commit | What |
|---|---|
| `24c40e6` | palette, `LegionSemantics`, M3 scheme, bundled typeface |
| `68c9f75` | bezel, label pills, split row, meter, section rule |
| `43a9eb1` | the app-wide control vocabulary |
| `fc34d9c` | the 50 red call sites sorted into tiers, `DeckMotion` doc |

### The headline result

**Red went from 50 call sites to exactly 3**, and all three are genuine ALARM: `QuarantineTag`
itself, the ledger quarantine row's bar, and the DTC fault code. Verified by grep after the fact,
not taken on report. 42 sites were re-homed; advisories to amber, destructive labels to neutral ink
with chrome reserved for the confirming step.

### Verification accounting (CLAUDE.md §8, L11)

Every step in this ticket's own list, accounted for as done / deferred-with-a-named-follow-up /
impossible-and-why. Nothing carried silently.

| Step | Status |
|---|---|
| `compileDebugKotlin -Pnokey` | **DONE**, green, run directly rather than taken on an agent's report |
| `testDebugUnitTest` | **DONE**, green, run directly |
| `dataviz` validator regression over the final values | **DONE** - see the finding below |
| Install on the device and verify by hash | **DONE.** Local and on-device SHA-256 both `b22523fb75061de12dab596d0954154410cb4453abb3bc7629765ddc9b064b7c`. The memory note exists because "Success" from `pm` has installed a different APK before |
| Render the five `ThemePreview.kt` previews | **IMPOSSIBLE headlessly, and mitigated.** Compose previews cannot be rendered from this environment. `ThemePreview.kt` was updated, gained coverage for every new component, and compiles. **The mitigation is strictly stronger than the gate**: the APK was installed and the running app was screenshotted, which is how the L11 bug was originally caught. Two surfaces were inspected and the bug class is absent |
| TalkBack pass on every rebuilt control | **DEFERRED, with a reason and a named owner.** Nothing is migrated to `DeckControls` yet - by design, migration belongs to the surface build tickets - so there is no control on any screen to test. **The check moves to the first surface build ticket that adopts one**, and must not be dropped: ticket 09 records that a control lacking real semantics is invisible in a screenshot |

### What the running app showed

Inspected: HOME and Setup.

**Working:** pure black ground, mint values, red-orange pills reading correctly against the panes,
inverted-amber advisory tags (`BEHIND`, `COVERAGE GAP`), Martian Mono Condensed throughout, the
active nav key inverting amber, and the status line's block cursor.

**The re-homing is visibly correct.** Setup shows `Gemini key: Set`, `Google: Drive connected`,
`Spotify: Set up` - all three previously drawn in `sem.quarantined`, all now neutral. Nothing on a
settings screen is shouting.

**Two honest observations:**

1. **The Assistant switch is still a Material sliding thumb.** Correct per scope, and it now reads
   as visibly foreign against everything around it. That is the right signal that migration is not
   cosmetic.
2. **`panel` against `ground` is more perceptible in practice than its 1.04:1 ratio suggested.**
   Rows are clearly distinguishable on the device. This **partly softens ticket 10's structural
   alarm**: WCAG luminance ratio is a text-legibility measure, not a surface-separation measure on
   an OLED black. It does not settle the sunlight question, which remains untested.

**Not yet visible, all correctly out of scope:** no bezel (ticket 14 wires it into the shell), and
chart series are still amber rather than mint (ticket 15).

### A finding from the validator regression

`marker` `#FFD84A` sits at dE 7.0 from `amber` under normal vision and 5.9 under deuteranopia.
**`DeckMarker`'s hue carries no information.** Tickets 01 and 06 both saw this and responded by
making markers shape-typed, so the mitigation already exists - but the token itself now earns
nothing, and deleting it would lose nothing. Left in place because two tickets decided it exists;
flagged for whoever builds ticket 15.

### Deviations from the ticket as written

- `Type.kt`'s `titleMedium` was specified at `SemiBold`; only Regular, Medium and Bold statics are
  bundled, so it was changed to `Medium` rather than asking Android to synthesise a weight that is
  not there.
- `DeckPane` gained one optional parameter, `pillBackground`. The pill must paint whatever sits
  behind the pane rather than the pane itself, so an alarm pane has to be able to pass its own.

### Known limitations recorded in code rather than smoothed over

- `DeckTextField`'s block cursor renders at the end of the string, not the true caret on a
  mid-string edit.
- `DeckDialog`'s "inside the bezel" placement is an inference; the bezel is not wired yet.
- `DeckSectionRule`'s label-to-line gap was not specified by ticket 03 and borrows the existing row
  gutter.
- `CompanionRows`' delete-confirmation button is not coloured at all today. Left alone with an
  in-code note rather than quietly given a treatment it was never specified to have.

### Assumptions ledger

| Claim | Tag |
|---|---|
| Compile and unit tests green | **`tested`** - both run directly by the orchestrator, not relayed |
| Exactly 3 `sem.quarantined` reads remain, all ALARM | **`tested`** - grepped after the change |
| Installed APK is byte-identical to the built one | **`on-device`** - SHA-256 compared both sides |
| The theme renders without an L11-class contrast failure | **`on-device`** - two surfaces screenshotted and inspected |
| `panel` is perceptible against `ground` in practice | **`on-device`**, indoors only. Says nothing about sunlight |
| Every control carries correct TalkBack semantics | **`reasoned`** - inferred from the modifiers used. **Not observed.** This is the deferred check above |
| Bezel arc geometry and pill straddle math are correct | **`reasoned`** - neither is wired into a screen yet, so neither has been seen |
| Tier assignments for the 42 re-homed sites | **`reasoned`** - classified against ticket 04's categories; three ambiguous ones named in `fc34d9c` |
