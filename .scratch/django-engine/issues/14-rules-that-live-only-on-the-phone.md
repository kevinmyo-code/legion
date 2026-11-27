---
map: django-engine
ticket: "14"
title: "Three rules live only on the phone, and a second app would not have them"
type: build
status: resolved
status-detail: "Resolved 2026-11-26: label cap and CHECK on the server, memory recall scoped by ?vehicle=. The Kotlin label cap stays on purpose for the no-engine path."
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---

# Three rules live only on the phone, and a second app would not have them

Kevin, 2026-09-07: *"django owns everything, the 2 android apps just consume"*, and the head unit is
a **separate app**. The audit that crossed places, voice notes, body and memory to Django looked for
rules duplicated between Kotlin and Python. It found eight duplicates that must NOT be deleted yet
(see below), and three rules that are not duplicated at all - they exist only in Kotlin, so the
server does not enforce them and a second client would simply not have them.

This is the drift Kevin asked about, and it is already real, before the second app is written.

| Rule | Where it lives | What a second client does today |
|---|---|---|
| Place label capped at 30 characters | `location/PlaceController.normalizeLabel` - a guard against a misheard sentence becoming a label | `PlaceSerializer` has no length validation and `places.label` has no CHECK. A 200-character label is accepted |
| **`car_anchored` memories are scoped to the active vehicle** | `data/local/CompanionMemoryDao.kt:49`, `category != 'car_anchored' OR vehicleId = :vehicleId` | Django serves every row unfiltered. Another client recalls memories anchored to a car the user is not in |
| A voice note's summary and transcript are written atomically | `VoiceNoteController.applyTranscriptionSuccess`, by construction | The server enforces it (`voice_notes_summary_needs_transcript`), so this one is a convention meeting a check rather than a gap - listed so it is not mistaken for one |

The middle one is the real defect. It is a recall rule - what the assistant is allowed to remember
about which car - implemented as a WHERE clause in one client's database query.

## Build

Move the first two into Django: a length validation and CHECK for the label, and vehicle scoping on
the memory read path (an `?vehicle=` filter, or scoping by the caller's active vehicle if the server
can know it - decide and say which). Then delete the Kotlin copies, since these have a synchronous
path or a read path and the local-first objection below does not apply to them.

## The eight duplicates that must NOT be deleted yet, and why

`WorkoutController`'s blank-exercise and non-positive-sets refusals, `SleepGap`'s duration range,
`MemoryConsolidator`'s importance clamp and category filter, `LiveToolbox`'s voice-note kind
coercion, `AriaBrain.remember`'s blank-text refusal, and `PlaceController`'s blank-label refusal.

Every one sits in front of a **local-first write**: `BodyWriteThrough` and `MemoryWriteThrough`
insert into Room unconditionally and push afterwards. Deleting the guard does not move the rule to
Django - it lets Room accept a row Django will refuse forever, the drain retries three times and
poisons it, and the user was told the write succeeded before any of that. §7 violated by the fix.

**The precondition is body and memory writing server-first**, which is ADR 0044 rule 4's own
direction ("the phone queues the write and says so"). That is its own ticket. Two of the eight are
not duplicates in the first place: the importance clamp and the category filter sanitise an LLM's
output before storage, which §4 requires regardless of what Django validates on arrival.

## Resolution (2026-11-26)

- **Label cap:** `PlaceSerializer` refuses a label over 30 in words (2026-09-07), and Postgres now
  holds it too: `places_label_length`, `ingest/migrations/0029`, SQL in `server/api/place_columns.py`.
  Added NOT VALID and validated only when no live row is over 30, so one stray live row cannot fail
  the deploy. Tombstones are exempt so such a row can still be forgotten or renamed. `backup_nightly`
  validates it later once no live row breaks it, and warns with the count until then.
- **`car_anchored` recall:** scoped on the server by `?vehicle=` (2026-09-07, `server/api/memory.py`).
- **Voice-note atomicity:** checked then, no bug.
- **The Kotlin copies are not deleted, on purpose (orchestrator ruling, 2026-11-26).** The label cap
  already stands down when the write goes to Django (`PlaceController.serverOwnsTheLabelCap`). It
  stays for the no-engine path, where the write goes straight into Room and no server can refuse
  it. The memory DAO filter stays because Room is the read path. Nothing more is owed here.
