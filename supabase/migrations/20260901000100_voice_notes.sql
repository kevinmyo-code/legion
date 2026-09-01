-- LEGION voice-notes, ticket 02: "Where a voice note lives, on the phone and on the server."
-- Ticket: .scratch/voice-notes/issues/02-the-store.md
-- Decision: docs/adr/0041-a-recording-kevin-starts-is-first-party.md
-- Depends on: 20260825000200_conventions.sql (touch_updated_at, apply_household_rls)
--
-- Kevin turns a recording on before a meeting or a solo thought, talks, turns it off, and this is
-- what is left behind server-side once ticket 03 uploads it: the title, the summary and the
-- verbatim transcript. **The audio itself is NOT uploaded** - ticket 02's own words, "the server
-- holds text; the file stays on the phone." That is a real, deliberate asymmetry with
-- data/local/VoiceNote.kt: the Room row's `audioPath` has no counterpart column here at all,
-- because there is nothing for it to point at server-side.
--
-- Authored, not gated, same posture as public.events (`20260825000400_aspect_dates_notes_merged.sql`):
-- a voice note is freely renamable and deletable by Kevin, so it gets touch_updated_at and NOT the
-- immutability trigger (private.forbid_mutation_of_facts is never attached to this table).
--
-- **provenance is deliberately `text`, not `public.provenance`.** That shared enum
-- (DETERMINISTIC / LLM_RECONCILED / UNRECONCILED / USER) describes a row's relationship to CLAUDE.md
-- section 4's NUMERIC reconciliation gate. ADR 0041 replaces that gate wholesale for a voice note
-- with the anchor CHAIN instead (summary anchored by transcript, transcript anchored by audio) -
-- a different guarantee than any of those four words claims, and none of them is true here:
-- LLM_RECONCILED would assert a numeric gate ran and passed (it never runs), UNRECONCILED would
-- borrow rule 7's transient/superseded-on-a-later-file machinery (nothing here is ever superseded
-- by a later file), USER would misdescribe a model-authored transcript as hand-typed, and
-- DETERMINISTIC is simply false - ticket 02's own words, "Never DETERMINISTIC - nothing here is."
-- The single legal value is enforced by CHECK rather than by widening the shared enum, so the
-- reconciliation-gate vocabulary is never asked to mean something it does not. See
-- data/local/VoiceNote.kt's VoiceNoteProvenance doc comment for the identical reasoning on the
-- Room side.

create table if not exists public.voice_notes (
    id           uuid primary key default gen_random_uuid(),

    started_at   timestamptz not null,
    -- Set the moment a recording is known to have genuinely stopped - an ordinary user-initiated
    -- stop, or the app gracefully winding down after losing the microphone to a higher-priority
    -- claimant. Null only when the process died mid-recording and nothing ever observed a stop
    -- time. Mirrors data/local/VoiceNote.kt's ended_at doc comment exactly - read THAT column's
    -- comment for why `interrupted`, not this column's nullness, is the one callers must check.
    ended_at     timestamptz,

    title        text,
    summary      text,
    -- Verbatim, LLM-derived (ticket 03). Nullable: a recording is uploaded before it is ever
    -- transcribed, and a failed transcription attempt (ticket 03's own "must not do" list) must
    -- leave this null rather than a partial or invented transcript.
    transcript   text,

    -- SOLO or MEETING (Kevin's own two cases, ticket 02's own words).
    kind         text        not null check (kind in ('SOLO', 'MEETING')),

    -- See this file's header comment for why this is not public.provenance.
    provenance   text        not null default 'LLM_DERIVED' check (provenance = 'LLM_DERIVED'),

    -- Explicit, not inferred from ended_at's nullness - see ended_at's own comment above.
    interrupted  boolean     not null default false,

    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now(),
    deleted_at   timestamptz,

    -- A summary anchored by nothing is exactly the ADR 0041 failure this ticket exists to prevent:
    -- a note is never summarized before it has a real transcript to summarize FROM.
    constraint voice_notes_summary_needs_transcript check (summary is null or transcript is not null)
);

create index if not exists voice_notes_started_at_idx on public.voice_notes (started_at) where deleted_at is null;

comment on table public.voice_notes is
    'A recording Kevin deliberately started and stopped (ADR 0041) - title, summary and verbatim '
    'transcript, synced like any other LEGION record. The audio file itself stays on the phone; '
    'this table holds text only. Authored, not gated: freely renamable and deletable.';

drop trigger if exists touch_updated_at on public.voice_notes;
create trigger touch_updated_at
    before update on public.voice_notes
    for each row execute function private.touch_updated_at();

select private.apply_household_rls('public.voice_notes');
