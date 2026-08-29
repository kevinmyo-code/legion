---
status: accepted
decided: 2026-08-28
decided-by: Kevin
source: "[[decisions#2026-08-28 (later) - the car-task fold is reversed, and the fleet projection runs]]"
tags: [adr]
---

# 40. The PC is the primary surface; the phone is voice-first and minimal

## Standing

ACCEPTED, nothing built. This is a direction, not a description of running code - the phone today
still carries the full per-aspect UI, the generated screens and the widget pager, and the PC surface
does not exist. Supersedes no ADR outright, but it materially weakens the premise of
[[0037-the-aspect-engine-is-the-spine]]'s survivor clause and puts
[[0035-every-voice-capability-has-a-hands-path]] under a tension named below rather than resolved.

## Context

Two things changed on 2026-08-28 and they point the same way.

**The backend became ours.** [[0038-byo-supabase-is-the-system-of-record]] landed, the schema and the
gate are applied and verified on a real project, and five aspects write to it. The system of record
is no longer the phone's Room database; the phone is one client of it.

**The history migration was abandoned** (Kevin, same day): *"we dont need the old data to port over
fully... whats important is we set up the backend properly for new data from the phone or any other
surface to be ingested."* That sentence names a plural of surfaces as the point, which the design
had been treating as a someday.

And ledger ingestion - the heaviest, most error-prone thing the phone does - is moving to the PC,
where the files already are.

## Decision

**The PC is where the work happens. The phone is where the day happens.**

| Surface | Role |
|---|---|
| Supabase | System of record. Both surfaces are clients; neither owns the schema at runtime |
| PC (new repo) | Read, write, edit, monitor, ingest. React + Vite + TypeScript on `supabase-js`; Python only where a library demands it |
| Phone (LEGION) | OBD and the car, the AI voice companion, calendar, todos, lists, groceries, notes. **Minimal UI, voice-first** |

**The phone's interaction model is voice asking and a modal answering**, not a screen per aspect.
Kevin's own framing: *"i ask it via voice, it generates a pop up modal."* The phone stops being a
place to browse data and becomes a place to say a thing and see the answer.

**Django was considered and rejected.** Its value is ORM, migrations, auth and admin; Supabase
already owns all four, and adopting Django means teaching its ORM not to own a schema it did not
create, plus a second auth story alongside RLS. Rejected in favour of the client talking to Postgres
directly under RLS.

## Consequences

**The PC ingests through the SAME gate.** `commit_statement` is the one implementation, already
proven against a 17-case corpus ([[0038-byo-supabase-is-the-system-of-record]], ticket 03 ruling 2).
A second ingestion path that reimplements the arithmetic is the failure that corpus exists to
prevent, and a new surface is exactly when it would happen.

**Python on the PC reopens a retired decision.** Ticket 03 ruling 3 retired the deterministic
statement parsers partly because PdfBox cannot run server-side in Deno. On a PC that constraint does
not exist - `pdfplumber` runs natively, and Project Andromeda already proved that path. CLAUDE.md
section 4 rule 1 prefers deterministic extraction wherever one exists, so the parsers may deserve to
live on the PC rather than die. **Not decided here**; it needs its own ruling.

**[[0037-the-aspect-engine-is-the-spine]]'s survivor clause weakens.** Ticket 18 spared `engine/`
specifically because `create_aspect`, the generated list/detail/form screens and the widget pager
were "a shipped, still-wanted feature." If the phone is a voice-first consumer with minimal UI, that
justification is much thinner and the engine may finally be deletable. Its own ticket.

**[[0035-every-voice-capability-has-a-hands-path]] is in tension, and this ADR does not resolve it.**
That ADR exists because voice is the path that FAILS - a loud car, a sleeping person, a wake word
that does not fire, a mic that opens deaf, all observed on this phone. A voice-generated modal is
still voice-INITIATED, so it is not a hands path. The domains the phone keeps (calendar, todos,
lists, notes) have hands paths today and must keep them. **What is not settled is whether a NEW
phone capability may ship voice-only on the grounds that the PC is its hands path** - the PC is not
in the car, which is where voice fails hardest. Flagged deliberately rather than quietly dropped.

**Repo split, and the schema stays put for now.** The PC app is a new repo. `supabase/migrations/`
remains in LEGION, because it is twenty-odd files with real headers and a working applied history
and moving it buys nothing today; the PC app pins the contract with generated types
(`supabase gen types typescript`) rather than owning the SQL. Revisit if schema edits start coming
mostly from the PC side, at which point `supabase/` earns its own repo and both surfaces consume it.
