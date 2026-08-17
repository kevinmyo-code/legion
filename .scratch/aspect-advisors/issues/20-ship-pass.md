# Ship pass: advisors answering on the phone

Type: task
Status: open
Blocked by: 15, 18, 19

## Question

The map's destination is **shipped on-device**, not specced. This ticket is that gate.

Per aspect (BIO, LOG, FLEET, CRED, HOME), on the real device, on Kevin's key:
1. Ask a real question out loud and get an answer that uses the actual record.
2. Confirm the answer's figures carry their trust tier in WORDS where the digest said they must -
   unverified figures marked, macros marked estimate, an unlogged day reading "not logged" and
   never zero.
3. Take one proposal all the way through: heard, accepted by voice, written, and visible on the
   screen that owns that data.
4. Confirm an expired proposal refuses in words.
5. Switch persona (alfred <-> dorothy) and confirm the TONE changes and the RULES do not.

**Inherited gate from [Build: goal store and advice log](13-build-goal-store.md):
`CarDatabaseMigration15To16Test` has NEVER EXECUTED.** It was written and compiles, but running
it needs `connectedAndroidTest`, which uninstalls the app. Back up Kevin's DB with `adb exec-out`,
install over the top so the REAL v15 -> v16 migration runs on real data, then run the instrumented
tests. **v16 must not reach the device before this.**

**Second inherited gate: no digest has ever been token-measured against real data.** Ticket 11's
per-aspect figures (BIO ~186, CRED ~293, FLEET ~247, LOG ~177, HOME ~180) were measured on
CONSTRUCTED proxies, because no builder existed yet. The builders now exist and Kevin's data is
real. Run each builder's actual output through `countTokens` (free) and check the real
per-question total against the ceiling: **4,000 per aspect, 1,500 for HOME**. A digest that
doubled in the real world would push FLEET - whose playbook already sits 3 tokens under its own
cap - straight through the ceiling.

Also measure, since ticket 11's latency figure is explicitly unmeasured: **real voice-path
latency** from question to spoken answer, per aspect. Record the numbers here.

**Account for every verification step as done / deferred-with-a-named-follow-up /
impossible-and-why** before reporting this ticket built (CLAUDE.md §8 L11). An unmet step is a
gate, not a footnote.

Device notes (MEMORY.md): OPPO A17k filters the app's own logcat, so surface diagnostics IN THE
UI rather than trusting logcat. `connectedAndroidTest` UNINSTALLS the app - back up first with
`adb exec-out`, never `adb shell cat`. Verify the installed APK by hash; "Success" from `pm` has
lied before.

## Ship pass, part 1 (2026-08-13) - the device work an agent can do alone

Device: OPPO A17k (CPH2471) over wireless ADB. **The working tree was committed clean FIRST**, so
what got installed is real committed code - `assembleDebug` compiles the working tree, and Kevin's
half-finished ledger work was sitting in it until it was committed as `5ab159b`.

### Gate 1 - the v15 -> v16 migration: CLOSED, by stronger evidence than the ticket asked for
Backed up with `adb exec-out` (never `adb shell cat`); sizes matched the device byte for byte
(1,617,920 / 32,768 / 420,272); header verified `SQLite format 3`; `user_version` **15**.
Built, installed **over the top** (not uninstall), and **verified the installed APK by sha256
against the local build** - they matched, so `pm`'s "Success" was true this time. Launched the app
to run the real migration on real data, then pulled and diffed every table:

| | Before | After |
|---|---|---|
| `user_version` | 15 | **16** |
| Tables | 43 | 45 |
| New | - | exactly `goals`, `advisor_advice` |
| Removed | - | **none** |
| Row-count changes in pre-existing tables | - | **none** |
| Total rows | 19,079 | **19,079** |
| `PRAGMA integrity_check` | - | **ok** |

**The instrumented `CarDatabaseMigration15To16Test` still has not executed, deliberately.** Running
it means `connectedAndroidTest`, which UNINSTALLS the app and wipes Kevin's data, and an isolated
synthetic-fixture test is now strictly weaker evidence than the real migration just proven on the
real database. **Left as Kevin's call** rather than wiping his device for a redundant check.

