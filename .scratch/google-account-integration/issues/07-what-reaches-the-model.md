# What of Kevin's mail and calendar reaches Gemini, is stored, or is spoken aloud?

Type: grilling
Status: resolved
Blocked by: -

## Question

Every other domain in LEGION holds data Kevin himself created or a document he chose to import. This
map is the first time the app reads a stream **other people wrote to him**, and it goes to a third
party (Google's Gemini, on Kevin's own key) the moment Alfred summarises it.

**Handed on by [ticket 03](03-gmail-scope-floor.md), resolved 2026-08-13.** Google's API Services
User Data Policy prohibits transferring restricted data to third parties and **says nothing about
LLM providers** (the agent tagged that reading `inferred`, not documented). Under `gmail.metadata` a
body physically cannot reach Gemini; under `gmail.readonly` - which ticket 03 recommends, because
search needs it - that guarantee becomes **a rule only LEGION enforces**. Ticket 03 also notes the
fallback: `gmail.metadata` is the same restricted tier, so trading search away for a hard technical
guarantee is available at no tier cost. **Rule on this deliberately. Do not inherit it as settled.**

Decide the boundary, explicitly, because nothing in `CLAUDE.md` covers it yet:

1. **What is sent to the model.** Subjects and senders only, or snippets, or full bodies on request?
   Per-call, not as a blanket permission.
2. **What is stored.** Does any mail or event content land in Room? `CompanionMemory` /
   `EpisodicTurn` already persist conversation. If Alfred reads out a subject line, that subject is
   now in the episodic log - decide whether that is fine or needs excluding.
3. **What syncs.** As of 2026-08-12 sync backs up the **whole database**, all 42 tables, to Drive.
   Anything stored under (2) therefore leaves the device. Decide with that in front of you.
4. **What is spoken aloud.** Alfred talks. A car is a place with passengers. Is there anything mail
   should not read out unprompted, and does that need a mechanism or just not building the
   proactive path (already out of scope)?
5. **Memory anchoring.** CLAUDE.md §7 requires the assistant's memory to stay anchored to external
   falsifiable facts. Mail is external and falsifiable, so it qualifies - but decide whether Alfred
   may form durable memories from it at all, or must re-read each time.
6. **Does any of this graduate into `CLAUDE.md` §7 as a rule?** If the answer is a principle rather
   than a one-off, file it to `memory/library/decisions.md` and apply it to `CLAUDE.md` in the same
   commit, per `memory/MEMORY.md`'s own instruction.

## Answer

**Read-through only. Mail is read, used in the answer, and dropped. It is never stored, never
synced, and never remembered.**

Resolved 2026-08-13 on the orchestrator's recommendation, delegated by Kevin. Ticket 04's decision
that no Google event touches the database means this rule covers calendar for free - **there is
nothing to exclude, because nothing is ever written.**

1. **What is sent to the model.** Sender, subject, date and Gmail's `snippet`, for at most the
   briefing cap (ticket 05). **A full body goes to Gemini only when Kevin asks for that specific
   message**, and Alfred says he is fetching it. Never a blanket "here is the inbox".
2. **What is stored: nothing.** No Room table, no entity, no DAO for mail. That is a design
   constraint on the build tickets, not a runtime check.
   **The sharp part, and the reason this ticket existed:** `EpisodicTurn` and `CompanionMemory`
   already persist conversation, so a subject line Alfred reads aloud would land in the episodic log
   by default, with nobody deciding it should. **Mail tool results must be excluded from episodic
   persistence explicitly.** This is a build requirement with a test, not a note.
3. **What syncs: nothing**, by construction of (2). Since 2026-08-12 sync backs up the whole
   database - all 42 tables - to Drive. The only defence that survives contact with a feature added
   later is that the content was never in the database at all. A table with an
   "excluded from sync" flag would have been one forgotten registration away from shipping Kevin's
   mail to Drive.
4. **What is spoken aloud: no new mechanism, because none is needed.** Gmail is pull-only (settled
   decision 4), so Alfred reads mail exactly when Kevin asks, in front of whoever is in the car.
   That is Kevin's call at the moment he makes it. A proactive path would have needed a rule; it is
   out of scope.
5. **Memory anchoring: Alfred may NOT form durable memories from mail.** He re-reads each time.
   CLAUDE.md §7 permits memory anchored to external falsifiable facts, and mail is external and
   falsifiable *at the moment it is read* - but a stored recollection of what somebody wrote to
   Kevin is a second-hand claim that outlives the message, cannot be re-checked once the mail moves
   or is deleted, and is not a fact about Kevin's car, statements or receipts. **This is the
   conservative reading and it is deliberate.**
6. **Yes, it graduates.** Proposed `CLAUDE.md` §7 guardrail, to be applied in the same commit as the
   build, per `memory/MEMORY.md`'s own instruction:

   > **Third-party content is read-through only.** Anything other people wrote *to* Kevin rather than
   > anything Kevin created or chose to import - mail first, and anything of that shape later - may
   > be read to answer a question and must then be dropped. Never persisted to Room, never synced,
   > never remembered, never used to form a durable memory. The guarantee is that it was never
   > stored, not that something remembered to exclude it.

   **Not applied to `CLAUDE.md` in this session.** Kevin delegated resolving tickets, not amending
   the rules file; the wording above is a recommendation for him to accept when the build lands.

### The one thing this does not settle

Ticket 03 handed over that Google's User Data Policy Limited Use section bars transferring restricted
data to third parties and **says nothing about LLM providers** (`inferred`, not documented). Sending
a mail body to Gemini on Kevin's own key is plausibly not a "transfer to a third party" in the sense
meant - Kevin is the user, the key is his, and there is no service in between - but **that reading is
untested and this ticket does not claim otherwise.** It matters only if LEGION ever seeks
verification, which the §5 amendment says it never will. Recorded, not resolved.

**The fallback stays on the table.** Ticket 03 established `gmail.metadata` is the same restricted
tier, so trading search away buys a *technical* guarantee that no body can reach Gemini, rather than
a policy one. Recommendation is `gmail.readonly` and the rule above; if Kevin ever wants the harder
guarantee, the cost is search and nothing else.
