-- LEGION backend-erp: the last two phone-only tables reach the server.
-- Ticket: .scratch/backend-erp/issues/14-a-vehicle-row-is-co-owned.md (its open question) and
--         .scratch/backend-erp/issues/24-do-the-conversation-logs-reach-the-server.md
-- Ruled 2026-08-29 (Kevin): "obd samples also goes to supabase. same for conversations for audit."
--
-- Both reverse ticket 01 ruling 10, which kept OBD live state and audio-adjacent data phone-only.
-- That ruling was about VOLUME and PRIVACY, not principle, and the volume argument has been
-- measured rather than assumed: 26,059 obd_samples on Kevin's device on 2026-08-29, up from 18,694
-- on 2026-08-16 - about 600/day. At roughly 100 bytes a row that is ~22 MB/year against a 500 MB
-- free tier. It fits, comfortably, for years. conversation_audit is 197 rows.
--
-- =============================================================================================
-- IDENTITY, AND WHY THE TWO TABLES ANSWER IT DIFFERENTLY
-- =============================================================================================
-- Neither source table has a portable identity. Every other synced table carries a `syncId` or an
-- `origin_guid`; `OdbSample` and `ConversationAudit` carry only a local autoincrement `id`, which
-- is a per-DEVICE counter and would collide the moment a second phone uploads. So each needs a key,
-- and the honest key is different for each.
--
-- **obd_samples: (vehicle_id, pid, recorded_at).** A sample IS its car, its PID and its instant -
-- two phones observing the same car at the same millisecond observed the same fact, and storing it
-- twice would be wrong, not merely wasteful. Re-uploading is therefore free: `on conflict do
-- nothing`. The theoretical collision (two genuinely distinct readings of one PID in the same
-- millisecond) is not physically meaningful for an ELM327 polling loop.
--
-- **conversation_audit: (device_id, local_id).** A conversation row is NOT a shared fact - it is
-- what one phone heard and said. Two phones can hold turn 41 and both are real. So the key carries
-- which device produced it, and `turn_seq` stays an ordinary column for regrouping an exchange.
--
-- **This changes what DeviceId MEANS, and `engine/DeviceId.kt`'s own doc comment must be corrected
-- in the same commit.** It currently states that the value "never leaves the device, and nothing
-- here transmits it anywhere", which was true when it only scoped widget layouts. It is about to be
-- false, and a comment that promises what the code no longer does is the exact shape that has bitten
-- this codebase twice (`EventReplicaDao.upsert`'s defeated guarantee, `GeneratedFormScreen`'s
-- "PHOTO ON FILE"). ANDROID_ID is a device identifier; sending it to the household's own project is
-- fine, but it must be stated rather than discovered.
--
-- UNAPPLIED as of this commit - no CLI or project credentials in this environment.

-- ---------------------------------------------------------------------------------------------
-- obd_samples. Append-only telemetry. NOT part of the immutable-gated set: the immutability
-- trigger exists for gated aspects (ledger, pantry) where a row asserts a verified fact. A
-- telemetry sample asserts an observation, and observations are corrected by newer observations,
-- not by editing old ones - so no trigger, and no reversal machinery either.
-- ---------------------------------------------------------------------------------------------
create table if not exists public.obd_samples (
    id           uuid        primary key default gen_random_uuid(),
    vehicle_id   uuid        not null references public.vehicles (id) on delete restrict,
    pid          text        not null check (length(trim(pid)) > 0),
    value        double precision not null,
    unit         text        not null,
    recorded_at  timestamptz not null,
    lat          double precision,
    lng          double precision,
    created_at   timestamptz not null default now()
);

create unique index if not exists obd_samples_natural_key_idx
    on public.obd_samples (vehicle_id, pid, recorded_at);

-- The query shape the phone already needed an index for: 18,694 rows with zero indexes made every
-- telemetry read a full scan (Room v25, 2026-08-16). Same access pattern server-side.
create index if not exists obd_samples_vehicle_time_idx
    on public.obd_samples (vehicle_id, recorded_at desc);

comment on table public.obd_samples is
    'Decoded OBD readings. Append-only; keyed on (vehicle_id, pid, recorded_at) because a sample IS '
    'its car, its PID and its instant - re-uploading the same reading is a no-op by construction. '
    'Not immutability-triggered: a telemetry observation is superseded by a newer one, never edited.';

-- ---------------------------------------------------------------------------------------------
-- conversation_audit. What the assistant heard and said, for auditing its behaviour later.
--
-- **The read-through rule is already satisfied at the SOURCE, and that is why this table is safe
-- to exist at all.** CLAUDE.md section 7 forbids persisting anything other people wrote to Kevin.
-- `ConversationAudit`'s redaction is per-ROW and happens at WRITE: a tool in
-- `LiveToolbox.EPISODIC_EXCLUDED_TOOLS` has its RESULT replaced before insert, while the fact that
-- the tool ran survives. So the phone's table never held the protected content, and neither can
-- this one. Section 7's own words: the guarantee is "that it was never stored, not that something
-- remembered to exclude it."
--
-- `redacted` is carried as its own column rather than inferred by string-matching the placeholder,
-- exactly as the Room entity does.
-- ---------------------------------------------------------------------------------------------
create table if not exists public.conversation_audit (
    id            uuid        primary key default gen_random_uuid(),
    device_id     text        not null check (length(trim(device_id)) > 0),
    local_id      bigint      not null,
    turn_seq      bigint      not null,
    -- LOWERCASE, and this was got wrong once. The entity's doc comment says "USER, COMPANION, or
    -- TOOL_RESULT" and this constraint was written from that prose; the column actually stores
    -- 'user'/'companion'/'tool_result'. The first real upload was rejected by
    -- conversation_audit_kind_check on 2026-08-29. Read the DATA, not the description of it.
    kind          text        not null check (kind in ('user', 'companion', 'tool_result')),
    tool_name     text        not null default '',
    args          text        not null default '',
    content       text        not null,
    redacted      boolean     not null default false,
    vehicle_id    text        not null default '',
    recorded_at   timestamptz not null,
    created_at    timestamptz not null default now()
);

create unique index if not exists conversation_audit_device_row_idx
    on public.conversation_audit (device_id, local_id);

create index if not exists conversation_audit_turn_idx
    on public.conversation_audit (device_id, turn_seq);

comment on table public.conversation_audit is
    'Every exchange with the assistant, for auditing its behaviour. Keyed on (device_id, local_id) '
    'because a conversation row is what ONE phone heard and said, not a shared fact - two devices '
    'can both hold turn 41 and both are real. Read-through redaction already happened on the phone '
    'at write time (CLAUDE.md section 7), so protected content was never stored here or there.';

comment on column public.conversation_audit.vehicle_id is
    'The active vehicle at the time, as the phone''s own obdMac string - CONTEXT, never a filter, '
    'and deliberately not a FK to public.vehicles: an audit row must survive a vehicle being '
    'deleted, and it records what was true then rather than what still exists now.';

select private.apply_household_rls('public.obd_samples');
select private.apply_household_rls('public.conversation_audit');
