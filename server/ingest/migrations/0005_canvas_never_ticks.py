"""`public.upsert_canvas_task`, replaced again: Canvas never ticks anything
(backend-etl ticket 04; Kevin, 2026-09-28: "i'll manually mark things as done.
i just need to know what needs doing ... perhaps we can be a double check, like
i can manually tick, but the thing also says submitted in canvas").

This supersedes 0004's discussions-only rule. 0003 ticked a row from Canvas's
submission state and 0004 exempted discussions; now no row is exempt because
no row is ticked:

- a NEW row is inserted `done = false`, whatever Canvas says;
- an EXISTING row keeps `done`, `done_at` and `provenance` exactly: they are
  absent from every UPDATE here, so the poller can neither tick nor untick;
- every upsert writes `structured_meta.canvas_submitted` (a bool: the caller's
  `submitted`, the poller's `is_submitted` definition), beside the raw evidence
  (`submission_state`, `submitted_at`, `score`, `grade`, `late`, `missing`,
  `excused`) it already wrote. The web and the phone render it as "Canvas says
  submitted" (tickets 11 and 12); the tick is Kevin's.

Everything else is 0004's body unchanged: matching, sub-deadline ownership,
tombstones and resurrection, the `manual_completion` stamp on discussions and
not_graded placeholders (it no longer changes what happens to `done`, but the
rows keep saying what they are), and `canvas_is_discussion`, left in place.

Because `canvas_submitted` is a new key, the first live run after this updates
each Canvas row once, and `/api/changes` re-delivers them once. An unchanged
row after that is still not UPDATEd.

0003 and 0004 are applied to the live database and are never edited; this
replaces the function in place, and its reverse restores 0004's body exactly.
"""

import importlib

from django.db import migrations

_PREVIOUS = importlib.import_module("ingest.migrations.0004_discussions_are_ticked_by_hand")._CREATE

