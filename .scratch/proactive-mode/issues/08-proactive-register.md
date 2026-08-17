# How Alfred sounds when nobody asked him anything

Type: grilling
Status: open
Blocked by: -

## Question

*"It's past 10pm, perhaps rest is in order"* is the whole brief in one line: dry, deferential, easy
to ignore. It **offers** rather than instructs, and it never nags twice.

The assistant's core register already exists - `ai/Personas.kt` ships ALFRED and DOROTHY with full
clauses (verified 2026-08-16; the "voice not written" claim was false). **This ticket does not
redefine that voice.** It writes the proactive-specific rules that sit on top of it, and hands them
to whatever owns the persona copy.

Decide:

1. **The proactive rules**, as clauses a persona must obey when it initiates rather than answers.
   Proposed, to accept or redraw: never repeat a declined nudge; never escalate tone across
   attempts; one line, never a paragraph; always offer, never instruct; **never mention that it is
   the second time**.
2. **How a raise differs by category.** A Safety warning at 3am and a Wellbeing rest nudge cannot
   share a register - one should be flat and urgent, the other soft and skippable. **Is the register
   a property of the category?**
3. **Does a declined nudge change the next one?** "Never nag twice" implies memory of the refusal,
   which is fog on this map. If the answer is that Alfred simply never repeats within a window,
   say so and keep it stateless.
4. **What Alfred says when Kevin asks "why did you say that?"** - if [the trigger
   engine](02-trigger-engine.md) carries a reason, this is where its wording is decided.
5. **Where these clauses live.** `Personas.kt` per-persona, or one shared proactive clause that
   every persona inherits? **The honesty rules already live inside each persona's own clause**
   (`.scratch/hands-and-senses/issues/12-assistant-identity.md`), which is a known weakness - a
   freeform persona could omit them. **Do not repeat that mistake here.**
