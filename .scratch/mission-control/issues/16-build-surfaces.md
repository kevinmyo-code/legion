# Build: the surfaces, one ticket per surface

Type: task
Status: resolved
Blocked by: 12, 13, 14, 15

## Question

Rebuild each surface to its inventory. Graduated from fog 2026-08-14, once ticket 12 said what is on
each surface and 13-15 landed the theme, shell and chart kit they all sit on.

**This is a container, not a unit of work. Take one surface at a time**, in the order below, and
resolve this ticket only when all of them are done - or split it into per-surface tickets the moment
any one of them turns out to need real argument rather than execution.

## Order, and why

1. **HOME** (`today`) - the pattern-setter. [Ticket 11](11-home-panel-inventory.md) holds its
   inventory. Do this first; the other four copy its grammar.
2. **BIO** (`body`) and **FLEET** (`fleet`) - already four panes each, so they drop onto the 2x2
   grammar with the least argument. [Ticket 12](12-surface-inventories.md).
3. **CRED** (`money`) - sheds three of its seven sections. Two of them merge into a new CATEGORIZE
   drilldown and `START OVER` moves to Setup, so this one moves navigation, not just layout.
4. **LOG** (`notes`) - **last, and read the warning below.**

## The shape rule, from ticket 12

Hero, then tiles, then full-width lists. Figures get tiles, rows get width. Every surface leads with
a hero: BIO/MASS, FLEET/UPLINK, CRED/SPEND, LOG/TODAY.

## LOG carries a known collision

**Ticket 12 flagged LOG's inventory as the weakest thing on the map** - `reasoned`, not read off the
screen, because `NotesScreen` is toggle-based and the `quant-viz` effort changed it recently.

That effort fixed a scroll regression by making its `LazyColumn` **the only scroll surface**.
[Ticket 05](05-tiling-grammar.md) says a tiled root scrolls inside a pinned shell. **Those two have
to be reconciled or the regression comes back.** Re-read `NotesScreen` and `.scratch/quant-viz/`
before touching it.

## Binding on every surface

- **Silent domains keep full-size tiles with worded empty states.** Grid position never moves
  (ticket 11).
- **Attention by tag, never by reordering.**
- **A half tile holds at most 7 characters of hero.** Check each figure; do not eyeball it
  (ticket 05).
