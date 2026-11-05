---
status: accepted
decided: 2026-11-05
decided-by: Kevin
supersedes: []
source: "[[decisions#2026-11-05 - Centralized memory and the ID vault (Kevin)]]"
tags: [adr]
---

# 75. Memory lives on the engine, private to the member it is about

## Standing

ACCEPTED 2026-11-05. Engine half built on `feat/memory-vault-engine` (`.scratch/memory-and-vault/`
tickets 01-04, not deployed); the phone half is tickets 09-11. Amends [[0052-a-row-may-be-private-to-one-member]]
(a new privacy-bearing table whose default is private) and **reverses the engine-mcp ruling of
2026-10-02** that no engine tool reads or writes memory (`server/engine_mcp/tools.py` module doc,
engine-mcp ticket 06 sub-question 2), for memory v2 only.

## Context

Alfred's memory lived on the phone (`ai/AriaBrain.kt`, `ai/MemoryConsolidator.kt`). The engine held
a replica (`companion_memories`, `memories`) with no member column, and the engine's tools were
barred from it, so the web assistant said it had no memory and the Claude worker could not see
any. Kevin, 2026-11-05: *"alfred's memory should stay on a centralized place, like the engine no?
i thought we did memories that way"*, then *"yeah lets do it. centralized memory. and the id
vault"*.

## Decision

- **One store on the engine**, `public.member_memories` (`server/member_memory/`). The phone, the
  web assistant and the Claude worker read and write it; the phone keeps a cache.
- **A memory is private to the member it is about.** A shared household fact (an address, a car)
  may be marked shared. ADR 0052's `owner_user` column and `household.tenancy.visible()` enforce
  it, with a check constraint that a memory is only ever private to the member it is about.
  Sharing is an explicit act; the consolidation job never shares.
- **Saving is automatic, visible and forgettable.** `manage.py consolidate_memories` distils
  finished conversation records. A Memory screen lists them (phone ticket 11). **"Forget that" or a
  swipe is a hard delete**, of the memory and every older version it superseded.
- **Consolidation reads only the person's own words**, plus the assistant's words from turns that
  called no tool. A turn that called a read-through tool is dropped whole. CLAUDE.md section 7
  holds by the shape of the extraction: mail never reaches the prompt.
- **A newer contradicting fact supersedes** the old one, which leaves recall but stays until the
  newer one is forgotten.
- **Recall is the phone's score on the engine** (recency + importance/10 + relevance, top 6,
  rehearsal), lexical, with two decay classes: `durable` 0.999/hour, `episodic` 0.99/hour (the
  first is an estimate). Embeddings are the next ticket; the test cluster has no pgvector.
- **Tools:** `recall_memory`, `list_memories`, `remember`, `forget_memory`, on `/mcp` and the web
  assistant. The three legacy tables stay out of the generic record tools.
- **Who:** members, and the Claude worker on the Alienware, which may read AND write (*"claude
  can write too."*). **Never a recruiter** (ADR 0068). **Never the work laptop:** each worker
  heartbeat binds its token to its machine, and memory refuses a token bound to a `listed`
  machine or a `claude-session-*` token bound to none.
- **Vault identifiers never become memories.** A deterministic filter refuses passport, NRIC,
  SSN, A-number and account-number shapes at every door ([[0076-the-id-vault]]).

## Consequences

- **The work-laptop fence is convention, not proof.** The scope is what the worker reports and the
  token name is the installer's. A member's token copied to another machine is a member's token.
  ADR 0067's follow-up, worker endpoints on machine tokens, closes it.
- **The engine cannot tell where a `/mcp` write's words came from.** The phone refuses `remember`
  after a mail tool; the Alienware worker reads Kevin's Gmail (ADR 0067, 2026-11-04) and may now
  write memories. The only lever there is the worker's prompt (ticket 08 adds the rule).
- A hard delete leaves no tombstone, so a cache refreshes from the whole list.
- The filter refuses any nine-digit run, so a nine-digit insurance policy number is refused too.
- Head-unit conversation records are consolidated like the phone's; they are the same member.
