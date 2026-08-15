# A seeded interval is a guess and has to say so

Type: grilling
Status: resolved (2026-08-15)
Blocked by: 01, 02

## Question

The 3,000-mile oil interval is not a hardcoded default and it is not a bug in the arithmetic. **An
LLM produced it**, from a prompt that explicitly asks for the *severe / heavy-duty* schedule:

```
"Use search to find the manufacturer-recommended SEVERE / heavy-duty maintenance schedule ...
 for a ${vehicle.year} ${vehicle.make} ${vehicle.model}. Respond with ONLY a raw JSON array ..."
```
`VehicleController.kt:739-757`, parsed by `parseIntervals` (`:759-781`).

It then renders on screen as `"every 3,000 mi - last at 132,400"` (`ui/fleet/FleetRows.kt:145`),
in exactly the same typography as a number Kevin typed himself. **The app currently cannot tell
Kevin which of its numbers it made up.**

CLAUDE.md §4 rule 5: *anything the document does not state cannot be gated, and must be surfaced as
an estimate, never as fact.* A maintenance interval is not something the car stated. It is the
pantry macro-estimate problem wearing a different hat.

Kevin's ruling at charting: **show it as a guess until I confirm it.**

## What has to be decided

1. **How provenance is stored.** A per-item flag, and what its values are. Two states
   (`SEEDED` / `CONFIRMED`) or three (a hand-typed value is arguably neither)? Note the existing
   vocabulary: `IngestMethod` already has `DETERMINISTIC` / `LLM_RECONCILED` / `UNRECONCILED`, and
   an LLM-guessed interval that reconciles against nothing is closest in spirit to `UNRECONCILED`.
   **Reuse the vocabulary or deliberately don't**, but say which.
2. **The Room change.** `maintenance_items` needs a column. Room is at **v19**. CLAUDE.md §5:
   verbatim generated SQL, additive only, `exportSchema`, no destructive fallback, migration test.
   Check whether this can ride with tickets 07 and 11's schema needs as one bump rather than three.
   **Widening an enum stored as TEXT is not a migration** (§5) - if provenance is TEXT, adding a
   constant later is free.
3. **What existing rows become.** Every row on Kevin's phone was seeded. Does the migration default
   them all to `SEEDED`, or does a row whose `updatedAt` is non-zero count as touched? Ticket 01
   answers whether any row has ever been edited.
4. **The words on screen.** §4 rule 7's discipline: **said in words, never by colour or a glyph
   alone.** What does an unconfirmed row actually read as? `"every 3,000 mi (LEGION's guess)"`?
   A `DeckTag`? Both? It must survive being read aloud too - `get_next_service` and
   `ask_maintenance` speak these numbers, and mission-control's disclosure precedent is that a
   spoken figure carries the same caveat as a rendered one.
5. **What confirming looks like**, and whether editing implies confirming. Editing 3,000 -> 7,500
   obviously does. Does tapping "yes that's right" on an unchanged 5,000 also? Is there a bulk
   "accept the whole seeded schedule" affordance, or is that exactly the rubber-stamp this ticket
   exists to prevent?
6. ~~**Should the seed prompt still ask for SEVERE?**~~ **DECIDED 2026-08-15 (Kevin): normal
   schedule, hard-coded. Severe is not offered as a setting.** Ticket 02 established that Schedule A
   is 7,500 mi or 6 months and Schedule B is 3,000 mi with no time interval at all - so the prompt,
   not the model, produced the 3,000. The cost was stated and accepted: a car living a short-trip,
   dusty or towing life has no route back to severe except editing items by hand, which is what
   this ticket's sibling (05) builds. Applied to `lookupServiceIntervals`
   (`VehicleController.kt:740-742`) as a standalone fix; see ticket 14 for the deliberate
   populate flow that replaces automatic seeding.

   **Still open here**, and not settled by that decision: ticket 02 found that some seeded items
   correspond to **nothing in the factory schedule at all** (`Brake Fluid Flush` on the XJ; the XJ
   has no cabin air filter). Those are not severe-vs-normal disagreements - they are inventions,
   and they are the strongest argument this ticket has for a provenance flag.