- **Content budget is 560dp**, measured (ticket 05's correction, ticket 14).
- **22dp feed rows cannot be tapped.** Anything that navigates is 48dp.
- **Migrate controls to `DeckControls`** as each surface is touched. This is where ticket 09's
  vocabulary stops being unused.
- **CLAUDE.md §4 survives the skin**: provenance, quarantine, estimate and `UNRECONCILED` are said
  in words. Money stays `Long` cents.

## Verification, per surface

- `compileDebugKotlin -Pnokey` and `testDebugUnitTest` green.
- **Install with `install -r`. NEVER `adb uninstall`, and never `pm clear`.**
  On 2026-08-14 a build agent ran `adb uninstall` mid-session on Kevin's real phone. Android's own
  Auto Backup happened to restore the database, so the 18,645 OBD samples and 148 ledger rows
  survived - **but that was luck, not a safety net.** What did not survive: the Keystore-encrypted
  Gemini key, the Drive sync authorisation, and the microphone and calendar permissions, none of
  which Auto Backup covers. All four had to be re-entered by hand.
  **This is Kevin's real logged data on his real phone, not a test fixture.** `install -r` upgrades
  in place and keeps everything.
- **Install and look at it.** Hash-verify the install; `adb` is at
  `AppData\Local\Android\Sdk\platform-tools\adb.exe`, not on PATH.
- **Sample pixels for anything that looks like a 1dp or a colour question.** Ticket 14 established
  that judging a rendered detail by eye is not a check.
- **TalkBack on any migrated control.** This is the check ticket 13 deferred, and it lands here.
  A control without real semantics is invisible in a screenshot.
- **Layout Inspector on any ambient element**, confirming recomposition stays flat for it and its
  ancestors. Owed since ticket 07; FLEET's uplink sweep is the first one that will need it.

## Two open items inherited

- **Bottom bezel padding renders ~5.5dp against ticket 03's 12dp** (ticket 14). Fix whenever the
  shell is next touched.
- **`DeckBar`'s `valueLabel` and `mark` collide**, and `DeckLineOverlay`'s endpoint labels can crowd
  the chart edge (ticket 15). Neither has a live caller yet; the first surface to use either must
  deal with it.

## Answer

All five surfaces rebuilt, installed and verified on the phone, 2026-08-14.

| Surface | Commit | Shape |
|---|---|---|
| HOME | `6347bfd` | SYSTEMS SWEEP dissolved into four tiles; ALERTS consolidated |
| BIO | `9a42f09` | MASS hero, INTAKE/SLEEP tiles, TRAINING full |
| FLEET | `9a42f09`, `2e1008d`, `14ef1ee` | UPLINK hero, MAINTENANCE/DRIVES tiles, CARS full |
| CRED | `2e1008d` | seven sections to four, three relocated |
| LOG | `de6af8c` | TODAY hero, MISSED/LISTS tiles, calendar and inbox below |

Plus `82055e4` (bezel padding), `5a67b7e` (account masking), `a5e2915`/`e3673e7` (the `install -r` rule).

### What the device caught that review could not

Every one of these was invisible in the diff and in the previews. **The rule that produced them is: install it and sample the pixels.**

1. **A full 16-digit card number rendered on the CRED root.** The BALANCES tile printed `accountId`
   raw, and on real data that is the PAN from a BofA statement. **Every preview uses
   `"BOFA ****4471"`, which already looks masked**, so the code read correctly until real data went
   through it. Fixed with `maskedAccountLabel` at all three display sites, six tests pinning the
   rule, and the principle recorded: **a stored identifier and a displayed one are not the same
   string.**
2. **`DeckBezel`'s content padding was measured from the wrong edge**, leaving content 7dp short on
   every side. It had already been found twice and misfiled both times - ticket 14 called it a
   bottom-padding deviation, this ticket's HOME build called it horizontal drift in the planning
   doc. One bug, two symptoms, three sightings before anyone understood it. Ticket 03's own
   arithmetic ("32dp, 6 + 1 + 9, doubled") was the proof the whole time.
3. **`HalfTileHero` silently dropped the second word** of a two-word hero: `NOT LOGGED` rendered as
   `NOT`, because `softWrap` defaulted true and `maxLines = 1` then ate the overflow line.
4. **The amber-instead-of-mint bug shipped four times** - chart series, HOME's tiles, BIO's MASS,
   and nearly CRED - every instance from reading `MaterialTheme.colorScheme.primary` where
   `LocalLegionSemantics.current.data` was meant.
5. **FLEET's UPLINK buried its own tiles.** Six real DTCs pushed MAINTENANCE, DRIVES and CARS
   entirely off screen. Fixed in two passes, and the second was needed because the first estimate
   was 12dp against a measured 52dp - the gap being rows that only exist on a car with real faults.

### Deviations from the tickets, each reported rather than taken silently

- **LOG keeps MISSED's full-detail rows** alongside its new tile. Ticket 12's inventory implied
  replacing them, but this domain has no drilldown to route a tap to, so collapsing working
  per-row controls into a passive figure would have been a functional regression.
- **CRED gained a BALANCES drilldown** beyond ticket 12's list. One tile cannot show four accounts
  across two currencies, and CLAUDE.md §4 forbids inventing an FX rate to combine them.
- **FLEET's ADAPTER and SPECS/VIN moved to CARS.** They are configuration, not telemetry.
- **Ticket 05's horizontal figures held.** Its vertical budget did not (560dp, corrected by ticket
  14), but 360dp, the 32dp bezel cost and the 328dp interior were measured rather than estimated
  and all survived contact.

### Verification accounting (CLAUDE.md §8, L11)

| Step | Status |
|---|---|
| Compile and unit tests | **DONE**, green after every commit, run directly rather than relayed |
| Install and hash-verify | **DONE**, SHA-256 compared both sides on every install |
| Screenshot each surface | **DONE**, all five plus Setup and the drilldowns |
| Sample pixels rather than eyeball | **DONE** - this is what caught items 1-4 above |
| LOG scroll regression test | **DONE**, and it is the check that mattered: a swipe changes 40% of pixels with the diff confined to y=200-1305, so content scrolled and the pinned shell did not. Tested with the calendar expanded and collapsed |
| Screen audit beyond the five surfaces | **DONE**, every drilldown and utility screen opened. Three pre-existing observations reported, nothing needing a fix |
| **TalkBack on migrated controls** | **NOT DONE.** Owed since ticket 13, and it is now genuinely owed - `DeckButton` is live on Setup's purge row, CRED's CATEGORIZE and LOG's calendar-grant row. **This is the one verification step this map has never satisfied.** |
| **Layout Inspector on an ambient element** | **NOT APPLICABLE yet.** The FLEET uplink sweep was deliberately not built; it is the only ambient element and ticket 07 requires a flat-recomposition check that cannot run headlessly |
| QUARANTINE drilldown on-device | **NOT REACHABLE** - no quarantined document exists right now. Source reviewed instead |