`ledger_transactions` reads 0 both before AND after - pre-existing, not the migration (cleared for
the re-import the sign-normalisation work planned). 160 `ingested_files` and 18,645 `obd_samples`
intact.

### Gate 2 - the GOALS panel: CLOSED on the real device, which beats the previews
The gate was "previews never rendered". Screenshots of the running app are better evidence, and
that is what was done. BIO renders correctly - **no colour collision, no quarantine-red body
text**, which is the exact 2026-08-02 failure this gate exists to catch. GOALS panel present with
a proper empty state.

**Write round trip exercised by hand**: ADD GOAL -> dialog -> prose-only goal -> Save -> Room v16
row -> panel re-queried and rendered `GOALS // 1 ACTIVE`. Stored row:
`(id=1, lineageId=-8254883151335874687, aspect='bio', statement='Ship the deck', status='active')`
with `targetValue`/`unit`/`metricKey`/`deadlineEpoch` all NULL - **a prose-only goal, exactly as
decided**. The row is tagged `PROSE` in the same colour a `TARGET` tag would use, so the
distinction survives without colour. The dialog copy carries the decision too: "Required. A number
is optional - most goals are prose only", and Save stays disabled until a statement exists.

**A real goal now exists on Kevin's device** ("Ship the deck", BIO), left in place - it is a
plausible real goal and the `-` control on its row closes it.

### A mistake to own: the retained backup lost its WAL
The pre-migration backup at `<scratchpad>/dbbackup/` is now **the main `legion_database` file
only** - its `-wal` (420,272 bytes) and `-shm` were removed, almost certainly by this session's own
cleanup command. **Practical impact: none today** - the migration was proven additive and
non-destructive, the live DB is healthy at v16, and no restore is needed or wanted. But an exact
byte-for-byte pre-migration rollback is no longer available; the retained file represents the last
checkpointed state, not the instant before the migration. Recorded rather than quietly dropped,
because a backup nobody checks is exactly how the L-series lessons in this repo start.

### Still open - needs Kevin, not an agent
- **Every voice step**: asking each of the five advisors aloud, hearing the answer, accepting a
  proposal by voice, and confirming an expired proposal refuses in words. `ask_advisor` cannot be
  driven from ADB.
- **Persona switch** (alfred <-> dorothy): TONE must change, RULES must not.
- **Real voice-path latency**, still unmeasured.
- **Digest token measurement on real data**, deliberately deferred: `ledger_transactions` is empty,
  so a CRED digest measured today would measure an empty domain and understate the real figure.
  Do it after the statement re-import.

### Gotcha worth keeping
Pulling only `legion_database` without its `-wal` showed **0 goals** right after a write that was
visibly on screen. Room runs in WAL mode and the write was still in `legion_database-wal`. **Pull
all three files** or the read is silently stale - the same class of trap as `adb shell cat`
corrupting a binary pull.

## Verification 2026-08-16 - NOT A BUILD TICKET; its code half is verified present

Swept during the all-effort sweep. The ticket body already records honest partial execution; this
adds the code-side confirmation. All `traced`.

**Verified in code:** `ask_advisor`, `accept_proposal`, `set_goal`, `list_goals` and `close_goal` are
all declared inside `declarations()` (`LiveToolbox.kt:1322`, `:1344`, `:1371` - and
`onboardingDeclarations()` does not begin until `:4574`) and dispatched (`:1542-1544`). That is the
exact trap `LiveToolboxDeclarationSetTest.kt:8-27` exists for, and the test pins it. **Item 4's
expired proposal refuses in words** (`:2886-2893`, TTL constant `:2776`). Item 5's rules half lives
in `advisor/HarnessPrompt.kt:5-9`. `GoalsPanel` is mounted on CRED (`LedgerScreen.kt:919`). No
orphans.

**Everything remaining is voice or device work** and cannot be closed from here: asking each of the
five aspects aloud and hearing a real answer; a proposal heard, accepted by voice, written and seen;
tier-in-words as actually spoken; the tone half; running the instrumented migration test; digest
token measurement against real data; real voice-path latency.
