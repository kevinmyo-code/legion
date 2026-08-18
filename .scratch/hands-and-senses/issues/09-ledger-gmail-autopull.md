---
map: hands-and-senses
ticket: 09
title: "Ledger auto-pull: statements walk from the inbox into the gate"
type: grilling
status: killed
status-detail: "Kevin, 2026-08-16, statements do not arrive in Gmail"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Ledger auto-pull: statements walk from the inbox into the gate

## Question

The google-account map ruled "Gmail as an ingestion source" out of scope, returnable as a fresh
effort - this is it. `gmail.readonly` (already granted) can search and fetch attachments; the
reconciliation gate does not care where a PDF came from. The pipeline is: find statement mail,
fetch the PDF attachment, hand it to `StatementDispatcher`, gate does the rest, `ingested_files`
dedups. Decide:

1. **Trigger.** Pull-only ("check my mail for statements") keeps the no-background-fetch rule
   pristine. Is that enough, or does the morning brief's statement module (if any) get to run the
   same search? Nothing here should invent a poller.
2. **The search.** App-owned query like the briefing precedent (`from:(bank senders)
   has:attachment filename:pdf newer_than:35d`), sender list curated by Kevin - or model-owned?
   App-owned is the trust answer; write the query.
3. **Attachment custody vs read-through.** The mail rule is read-through: bodies dropped, nothing
   stored. An attachment is DIFFERENT on purpose - the PDF becomes an ingestion input exactly like
   a file picked from Drive, and its rows land in Room behind the gate. State the boundary
   explicitly: attachment bytes in, gated rows stored, mail body and metadata dropped; the mail
   rule is amended for attachments only, nothing else.
4. **Failure and quarantine surfacing.** A fetched statement that fails the gate quarantines like
   any other; where does Kevin hear about it - same surface as Drive-folder ingestion
   (`.scratch/ledger-drive-ingestion/` built that story; zoom it, do not re-decide it)?
5. **Dedup.** `ingested_files` keys on what for a mail-sourced file (message id, attachment id,
   content hash)? Same statement arriving by mail AND Drive folder must not double-ingest.
6. **Tool budget.** One tool or a parameter on an existing ingestion tool? Write the description.

## Answer

**Killed 2026-08-16 by Kevin: "ledger gmail > kill it. my statements dont land in gmail."**

Killed, not archived. The whole pipeline was "find statement mail -> fetch the PDF attachment ->
`StatementDispatcher` -> gate", and the first step has no input. There is nothing to search for.
Unlike [Health Connect](11-health-connect-scope.md), which parks until a device appears, this one
does not park on anything Kevin is likely to change - he would have to move his banking mail to
Gmail, which is not a feature request, it is a life change.

**Nothing here was wrong on the merits.** The design premise held: `gmail.readonly` is granted, the
reconciliation gate does not care where a PDF came from, and `ingested_files` would have deduped.
It was a correct plan for a mailbox Kevin does not have.

**The existing SAF Drive-folder path remains the only ingestion route**, which is what
`.scratch/ledger-drive-ingestion/` built and what actually matches how Kevin gets statements.

**Consequence for two other tickets, flagged not decided:**

- Map settled decision 4 puts the **morning brief's news module** on "Kevin's newsletters via the
  existing Gmail tool". That assumes his Gmail carries newsletters worth reading. **Unverified** -
  it is the same class of assumption that just killed this ticket.
- [Inbox intelligence](18-inbox-intelligence.md) is entirely mail-derived (package tracking,
  staleness). Same dependency, same unverified assumption.

Both should confirm what Kevin's Gmail actually contains before a session is spent on either. See
the map's "Decisions so far" entry for this ticket.

**Revives only if** Kevin's statements start arriving as mail attachments.
