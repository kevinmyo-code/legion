---
map: hands-and-senses
ticket: 20
title: Memory that forgets like a person does
type: grilling
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# Memory that forgets like a person does

material. It carries a Room decision (the legacy `MemoryEntry` table) and a CLAUDE.md §7 tension,
so it needs its own settled-decisions block. See map.md, "Efforts in disguise".

## Question

Kevin designed a human-shaped memory system in Midnight AI: memories fade with staleness, key
events survive, details go fuzzy. **Verified 2026-08-16: the architecture ported; the forgetting
did not.**

What exists:
- `data/local/CompanionMemory.kt` - `importance` (1-10, default 5), `createdAt`, `lastAccessedAt`
  (default 0), `embeddingVector` + `embeddingModel` (nullable, so retrieval may never have been
  wired), categories `car_anchored` / `driver` / `relationship`, sources including `CONSOLIDATED`
  and `REFLECTION`.
- `ai/MemoryConsolidator.kt` - raw turns into consolidated memories.
- `ai/ReflectionEngine.kt` - synthesizes insights from CONSOLIDATED rows once accumulated
  importance since the last reflection passes 30. Stanford generative-agents shape, cited in its
  own doc comments.
- `CompanionMemoryDao.touch()` - refreshes `lastAccessedAt` on recall, commented as **"ticket 03's
  decay input"**.

What does NOT exist:
- **Any decay function.** Nothing consumes `lastAccessedAt`. No retrieval scorer combining
  recency + importance + relevance surfaced anywhere in the tree.
- **Any gradual forgetting.** The only deletions are two hard resets (per-car "new companion",
  global "forget memories"). Nothing prunes, demotes, or blurs.
- **A second table, `MemoryEntry`/`MemoryDao`**, which `CompanionMemoryDao` calls "the old"
  one. Live, legacy, or dead weight is undecided.

Decide:

1. **The decay curve, concretely.** Stanford's paper uses an exponential recency decay on hours
   since last access, combined with importance and embedding relevance into one retrieval score.
   Is that the model? What are the constants, and does `importance` set a floor so a 10 never
   falls out of reach no matter how stale?
2. **What "fuzzy" actually means mechanically.** Three different things, and they have different
   risks: (a) the memory is retrieved less often (scoring only - safe, reversible, no data lost);
   (b) the detailed text is REWRITTEN into a gist and the detail deleted (an LLM rewrite - can
   hallucinate the gist, and the original is gone); (c) the row is deleted outright (honest, total).
   `MemoryConsolidator` already does a version of (b) at intake. Pick per category.
3. **The §7 tension, which is the sharp part of this ticket.** CLAUDE.md: "memory stays anchored to
   external falsifiable facts". Deliberate blurring introduces drift on purpose. The safeguard is
   already in the architecture: fleet/ledger/pantry facts live in Room tables, so a fuzzy memory
   about the CAR can be re-checked against the record, while a fuzzy memory about a CONVERSATION
   cannot. **Decide which categories may fuzz and which must stay exact or be deleted** - a
   confidently wrong recollection is worse than an admitted gap. Does Alfred ever say "I remember
   something about that, but not clearly"? That single line may be the whole feature: honest
   fuzziness beats silent fuzziness.
4. **What is unforgettable.** Key events survive - by importance threshold, by category, by
   explicit pin ("remember this")? Can Kevin see and edit what it remembers, and is there a
   surface for that? (A memory system with no inspection surface cannot be corrected when wrong.)
5. **When decay runs.** On write, on read, or as a scheduled pass? A scheduled pass is background
   work; the standing pull-based preference applies. Does it need to run at all, or is decay
   purely a scoring function evaluated at retrieval time (cheapest, no job, nothing lost)?
6. **Retrieval, and whether embeddings ever got wired.** `embeddingVector` is nullable and no
   scorer was found - determine whether it is populated in practice, and if not, whether retrieval
   should use embeddings at all or something simpler. Coordinate with
   [the vault retrieval research](16-vault-retrieval-research.md): if that ticket concludes a
   small corpus needs no vector store, the same arithmetic may apply here.
7. **The legacy table.** Is `MemoryEntry` still written to? If dead, its removal is a Room
   migration (additive-only rules apply - dropping a table is not additive, so decide carefully).
8. **The Drive backup.** The whole database syncs; memories ride along. Nothing to decide unless
   forgetting must also propagate - confirm a deleted memory does not come back on restore.

## Verification 2026-08-16 - THE PREMISE IS HALF FALSE, and this effort shrinks

Swept against the tree before this graduates to its own map. The map's settled decision 11 claims
"no decay function, no pruning, no retrieval scorer". **Two of those three are wrong.** All `traced`.

**A decay function EXISTS and is wired.** `ai/AriaBrain.kt:452-458` -
`score() = RECENCY_WEIGHT * recencyDecay + IMPORTANCE_WEIGHT * (importance/10) + RELEVANCE_WEIGHT * relevance`,
with `recencyDecay = RECENCY_DECAY_PER_HOUR ^ hoursSinceAccess`. Constants at `:745-748`: **0.99 per
hour, all three weights 1.0**, documented as the Generative Agents default with a **~69 hour
half-life** (`:739-744`). Dated "Ticket 03 (2026-07-22)" - this is months-old code.

**A retrieval scorer EXISTS and is wired.** `recallMemories` (`:401-427`) scans both stores, hard
-gates on keyword overlap (`:417`), sorts by `score()` (`:419`), takes `RECALL_LIMIT = 6` (`:737`),
and **rehearses** by calling `companionMemoryDao.touch()` (`:424`). Reachable: `recall_memory`
declared at `LiveToolbox.kt:1073` inside `declarations()`, dispatched `:1490`, called `:2023`.

**Only PRUNING is genuinely absent** - `CompanionMemoryDao` has no age or score-based delete. And
that is **a stated design position, not an omission**: `AriaBrain.kt:741-743` says "the 'forgetting'
is a score falling out of the top `RECALL_LIMIT`, not a purge."

**`embeddingVector` is an ORPHAN FIELD.** Declared `CompanionMemory.kt:52` with `embeddingModel` at
`:53`; **zero other references anywhere.** Nothing writes it, nothing reads it. `relevance()` is
keyword overlap only (`:467-471`).

**The legacy `MemoryEntry` table is live**, read every recall as `Candidate.Legacy`
(`AriaBrain.kt:431`) with a hardcoded neutral importance of 5 (`:436`) and `timestamp` doing duty as
both createdAt and lastAccessedAt (`:439-441`).

### What this leaves for the `memory-decay` map

**Answered already, do not re-decide:** the decay curve (0.99/hr, equal weights, ~69h half-life) and
whether embeddings were ever wired (**no** - the field exists and is dead).

**Genuinely open:** pruning (against a documented no-purge position), unforgettability, the legacy
`MemoryEntry` Room decision, backup semantics, and whether the orphan `embeddingVector` field should
be filled or deleted.
