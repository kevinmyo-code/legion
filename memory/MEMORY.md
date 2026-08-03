# MEMORY.md

Dashboard for LEGION. Read before responding. **MEMORY.md wins for state, CLAUDE.md wins for
rules.** Depth lives in the library, not here. Under 80 lines. MIDNIGHT_AI: see CLAUDE.md §1.

## Status as of 2026-08-02 (session 2)

- **THE ASSISTANT TALKS.** Live socket, mic, audio out, VAD, full duplex, verified on the A17K.
  Two switchable companions: Alfred (dry butler) and Dorothy (warm housekeeper), both heard.
- **THE MAP IS BUILT.** All 11 tickets resolved, 03-09 implemented. Builds clean, 95 tests.
- **Both LLM paths proven on hardware** on Kevin's key: ledger extraction (reconciled, and
  separately REFUSED when a doc printed no total) and pantry receipt vision.
- `feat/ledger-ingestion` is 20 commits, 7 UNPUSHED. `dev` is 14 ahead of `origin/dev`; `main` is
  far behind. The merge is Kevin's.

## Blocking

- **Drive OAuth keyed to package + SHA-1 cert.** Stranger's build fails auth. Unresolved.
- **Onboarding has no screen**; `OnboardingFlow` unwired, the picker replaced it for now.
- **Firebase not wired.** `MidnightEvents` logs via `Log.d`; a swallowed exception is invisible.
- **Crisis resource is US-only (988).** **`.claude/plans/wiggly-beaming-quasar.md` never existed.**

## Untested / unverified

- **`sync/` has NEVER executed.** The only untested surface where a wrong ruling loses data silently.
- **OBD, wake word, proactives never run.** Wake word CANNOT: `assets/vosk-model/` is a README only.
- **`assets/dtc_descriptions_seed.json` has NEVER existed** in git; every fault reads "not
  identified locally".
- **The 30 voice clips have never been HEARD** (they resolve and play; judging them is Kevin's).
- **Nobody has asked the assistant a QUESTION.** No tool call has ever run from voice;
  `DEBUG_SAY` produced no turn, likely needs an active conversation.
- **Also untested:** tab-switch-during-scan, the `+` fix, the nav graph, probe steps 7-9, ledger
  dedup, pantry writes.

## In-flight

**PICK UP HERE: porting Kevin's Midnight AI data.** Fleet shows only the Cherokee because LEGION's
DB has one vehicle; the old app has THREE. Investigated, not built - **a decision is owed on import
shape** (one-time adb script vs in-app screen vs vehicles-only; options given, none chosen).

- **Drive is NOT the route:** `appDataFolder` is per-application, LEGION cannot see Midnight AI's.
- **`com.kevin.midnightai` is STILL INSTALLED and debuggable**, so its DB reads directly (needs
  `MSYS_NO_PATHCONV=1`): `adb exec-out run-as com.kevin.midnightai cat /data/data/com.kevin.midnightai/databases/midnight_ai_database > out.db`
- **12 of 13 tables are SCHEMA-IDENTICAL** v12 -> v5; only `build_entries` differs, by the dropped
  `photoPath` (1 row). Portable: 3 vehicles, 11532 obd_samples, 34 drive logs, 41 code_events, 24
  maintenance_items, 4 places, 14 car_tasks, 2 specs, 2 recaps, 41 memories, 1 service_record.
  **Retired, do not port:** 527 `music_plays`.

Then: **`sync/`** (still never executed), a **DTC seed dictionary**, then ledger insights.

- **Two prototypes, NEITHER to be merged:** `proto/ledger-ui`, `proto/fleet-pantry-ui`.
## Notes for next session

- **RUN IT ON THE PHONE.** Five bugs survived compile, the full suite AND review: red body text,
  an invisible saved key, every date a day early, a foreground-service type that made the
  assistant unstartable since the port, and a picker leaving NO profile active.
- **A date-only value and an instant must not share a formatter.** Dates store at UTC midnight;
  render in UTC (`documentDate`). The other 8 call sites are instants, correct in local.
- **Fixtures must carry KNOWN, DERIVED totals and dates** (`tools/`). Only reason the `+` bug and
  the date bug were findable at all.
- **L11 and L12 now live in CLAUDE.md §8** - verification steps are gates; process-wide init
  belongs in `MidnightApplication.onCreate`, never a service.
- **Verify what the librarian writes.** Four FILE passes, four hand corrections.
- **Device quirks:** ADB after a reboot needs a FRESH `adb pair` (network is `192.168.4.x` now); `pm clear` is OEM-blocked; **`connectedAndroidTest` WIPES app data** (it cost
  the ledger rows, pantry receipt, folder grant and Gemini key today); `adb push`ed files are
  invisible to the Downloads provider; unsigned `.ps1` files are refused, pipe via
  `Invoke-Expression`; `uiautomator dump` serves STALE content for popups - screenshot instead.
## Library

`memory/library/` (catalog: `INDEX.md`). Never bulk-read shelves; dispatch the librarian. **Most
shelves are FROZEN Midnight AI history**, banner-marked. LIVE: `decisions.md`, `lessons.md`,
`playbook-coding.md` (partly). CLAUDE.md §11.

## How to update this file

- Under 80 lines. One-liners; narratives go to the library via the librarian (FILE).
- Session end: dispatch librarian FILE, then refresh Blocking / In-flight / Notes.
- A decision changing a CLAUDE.md rule is filed to `library/decisions.md` AND applied to CLAUDE.md
  in the same commit.
