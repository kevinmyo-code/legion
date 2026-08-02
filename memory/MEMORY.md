# MEMORY.md

Dashboard for LEGION. Read before responding. **MEMORY.md wins for state, CLAUDE.md wins for
rules.** Depth lives in the library, not here. Under 80 lines. MIDNIGHT_AI: see CLAUDE.md §1.

## Status as of 2026-08-02 (session 2)

- **Builds clean.** `-Pnokey` compile + `testDebugUnitTest` green (42 tests). Detail: README.md.
- **PIPELINE WORKS END TO END ON HARDWARE.** Folder -> scan -> gate -> real Gemini call -> gate
  REFUSED the result. Money spent, zero rows written. §4 validated live.
- **The shell renders**; ledger is real (variant B). Fleet/pantry interiors are ticket 09.
- **`feat/ledger-ingestion` is 6 parts deep and PUSHED.** `dev` is still 14 ahead of `origin/dev`.
  Repo public: `github.com/kevinmyo-code/legion`. `dev` is the trunk; `main` is behind.

## Blocking

- **Drive OAuth keyed to package + SHA-1 cert.** Stranger's build fails auth. No approach chosen.
- **Assistant identity is placeholder** (`ai/AssistantIdentity.kt`). Blocks onboarding; own effort.
- **The permission chain has NEVER been exercised** - and a correct refusal is its whole point.
- **Sync retry exhaustion throws**, unreported; ticket 10 scopes log-and-skip.
- **Firebase not wired.** `MidnightEvents` logs via `Log.d`; a swallowed exception is invisible.
- **Crisis resource is US-only (988).** Unfixed.
- **`.claude/plans/wiggly-beaming-quasar.md` does NOT exist** despite both READMEs citing it.

## Untested / unverified

- **On the A17K:** v3->v4 migration, ticket 04 case 7, the shell (dark + light), the full ledger
  pipeline incl. a live LLM call. Fleet, pantry, sync, assistant: never run.
- **`read by AI` has STILL never rendered** - needs a layout that falls through AND reconciles;
  every such fixture is built to fail. FIXTURE gap.
- **A tab switch DURING a live scan is untested**; the fix is `reasoned` (service scope).
- **The nav graph has ZERO tests.** Back-stack behaviour is `reasoned` only.
- **None of `sync/` has ever run here.** Ticket 10's rulings are `traced`, none `tested`.
- **Probe steps 7-9 unrun**: reboot grant persistence, offline failure mode.
- **One unexplained process death** 2026-08-02: empty crash buffer, no FATAL, no recurrence.
- `LedgerController` dedup and `PantryController` DB-write paths untested (Robolectric mismatch).
- Every ported fleet path (OBD, wake word, proactives) compiles, never exercised.

## In-flight

**`.scratch/ledger-drive-ingestion/` - MAP COMPLETE, 11/11**, and the eleven resolutions ARE the
build spec. Tracked in git; decisions also in `library/decisions.md`. **Built: 03-08.** Only
**ticket 09 (fleet + pantry screens)** is left.

- **Ticket 08 is not fully closed:** no fixture renders `read by AI`, and the run used a LOCAL SAF
  tree, so the probe's stale-listing latency is unreproduced. Needs a real Drive folder.
- **Before quoting any LLM cost, pull live `gemini-3.5-flash-lite` pricing.** Still no constant.
- **Ticket 09: set `contentColor` explicitly on error-container surfaces** (`errorContainer` shares
  a value with `secondaryContainer`), and reuse `ui/common/` rather than duplicating it.
- **Two prototype branches, NEITHER to be merged:** `proto/ledger-ui`, `proto/fleet-pantry-ui`.
- **Open, not blocking:** Compose BOM vs navigation-compose skew; deep-link `navigate()` lacks
  `launchSingleTop`/`popUpTo`.

## Notes for next session

- **A foreground service is not a home for process-wide init (L12).** Ticket 07 made
  `AriaForegroundService` opt-in and OFF by default; everything it incidentally seeded silently
  stopped being seeded. **`MidnightApplication.onCreate` is the safe home.**
- **A ticket's own verification step is not optional (L11, CLAUDE.md §8).** Ticket 07's skipped
  `ThemePreview` render shipped an M3 colour collision. **One colour value, one M3 role.**
- **Run it on the phone.** Every serious bug this session - red text, silent scans, forgotten
  folder, invisible key - survived compile, tests and review. Only installing found them.
- **Do not resolve a schema ticket before its consumers.** Ticket 03 took four amendments.
- **Verify what the librarian writes.** Three FILE passes so far, all needed hand correction.
- **Two contested calls the port left open:** `MusicController` vs Spotify App Remote, and
  `BuildSheetController` entries now text-only.
- **ADB after a PC reboot needs a FRESH `adb pair`**, and the live port was the PAIRING dialog's.
- **Device quirks:** `pm clear` is OEM-blocked (uninstall to wipe); `adb push`ed files are invisible
  to the Downloads provider - stage SAF fixtures under the device root.

## Library

`memory/library/` (catalog: `INDEX.md`). Never bulk-read shelves; dispatch the librarian. **Most
shelves are FROZEN Midnight AI history**, banner-marked. LIVE: `decisions.md`, `lessons.md`,
`playbook-coding.md` (partly). CLAUDE.md §11.

## How to update this file

- Under 80 lines. One-liners; narratives go to the library via the librarian (FILE).
- Session end: dispatch librarian FILE, then refresh Blocking / In-flight / Notes.
- A decision changing a CLAUDE.md rule is filed to `library/decisions.md` AND applied to CLAUDE.md
  in the same commit.
