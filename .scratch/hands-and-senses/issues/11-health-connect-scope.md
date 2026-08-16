# Health Connect: what does LEGION do with a body's data?

Type: grilling
Status: archived (Kevin, 2026-08-16) - no wearable to read from
Blocked by: 10

## Question

With [the Health Connect facts](10-health-connect-research.md) on the table, decide the LEGION
side. The prize named in the brainstorm: pantry's estimated macros in vs measured energy out is
the first cross-aspect insight nobody else can compute. Decide:

1. **Which metrics, which tools.** Sleep, steps, heart rate, workouts, calories - all, or start
   narrower? Pull-based tool(s) with what descriptions, and what does the tool budget argument
   look like? (One `health` tool with a metric parameter is the notes-domain shape.)
2. **The estimate rule, doubled.** Pantry macros are LLM guesses; calorie burn is a wearable's
   model guess. An insight computed from two estimates is spoken how? "Roughly", always, with
   both sources named - write the register. Nothing here can reconcile against a document, so
   NOTHING from this aspect is ever presented as verified fact. That is §4 rule 5 territory, not
   gate territory - say so in the tool descriptions.
3. **Storage.** Read-through like mail (Health Connect IS the store; LEGION queries on demand), or
   does any derived aggregate land in Room? Read-through is the default under the standing
   preference; a derived row needs an argument.
4. **CompanionMemory boundary.** May the assistant remember health patterns ("you sleep badly
   after late caffeine") - or is that unfalsifiable-adjacent inference the memory rules exclude?
   Where health sits against "memory stays anchored to external falsifiable facts".
5. **Crisis adjacency.** Health data plus a warm persona is where wellness theater starts. LEGION
   is not a health coach; `CrisisDetector` boundaries stay. What the assistant may NOT say about
   health data (no diagnosis, no trends framed as medical advice) - write the line.
6. **Wearable reality.** What does Kevin actually wear/log today? If nothing writes to Health
   Connect on his phone, this aspect has no data and the ticket may park.

## Answer

**Archived 2026-08-16 by Kevin: "archive 11, i dont have a fitbit or a watch."**

Not resolved and not rejected on its merits - the data source does not exist. Health Connect is a
read API over records other apps write, and with no wearable there is nothing writing sleep, heart
rate, workouts, or calories burned. Four of the five metrics [the research](10-health-connect-research.md)
established are simply absent, and the prize this ticket existed for - **pantry's estimated macros
in versus measured energy out** - needs the "out" side, which is the one that requires a device.

**One fact for whoever un-archives this:** steps are the exception. The A25 can produce a step
record from the phone's own sensors via Samsung Health, no watch involved (`reasoned` - not
verified on Kevin's device, and it depends on Samsung Health being installed and writing to Health
Connect). So a steps-only version is technically possible today. It was not pursued because
steps alone do not compute the macros-versus-burn insight, which is the entire reason the ticket
was charted.

**[The research](10-health-connect-research.md) stays resolved** and is retained in full. Nothing
in it was falsified - it just has no consumer. The findings that matter most on a revisit: no
background permission is needed for foreground pull tools; the 30-day read window is real; sideload
is fine because the Play declaration is store-review-only; sync freshness is **undocumented** and
needs an on-device test before any "how did I sleep" tool can be trusted; and there is one
verify-at-build on connect-client library minSdk against the app's minSdk 24.

**Un-archive trigger:** Kevin acquires a wearable, or a phone-only metric becomes independently
worth having.
