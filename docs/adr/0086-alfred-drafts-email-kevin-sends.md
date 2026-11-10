---
status: accepted
decided: 2026-11-10
decided-by: Kevin
supersedes: []
source: "[[decisions#2026-11-10 - Alfred drafts email; Kevin checks it in Gmail or says send (Kevin)]]"
tags: [adr]
---

# 86. Alfred drafts email in Kevin's Gmail; it sends only on Kevin's own "send"

## Standing

**One `email` tool, on the phone only, with three actions.** `draft` saves a real Gmail draft and
sends nothing. `open` opens the Gmail app (at the draft if Gmail honours the link, else Gmail
itself) and says where the draft is. `send` sends a draft from this conversation, only after Kevin
says send. Dispatch is `service/EmailToolbox.kt`; the declaration is in
`service/tools/mail/Declarations.kt`.

## Context

Kevin, 2026-11-10: *"i want alfred to draft it, offer for me to check in gmail app on phone, while
also sending it if i say send without checking."* Until now the phone's Gmail grant was read-only
(`gmail.readonly`) and every mail tool said it could not send. A sent email cannot be unsent, so
this is the most consequential write Alfred has.

## Decision

- **A new, separate grant.** `gmail.compose` is asked for only by `email`, alongside
  `gmail.readonly`, as its own incremental request (`GmailAuth.COMPOSE_SCOPES`). The read tools
  keep asking for read-only alone, so a refused or lapsed compose grant never touches reading mail.
  With no grant, `email` drafts nothing, opens Google's approval screen, and says so.
- **The send gate is code** (`gmail/EmailSendGate.kt`). A send goes ahead only when: the draft id
  was made by `email` in this conversation (in-process, 15 minutes); the turn sending it is not the
  turn that drafted it (the read-back comes between); and Kevin's own words in that turn say send
  with no hedge, negation or question. A bare "yes" is not a send. The model's word is never enough.
- **"Sent" only on Gmail's success.** A 2xx from `drafts.send` with the sent message's id is the
  only path to `sent: true`. Any other ending checks whether the draft is still in Drafts and says
  what is known: still there means not sent; gone with no confirmation means unconfirmed, check
  Sent; no answer at all means not confirmed, check Gmail. It never says sent on a guess.
- **Never guess a recipient.** An address is used as given. A name resolves only when contacts,
  then recent mail senders, give exactly one address; two or more are read out to choose from, none
  is a question. Nothing is drafted until it resolves.
- **Read-through and logs.** `email` is in `EPISODIC_EXCLUDED_TOOLS` (a recipient lookup reads
  recent mail), so its result and Alfred's read-back that turn are redacted from LIVE_AUDIT and the
  conversation record. The tool's args are logged with `body` replaced by its length
  (`EmailToolbox.redactArgs`). Kevin's own spoken words log as they always have, and they may
  include what he dictated.
- **Hands path (ADR 0035):** the Gmail app itself, Drafts, where the draft can be edited, sent or
  deleted by hand.
- **Not the Alienware worker.** The Claude session task keeps its read-only Gmail allowlist
  (CLAUDE.md section 7 carve-out, 2026-11-04). Nothing here gives it a way to draft or send.

## Consequences

- A Google Cloud console step: `gmail.compose` must be added to the OAuth consent screen's scopes.
  It is a restricted scope, so in Testing status the grant lapses every seven days, like read-only.
- No public Gmail intent opens one draft. `open` tries `https://mail.google.com/mail/u/0/#drafts/<message id>`
  aimed at the Gmail app and falls back; the result only ever claims Gmail is open.
- The draft cannot be edited by voice; a change is a new draft (the old one stays in Drafts).
- The setup payload grows by one declaration; its protocol rides in the results, billed per call.
