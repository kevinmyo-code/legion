# MEMORY.md

Dashboard for LEGION. Read before responding. **MEMORY.md wins for state, CLAUDE.md wins for
rules.** Depth lives in the library, not here. Under 80 lines. MIDNIGHT_AI: see CLAUDE.md §1.

## Status as of 2026-08-02 (session 2)

- **Builds clean.** `-Pnokey` compile + `testDebugUnitTest` green (30 tests). Detail: README.md.
- **The shell EXISTS and renders on hardware.** One activity, four tabs, `LegionTheme`. Tab
  interiors are still placeholders - tickets 08/09.
- **`feat/ledger-ingestion` is 4 parts deep and UNPUSHED**, atop `dev` being 14 ahead of `origin`.
  Repo public: `github.com/kevinmyo-code/legion`. `dev` is the trunk; `main` is behind.
- **The wayfinder map is COMPLETE (11/11).** Next pass is implementation, not decisions.

## Blocking

- **Drive OAuth keyed to package + SHA-1 cert.** Stranger's build fails auth; threatens
  clone-and-run. No approach chosen.
- **Assistant identity is placeholder** (`ai/AssistantIdentity.kt`). Blocks onboarding; own effort.
- **The permission chain has NEVER been exercised.** Never flipped on a device, and a correct
  refusal is its whole point.
- **Sync retry exhaustion throws**, unreported; ticket 10 scopes log-and-skip.
- **Firebase not wired.** `MidnightEvents` logs via `Log.d`; a swallowed exception is invisible.
- **Crisis resource is US-only (988).** Unfixed.
- **`.claude/plans/wiggly-beaming-quasar.md` does NOT exist** despite both READMEs citing it.

## Untested / unverified

- **On the A17K:** v3->v4 migration, ticket 04 case 7, the shell. All else is compile + units.
- **The nav graph has ZERO tests.** Back-stack behaviour is `reasoned` only.
- **None of `sync/` has ever run here.** Ticket 10's rulings are `traced`, none `tested`.
- **Probe steps 7-9 unrun**: reboot grant persistence, offline failure mode. USB never enumerated.
- **DARK rendered, LIGHT never - and dark shipped a colour bug anyway** (Notes). Once != verified.
- **One unexplained process death** 2026-08-02: empty crash buffer, no FATAL, no recurrence since.
- `LedgerController` dedup and `PantryController` DB-write paths untested (Robolectric mismatch).
- Every ported fleet path (OBD, wake word, proactives) compiles, never exercised.

## In-flight

**`.scratch/ledger-drive-ingestion/` - MAP COMPLETE, 11/11**, and the eleven resolutions ARE the
build spec. Tracked in git; decisions also in `library/decisions.md`. **Built: 03, 04, 05, 06, 07.**
Next is ticket 08 (ledger UI), which is also what finally CALLS `IngestScanner.scan()`.

- **Before any spend-gate UI, pull live `gemini-3.5-flash-lite` pricing.** Ticket 06 took none.
- **Ticket 08 must set `contentColor` explicitly on error-container surfaces** - `errorContainer`
  now shares a value with `secondaryContainer`, so the default is dim ink.
- **Ticket 09 mandates extracting** `SectionHeader`, `Hairline`, `ReadingRow`, `NotBuiltRow` into
  `ui/common/` before the aspects diverge.
- **Two prototype branches, NEITHER to be merged:** `proto/ledger-ui`, `proto/fleet-pantry-ui`.
- **Design for "a scan may legitimately find nothing new."** A new file stayed invisible 2m36s+.
- **Open, not blocking:** Compose BOM vs navigation-compose 2.8.0 skew; deep-link `navigate()`
  lacks `launchSingleTop`/`popUpTo`.

## Notes for next session

- **A ticket's own verification step is not optional (L11).** Ticket 07 said in writing to render
  the `ThemePreview` previews before building on the theme. It was skipped - the coding agent
  flagged it unmet and the orchestrator proceeded anyway - and the bug it existed to catch shipped
  into the consent screen. **Never give one colour value to two M3 roles.**
- **Do not resolve a schema ticket before its consumers.** Ticket 03 took four amendments.
- **Re-read source before treating an inherited blocker as a constraint** - append-only dissolved
  on contact with `sync/`.
- **`.scratch/` is tracked in git**, but filing to the library is still required.
- **Verify what the librarian writes.** Both the 2026-07-29 and 2026-08-02 FILE passes needed
  hand correction.
- **Two contested calls the port left open:** `media/MusicController` alongside Spotify App Remote,
  and `vehicle/BuildSheetController` entries now text-only.
- **ADB after a PC reboot needs a FRESH `adb pair`, not just `connect`** - and the live port was
  the PAIRING dialog's, not the main screen's. `qa` (Owen) is a real seat.

## Library

`memory/library/` (catalog: `INDEX.md`). Never bulk-read shelves; dispatch the librarian. **Most
shelves are FROZEN Midnight AI history**, banner-marked. LIVE: `decisions.md`, `lessons.md`,
`playbook-coding.md` (partly). CLAUDE.md §11.

## How to update this file

- Under 80 lines. One-liners; narratives go to the library via the librarian (FILE).
- Session end: dispatch librarian FILE, then refresh Blocking / In-flight / Notes.
- A decision changing a CLAUDE.md rule is filed to `library/decisions.md` AND applied to CLAUDE.md
  in the same commit.