### Still open

- **The FLEET uplink sweep** (ticket 07). The last unbuilt decision on the map.
- **TalkBack.** See above.
- **Ticket 10's daylight pass.** Kevin ruled it fine without measuring; the computed matrix on that
  ticket stands as the only evidence, and four tokens fail their floors on paper.
- `DeckBar`'s label/mark collision and `DeckLineOverlay`'s endpoint crowding (ticket 15). Still no
  live caller.

### Assumptions ledger

| Claim | Tag |
|---|---|
| Compile and tests green at every commit | **`tested`** - run directly by the orchestrator |
| Every install byte-identical | **`on-device`** - SHA-256 both sides |
| Five surfaces render as described | **`on-device`** - screenshotted and inspected |
| Hero colours are mint | **`on-device`** - pixel-sampled, not eyeballed |
| Bezel interior 327dp vs 328 spec | **`on-device`** - 267 scanlines |
| LOG still has one scroll surface | **`on-device`** - swipe diff confined to the content region |
| FLEET clears the fold by 61.5dp | **`on-device`**, relayed from the build agent's pixel measurement, not independently re-measured by the orchestrator |
| Account masking changes display only, never identity | `traced` - `sameCard` and dedup read the stored value; six tests include that relation |
| TalkBack semantics on the migrated controls | **`reasoned`** - inferred from the modifiers. **Never observed.** |

## TalkBack, finally checked (2026-08-14)

**The check owed since ticket 13 is now done, and `DeckControls` passes.** TalkBack itself could not
be enabled - `WRITE_SECURE_SETTINGS` is denied over wireless ADB - so the evidence is the
accessibility node tree via `uiautomator dump`, which is what TalkBack reads.

| Control | Result |
|---|---|
| CRED `RUN CATEGORIZATION` (`DeckButton`) | **PASS** - 48dp bounds, `clickable="true"`, name present |
| Setup `PURGE LEDGER` (`DeckButton`, destructive) | **PASS** - clickable node at `[1109,1205]` is exactly **48.0dp**, spanning its own label at `[1144,1170]` |
| LOG calendar-grant row | **NOT REACHABLE** - Google Calendar is already linked on this device, so the row never renders. Unchecked, and it stays unchecked |

`DeckControls` was read to confirm the mechanism rather than inferring it from the dump:
`Role.Button` on `DeckButton`'s `clickable`, `Role.Switch` and `Role.Checkbox` on the `toggleable`
variants, `Role.RadioButton` on `selectable`, and an explicit `stateDescription` on the switch.
Ticket 09's requirement is met in the code and confirmed in the tree.

### A false positive, recorded because the method matters more than the result

A build agent reported this as **"a severe, real, reproducible defect"**: the purge row measuring
29dp unarmed and collapsing to 3dp and 1dp when armed, with empty `content-desc`, which would mean
TalkBack announcing the app's only destructive control as unlabelled. It spent a very large budget
on eight-plus diagnostic rebuilds, ruled out `Row`, `weight`, the colour branch, the string content,
`BasicText`, and `GlanceCardOverlay`, then concluded the cause was "tied to absolute screen
Y-position", which it could not explain. It correctly reverted everything rather than shipping a
speculative fix.

**None of it was real.** `uiautomator dump` reports the bounds of what is *currently rendered*, and
the purge row sits last in a scrolling list, so a dump taken without scrolling to it measures a
partially-clipped node. Scrolled into view, the same node is 48dp. The screenshot confirms it: a
neutral ink outline, full label, correctly sized - exactly ticket 04's neutral-until-commit spec.

An earlier audit had already seen the same symptom, measured it at 19dp unscrolled versus 54dp
scrolled, and correctly dismissed it as ordinary list behaviour. That finding existed and was not
consulted.

**The lesson is narrow and worth keeping: a device measurement is only valid for the state the
device was actually in.** Pixel sampling and node dumps are stronger evidence than eyeballing a
screenshot, which is why this map adopted them - but they are not automatically trustworthy. **Scroll
the target into view before measuring it**, or the measurement describes the clip, not the widget.
