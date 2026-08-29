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

**The shape Kevin is after, in his own words: Lattice.** *"one central database, perhaps with AI,
many things connecting to it."* That is the right north star for this system and it is worth naming,
because it decides where capability lives: **in the data layer, not in a client.** N clients over one
record must not each reimplement the rules, or they will disagree - and the disagreement will be
found in the data, months later, by someone reading a wrong number.

This project has already followed that instinct once, before it had a name for it: the
reconciliation gate lives in SQL as `commit_statement`, with the phone holding only a pre-check and a
shared corpus proving the two agree. Every new client inherits the gate for free. That is the pattern
to repeat, not a special case.

**The limit of the analogy, stated so it does not get over-applied:** Lattice is a product with a
company behind it. This is two adults, two phones and a laptop. The useful part is the SHAPE - one
record, many thin clients, capability in the middle - not the scale. Nothing here justifies building
a platform, an abstraction layer for clients that do not exist, or a plugin system. CLAUDE.md's
existing rule stands: no Kevin-hosted anything, no speculative generality.

## Decision

**The PC is where the work happens. The phone is where the day happens.**

**AMENDED 2026-08-28, same day, and the amendment is a better split than the original.** This ADR
first said "PC" and "phone" as though there were two clients. There are two KINDS of client, and the
PC is not one of them - it is one viewport of the general client.

| Surface | Role |
|---|---|
| Supabase | System of record. Every client is a consumer; none owns the schema at runtime |
| **Web app** (one codebase, responsive) | The **GENERAL** client. Wide viewport gets ingest, monitor, bulk edit; narrow viewport gets calendar, todos, lists, groceries, notes. React + Vite + TypeScript on `supabase-js`; Python only where a library demands it |
| **Android** (LEGION) | The **SPECIALIZED** client. OBD and the car, wake word, background audio, the Alfred voice companion - **the things a browser cannot reach** |

**What forced the amendment: the second phone is an iPhone.** Kevin will not pay Apple's developer
costs, and does not need to - the same React app, added to the Home Screen from Safari, gives that
phone everything the Android app offers minus the hardware-bound parts. So the general client is
already cross-platform for free, and building a second native app would be paying to duplicate it.

**This is the sharper reason for LEGION to exist.** Not "the phone client", which invites every
feature to be built twice, but "the client for what needs hardware a browser cannot touch." Anything
that does NOT need OBD, a wake word, background audio or an always-on service should be built once,
in the web app, and reached from both phones.

**What the iPhone gives up, recorded so it is not discovered later:** push notifications work on iOS
16.4+ but only once the page is added to the Home Screen, and less reliably than native; there is no
geofencing, no background location, no wake word, no Bluetooth. None of that is in scope for the
domains the general client serves. **Reminders that must actually FIRE are the one thing to test
early rather than assume**, since that is the single capability where the web app's ceiling is lower
than the Android app's and the difference is silent.

**A public URL raises the stakes on RLS.** The anon key is public by design and RLS plus auth is the
entire boundary between the internet and the household's data. That was already true; a hosted page
makes it reachable rather than theoretical. The schema's deliberate absence of insert/update/delete
policies on `household_members` ([[0038-byo-supabase-is-the-system-of-record]], ticket 02) is the
right instinct and should be re-read before the app is public.

**The phone's interaction model is a set of PRE-MADE modals that voice brings to the foreground.**

**CORRECTED 2026-08-28, same day, and the correction matters more than the sentence it replaces.**
This ADR first said the phone was "voice asking and a modal answering... it generates a pop up
modal", read off Kevin's earlier shorthand. He clarified: *"not voice generated, voice called. pre
made modals, voice calls it to trigger it to foreground."*

**The modals are ordinary hand-built UI that exists whether or not anyone speaks. Voice is a
LAUNCHER, not a renderer.** Saying "what's due today" foregrounds the same modal a tap would reach.
Nothing is composed at runtime from a model's output.

That is a different architecture from the one the wrong sentence described, in three ways that all
matter:

