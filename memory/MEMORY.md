# MEMORY.md

Dashboard for LEGION. Read before responding. CLAUDE.md = locked rules. This file = what is
happening now. **MEMORY.md wins for state, CLAUDE.md wins for rules.** Depth lives in the library,
not here. Keep this file under 80 lines.

Predecessor: MIDNIGHT_AI (`C:\Users\Kwin\StudioProjects\MIDNIGHT_AI`), a frozen private archive.
Never build there. Its memory files describe a dead head-unit car launcher.

## Status as of 2026-08-01

- **Builds clean.** `compileDebugKotlin -Pnokey` and `testDebugUnitTest` green (19 tests: 11 ledger,
  8 pantry). fleet ported; ledger and pantry done. Per-aspect detail is in README.md, not here.
- **`ui/` holds a theme and placeholders, nothing else.** The design language is decided and built
  (Instrument on M3, `ui/theme/`); no screens exist yet.
- Repo public: `github.com/kevinmyo-code/legion`. `dev` is the trunk, `main` two commits behind.
- **Tooling set up 2026-08-01:** memory system (this file, CLAUDE.md, TEAM.md, `.claude/agents/`,
  the banner-pruned library - CLAUDE.md §11) and `.claude/skills/` (31 files, 8 adapted).

## Blocking

- **The lost design doc.** Both READMEs and Midnight AI's memory cite LEGION's
  `.claude/plans/wiggly-beaming-quasar.md` as the full ledger + pantry design. **It does not
  exist** - `.claude/` was never committed and did not survive the machine port. Recoverable only
  from the code and README. Do not send an agent to read that path.
- **Drive OAuth is keyed to package + SHA-1 signing cert**, so a stranger's own build fails
  authorization. Directly threatens clone-and-run. Unresolved, no approach chosen.
- **Drive has no compare-and-swap.** Shared-file last-write-wins will silently lose rows; sync must
  become append-only. Unresolved, and ledger data is the worst thing to lose rows from.
- **Assistant identity is placeholder copy** (`ai/AssistantIdentity.kt`). The Alfred/JARVIS voice
  has not been written; everything user-facing is blocked behind it.
- **Crisis resource is US-only (988).** Carried over unfixed.
- **Firebase is not wired up.** `MidnightEvents` logs via `Log.d`: no crash reporting, no remote
  observability, so a swallowed exception is invisible in the field.

## Untested / unverified

- **No LEGION code has run on a device.** Compile + unit tests are the whole verification story.
  The only thing exercised on hardware is a standalone SAF probe app, not this codebase.
- **The Drive access model is settled** (ticket 11, 2026-08-02, Oppo A17K API 31). Still unrun:
  reboot persistence of the grant, and the offline failure mode. USB never enumerated on Kevin's
  machine, so the session ran over wireless ADB and both tests sever that transport. Sub-question 4
  stays `traced`.
- **The theme compiles but has never been rendered.** Five previews exist in `ui/theme/ThemePreview.kt`.
- `LedgerController`'s dedup path and `PantryController`'s DB-write path are untested (Robolectric
  `ShadowContentResolver` mismatch, judged not worth chasing).
- Every ported fleet path (OBD, sync, wake word, proactives) compiles but has not been exercised in
  this app.

## In-flight

**Wayfinder effort `.scratch/ledger-drive-ingestion/` - 4 of 11 tickets resolved.** Destination is a
build-ready spec for Drive-folder batch ingestion plus a basic UI across all three aspects. The map
and every ticket are now TRACKED IN GIT (see the gitignore note below), so read them directly:
`.scratch/ledger-drive-ingestion/map.md`.

| State | Tickets |
|---|---|
| Resolved | 01 SAF feasibility, 02 design language, 03 ingested-file ledger, 11 SAF probe (steps 7-9 unrun) |
| **Frontier (takeable now)** | 04 twin transactions, **05 batch mechanics**, 07 app shell + ignition, 09 fleet/pantry UI, 10 does ledger sync |
| Blocked | 06 spend gate (needs 05), 08 ledger UI (needs 05) |

**Suggested next: 05 (batch mechanics).** Only thing still gating 06 and 08. 03 handed it measured
per-file I/O cost, a defined resume unit, and the stale-listing constraint; they are summarised at
the bottom of ticket 05 so it needs no re-reading of 01 or 03.

**The crux is TESTED and passed (2026-08-02).** A file uploaded after the grant appears in
`listFiles()` with no re-pick, so the Drive access model holds. Caveat: it was invisible for at
least 2m36s and appeared only after the Drive app was opened; the two variables were not isolated.
**Design for "a scan may legitimately find nothing new."**

## Notes for next session

- **Render the five theme previews in Studio before building any screen on them.** The Instrument
  theme compiles and has never been drawn. Semantic money and provenance roles live in
  `LegionSemantics` via `LocalLegionSemantics`, NOT in `ColorScheme` - reach for them, do not add
  colours to the scheme.
- **`.scratch/` maps, tickets and research are now tracked in git**, reversing the blanket ignore
  that destroyed the previous 15-ticket map. Filing decisions to `library/decisions.md` as they are
  made is still required: git protects working state, the library is what gets read.
- Five commits landed on `dev` on 2026-08-01: memory system, skills port, Drive-access decision,
  theme, and this handoff.
- **Two contested calls the port left open, flagged not decided:** whether `media/MusicController`
  is still wanted alongside Spotify App Remote, and that `vehicle/BuildSheetController` entries are
  now text-only (`photoPath` dropped) as a schema change, not just a doc update.
- **ADB works now.** The Oppo A17K is an ordinary phone with working ADB, so `qa` (Owen) is a real
  seat rather than a reasoning exercise. Every "silent failure" severity rating inherited from
  Midnight AI was driven by ADB being blocked on the head unit; that constraint is gone.
- The live map is `.scratch/ledger-drive-ingestion/` (11 tickets, tracked in git - see In-flight).
  Both resolved tickets are filed to `library/decisions.md` under 2026-08-01. **The original
  15-ticket `.scratch/multi-aspect-assistant/` map with 12 contested calls is GONE, not stale** -
  it predates this repo and its numbering does not map onto the current tickets.
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
