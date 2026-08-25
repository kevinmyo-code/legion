---
map: backend-erp
type: research
status: resolved
title: "Research: wiki-style notes + AI second brain landscape, mapped onto the engine"
date: 2026-08-25
---
# Research: wiki-style notes, the "AI second brain" landscape, and LEGION

Brief: identify Kevin's two reference videos, separate signal from slop in the AI-second-brain
space, map onto the engine + Notes aspect + companion_memories + the Supabase move.
**Videos were NOT watched.** Identification is from the YouTube pages' own metadata plus written
sources describing the same talks. Every video-content claim below is traced to a write-up, not
to the footage.

## 1. The two videos

| # | URL | Title (from the YouTube page) | Who |
|---|---|---|---|
| 1 | youtube.com/watch?v=XNX-1h2K-9U | "Building Docs for Agents, Not Humans: Inside OpenWiki" | Brace Sproul, Head of Applied AI at LangChain (per search results, not the page) |
| 2 | youtube.com/watch?v=I3bpdgFJCUY | "LLM Knowledge Bases: a practical guide — Ben Holmes, Warp" | Ben Holmes, DevRel at Warp |

Video 1 is about agent-facing CODE documentation (OpenWiki, an open-source CLI that generates
and maintains docs for a codebase; adjacent to AGENTS.md / llms.txt). Relevant to LEGION's own
docs layer at most; not the notes system Kevin pointed at.

**Video 2 is the one.** Talk from an August 2026 conference. The system it shows, corroborated
by Holmes's own blog post ("Build a Self-Updating LLM Knowledge Base",
bholmes.dev/blog/llm-knowledge-bases/) and a ZenML LLMOps case study:

### The Ben Holmes system, characterized

- **Capture**: raw voice dictation via a LOCAL transcription model (Handy / Voice Ink), ~200
  wpm, dumped as plain markdown into a `raw/` directory. Zero metadata required at capture time.
  Capture is deliberately frictionless and messy.
- **Enrichment** (`/enrich-note` agent skill, run as a batch job): for each raw note the agent
  adds (a) tags drawn from a central `tags.md` registry - one file listing every tag with a
  one-line description, agents explicitly instructed to be RELUCTANT to coin new tags, because
  unconstrained agents proliferate tags; (b) source URLs via web research; (c) a `## Related`
  section of `[[wikilinks]]` found by grep-searching the whole vault; (d) an `enrichedAt`
  frontmatter timestamp for idempotency.
- **Wiki compilation** (`/refresh-wiki` skill, weekly): aggregates enriched notes into
  persistent wiki pages under `wikis/[topic]/` - entity pages (people, places, orgs, concepts),
  index pages, concept syntheses, contradiction detection between notes.
