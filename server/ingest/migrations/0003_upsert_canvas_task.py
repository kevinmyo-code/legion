"""`public.upsert_canvas_task`: the one write path for a Canvas-backed task row
(backend-etl ticket 04, carrying two-clients 03's "Where the sub-deadline rule
is enforced" verbatim).

The rules this function holds are ones two writers must agree on: the phone
ticks a first-post row by hand through `/api/events`, and `canvas_poll` flips
the parent from Canvas. Neither may cascade into the other, so the rules live
in Postgres rather than in the poller's Python:

- match on `structured_meta->>'canvas_assignment_id'`, then `origin_guid`;
- `done` is set from Canvas's submission state ONLY on a row with no
  `parent_canvas_assignment_id`, and only ever from false to true;
- a row carrying `manual_completion: true` never has `done` touched;
- the same payload twice writes nothing the second time: an unchanged row is
  not UPDATEd at all, so `touch_updated_at` never fires and the phone's
  `/api/changes?since=` feed does not re-deliver it.

It also refuses to resurrect a row a person deleted (a tombstone without the
poller's own `canvas_tombstoned_at` marker), and leaves a sub-deadline row the
poller did not create exactly as it is.

plpgsql, not `language sql`, on purpose: a plpgsql body is not resolved until
it runs, and in the pytest database `public.events` does not exist when this
migration runs (the legacy tables are layered on afterwards by conftest), so
the CREATE runs with `check_function_bodies` off for its own transaction.
"""

from django.db import migrations

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
    v_submitted_at timestamptz := (p_task->>'submitted_at')::timestamptz;
    v_manual       boolean := coalesce((p_task->>'manual_completion')::boolean, false);
    v_may_insert   boolean := coalesce((p_task->>'insert')::boolean, true);
    v_read         jsonb := jsonb_build_object('read_at', p_read_at);
    v_old_meta     jsonb;
    v_meta         jsonb;
    v_starts_new   timestamptz;
    v_done         boolean;
    v_done_at      timestamptz;
    v_provenance   text;
    v_resurrect    boolean := false;
    v_may_tick     boolean;
    v_changed      text[] := '{}';
    v_id           uuid;
begin
    if p_household is null then
        raise exception 'upsert_canvas_task: no household was given, so nothing was written';
    end if;
    if nullif(trim(coalesce(p_task->>'title', '')), '') is null then
        raise exception 'upsert_canvas_task: the task has no title, so nothing was written';
    end if;

    -- -------------------------------------------------------------------
    -- A sub-deadline (a discussion's initial post). Never ticked here.
    -- -------------------------------------------------------------------
    if v_parent is not null then
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
        if v_manual then
            v_meta := v_meta || '{"manual_completion": true}'::jsonb;
        end if;
        v_done := v_submitted and not v_manual;
        v_id := gen_random_uuid();
        insert into public.events (
            id, household_id, title, starts_at, all_day, source, done, done_at,
            exact, exact_downgraded, provenance, created_at, updated_at,
            origin_guid, structured_meta, kind
        ) values (
            v_id, p_household, p_task->>'title', v_starts, false, 'legion', v_done,
            case when v_done then coalesce(v_submitted_at, now()) end,
            false, false, 'DETERMINISTIC', now(), now(),
            v_origin, v_meta || v_read, 'task'
        );
        return jsonb_build_object(
            'action', 'inserted', 'id', v_id, 'title', p_task->>'title',
            'starts_at', v_starts, 'done', v_done);
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
    if v_manual then
        v_meta := v_meta || '{"manual_completion": true}'::jsonb;
    end if;
    if v_resurrect then
        v_meta := v_meta - 'canvas_tombstoned_at';
    end if;

    v_may_tick := not coalesce((v_meta->>'manual_completion')::boolean, false)
              and not (v_old_meta ? 'parent_canvas_assignment_id');
    v_done := v_row.done;
    v_done_at := v_row.done_at;
    v_provenance := v_row.provenance::text;
    if v_may_tick and v_submitted and not v_row.done then
        v_done := true;
        v_done_at := coalesce(v_submitted_at, now());
        v_provenance := 'DETERMINISTIC';
        v_changed := v_changed || 'done'::text;
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

    -- `title` and `origin_guid` are deliberately absent: a matched row keeps
    -- both (decisions.md 2026-09-04, ruling 3).
    update public.events
       set starts_at = v_starts_new,
           done = v_done,
           done_at = v_done_at,
           provenance = v_provenance::public.provenance,
           structured_meta = v_meta || v_read,
           deleted_at = case when v_resurrect then null else deleted_at end
     where id = v_row.id;
    return jsonb_build_object(
        'action', case when v_resurrect then 'resurrected' else 'updated' end,
        'id', v_row.id, 'title', v_row.title, 'changed', to_jsonb(v_changed),
        'starts_at', v_starts_new, 'done', v_done);
end;
$$;
"""

_DROP = "drop function if exists public.upsert_canvas_task(uuid, jsonb, timestamptz);"


class Migration(migrations.Migration):
    dependencies = [
        ("ingest", "0002_source_credentials"),
    ]

    operations = [
        migrations.RunSQL(sql=_CREATE, reverse_sql=_DROP),
    ]
