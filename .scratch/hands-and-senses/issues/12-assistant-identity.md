# Write the assistant's actual voice

Type: prototype
Status: open
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
