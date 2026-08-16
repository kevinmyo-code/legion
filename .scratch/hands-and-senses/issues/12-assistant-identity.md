# Write the assistant's actual voice

Type: prototype
Status: closed - premise falsified, remainder BACK BURNER (Kevin, 2026-08-16)
Blocked by: -

## Question

`ai/AssistantIdentity.kt` is placeholder copy by its own doc comment; CLAUDE.md lists "the
assistant's actual voice" as not built. Persona traits (`PersonaTraits.kt` stages) are modifiers
layered on a core identity that does not exist yet. This is a writing task with a prototype shape:
draft it, react to it, iterate.

1. **Drafts to react to.** Two or three full identity-clause drafts in the Alfred/JARVIS register
   band CLAUDE.md fixes (competent, dry, useful - a tool with a personality): e.g. one drier
   Alfred, one warmer JARVIS, one sparser minimal. Real, complete system-prompt text, not
   descriptions of text. Kevin reads them aloud against real tool-call transcripts, picks, edits.
2. **What the identity clause owns vs what stages modify.** The boundary between
   `AssistantIdentity` (invariant: name, register, honesty rules, how estimates are spoken, how
   refusals sound) and `PersonaTraits` fragments (warmth, humor, formality). A stage must not be
   able to contradict the invariants - which lines are load-bearing?
3. **The rails, in the text itself.** The safety amendment allows feeling and warmth; the
   identity text must still carry: no compulsion mechanics, estimates said as estimates,
   `CrisisDetector` overrides everything and the character stops. These live IN the prompt, not
   only in CLAUDE.md.
4. **Name.** Is the assistant called Alfred (the register word used everywhere), LEGION, or
   something else? Kevin's call; it appears in every spoken sentence and in `Voices` sample copy.

Asset: drafts land in this effort's `research/12-identity-drafts.md` (or directly as a
`AssistantIdentity.kt` candidate diff), linked from the Answer.

## Answer

Closed 2026-08-16 without doing the drafting work, because **the ticket's premise is false.** All
code facts below are `traced` against `app/src` on 2026-08-16.

### The voice was already written

`ai/AssistantIdentity.kt:8` reads "**No longer placeholder.**" It is a 67-line resolver holding no
register copy. The copy lives in **`ai/Personas.kt`** (159 lines): `ALFRED` (`:58-103`) and
`DOROTHY` (`:105-152`), each with a full identity clause, a `delivery` accent/idiom block, a
compressed `shortClause` for sub-agent one-shots, and five greetings. `AssistantIdentity` exposes
`clause` / `delivery` / `shortClause` / `greeting`, all resolving through
`personaFor(CompanionProfile.persona(context))`.

The only surviving placeholder comment in the whole identity area is
`ai/OnboardingFlow.kt:104-106`. That one is still real and is a separate gap.

CLAUDE.md §1, §6 and §10 all asserted the opposite. **Corrected in the same commit as this
resolution.** Third instance of the `repo-ahead-of-docs` lesson.

### Ticket item 4 (the name) was never a decision

**LEGION is the app; the thing Kevin talks to is a companion he names.** Kevin, 2026-08-16: "the
app is legion, the thing i talk to is configurable. midnight had a mechanism where users would
name their avatars." CLAUDE.md's "Alfred/JARVIS" is a **register band, not a name.**

That already works: `AssistantIdentity.withName` (`:60-65`) regex-replaces the persona's
`defaultName` with the driver's chosen name, so a profile can be Alfred's register wearing another
name. The picker ships - `ui/CompanionsScreen.kt` + `ui/companions/CompanionRows.kt:171-207` let a
profile choose name (free text), persona (radio over `BUILT_IN_PERSONAS`), and TTS voice (30
`CURATED_VOICES` with audition) independently.

### What is actually missing: freeform authoring. Kevin put it on the back burner.

Kevin, 2026-08-16: *"i used to let the user build the personality which basically were system
prompt edits... now im thinking if i want that + a few pre built personas"* -> then, after the
findings: **"put it back burner for now. we just keep alfred and dorothy."**

The Midnight AI mechanism is **ported, complete, and orphaned**. `ai/PersonaTraits.kt` (272 lines)
holds five staged questions (age, temperament, chattiness, humor, treats), five choices each with
an additive sentence fragment, `CUSTOM_KEY` for free text, and `assemblePersona()` (`:219-234`)
which builds the whole prose block. Its **only** caller is `CompanionProfile.savePersona()`
(`:269`), and `savePersona` **has no production caller** - the roster UI writes a persona KEY
(`CompanionsScreen.kt:136`). The stage machinery survives in the spoken onboarding menu
(`OnboardingFlow.kt:167-172`) and is reused by `VoiceStyle.kt`.

### The trap, recorded so a future session does not walk into it

**Do not simply re-wire `savePersona`.** `CompanionProfile.persona()` is **dual-typed** - a persona
key in the live path, prose in the legacy one (`CompanionProfileTest.kt:36-40` stores
`"You are Alfred."`) - and `personaFor()` falls back to `ALFRED` on any unrecognised string
(`Personas.kt:159`). Freeform prose written to that field is therefore **silently discarded and the
user gets Alfred**, with no error; the prose survives only in a blank-check at `AriaBrain.kt:248`.
A silent no-op wearing a personality.

### The kernel question, left open

Never put to Kevin, because the effort was deferred first. It is the real design question whenever
freeform returns: **the honesty rules currently live INSIDE each persona's own clause** - Alfred's
"you never pretend to know a number you do not have" (`Personas.kt:83-84`) is Alfred's line, not a
rail. A freeform author would simply not write it. Only the safety/crisis block is properly
separated and already declares that it overrides the persona and its tone
(`AriaBrain.kt:144-150`). The estimate rule and the no-overclaim rules are not.

So freeform authoring requires extracting an **immutable kernel** (honesty, how estimates are
spoken, refusals, crisis override) out of the two existing personas, leaving them as pure register.
That plus the field-type fix, the orphaned builder, a freeform UI, and precedence rules is why this
**graduates to its own effort, `persona-authoring`, when Kevin wants it** - not a ticket.

### Also found

**No tests exist** over `AssistantIdentity`, `Personas.kt`, `PersonaTraits.kt`, `FirstGreeting.kt`,
or `AriaBrain.assembleBase()`. The `withName` rename substitution and the base-prompt assembly
order are both untested. Not fixed here.

**Base system prompt is ~2,000-2,200 tokens** (`estimate`, from measured character counts), largest
contributor `sharedInstructions` at ~1,100. The identity clause is ~10-15% of it.