7. **Should there be an LLM seed at all?** Kevin declined the "stop guessing entirely" option, so
   this is not reopened - but ticket 02 may return a schedule good enough to **bundle as an asset**
   for this specific car, and CLAUDE.md §7 prefers bundled to fetched. A bundled table is
   deterministic; an LLM call is not. Rule on whether a bundled schedule, where one exists, takes
   precedence over the LLM.

## Watch for

`parseIntervals` turns anything `<= 0` or missing into `null`, and `null` renders as
`"no interval on file"` with **no meter drawn at all** (`FleetRows.kt:145,159,193`). So a seed that
half-fails is already invisible rather than wrong. That is the safe direction, but it means
**"the schedule looks fine" and "the schedule is mostly empty" look similar** - check which of the
two Kevin's phone is actually in, using ticket 01's data.

---

## Answer (2026-08-15)

### The count: six places, and two of them are LLM prompts

| Surface | What it does with an interval |
|---|---|
| `ui/fleet/FleetRows.kt` | renders the sub-line `"every 3,000 mi - last at 118,483"` |
| `vehicle/VehicleController.kt` | `nextService` / `formatRemaining` - **spoken** |
| `vehicle/CarToolbelt.kt` | `maintenanceSchedule` formatter - fed to sub-agents |
| `vehicle/MaintenanceAgent.kt` | **pre-seeded into the agent's prompt** |
| `advisor/playbooks/FleetPlaybook.kt` | **named in the advisor's prompt** |
| `advisor/AdvisorProposalExecutor.kt` | proposal text, spoken and stored |

**The last two matter more than they look.** Feeding an unlabelled guess into a model that then
states it back confidently is how an estimate launders itself into a fact. A disclosure that stops
at the screen is not a disclosure - it just moves the lie to the loudest channel. §4 rule 5 is about
what the app *asserts*, and the app asserts things out loud.

### Decisions (Kevin, 2026-08-15)

**1. A `[GUESS]` tag on the row.** A `DeckTag` beside the value, on the same ladder as the ledger's
`UNRECONCILED` treatment.

**This satisfies §4 rule 7 because the tag contains the WORD**, not a colour and not a glyph. That
is the whole test, and it is why the tag may never degrade to a coloured dot or an icon under
layout pressure - at that point it stops complying. Mission-control's `DeckTagStyle` ladder already
exists for exactly this and is reused rather than reinvented.

**2. Confirm-all exists, but it re-states every value first.** A list of what is about to be
blessed, read before agreeing. Fast without being blind. A plain accept-all was declined, correctly:
it turns the flag into a formality within a week, and this ticket exists precisely because 3,000
looked exactly as authoritative as a number Kevin typed.

**3. No bundled factory table. The LLM lookup stays, for every car.** One code path; the XJ gets no
privilege over any other car. Ticket 02's research remains a **reference for checking**, not a
data source.

Consequence, stated so it is not discovered later: **the 3,000 -> 7,500 correction comes entirely
from the prompt fix already shipped** (SEVERE removed), plus Kevin confirming the value. Nothing
deterministic backs it up. That is a deliberate trade, not an oversight.

### Refinements added on resolution

**a. Two states, stored as TEXT.**

```
intervalSource: "SEEDED" | "CONFIRMED"
```

`SEEDED` = LEGION produced it. `CONFIRMED` = the driver typed it, accepted it, or confirmed it.
**Not reusing `IngestMethod`** (`LedgerTransaction.kt:31`): that vocabulary exists to describe what
survived the §4 reconciliation gate, and a maintenance interval never enters that gate - there is
nothing to reconcile it against. Borrowing the word would imply a check that never ran.

Stored as TEXT deliberately. CLAUDE.md §5: **widening an enum stored as TEXT is not a migration**,
so if a bundled/`FACTORY` state is ever wanted (decision 3 declines it *today*), adding it costs no
schema change. Confirm that the same way §5 says to - read the column's `createSql` in
`app/schemas/`, don't assume.