_CREATE = r"""
-- The body names public.events, which the pytest database only gains after
-- migrate; skip body validation so creation does not depend on it.
set local check_function_bodies = off;

create or replace function public.upsert_canvas_task(
    p_household uuid,
    p_task jsonb,
    p_read_at timestamptz
) returns jsonb
language plpgsql
set search_path = public, pg_temp
as $$
declare
    v_row          public.events%rowtype;
    v_found        boolean := false;
    v_origin       text := p_task->>'origin_guid';
    v_parent       text := p_task->>'parent_canvas_assignment_id';
    v_aid          text := p_task->>'canvas_assignment_id';
    v_evidence     jsonb := coalesce(p_task->'evidence', '{}'::jsonb);
    v_insert_meta  jsonb := coalesce(p_task->'insert_meta', '{}'::jsonb);
    v_starts       timestamptz := (p_task->>'starts_at')::timestamptz;
    v_submitted    boolean := coalesce((p_task->>'submitted')::boolean, false);
    v_manual       boolean := coalesce((p_task->>'manual_completion')::boolean, false);
    v_may_insert   boolean := coalesce((p_task->>'insert')::boolean, true);
    v_read         jsonb := jsonb_build_object('read_at', p_read_at);
    v_old_meta     jsonb;
    v_meta         jsonb;
    v_starts_new   timestamptz;
    v_resurrect    boolean := false;
    v_changed      text[] := '{}';
    v_id           uuid;
begin
    if p_household is null then
        raise exception 'upsert_canvas_task: no household was given, so nothing was written';
    end if;
    if nullif(trim(coalesce(p_task->>'title', '')), '') is null then
        raise exception 'upsert_canvas_task: the task has no title, so nothing was written';
    end if;

    -- What Canvas says, shown beside the task and never applied to `done`.
    v_evidence := v_evidence || jsonb_build_object('canvas_submitted', v_submitted);

    -- -------------------------------------------------------------------
    -- A sub-deadline (a discussion's initial post).
    -- -------------------------------------------------------------------
    if v_parent is not null then
        v_evidence := v_evidence || '{"manual_completion": true}'::jsonb;
        if v_origin is not null then
            select * into v_row from public.events e
             where e.household_id = p_household and e.origin_guid = v_origin;
            v_found := found;
        end if;
        if not v_found then
            select * into v_row from public.events e
             where e.household_id = p_household
               and e.deleted_at is null
               and e.structured_meta->>'parent_canvas_assignment_id' = v_parent
               and e.structured_meta->>'sub_deadline' = coalesce(p_task->>'sub_deadline', '')
             order by e.created_at, e.id
             limit 1;
            v_found := found;
        end if;

        if v_found then
            if v_row.origin_guid is distinct from v_origin then
                return jsonb_build_object(
                    'action', 'left_alone', 'id', v_row.id, 'title', v_row.title,
                    'reason', 'a sub-deadline row this poller did not create');
            end if;
            if v_row.deleted_at is not null then
                return jsonb_build_object(
                    'action', 'left_alone', 'id', v_row.id, 'title', v_row.title,
                    'reason', 'deleted by hand');
            end if;
            v_old_meta := coalesce(v_row.structured_meta, '{}'::jsonb);
            v_meta := v_old_meta || v_evidence;
            v_starts_new := coalesce(v_starts, v_row.starts_at);
            if v_starts_new is distinct from v_row.starts_at then
                v_changed := v_changed || 'starts_at'::text;
            end if;
            if (v_meta - 'read_at') is distinct from (v_old_meta - 'read_at') then
                v_changed := v_changed || 'structured_meta'::text;
            end if;
            if cardinality(v_changed) = 0 then
                return jsonb_build_object(
                    'action', 'unchanged', 'id', v_row.id, 'title', v_row.title);
            end if;
            -- `done` and `done_at` are deliberately absent from this SET.
            update public.events
               set starts_at = v_starts_new,
                   structured_meta = v_meta || v_read
             where id = v_row.id;
            return jsonb_build_object(
                'action', 'updated', 'id', v_row.id, 'title', v_row.title,
                'changed', to_jsonb(v_changed), 'starts_at', v_starts_new, 'done', v_row.done);
        end if;

        v_id := gen_random_uuid();
        insert into public.events (
            id, household_id, title, starts_at, all_day, source, done, done_at,
            exact, exact_downgraded, provenance, created_at, updated_at,
            origin_guid, structured_meta, kind
        ) values (
            v_id, p_household, p_task->>'title', v_starts, false, 'legion', false, null,
            false, false, 'DETERMINISTIC', now(), now(),
            v_origin, v_evidence || v_insert_meta || v_read, 'task'
        );
        return jsonb_build_object(
            'action', 'inserted', 'id', v_id, 'title', p_task->>'title',
            'starts_at', v_starts, 'done', false);
    end if;

    -- -------------------------------------------------------------------
    -- A Canvas assignment.
    -- -------------------------------------------------------------------
    if v_aid is null then
        raise exception
            'upsert_canvas_task: the task names neither canvas_assignment_id nor '
            'parent_canvas_assignment_id, so nothing was written';
    end if;

    select * into v_row from public.events e
     where e.household_id = p_household
       and e.structured_meta->>'canvas_assignment_id' = v_aid
       and not (e.structured_meta ? 'parent_canvas_assignment_id')
     order by (e.deleted_at is null) desc, e.created_at, e.id
     limit 1;
    v_found := found;
    if not v_found and v_origin is not null then
        select * into v_row from public.events e
         where e.household_id = p_household and e.origin_guid = v_origin;
        v_found := found;
    end if;

    if not v_found then
        if not v_may_insert then
            return jsonb_build_object(
                'action', 'not_inserted', 'title', p_task->>'title',
                'reason', coalesce(p_task->>'insert_reason', 'insert refused by the caller'));
        end if;
        v_meta := v_evidence || v_insert_meta;
        if v_manual or public.canvas_is_discussion(v_meta) then
            v_meta := v_meta || '{"manual_completion": true}'::jsonb;
        end if;
        v_id := gen_random_uuid();
        -- `done` is false whatever Canvas says: the tick is Kevin's.
        insert into public.events (
            id, household_id, title, starts_at, all_day, source, done, done_at,
            exact, exact_downgraded, provenance, created_at, updated_at,
            origin_guid, structured_meta, kind
        ) values (
            v_id, p_household, p_task->>'title', v_starts, false, 'legion', false, null,
            false, false, 'DETERMINISTIC', now(), now(),
            v_origin, v_meta || v_read, 'task'
        );
        return jsonb_build_object(
            'action', 'inserted', 'id', v_id, 'title', p_task->>'title',
            'starts_at', v_starts, 'done', false);
    end if;

    if v_row.deleted_at is not null then
        if coalesce(v_row.structured_meta, '{}'::jsonb) ? 'canvas_tombstoned_at' then
            v_resurrect := true;
            v_changed := v_changed || 'deleted_at'::text;
        else
            return jsonb_build_object(
                'action', 'left_alone', 'id', v_row.id, 'title', v_row.title,
                'reason', 'deleted by hand');
        end if;
    end if;

    v_old_meta := coalesce(v_row.structured_meta, '{}'::jsonb);
    v_meta := v_old_meta || v_evidence;
    if v_manual or public.canvas_is_discussion(v_meta) then
        v_meta := v_meta || '{"manual_completion": true}'::jsonb;
    end if;
    if v_resurrect then
        v_meta := v_meta - 'canvas_tombstoned_at';
    end if;

    v_starts_new := coalesce(v_starts, v_row.starts_at);
    if v_starts_new is distinct from v_row.starts_at then
        v_changed := v_changed || 'starts_at'::text;
    end if;
    if (v_meta - 'read_at') is distinct from (v_old_meta - 'read_at') then
        v_changed := v_changed || 'structured_meta'::text;
    end if;

    if cardinality(v_changed) = 0 then
        return jsonb_build_object('action', 'unchanged', 'id', v_row.id, 'title', v_row.title);
    end if;

    -- `done`, `done_at` and `provenance` are deliberately absent: the poller
    -- never ticks and never unticks. `title` and `origin_guid` are absent too:
    -- a matched row keeps both (decisions.md 2026-09-04, ruling 3).
    update public.events
       set starts_at = v_starts_new,
           structured_meta = v_meta || v_read,
           deleted_at = case when v_resurrect then null else deleted_at end
     where id = v_row.id;
    return jsonb_build_object(
        'action', case when v_resurrect then 'resurrected' else 'updated' end,
        'id', v_row.id, 'title', v_row.title, 'changed', to_jsonb(v_changed),
        'starts_at', v_starts_new, 'done', v_row.done);
end;
$$;
"""


class Migration(migrations.Migration):
    dependencies = [
        ("ingest", "0004_discussions_are_ticked_by_hand"),
    ]

    operations = [
        migrations.RunSQL(sql=_CREATE, reverse_sql=_PREVIOUS),
    ]
