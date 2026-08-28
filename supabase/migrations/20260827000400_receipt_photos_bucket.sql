-- LEGION backend-erp, ticket 09 (`.scratch/backend-erp/issues/09-backups-do-not-cover-files.md`),
-- the Storage half of ticket 01 ruling 10 as amended.
--
-- DatabaseSnapshot (app-side) stays database-only on purpose - see the ticket's "RULED 2026-08-26"
-- section. This migration is the thing that makes that ruling safe: a household-private Storage
-- bucket that receipt photos upload into, so a photo has a durable, replicated home even though
-- neither the on-device backup nor `pantry_receipts.source_image_path` (a local staging path,
-- deleted once the receipt is gated - see `PantryReceipt`'s own class doc) ever covers it.
--
-- **UNAPPLIED as of this commit** - written and reviewed, not run against any project. No CLI, no
-- credentials available in this session. Apply the same way as every other file here:
-- `supabase link --project-ref <ref> && supabase db push`, or paste into the dashboard SQL editor
-- (see 20260825000100_household_and_rls.sql's own header for the CLI-vs-dashboard tradeoff).
--
-- Idempotent, like every migration here: safe to re-apply.

-- ---------------------------------------------------------------------------------------------
-- 1. The bucket itself.
--
-- Private (`public = false`): every read goes through `downloadAuthenticated`, never a public URL,
-- matching the household-membership gate every other table in this project uses. `file_size_limit`
-- is set generously above what `PantryPhotoStore.save`'s 1600px/90-quality JPEG ever produces (a
-- receipt photo compressed that way is a few hundred KB, not several) purely as a sanity backstop,
-- not a tuned budget. `allowed_mime_types` is JPEG only because `PantryPhotoStore.save` is the
-- ONLY writer (`SupabasePhotoBackend.uploadReceiptPhoto`, `app/src/main/java/com/kevin/legion/
-- backend/SupabasePhotoBackend.kt`) and it always compresses to JPEG - there is no path that would
-- ever need to upload anything else here.
-- ---------------------------------------------------------------------------------------------
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('receipt-photos', 'receipt-photos', false, 10485760, array['image/jpeg'])
on conflict (id) do update set
    public              = excluded.public,
    file_size_limit     = excluded.file_size_limit,
    allowed_mime_types  = excluded.allowed_mime_types;

-- ---------------------------------------------------------------------------------------------
-- 2. RLS, and why `private.apply_household_rls` (20260825000200_conventions.sql) does NOT apply
-- here even though it is the standing convention for every other table.
--
-- That macro `alter table`s the target and grants/revokes at the TABLE level - correct for a table
-- this project owns outright (`public.receipts`, `public.places`, ...), because every row in it
-- belongs to exactly one aspect. `storage.objects` is different: it is Supabase-owned
-- infrastructure shared by every bucket in the project, present and future. A table-level REVOKE
-- or GRANT on it would reach buckets this migration knows nothing about (Supabase provisions the
-- role grants on `storage.objects` itself when the Storage extension is enabled), and a bucket
-- with no matching policy is already unreadable under RLS by default - there is nothing to add
-- privilege-wise, only a policy to scope.
--
-- So the household check is expressed as a `bucket_id = 'receipt-photos'` filter inside a POLICY
-- on `storage.objects`, one per operation this app actually performs (select/insert; update and
-- delete are intentionally NOT granted - see point 3 below). `private.is_household_member()` is
-- reused as-is: it is SECURITY DEFINER and schema-qualified, so it works identically here as it
-- does gating `public.receipts`.
-- ---------------------------------------------------------------------------------------------
drop policy if exists receipt_photos_select on storage.objects;
create policy receipt_photos_select
    on storage.objects
    for select
    to authenticated
    using (bucket_id = 'receipt-photos' and (select private.is_household_member()));

drop policy if exists receipt_photos_insert on storage.objects;
create policy receipt_photos_insert
    on storage.objects
    for insert
    to authenticated
    with check (bucket_id = 'receipt-photos' and (select private.is_household_member()));

-- ---------------------------------------------------------------------------------------------
-- 3. No update or delete policy, deliberately.
--
-- `SupabasePhotoBackend.uploadReceiptPhoto` uploads under a content-addressed path (the same
-- `content_sha256` the commit RPC already computes over the same bytes), `upsert`d so a retried
-- upload of identical bytes is a safe no-op rather than a conflict - that is the one case that
-- looks like an update and it is handled by upsert-on-insert, not a real UPDATE policy. A genuine
-- update (different bytes at the same path) or a delete would mean the app can silently replace or
-- remove a photo a receipt row still points at, which is the exact "quietly restores a row
-- pointing at nothing" failure this ticket exists to close, moved from the backup layer to the
-- storage layer. With no policy for either operation, both are refused outright under RLS's
-- default-deny, for every caller including the household - if a photo ever needs to go, that is a
-- deliberate, out-of-band decision, not something a client call can do by accident.
-- ---------------------------------------------------------------------------------------------

comment on policy receipt_photos_select on storage.objects is
    'Household members may read receipt photos; anon and non-members match no policy and are '
    'refused by RLS default-deny. Mirrors public.receipts'' own household_all policy, scoped to '
    'this one bucket rather than applied table-wide.';

comment on policy receipt_photos_insert on storage.objects is
    'Household members may upload receipt photos. No update/delete policy exists for this bucket - '
    'see this migration''s own section 3 comment for why that is deliberate, not an oversight.';