**b. Editing confirms. So does accepting.** Any driver action that names the value moves it to
`CONFIRMED` - typing it, accepting a populate diff row (ticket 14), accepting an advisor proposal
(ticket 05's rule). No separate "yes I agree" step on top of an edit.

**c. The tag renders only when there is an interval to qualify - and this is not cosmetic.**

Kevin's device already has the counter-example. `Brake Fluid` and `Brake Pads` are orphan rows
created by `log_service` with **no interval at all** (ticket 01), and their sub-line already reads
`"no interval on file"`. Tagging that `[GUESS]` would be nonsense: there is no number to doubt.
**Null interval -> no tag.** The existing wording already tells the truth there.

### What the tag actually claims

Broader than "this number might be wrong", because ticket 02 proved the problem is broader:

- `Brake Fluid Flush` is on Kevin's Jeep with a 24-month interval. **The XJ's factory schedule
  contains no brake fluid service at all** - only a monthly level check.
- The **XJ has no cabin air filter**, and LEGION has seeded that item onto other cars in the roster.

So a seeded row can be wrong about *whether the item exists*, not merely about its numbers.
`[GUESS]` therefore means: **LEGION added this row and the figures on it, and you have confirmed
neither.** Confirming says the item belongs on this car AND its interval is right. That is one act,
not two, and the ticket-14 diff is where "this isn't a thing on my car" gets expressed as a delete.

**The anchor is out of scope of this flag.** `lastDoneMileage`/`lastDoneDate` come from the driver
logging work; they were never LEGION's guess and are not tagged.

### Room

One additive column on `maintenance_items`: `intervalSource TEXT NOT NULL DEFAULT 'SEEDED'`.
**v19 -> v20.** CLAUDE.md §5: verbatim generated SQL, additive only, `exportSchema`, schema JSON
committed, no destructive fallback, migration test.

**Every existing row defaults to `SEEDED`, and that is correct** - all 54 on Kevin's device were
LLM-produced (ticket 01). `updatedAt` cannot be used to detect a prior hand-edit: it is non-zero on
all 54 because the Kotlin default stamps construction, so it says nothing about authorship.

Ticket 14 also wants an `engine` column on `vehicles`. **Do not hold this bump for it** - 06 gates
the build of 05, 09 and 14, so it goes first and takes v20. If 14 lands close behind, it takes v21;
two small additive migrations are cheaper than a blocked ticket.

### Spoken form

The tag becomes words, because a tag cannot be heard:

> "Oil change is every 3,000 miles - though that's my guess, not something Jeep published."

Binding on `VehicleController.nextService`, `CarToolbelt.maintenanceSchedule`,
`MaintenanceAgent`'s prompt and `FleetPlaybook`'s. **The two prompts must carry the flag into the
model's context**, so a sub-agent reasoning about the schedule cannot present a guess as fact - and
their tool descriptions say "estimate", per §4 rule 5's existing requirement.

### Verification

Binding on whoever builds this (L11):

1. On the device, with Kevin's real schedule: **every one of his 54 rows shows `[GUESS]` except the
   ones with no interval**, which show none.
2. Confirm one, force-stop, reopen, **pull the database** and check `intervalSource` flipped. Two
   confirmations, per ticket 05's rule.
3. **Ask it out loud** and confirm the spoken form carries the caveat. The screen working is not
   evidence the spoken path does - §4 rule 7's "in words, on every surface".
4. Confirm the migration against a **copy** of Kevin's real database before the device, and check
   the schema JSON is byte-consistent after kapt.
5. Confirm the confirm-all screen lists values before blessing them, not after.

### Assumptions ledger

- `traced`: the six interval surfaces; `IngestMethod`'s definition and purpose; that all 54 rows
  have non-zero `updatedAt` and so cannot reveal authorship; the `Brake Fluid`/`Brake Pads` null
  intervals; `DeckTagStyle`'s existence.
- `on-device`: Kevin's 54 rows and their contents, from the 2026-08-15 database pull.
- `reasoned`: that a `TEXT` column keeps a future `FACTORY` state migration-free. It follows from
  §5's stated rule, but has not been exercised for this column.
- **Not built.** Nothing here is implemented. **Unblocks the builds of 05, 09 and 14.**

