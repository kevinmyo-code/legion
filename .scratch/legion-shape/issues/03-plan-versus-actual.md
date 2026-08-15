# Is plan-versus-actual one idea or four?

Type: grilling
Status: resolved (2026-08-06, Kevin)
Blocked by: 01

## Question

Kevin described what he wants from each domain. Read side by side, they are the same shape:

| Domain | The plan | The actual | What he wants to see |
|---|---|---|---|
| Ledger | monthly budget per category | spending | how much is left, per category |
| Cars | maintenance schedule | logged services, OBD | what is coming up |
| Workouts | an AI-made plan | logged workouts + weight | how well he stuck to it |
| Meals | calorie/macro target | what he ate | how close he is |

Verbatim: *"i want to set a budget every month, and as i spend, i want to see how much i can still
use per category"*; *"log maintenance and keep track of upcoming stuff"*; *"i want the ai to be able
to make a plan, and i log my workouts and my weight to it and see how far we have stuck to it"*;
*"just an estimate of calories and macros"*.

He did not describe four apps. He described one idea wearing four coats. Is it built as one?

## Resolution

**One idea, four coats. Shared vocabulary and shared rules; SEPARATE storage per domain.**

Shared, decided once and identical everywhere:
- The three words: a **target**, a **log**, and the **gap** between them. (Ticket 05 defines them.)
- The trust rule from ticket 02: a gap computed from reported actuals is itself reported, and says so.
- The one question every domain answers: **"how am I doing against the plan?"**

Not shared:
- Storage. A workout target has exercises and progression; a budget target has an amount per
  category. One table for both fits neither.
- The screens, beyond reading as the same idea.

**Explicitly rejected: one generic engine.** Offered and declined. Four domains alike in shape and
unalike in detail are exactly where a clever generic system fits none of them.

**Explicitly rejected: four independent builds.** The deciding argument is ticket 02: "what does the
gap mean when the actual is only reported" must be answered once. Four builds answer it four times
and get it slightly different in each.

**The ledger's lostness is explained by this ticket.** It had no target. With no target there is no
gap, so "how much can I still spend" was unanswerable, so the domain grew features instead of
finishing.
