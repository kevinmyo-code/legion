# Ledger auto-pull: statements walk from the inbox into the gate

Type: grilling
Status: open
Blocked by: -

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
