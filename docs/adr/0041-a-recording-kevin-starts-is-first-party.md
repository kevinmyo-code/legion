---
status: accepted
decided: 2026-09-01
decided-by: Kevin
source: "[[decisions#2026-09-01 - a recording Kevin starts is first-party, and its transcript is kept]]"
tags: [adr]
---

# 41. A recording Kevin starts is first-party, and its transcript is kept

## Standing

**A voice note Kevin deliberately starts and stops is his own record.** Its verbatim transcript,
its summary, and its source audio are persisted, synced, and retained like any other LEGION record.
Other people's speech inside that recording is persisted with it.

Kevin, 2026-09-01, asked directly whether ADR-less §7 third-party read-through binds a recorded
meeting: *"drop 7 entirely. we can keep all transcripts."*

## Why

§7's read-through rule was written about **inbound** content - mail, and things of that shape, that
arrive unasked and that Kevin never chose to create. A recording has the opposite provenance: it
exists because Kevin pressed a button, at a moment he chose, in a room he was in.

The rule's guarantee was "it was never stored". Applying that guarantee to a feature whose entire
purpose is storage does not make the feature safer, it makes it not a feature.

**The consent question is answered outside the app.** Kevin, same session, on what LEGION owes the
other people in the room: *"they will know, i'll tell tem."* No in-app indicator, no spoken
announcement, no passenger-consent surface. This is recorded as a decision made with open eyes, per
the honest floor the retired ambient-listening map asked for, and it is Kevin's to make: the room is
his, the disclosure is his, and LEGION is not the party that meets the people in it.

## What is NOT dropped

This ADR is about recordings. It does not touch:

- **The mail read-through path.** `LiveToolbox.EPISODIC_EXCLUDED_TOOLS` and
  `rememberBlockedByReadThroughTool` still bind for `search_mail`, `read_mail`, `ask_mail`,
  `get_sitrep`, `track_package` and `flight_status`. Mail still never reaches Room.
- **§7's unfalsifiable-memory rule.** A persona still may not invent history with the user. A
  transcript is falsifiable - the audio is kept beside it - which is precisely why it is allowed to
  be remembered where an ambient impression was not.
- **The crisis path.** `ai/CrisisDetector.kt` is unchanged, and a recording makes it more likely to
  fire, not less.

## The anchor chain, in place of §4's gate

A transcript states no total and has nothing to reconcile against, so §4's numeric gate has no
purchase here. What replaces it is **retention of the evidence**, which is §4 rule 8's actual claim:

| Layer | Anchored by |
|---|---|
| Summary (LLM-derived) | The verbatim transcript stored beside it |
| Transcript (LLM-derived) | The source audio file stored beside it |
| Audio | Itself |

**Nothing numeric extracted from a recording may be asserted as fact.** A figure spoken in a meeting
is hearsay through two nondeterministic layers; if it is to become a ledger row or a goal target it
goes through that aspect's own ingestion path, not through the note. The summary is labelled
LLM-derived on every surface that renders it.

## Consequences

- §7's third-party bullet in `CLAUDE.md` gains an explicit carve-out naming recordings.
- Deleting a voice note deletes its audio, its transcript and its summary together. The chain is
  retained or destroyed whole; a summary outliving its transcript is the failure this ADR forbids.
