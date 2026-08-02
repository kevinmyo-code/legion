# MEMORY.md

Dashboard for LEGION. Read before responding. **MEMORY.md wins for state, CLAUDE.md wins for
rules.** Depth lives in the library, not here. Under 80 lines. MIDNIGHT_AI: see CLAUDE.md §1.

## Status as of 2026-08-02

- **Builds clean.** `-Pnokey` compile + `testDebugUnitTest` green (19 tests). Detail: README.md.
- **`ui/` = theme + placeholders.** No real screens. `MainActivity` still uses `MaterialTheme`.
- Repo public: `github.com/kevinmyo-code/legion`. `dev` is the trunk; `main` is behind.
- **The wayfinder map is COMPLETE (11/11).** Next pass is implementation, not decisions.

## Blocking

- **Drive OAuth is keyed to package + SHA-1 signing cert.** A stranger's own build fails auth.
  Threatens clone-and-run. No approach chosen.
- **Assistant identity is placeholder** (`ai/AssistantIdentity.kt`). Ticket 07 deferred onboarding
  because of it. Needs its own effort.
- **Sync retry exhaustion throws.** After `MAX_CONFLICT_RETRIES`, `syncFile` calls `check(...)` and
  nothing reports it. Ticket 10 puts log-and-skip in scope. (The old "Drive has no CAS / must become
  append-only" blocker was WRONG - see `decisions.md` 2026-08-02.)
- **Firebase not wired.** `MidnightEvents` logs via `Log.d`, so a swallowed exception is invisible.
- **Crisis resource is US-only (988).** Unfixed.
- **`.claude/plans/wiggly-beaming-quasar.md` does NOT exist**, despite both READMEs citing it. Never
  survived the port. Do not send an agent to read that path.

## Untested / unverified

- **Almost nothing has run on a device.** Verified on the A17K so far: the v3->v4 Room migration
  (`tested` 2026-08-02) and the Instrument dark theme. Everything else is compile + unit tests.
- **None of `sync/` has ever run here.** Ticket 10's rulings are all `traced`, none `tested`.
- **Probe steps 7-9 unrun**: reboot grant persistence, offline failure mode. USB never enumerated;
  wireless ADB severs on both.
- **Theme: DARK rendered on hardware and holds up. LIGHT still unrendered.**
- `LedgerController` dedup and `PantryController` DB-write paths untested (Robolectric mismatch).
- Every ported fleet path (OBD, wake word, proactives) compiles, never exercised.

## In-flight

**`.scratch/ledger-drive-ingestion/` - MAP COMPLETE, 11/11.** The map plus its eleven ticket
resolutions ARE the build spec. Tracked in git; every decision also in `library/decisions.md`
(2026-08-01/02). Carry-forward actions:

- **Before any spend-gate UI: pull live `gemini-3.5-flash-lite` pricing.** Ticket 06 adopted NO
  price constant; the analyst's figures are `reasoned`, unverified. Thinking-token billing unknown.
- **Ticket 07 deletions**: `BootReceiver` + `RECEIVE_BOOT_COMPLETED`, and manifest entries for
  `SavedPlaces` / `LedgerImport` / `PantryImport` activities. `MainActivity` -> `LegionTheme`.
- **Ticket 09 mandates an extraction**: `SectionHeader`, `Hairline`, `ReadingRow`, `NotBuiltRow`
  into `ui/common/`, before the three aspects diverge.
- **Two prototype branches, NEITHER to be merged:** `proto/ledger-ui` (`476318e`),
  `proto/fleet-pantry-ui` (`07abbdf`). Both carry a temporary `MainActivity` host.
- **Design for "a scan may legitimately find nothing new."** The crux passed on device, but a new
  file was invisible 2m36s+ and appeared only after the Drive app was opened.

## Notes for next session

- **Do not resolve a schema ticket before its consumers.** Ticket 03 took four amendments; nothing
  overturned, but unstable until they ran. Log at the bottom of ticket 03.
- **Re-read source before treating an inherited blocker as a constraint.** The append-only blocker
  had been carried since the port and dissolved on contact with `sync/`.
- **`.scratch/` is tracked in git**, but filing to `library/decisions.md` is still required: git
  protects working state, the library is what gets read.
- **Verify what the librarian writes.** A 2026-07-29 FILE dispatch invented substantial detail and
  had to be corrected by hand.
- **Two contested calls the port left open:** whether `media/MusicController` is still wanted
  alongside Spotify App Remote, and `vehicle/BuildSheetController` entries now text-only.
- **ADB works** (A17K, wireless). `qa` (Owen) is a real seat.

## Library

`memory/library/` (catalog: `INDEX.md`). Never bulk-read shelves; dispatch the librarian. **Most
shelves are FROZEN Midnight AI history**, banner-marked. LIVE: `decisions.md`, `lessons.md`,
`playbook-coding.md` (partly). CLAUDE.md §11.

## How to update this file

- Under 80 lines. One-liners; narratives go to the library via the librarian (FILE).
- Session end: dispatch librarian FILE, then refresh Blocking / In-flight / Notes.
- A decision changing a CLAUDE.md rule is filed to `library/decisions.md` AND applied to CLAUDE.md
  in the same commit.
