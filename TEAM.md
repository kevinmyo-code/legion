# TEAM.md

Four seats. `CLAUDE.md` holds the rules; these files hold only what is specific to a seat.

| Agent | For |
|---|---|
| `builder` | Writing code |
| `auditor` | Reading code adversarially — defects, arithmetic, data integrity, diff review |
| `device` | Anything needing the real phone |
| `scout` | Read-only breadth search across code, library or maps |

Dispatch is the default, not an escalation (CLAUDE.md §8, standing). Judgement still applies: a
one-line fix does not need an agent.

## Rewritten 2026-09-01, and why it matters more than the tidying

The previous roster was six agents and 438 lines. Roughly half of `coding.md` was **factually
wrong** — it described three aspects when there were six, Room v3 when the schema was at v55, `ui/`
as a clean slate with no design language, "no backend, ever" after the Supabase backend shipped, and
PdfBox conventions two days after PdfBox was deleted.

**It also named the wrong phone.** Three separate agents in one session reported testing on a device
that is not the one attached. That was not hallucination — it was in their prompt, and they were
being faithful to it.

That is the whole lesson: **a stale instruction is not ignored, it is obeyed.** The volume was never
the problem; the rot was, and volume was how the rot hid.

## The rule that keeps it from happening again

**An agent file states no fact that can go stale.** No counts, no version numbers, no package maps,
no device models, no lists of what exists. Every wrong line found in the old roster had one of those
in it.

What an agent file may contain:

- **Its role**, and where its judgement ends and the orchestrator's begins.
- **Guardrails that were paid for** — the uninstall that destroyed the Keystore key, the 322 deleted
  comments, `adb shell cat` corrupting a binary pull. These are cheap to state and expensive to
  rediscover.
- **How to check something**, which stays true as the thing checked changes.
- **The reporting contract** — the assumptions ledger, tagging each claim `built` / `tested` /
  `traced` / `reasoned` / `on-device`. This has genuinely earned its place: it is how a
  confidently-worded inference gets caught before it is acted on.

Everything else the agent can look up, and a lookup is current where a description is not.

## Why four seats and not six

`bug-hunter`, `senior-dev` and `analyst` were three ways of saying *read this carefully and tell me
what is wrong*, and in practice the distinction never decided a dispatch — all three sat unused
through a session where `coding` was dispatched a dozen times. They are now `auditor`. `librarian`'s
retrieval half is what `scout` does; its filing half was always done inline.

Add a seat when a dispatch is genuinely ambiguous between two existing ones, not before.
