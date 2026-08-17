# What decides there is something worth saying

Type: grilling
Status: open
Blocked by: -

## Question

The architectural fork of this whole map. Nothing evaluates goals, time of day, or location today
and decides a line is worth speaking - the 19 existing raises are each hard-coded at their own call
site.

Three shapes:

- **(a) A deterministic rule engine.** Cheap, predictable, zero tokens, inspectable, dumb.
- **(b) A periodic LLM pass over current state.** Smart, varied, costs money every tick,
  nondeterministic - **and it can invent a reason to speak**, which is the failure mode that makes
  proactivity intolerable.
- **(c) Hybrid: deterministic rules decide WHETHER to speak, an LLM only phrases the line.**

**(c) is the recommendation to argue against rather than a foregone conclusion.** It is the same
split as the reconciliation gate (CLAUDE.md §4) - determinism owns the decision, the model owns the
prose - and this codebase already has that instinct everywhere else.

Decide:

1. **Which shape**, and what the rules are made of if (a) or (c).
2. **Where the rules live.** `advisor/` already has playbooks, digest builders and `AdvisorAgent`
   with a writable-op allowlist. **Zoom `AdvisorAgent` before proposing anything new** - the parent
   ticket flags that whether any of it is wired to a proactive raise is unverified, and this map
   should reuse rather than build a second decision layer beside it.
3. **What a trigger can read.** `goals/GoalController` + `GoalProgress`, `sleep/SleepTarget`, the
   body aspect, `maintenance_items`, `code_events`, calendar. **Every one must be a falsifiable
   fact Kevin could check himself** - that is compulsion test item (a) and it constrains the engine's
   inputs, not just its output.
4. **Does a raise carry its reason?** If Alfred says "it's past 10pm", can he answer "why did you say
   that?" with the actual trigger. An inspectable reason is the cheapest trust mechanism available
   and it is nearly free under (a) or (c), nearly impossible under (b).
5. **Cost.** Under (b) or (c), what does a tick cost on Kevin's own key, and how often does it tick?
   The map's standing preference makes every new domain argue its token cost.
