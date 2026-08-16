# Memory that forgets like a person does

Type: grilling
Status: open
Blocked by: -

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