- **Scheduling**: nightly enrichment + weekly wiki refresh. Started local (Codex scheduled
  tasks), moved to cloud (Warp's oz.dev + Obsidian Sync + Obsidian headless CLI) after the
  local automations stopped running when he traveled without the laptop. Open-weight models
  (Kimi k2.6 cited) to keep token cost down.
- **Visualization**: generated HTML - a GitHub-style note "burndown" and a tag-clustered
  "thought constellation" graph.
- **The frame** (borrowed explicitly from Andrej Karpathy's llm-wiki gist, April 2026):
  *"Obsidian is the IDE; the LLM is the programmer; the wiki is the codebase."* Knowledge
  management as a COMPILATION step, not a retrieval step. Holmes's central claim: model quality
  is no longer the constraint - capture volume and workflow design are.

**What the write-ups do NOT establish**: behavior at 1000+ notes, error handling, quality
assurance of the enrichment, or cost at scale. The ZenML study says so outright. Evidence of
sustained use is real but thin: overnight execution logs shown in the talk, completed wikis on
two domains, "daily paper"-style updates. Months, not years.

Sources:
- https://bholmes.dev/blog/llm-knowledge-bases/ (primary, Holmes's own write-up)
- https://www.zenml.io/llmops-database/building-llm-powered-knowledge-management-systems-for-personal-note-taking
- https://daily.dev/posts/llm-knowledge-bases-a-practical-guide-ben-holmes-warp-ug9sgsrdf
- https://finance.biggo.com/news/e8b981bf874027ef

## 2. Landscape triage: signal vs slop

Kevin's prior: "mostly ai influencer hype/slop." Correct as a base rate. The triage test that
actually separates them: **who does the gardening.** Pre-AI PKM (PARA, Zettelkasten, BASB)
died for most people because the human had to do the linking, tagging, and reorganizing - the
"second job" problem, documented across the Zettelkasten forums and PKM blogs for years
(zettelkasten.de, ptkm.substack.com). The ONLY genuinely new thing AI adds is that the model
can do the maintenance. Anything that still needs the human to file, link, and review weekly is
2020 PKM with a chat box, whatever the landing page says.

### Signal (three patterns, named)

**S1. The llm-wiki pattern (Karpathy gist -> Holmes/Warp, above).**
- What: messy capture -> scheduled agent enrichment (controlled tags, resolved links, sourced
  facts) -> compiled wiki pages as a durable artifact agents navigate instead of re-deriving.
- Requires: markdown store the agent can grep, a skill/prompt file, a scheduler, tag registry.
  No new infrastructure category.
- Evidence of sustained use: Holmes's own logs and wikis (months); Karpathy's gist spawned a
  wave of independent reimplementations (dev.to, multiple Medium/Substack builds against
  Obsidian and codebases) - the pattern replicates, which slop does not.
- Honest weakness: nobody has published what happens at scale, and the enrichment quality is
  unaudited by construction.

**S2. Agent memory layers: Mem0, Letta, Zep.** Three incompatible bets:
- **Mem0**: passive fact extraction from conversations, vector + light graph, scoped layers
  (session/user/org). Biggest adoption (~48K stars). LongMemEval ~49%.
- **Letta** (MemGPT lineage): the AGENT manages its own memory tiers with tools - OS paging
  model. Architecturally interesting, heaviest to run.
- **Zep**: TEMPORAL knowledge graph - entities as nodes, facts as edges **with validity
  intervals**, so it represents facts changing over time instead of overwriting. Best
  LongMemEval number (~63.8%).
- Caveat, stated plainly: those benchmark numbers circulate through vendor posts and SEO
  comparison farms (stork.ai, niteagent, datapace - most of the "vs" articles ARE the slop);
  treat them as directional, unverified. One recurring claim worth keeping regardless of
  source: vector-only retrieval precision degrades past a few hundred memory entries without
  temporal/graph structure.
- Verdict for LEGION: **do not adopt any of them.** LEGION already has the parts that matter
  (consolidation, reflection, importance/decay, audit trail, embedding slots with model
  tagging). The one idea worth STEALING is Zep's validity intervals - supersede, never delete -
  which is also exactly LEGION's existing audit posture extended to the memory rows themselves.

**S3. RAG-over-notes that shipped and survived: Khoj.** Self-hostable, open source, active
(2.0 beta, March 2026), real Obsidian plugin, scheduled automations, local-LLM capable. It is
the existence proof that chat-over-your-own-markdown works as a product. Reor: local-first
Obsidian-like with auto-linking via embeddings - interesting, low activity signal. Rewind
pivoted to the Limitless pendant (hardware capture); the capture-everything wing of this space
keeps pivoting, which is itself evidence.

### Slop (named)

- **The "vs" comparison-farm articles** on memory layers - content marketing wearing a
  benchmark.
- **PARA/BASB repackaged with "AI"** - courses and templates where the human still does the
  filing. The PKM community's own literature documents the abandonment pattern ("are we
  spending so much time maintaining our second brain that it becomes a second job?" -
  apragmaticmind.com; practitioners publicly dumping PARA - zettelkasten forum threads).
- **"Chat with your notes" as the whole product** - retrieval without compilation. The chat
  answer evaporates; nothing compounds. This is the exact failure Karpathy's frame names.
- **Knowledge-graph screenshots as proof** - the Obsidian graph view has been a vanity metric
  since 2020. Holmes's constellation is the same thing, prettier. Ignore graphs as evidence;
  ask what the agent DOES with the structure.

## 3. Mapping onto LEGION

Ground truth used (read, not assumed): `engine/notes/NotesAspectSeeder.kt` (Notes aspect =
one `Item` record type: text/done/reminder/repeat - it is a TODO/reminder store, not a notes
store), `data/local/FieldDef.kt` (FieldType has TEXT, CHOICE, MULTI_SELECT_CHOICE, and
REFERENCE with RecordStore-enforced integrity), `data/local/CompanionMemory.kt` (importance,
decay, syncId, embeddingVector+embeddingModel), `.scratch/backend-erp/map.md`.

### 3a. Wiki notes as an engine record type

**Recommendation: a second record type `Note` on the EXISTING Notes aspect, not a new
aspect.** The aspect is the module; prose notes and checklist items are one module's two
record types, same as an ERP.

Fields, in engine vocabulary (all addable as FieldDef rows, no Room migration - the engine's
designed growth path, per the FIELD_LOGGED_AT precedent in NotesAspectSeeder):

| Field | FieldType | Notes |
|---|---|---|
| `title` | TEXT, required | The wikilink target - links resolve by title, like Obsidian |
| `body` | TEXT | Markdown, `[[Title]]` wikilinks inline. Voice-dictatable raw capture |
| `tags` | MULTI_SELECT_CHOICE | The CHOICE config IS the tags.md registry: a controlled vocabulary the enrichment agent must pick from and is reluctant to extend. Engine already serializes choice options in FieldDef.config |
| `enrichedAt` | DATETIME | Idempotency marker, straight from Holmes |
| `sourceUrl` | TEXT | Enrichment-researched source, optional |

**Links: derive, don't hand-model.** `[[wikilinks]]` are many-to-many; REFERENCE is a scalar
field, so modeling edges as reference fields means either N link-fields or a `NoteLink` record
type with two REFERENCE fields per edge. Do the latter ONLY if edges need to be first-class
(queryable, typed). Simpler and truer to the pattern: body text is the source of truth for
links; a nightly enrichment pass parses `[[Title]]`, resolves against `Note.title`, and writes
the resolved edges (a `related` JSON field, or NoteLink records if first-class). Edges are
DERIVED data - rebuildable from bodies, so losing them costs one enrichment run. That keeps
capture (voice: "note: ...") requiring zero structure, which is the whole point.

**The enrichment pass is LEGION-shaped already.** It is the consolidation/reflection cadence
pointed at notes: a scheduled SubAgent one-shot per un-enriched note (tags from the registry,
link resolution, optional source lookup), then a weekly synthesis pass. On the Supabase move
this becomes trivially better: once the backend is the source of truth, enrichment can run
from ANY surface (the Windows box, a scheduled function) - which is precisely the failure that
forced Holmes to the cloud (local automations died when the laptop traveled).

**§4 posture, stated so nobody re-litigates it:** notes are user-AUTHORED content, not
ingested documents - there is no printed total, so the gate does not apply to the note itself.
But everything the enrichment agent ADDS (tags, links, sources, syntheses) is LLM-derived and
must be (a) provenance-separated from what Kevin dictated, (b) reversible - never rewrite the
user's body text, only add structured fields around it. Same shape as pantry macros: the
agent's contribution is an annotation, never asserted as the user's own words. Wiki synthesis
pages, if built, are derived artifacts - regenerable, never the store.

### 3b. Cross-interface memory (beyond "move the table")

Moving companion_memories to Supabase is necessary and mostly ready (`syncId` exists,
`embeddingModel` discipline exists). What "Alfred on Windows remembers what Alfred on the
phone learned" needs BEYOND the move:

1. **The audit trail moves WITH it, atomically.** A memory whose provenance stayed on the
   phone is unauditable from the laptop. MemoryAudit rows ship in the same migration.
2. **One writer, server-side.** RecordStore is the engine's only writer locally; the memory
   write path needs the same single choke point as a Supabase RPC (map ticket 03's shape), or
   two surfaces consolidating independently will duplicate and contradict.
3. **Supersession, not deletion (the one Zep idea worth stealing).** Add
   `supersededAt`/`supersededBySyncId` so a corrected fact retires its predecessor instead of
   overwriting it. Facts change over time; the forgetting/decay layer already leans this way,
   and it makes cross-device conflict resolution append-only - the same lesson as the Drive
   CAS finding (last-write-wins silently loses rows).
4. **Consolidation/reflection run ONCE, somewhere.** Two devices each running nightly
   consolidation against the shared table is a race. Either the backend schedules it or one
   surface owns the cadence; decide in ticket 01 (what the backend owns).
5. **Retrieval stays per-surface.** Embeddings are already model-tagged; each surface queries,
   nothing pre-syncs "relevant" subsets.

### Top recommendation

Adopt the **llm-wiki pattern, not any product**: `Note` record type on the Notes aspect
(title/body-with-wikilinks/registry-tags/enrichedAt), links derived by a scheduled SubAgent
enrichment pass, enrichment output provenance-separated from dictated text. It reuses the
engine's FieldDef growth path, the existing SubAgent + consolidation cadence, and gets
strictly better under the Supabase move. No new vendor, no new infrastructure category.

## Assumptions ledger

- Video titles: **traced** (YouTube pages via WebFetch; pages returned title only).
- Video 2 system mechanics: **traced** to Holmes's own blog post and the ZenML case study,
  NOT to the video - the footage was not watched, and channel/date/description were not
  recoverable from the page.
- Video 1 speaker (Brace Sproul / LangChain): **reasoned** from a search snippet, single
  source, unverified.
- Mem0/Letta/Zep architecture characterizations: **traced** to multiple sources; the
  LongMemEval numbers and the "degrades past ~500 entries" claim: **reasoned/unverified** -
  they circulate through vendor and SEO content.
- PKM abandonment pattern: **traced** to community sources (zettelkasten.de, forum threads,
  apragmaticmind) - qualitative, no hard longitudinal data exists.
- Repo claims (Notes aspect shape, FieldType roster, CompanionMemory fields, map contents):
  **traced** - files read this session.
- "FieldDef rows need no Room migration": **traced** to NotesAspectSeeder's FIELD_LOGGED_AT
  doc comment; not re-verified against RecordStore.
- Feasibility of `Note` as a record type (TEXT body size limits, grep-equivalent search over
  record payloads): **reasoned** - not checked against PayloadCodec or query paths.
