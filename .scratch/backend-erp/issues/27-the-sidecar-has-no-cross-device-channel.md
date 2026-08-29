---
type: decision
status: open
blocked_by: []
map: backend-erp
---

# The sidecar solved the co-owned row and lost the persona's sync channel

**Found 2026-08-29 by the agent that built fleet cutover step 1, and flagged rather than buried -
which is the only reason it is catchable now instead of on a second phone.**

## What happened, and it is a correct build with a real consequence

Ticket 26 step 1 moved the seven phone-only `Vehicle` columns into `VehicleSidecar`, keyed on
`serverId`, and dropped `vehicles` from the `SyncEngine` registry in the same commit - exactly as
ruling 05 requires, since the writes moved.

**Dropping that `Spec` retired the Drive channel those columns used to travel on.** They are now
per-DEVICE state with no cross-device channel at all:

| Column | Was | Is now |
|---|---|---|
| `personaPrompt`, `voiceName`, `personaTraits` | synced between phones via Drive | per-device |
| `archived` | synced | per-device |
| `onboarded`, `lastOdometerPromptAt` | synced | per-device |
| `tripMilesSinceBaseline` | synced | per-device |

Nothing was lost - the data is on the phone that wrote it. What was lost is the guarantee that the
OTHER phone agrees.

## Why this matters more than it looks

**The persona columns contradict a decision already taken.** Ticket 02 ruled that personas and
memories bind to the USER, not the device, and `memory/MEMORY.md` records the cross-interface
requirement in Kevin's own framing: *companion_memories follows the user to Supabase so Alfred
remembers across phone/Windows.* A per-device `personaPrompt`/`voiceName`/`personaTraits` is the
opposite of that.

`archived` is the second-sharpest: archiving a car on one phone and having it reappear on the other
is the kind of quiet disagreement that reads as a bug for months before anyone traces it.

`tripMilesSinceBaseline` is genuinely per-device and arguably correct as-is - it accumulates from
whichever phone was in the car.

## Why it is not urgent TODAY

**There is only one Android phone.** The second phone is an iPhone that will run the web app, and
the web app has no fleet UI. The A25 is the only device writing these columns, so nothing can
disagree with anything yet.

That is a reprieve, not a fix. It expires the moment a second LEGION install exists.

## The options

1. **Split the sidecar by ownership.** Persona and `archived` are USER state and belong on the
   server (a `vehicle_user_state` table, or onto `companion_profiles` where personas already live).
   `tripMilesSinceBaseline`, `onboarded` and `lastOdometerPromptAt` stay per-device, correctly.
   Most honest, and it is what ticket 02's ruling already implies.
2. **Put the whole sidecar on the server.** Simplest, and it reopens ticket 01 ruling 10, which kept
   persona phone-only deliberately. Note that ruling was made when there was no server at all.
3. **Leave it per-device and say so.** Defensible only while one phone exists, and it silently
   becomes wrong later.

**Recommendation: option 1**, and it does not block step 2 of the cutover. The split is a schema
question about who owns a column, and answering it while only one device writes is far cheaper than
answering it after two devices disagree.

## Worth keeping regardless of the answer

`SyncEngine`'s registry is a `Spec` list, and removing an entry is one-line surgery - **but any
phone-only column that table's write used to carry loses its cross-device channel the moment the
`Spec` goes**, unless a replacement already exists. That is not obvious from the call site and it
has now bitten once. It belongs in the checklist for every remaining registry drop in this cutover,
of which there are nine.

## RULED 2026-08-29, and the recommendation above is WITHDRAWN. Three of the seven are dead.

Delegated ("go per ur recommendations"), so this is my call - and checking before building changed
the answer. I recommended splitting the sidecar by ownership and moving persona to the server.
**Persona should not go to the server. It should stop being carried at all.**

### `personaPrompt`, `voiceName`, `personaTraits` are vestigial

Traced every reader. The only consumers are `FleetEngineStore`'s own copy-through - it reads them off
the legacy row and writes them into the sidecar, and nothing else ever looks at them. **The live
session does not use them:** `LiveSessionController` calls `CompanionProfile.voice(appContext)` at
every one of its four socket-open sites, never `Vehicle.voiceName`.

They are leftovers from the per-car identity model **the 2026-07-30 pivot killed**. CLAUDE.md §2, a
locked decision: *"One global assistant identity. Cars are data, not identities. Per-car
`CompanionProfile` keying and Midnight AI's `CompanionIdentity` Zero-vs-car-self split are both
dead."* And §10 records `PersonaTraits.kt` as ported, complete and orphaned - its only caller has no
production caller.

**So there was never a cross-device channel to lose here.** Three of the seven columns this ticket
worried about carry nothing anyone reads. Syncing them would have been building a channel for dead
data, which is worse than the gap it fixed.

### `archived` is the one that genuinely needs a channel

42 references, live, and it is USER state rather than device state: a car Kevin retired is retired
everywhere. It gets a real column on `public.vehicles` and syncs like any other server-owned field.

### The remaining three stay per-device, and that is correct

`tripMilesSinceBaseline` accumulates from whichever phone was in the car. `onboarded` and
`lastOdometerPromptAt` are about what THIS install has already asked. None of them means anything on
another device.

### What is built

1. **`archived` moves to the server** - a column on `public.vehicles`, out of the sidecar.
2. **The three persona columns leave the sidecar.** They are NOT dropped from the legacy `Vehicle`
   table: CLAUDE.md §5 is additive-migrations-only, and a dead column costs nothing where it sits.
   They stop being read, written or carried, and `Vehicle`'s own doc says why.
3. **The sidecar keeps three columns**, all genuinely per-device, and its doc comment says which and
   why - so the next person does not re-derive this.

**The general lesson, which is the reason this ticket earned its keep:** a column that stopped being
read does not announce itself. Three of these have been copied through every fleet refactor since the
port, and the cutover was about to give them a server table. **Before syncing a column, check that
something reads it.**