- **The UI is deterministic.** Hand-written, previewable, screenshot-testable, reviewable in a diff.
  Generated-at-runtime UI is none of those.
- **Voice failure degrades to inconvenience, not to loss.** If the wake word misses or the mic opens
  deaf, the modal is still there to be tapped. The capability does not disappear with the microphone.
- **The phone shrinks in SURFACE, not in capability.** Fewer screens to browse, not fewer things it
  can do. A modal per capability is still a full hands path.

This is also not the engine's generated UI wearing a new name. Those screens are composed from field
definitions at runtime; these are written by hand, one per capability, and there are few of them
because the phone keeps few capabilities.

**Django was considered and rejected.** Its value is ORM, migrations, auth and admin; Supabase
already owns all four, and adopting Django means teaching its ORM not to own a schema it did not
create, plus a second auth story alongside RLS. Rejected in favour of the client talking to Postgres
directly under RLS.

## AMENDED 2026-08-28 (late): the phone's UI STAYS. Modals are additive.

Kevin: *"lets just keep the UI as is for now. classic home screen etc. just the voice called modals
that bring to foreground we add."*

**Nothing is being removed from the phone.** The existing screens - the widget-pager dashboard, the
per-aspect Classic screens, HOME/BIO/LOG/FLEET/CRED, the persistent Tap-to-talk row - all stay
exactly as they are. Voice-called modals are a NEW capability layered on top, not a replacement for
a UI being stripped out.

This corrects the two places above where this ADR said the phone was shedding browsing surfaces and
shrinking to voice plus modals. It is not. It is keeping everything it has and gaining a faster way
to reach some of it.

**What actually survives from the original decision**, because most of it does:

- The **web app is still the general client** and still the right home for ingest, monitor and bulk
  edit. That was never about taking screens off the phone; it was about not building them twice.
- **Android is still the specialized client** for OBD, wake word, background audio and the voice
  companion. That reason stands on its own.
- **Capability still lives in the data layer.** Unchanged, and the reason for it is unchanged.

**What is withdrawn:**

- "The phone stops being a place to browse data" - **false, it stays exactly that.**
- "Minimal UI" as a goal for the Android app - **withdrawn.** The UI is what it is and it works.
- **Ticket 21's premise is gone.** It asked whether `engine/` loses its reason to live because the
  phone was losing the generated screens and the widget pager. The phone is not losing them, so
  ticket 18's survivor clause holds unchanged and the engine stays. See that ticket's own withdrawal.

**Why this is the better call and not just a smaller one:** the UI on that phone was built,
device-verified, and corrected against real use over weeks - the mission-control language, the
colour semantics, the alarm tiers, five surfaces of panel inventory. Replacing a working shell to
chase a cleaner idea is how a project spends a month arriving back where it started. Adding a
shortcut to a working shell costs one feature.

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

**[[0035-every-voice-capability-has-a-hands-path]] is SATISFIED BY CONSTRUCTION, and the corrected
model is why.** An earlier draft of this ADR flagged a tension here, on the mistaken premise that
voice GENERATED the modal - in which case the modal would be voice-initiated and therefore not a
hands path at all. With pre-made modals the tension does not exist: every modal is reachable by hand
because it has to be built and navigated to anyway, and voice is a shortcut to a destination that
already exists.

**The rule that falls out, and it is the one to enforce:** a modal that ONLY voice can summon would
break ADR 0035, and nothing about this architecture prevents someone building one. So - **every
modal has a hands route to it, and voice never creates a destination that hands cannot reach.**
That is a cheaper invariant to hold than the old one, because it is structural rather than a
discipline: both paths open the same composable.

**Repo split, and the schema stays put for now.** The PC app is a new repo. `supabase/migrations/`
remains in LEGION, because it is twenty-odd files with real headers and a working applied history
and moving it buys nothing today; the PC app pins the contract with generated types
(`supabase gen types typescript`) rather than owning the SQL. Revisit if schema edits start coming
mostly from the PC side, at which point `supabase/` earns its own repo and both surfaces consume it.
