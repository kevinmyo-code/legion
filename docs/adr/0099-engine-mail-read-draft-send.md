---
status: accepted
decided: 2026-11-17
decided-by: Kevin
source: "[[decisions#2026-11-17 - The engine reads, drafts and sends Kevin's mail, read-through only (Kevin)]]"
tags: [adr]
---

# 99. The engine reads, drafts and sends Kevin's mail, read-through only

## Standing

**The engine has Gmail read, draft and send, through a `mail` specialist.** These safeguards are binding and enforced in code:

1. **Mail content is read-through only.** It is never stored: not in turn state, checkpoints, traces, eval candidates, member memory, baselines or change events.
   - A trace records only that a mail tool ran, its outcome and its timing. The arguments are dropped, because a search query can be personal.
   - Later turns see "mail read; content not kept".
   - The trace's final text for a mail turn is replaced.
2. **Nothing acts in a turn that read mail.** Reading mail taints the turn ([[0090-no-write-after-third-party-text]]). Every write, draft, send, relay action or memory write in that turn refuses in words. The mail specialist has no household-row, memory or device tools at all.
3. **A send happens only after a read-back and his spoken yes in a later turn.**
   - The read-back is recipient, subject and gist.
   - The send gate checks four things: the draft was made in this conversation, the sending turn is not the drafting turn, his own words say send with no hedge, negation or question, and the sending turn is not tainted.
   - This is the gate of [[0086-alfred-drafts-email-kevin-sends]], moved to the engine.
4. **Mail is refused to some callers.** Never from a scheduled or background agent, never over `/mcp`, never for the recruiter role.
5. **He grants access himself** on Google's consent screen. The refresh token lives in Secret Manager, never in Postgres or logs.
6. **The Claude worker gets none of this:** no token, no tool. Its read-only Gmail carve-out in CLAUDE.md section 7 is unchanged.

## Context

Kevin: *"can we give the engine access to my mail? read write draft send"*.

Mail on the phone ([[0086-alfred-drafts-email-kevin-sends]]) would otherwise need a phone brain, and the phone's brain retires ([[0095-alfreds-brain-runs-on-the-engine]]).

## Decision

- **Scopes:** `gmail.readonly` and `gmail.compose`, the same two the phone asks for today. `gmail.send` is not needed, because `compose` sends drafts.
- **The OAuth client** is a web client in the household's own Google Cloud project, so clone-and-run holds.
- **Mail gets its own specialist, not a concierge capability.** Isolating its allowlist is a code guard, not a prompt.
- **A reply is composed from his own words in the drafting turn.** The engine fills only the headers from the thread. Thread text is not kept across turns, so a reply cannot quote it unless he dictates it.

## Consequences

- Both scopes are restricted. While the OAuth app stays in Testing status, the grant lapses every seven days (as [[0086-alfred-drafts-email-kevin-sends]] records). The engine then says in words that mail access lapsed, and the web app re-grants. Whether an unverified production app avoids the lapse is not verified here.
- The engine's mail work starts with Kevin granting consent once at a keyboard.
- `tests/test_engine_mcp.py`'s assertion that no engine tool reads mail is replaced by tests of these safeguards when the mail stage is built.
- Amends [[0086-alfred-drafts-email-kevin-sends]]: the `email` tool moves from the phone to the engine, and its gates move with it.
- Amends CLAUDE.md section 7: read-through content may be read on the engine, still never stored.
