# MEMORY.md

Dashboard for LEGION. Read before responding. **MEMORY.md wins for state, CLAUDE.md wins for
rules.** Depth lives in the library, not here. Under 80 lines. MIDNIGHT_AI: see CLAUDE.md §1.

## Status as of 2026-08-02 (session 2)

- **THE MAP IS BUILT.** All 11 tickets resolved, 03-09 implemented. No unbuilt ticket remains.
- **Builds clean.** `-Pnokey` compile + `testDebugUnitTest` green (71 tests). Detail: README.md.
- **Both LLM paths proven on hardware** on Kevin's key: ledger extraction (reconciled, and
  separately REFUSED when the doc printed no total) and pantry receipt vision.
- All four tabs are real screens. `feat/ledger-ingestion` is 13 commits, PUSHED. `dev` is still
  14 ahead of `origin/dev`; `main` is far behind everything. The merge is Kevin's.

## Blocking

- **Drive OAuth keyed to package + SHA-1 cert.** Stranger's build fails auth. No approach chosen.
- **Assistant identity is placeholder** (`ai/AssistantIdentity.kt`). Blocks onboarding; own effort.
- **The permission chain has NEVER been exercised** - and a correct refusal is its whole point.
- **Sync retry exhaustion throws**, unreported; ticket 10 scopes log-and-skip.
- **Firebase not wired.** `MidnightEvents` logs via `Log.d`; a swallowed exception is invisible.
- **Crisis resource is US-only (988).** Unfixed.
- **`.claude/plans/wiggly-beaming-quasar.md` does NOT exist** despite both READMEs citing it.

## Untested / unverified

- **`sync/` has NEVER executed.** Ticket 10's rulings are all `traced`. Largest untested surface,
  and the only one where a wrong ruling means silent data loss.
- **The whole fleet aspect is unexercised** - OBD, wake word, proactives compile, never run. The
  fleet screen renders DISCONNECTED because that is genuinely the state.
- **`assets/dtc_descriptions_seed.json` has NEVER existed** in git history despite
  `DtcDescriptions` calling it bundled. Every fault code reads "not identified locally".
- **No run against a REAL Drive folder** - a local SAF tree cannot reproduce the 2m36s stale listing.
- **A tab switch DURING a live scan** - `reasoned` (service scope); fixtures finish too fast to stage.
- **The `+` money fix** is unit-tested only. **The nav graph has ZERO tests** (back-stack `reasoned`).
- **Probe steps 7-9 unrun**: reboot grant persistence, offline failure mode.
- `LedgerController` dedup and `PantryController` DB-write paths untested (Robolectric mismatch).

## In-flight

**Nothing.** `.scratch/ledger-drive-ingestion/` is complete: 11/11 resolved, 03-09 built. The next
effort needs its own map. Candidates, in the order I would take them:

1. **`sync/`** - the biggest risk, and today proved `traced` claims die on contact with hardware.
2. **The assistant's voice** (`AssistantIdentity`), which unblocks onboarding.
3. **A DTC seed dictionary**, so FAULTS says something useful.
4. **Ledger categorisation / FX / insights** - nothing to port, new design work.

- **Two prototype branches, NEITHER to be merged:** `proto/ledger-ui`, `proto/fleet-pantry-ui`.
- **Open, not blocking:** Compose BOM vs nav-compose skew; deep-link `navigate()` lacks `popUpTo`.

## Notes for next session

- **RUN IT ON THE PHONE.** Three bugs today survived compile, 71 tests AND senior-dev review: body
  text in quarantine red, a saved key the gate could not see, every document date a day early.
- **A date-only value and an instant must not share a formatter.** Ingestion stores dates at UTC
  midnight; render in UTC (`documentDate`). The 8 other call sites are instants, correct in local.
- **Fixtures must carry KNOWN, DERIVED totals and dates.** Both generators (`tools/`) compute the
  printed total from their rows. Only reason the `+` bug and the date bug were findable.
- **A foreground service is not a home for process-wide init (L12).** `MidnightApplication.onCreate`.
- **A ticket's own verification step is a gate, not a note (L11, CLAUDE.md §8).**
- **Verify what the librarian writes.** Four FILE passes, all needed hand correction. Two
  contested port calls still open: `MusicController` vs Spotify, `BuildSheetController` text-only.
- **ADB after a reboot needs a FRESH `adb pair`**, and the network moved to `192.168.4.x` today.
- **Device quirks:** `pm clear` is OEM-blocked (uninstall to wipe); `adb push`ed files are invisible
  to the Downloads provider (stage SAF fixtures under the device root); this machine's execution
  policy refuses unsigned `.ps1` FILES, so run generators via `Invoke-Expression (Get-Content -Raw)`.

## Library

`memory/library/` (catalog: `INDEX.md`). Never bulk-read shelves; dispatch the librarian. **Most
shelves are FROZEN Midnight AI history**, banner-marked. LIVE: `decisions.md`, `lessons.md`,
`playbook-coding.md` (partly). CLAUDE.md §11.

## How to update this file

- Under 80 lines. One-liners; narratives go to the library via the librarian (FILE).
- Session end: dispatch librarian FILE, then refresh Blocking / In-flight / Notes.
- A decision changing a CLAUDE.md rule is filed to `library/decisions.md` AND applied to CLAUDE.md
  in the same commit.
