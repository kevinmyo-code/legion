---
map: dev-aspect
ticket: "02"
title: "Azure DevOps and the employer-data boundary"
type: grilling
status: resolved
status-detail: "Resolved 2026-09-01 with Kevin, amended same day by ticket 03 research. Read-through at voice time, never persisted. Field ALLOWLIST, never a denylist. PAT on-device only."
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Azure DevOps and the employer-data boundary

## Resolution (Kevin, 2026-09-01)

**Azure DevOps is queried live at the moment Kevin asks, and nothing is ever persisted.** No
Supabase table, no Room table, no cached rows, no summary. Read to answer, then dropped.

This is CLAUDE.md section 7's third-party rule followed natively rather than carved an exception
out of, and it was the grill's question B:

> Why persist Azure at all? Query it live when you ask, answer, discard. Nothing to breach, nothing
> to delete when you leave the job.

### The fact that changed the weighting

Kevin, 2026-09-01: *"its company's azure, but its all my own projects, solo dev work."*

That matters in one direction and not the other. **Section 7's concern is much weaker** - the work
items are Kevin's own writing, not a colleague's, so this is not other-people's-prose the way mail
is. **The company's ownership of the infrastructure is unchanged.** It is still their tenant, their
data, their policy, and a personal cloud Postgres holding it is still an export whether or not
Kevin typed every word himself.

Read-through resolves both at once and costs almost nothing here, because the query is occasional
and a second of latency on a spoken answer is invisible.

### The four conditions, all binding

1. **Nothing persists.** No table anywhere. Not a cache, not a "last known", not an embedding, not
   an episodic memory. The exclusion is enforced at the write site the way
   `LiveToolbox.EPISODIC_EXCLUDED_TOOLS` is, not by each future change remembering.
2. **The PAT lives on-device only**, in encrypted preferences, entered by hand. It is never sent to
   Supabase, never in an Edge Function secret, never in the repo. It is an employer credential with
   a different owner from the BYO Gemini key, and it does not get the same server-side treatment.
3. **Titles, state, type, project, URL and dates only, and the restriction is an ALLOWLIST.**
   Ticket 03's research confirmed `POST _apis/wit/workitemsbatch` honours a `fields` array and
   returns nothing else, so the body is never fetched rather than fetched and discarded. Three
   findings from that research bind here and are not optional:

   - **The allowlist is a hardcoded constant. A denylist is forbidden.** `System.History` IS the
     comment thread - Microsoft: *"There is no separate Discussion field... all text entered into
     the Discussion box is appended to History."* It is an ordinary field name among up to 1024 a
     custom process may define, and nobody in this repo can enumerate the employer's. A denylist
     that misses one leaks it silently; an allowlist that misses one merely fails to show a column.
   - **`$expand` is never sent.** `$expand=all` returns every field regardless, and its interaction
     with `fields` is undocumented. Not a default to override - a parameter that does not appear in
     the client at all.
   - **`System.AssignedTo` is NOT on the allowlist.** It returns a full IdentityRef carrying a
     `uniqueName` work email. Kevin's own solo projects make the assignee field worthless anyway;
     including it would persist nothing (read-through) but would still speak colleagues' names and
     emails aloud for no gain.
4. **A failed or absent PAT says so in words.** "I cannot reach Azure DevOps" is not the same
   sentence as "nothing is pending", and section 1's unreadable-versus-empty rule applies exactly.

### What is still Kevin's to check, and is not resolved by this ticket

Company policy on connecting a personal device to their Azure DevOps with a PAT. Read-through
removes the data-at-rest question entirely; it does not remove the access question. Kevin owns
that, this repo cannot answer it, and the build does not wait on it because a read-through tool
that is never granted a PAT simply never returns anything.

### Consequences for the rest of the map

- Ticket 06 becomes an on-device read-through client, not a sync. No Edge Function.
- Ticket 07's staleness contract does not apply to Azure at all - a live answer has no age.
- Question 15 (who deletes it when you leave) is answered by construction: nothing to delete.
- Question 10 (partner can query it via household RLS) is answered by construction: it never
  reaches Supabase, so RLS never sees it.

## Verification

- Entry in `memory/library/decisions.md` dated 2026-09-01.
- A test asserts no Azure-sourced field is reachable from any persistence path, in the shape of the
  `EPISODIC_EXCLUDED_TOOLS` exclusion.
- Dump the database after a real Azure query on the phone and confirm no work-item text is in it.
  Inspected, not reasoned.

### Amended 2026-09-01 by ticket 03's research: three facts Kevin should know

None of these changes the ruling. All three were unknown when it was made.

1. **Company admins can see this traffic.** Azure DevOps does not log work-item READS in the audit
   log (*"Token access events aren't currently logged"*), but the organisation **Usage page** shows
   a Project Collection Administrator the user, IP, User-Agent, Command and UriStem of every REST
   call, and Microsoft documents it as a tool for investigating other users. PAT creation is
   separately audited by name (`Token.PatCreateEvent`). This is not a reason not to do it - it is a
   reason not to do it quietly and be surprised later.
2. **PAT is the only option.** Entra OAuth is not available to a phone app per Microsoft's own FAQ,
   so condition 2's on-device PAT is not a choice between two mechanisms. It is the only documented
   one.
3. **The org can disable PAT creation outright** for Entra-backed organisations, with a named
   per-person allowlist. If Kevin's org has, this map's Azure half is dead on arrival and no amount
   of building changes that. Worth checking before ticket 06 is built, not after.
