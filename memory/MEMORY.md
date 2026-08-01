# MEMORY.md

Dashboard for LEGION. Read before responding. CLAUDE.md = locked rules. This file = what is
happening now. **MEMORY.md wins for state, CLAUDE.md wins for rules.** Depth lives in the library,
not here. Keep this file under 80 lines.

Predecessor: MIDNIGHT_AI (`C:\Users\Kwin\StudioProjects\MIDNIGHT_AI`), a frozen private archive.
Never build there. Its memory files describe a dead head-unit car launcher.

## Status as of 2026-08-01

- **Builds clean, no UI.** `./gradlew compileDebugKotlin -Pnokey` succeeds, `testDebugUnitTest`
  green (19 tests: 11 ledger, 8 pantry). Verified with real builds on 2026-07-31, not source
  inspection.
- **fleet** aspect ported and compiling. **ledger** done (deterministic-first, LLM fallback).
  **pantry** done (LLM-vision-first). See README.md for per-aspect detail; not duplicated here.
- **`ui/` is an intentional clean slate.** Only placeholders exist. No replacement design language
  has been chosen since city-pop died with the pivot.
- Repo is public and live: `github.com/kevinmyo-code/legion`. Only branch is `main`.
- **Memory system set up 2026-08-01** (this file, CLAUDE.md, TEAM.md, `.claude/agents/`, and the
  library copied wholesale from Midnight AI then banner-pruned - see CLAUDE.md §11).
- **Skills ported 2026-08-01.** `.claude/skills/`, 31 files. Eight adapted for LEGION, the rest
  verbatim; the prototype trio had to be rewritten because it enforced the dead frame-clock motion
  ban and head-unit preview sizes. `wayfinder` is repo-only with no plugin equivalent.
- **First wayfinder map charted 2026-08-01:** `.scratch/ledger-drive-ingestion/`, ten tickets, for
  Drive-folder batch ingestion plus a basic UI across all three aspects. `.scratch/` is gitignored
  and has been lost once already, so file each resolution to `library/decisions.md` as it lands.

## Blocking

- **The lost design doc.** Both READMEs and Midnight AI's memory cite LEGION's
  `.claude/plans/wiggly-beaming-quasar.md` as the full ledger + pantry design. **It does not
  exist** - `.claude/` was never committed and did not survive the machine port. The design is now
  only recoverable from the code and README. Do not send an agent to read that path.
- **Drive OAuth is keyed to package + SHA-1 signing cert**, so a stranger's own build fails
  authorization. Directly threatens the clone-and-run hard requirement. Unresolved, no approach
  chosen.
- **Drive has no compare-and-swap.** Today's shared-file last-write-wins sync will silently lose
  rows. Sync must become append-only. Unresolved.
- **Assistant identity is placeholder copy** (`ai/AssistantIdentity.kt`). The Alfred/JARVIS-register
  voice has not been written. Everything user-facing is blocked behind this.
- **Crisis resource is US-only (988).** Carried over unfixed from Midnight AI.
- **Firebase is not wired up.** `MidnightEvents` logs via `Log.d`, so there is no crash reporting
  and no remote observability at all right now.

## Untested / unverified

- **Nothing has run on a device.** Compile + unit tests are the whole verification story.
- `LedgerController`'s dedup path and `PantryController`'s DB-write path are untested (Robolectric
  `ShadowContentResolver` mismatch, judged not worth chasing).
- Every ported fleet path (OBD, sync, wake word, proactives) compiles but has not been exercised in
  this app.

## In-flight

Nothing. The port session closed 2026-07-31 with both aspects done and committed.

## Notes for next session

- **Decide the branching model in practice.** CLAUDE.md §8 specifies `main` + `dev`, but `dev` does
  not exist here yet - the port landed straight on `main`. Create it before the next feature.
- **Two contested calls the port left open, flagged not decided:** whether `media/MusicController`
  is still wanted alongside Spotify App Remote, and that `vehicle/BuildSheetController` entries are
  now text-only (`photoPath` dropped) as a schema change, not just a doc update.
- **ADB works now.** The Oppo A17K is an ordinary phone with working ADB, so `qa` (Owen) is a real
  seat rather than a reasoning exercise. Every "silent failure" severity rating inherited from
  Midnight AI was driven by ADB being blocked on the head unit; that constraint is gone.
- Fresh wayfinder map lives at `.scratch/multi-aspect-assistant/` (gitignored, disposable -
  decisions.md is the authority). Its 5 contested items are already resolved and filed. **The
  original 15-ticket map with 12 contested calls is GONE, not stale** - do not assume its numbering.
- Resolved research carried forward from the lost map, per the librarian's prior digest and
  **worth re-verifying before building on**: NOOA research, Drive research.
- **Verify what the librarian writes.** On 2026-07-29 a FILE dispatch invented substantial detail
  (a YAML file that did not exist, crashes never observed, wrong commit attributions) and had to be
  corrected by hand. Same failure mode, in the tool meant to prevent it.

## Library

Long-term memory is `memory/library/` (card catalog: `memory/library/INDEX.md`). Do not bulk-read
shelves into context. Dispatch the librarian: RETRIEVE for a digest, FILE at session end.

**Most shelves are FROZEN Midnight AI history.** Each carries a status banner; `INDEX.md` carries a
status column. LIVE: `decisions.md` (2026-07-31 entries), `lessons.md`, `playbook-coding.md`
(partly). Everything else is reference only. See CLAUDE.md §11.

## How to update this file

- Keep it under 80 lines. One-liners only; narratives go to the library via the librarian (FILE).
- Every session end: dispatch librarian FILE with session notes, then refresh Blocking, In-flight,
  and Notes here.
- A decision that changes a CLAUDE.md rule gets filed to `library/decisions.md` AND applied to
  CLAUDE.md in the same commit.
