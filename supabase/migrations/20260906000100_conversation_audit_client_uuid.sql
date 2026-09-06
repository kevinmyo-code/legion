-- LEGION: `conversation_audit` gets a client-minted identity.
-- Depends on: 20260829000100_obd_samples_and_conversation_audit.sql
-- UNAPPLIED as of writing (2026-09-06). Kevin applies this one; nothing here has run.
--
-- ## Why
--
-- `conversation_audit` was keyed on `(device_id, local_id)`. `device_id` is the phone's ANDROID_ID
-- and `local_id` is Room's AUTOINCREMENT rowid. The rowid restarts at 1 whenever the phone's table
-- is emptied; ANDROID_ID does not change. On 2026-09-03 that happened, and the 142 rows the phone
-- recorded afterwards carried local ids 1..142 - ids this table had already issued, in August, to
-- 142 completely unrelated rows. The uploader posts `on conflict do nothing`, so PostgREST matched
-- the new rows against the old ones, discarded all 142, and returned 201.
--
-- Nothing failed. Nothing logged. The phone advanced its upload watermark over rows the server had
-- never seen, and reported `uploaded=142`. Three days of the only durable record of what a tool
-- call actually did - the table that exists because of the "it said 142k" incident - were sitting
-- on one phone with a 14-day delete timer already counting down on them.
--
-- A rowid is not an identity. A client-minted UUID is, and it is what every other synced table in
-- this project already uses (`origin_guid`). This table was the last one keyed on a local rowid.
--
-- ## What this does
--
-- 1. Adds `client_uuid`, NULLABLE. It has to be nullable: the 277 rows already here were written
--    before the phone minted one, they are real evidence, and there is no honest value to invent
--    for them. NULL means "recorded before this column existed", not "unknown" - and see the
--    partial index below for why NULL is safe rather than merely tolerated.
--
-- 2. A UNIQUE index over it, PARTIAL (`where client_uuid is not null`). Postgres would allow
--    multiple NULLs under a plain unique index anyway, but stating the predicate makes the intent
--    checkable rather than incidental: exactly the pre-existing rows are exempt, every row from
--    here on must carry a distinct one. This becomes the uploader's `on conflict` target.
--
-- 3. Narrows `conversation_audit_device_row_idx` to the same pre-UUID rows, and this step is NOT
--    optional - see the section below, which is the one thing to read before applying.
--
-- ## READ THIS BEFORE APPLYING: step 3 is what lets the 142 rows through
--
-- The brief for this change said to leave `(device_id, local_id)`'s unique index in place, on the
-- grounds that it stops being the upsert key but is still true of the old rows. The first half is
-- right and the second half is the problem: it is still true of the old rows AND still enforced
-- against new ones.
--
-- `insert ... on conflict (client_uuid) do nothing` only swallows conflicts on the target it names.
-- The 142 rows waiting on the phone carry `device_id = <the A25>` and `local_id` 1..142, and this
-- table already holds `(<the A25>, 1..142)`. So every one of them violates
-- `conversation_audit_device_row_idx`, which the ON CONFLICT clause does not cover, and Postgres
-- raises `duplicate key value violates unique constraint` - a 409 that fails the whole batch.
--
-- Left in place, this index does not merely fail to help; it blocks the repair entirely. The
-- uploader would retry forever, honestly reporting 142 rows pending and never sending them.
--
-- Narrowing it to `where client_uuid is null` keeps the invariant exactly as true as it ever was
-- for the 277 rows it was written for - no old row loses a guarantee, nothing is dropped, nothing
-- is rewritten - while letting rows that carry a real identity be governed by that identity
-- instead. The alternative (dropping it outright) discards a true statement about the old rows for
-- no gain.
--
-- If you would rather keep the old index unnarrowed, then the 142 rows cannot be uploaded under
-- their current local ids at all, and that needs a different decision, not a different migration.
-- ---------------------------------------------------------------------------------------------

alter table public.conversation_audit
    add column if not exists client_uuid text;

comment on column public.conversation_audit.client_uuid is
    'The row identity minted by the phone that recorded it (Room ConversationAudit.clientUuid), and '
    'the uploader''s on-conflict target. NULL only on rows written before 2026-09-06, when this '
    'table was keyed on (device_id, local_id) - a pair that stopped identifying anything the moment '
    'the phone''s AUTOINCREMENT rowid restarted, silently discarding three days of audit rows. A '
    'rowid is not an identity; this is.';

create unique index if not exists conversation_audit_client_uuid_idx
    on public.conversation_audit (client_uuid)
    where client_uuid is not null;

-- Step 3. The old key, narrowed to exactly the rows it was written for. See the section above.
drop index if exists public.conversation_audit_device_row_idx;

create unique index if not exists conversation_audit_device_row_idx
    on public.conversation_audit (device_id, local_id)
    where client_uuid is null;

comment on table public.conversation_audit is
    'Every exchange with the assistant, for auditing its behaviour. Keyed on client_uuid, minted by '
    'the phone that recorded the row - a conversation row is what ONE phone heard and said, not a '
    'shared fact, and two devices can both hold turn 41 with both real. It was keyed on '
    '(device_id, local_id) until 2026-09-06; local_id is an AUTOINCREMENT rowid that restarts when '
    'the phone''s table is emptied, so that pair silently collided with unrelated older rows and '
    'three days of uploads were discarded as duplicates. Read-through redaction already happened on '
    'the phone at write time (CLAUDE.md section 7), so protected content was never stored here or '
    'there.';
