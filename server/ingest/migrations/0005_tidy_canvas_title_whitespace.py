"""One-off data fix: the whitespace Canvas left in two titles the poller wrote.

The 2026-09-28 live run of `canvas_poll` inserted
`COSC 3318 Python Programming ·  Module 2: Assignment ` and
`COSC 3318 Python Programming ·  Module 3: Assignment  `: Canvas's assignment
names carried leading and trailing spaces, and the poller joined them in as
they came. `ingest.canvas.tidy_name` stops new ones; this tidies the rows
already written.

**Only rows the poller created.** A row must carry `canvas_assignment_id` in
`structured_meta` AND have an `origin_guid` starting `canvas:` (which only
`upsert_canvas_task` inserts), so a title Kevin typed or the 2026-09-05 hand
script seeded is never touched. And only a title that actually changes, so
nothing else gets a new `updated_at`.

**`updated_at` moves by the `touch_updated_at` trigger** (`before update` on
`public.events`, `private.touch_updated_at()` sets `new.updated_at := now()`),
which is what the phone's `/api/changes` feed reads. It is set explicitly here
too, so the fix does not depend on the trigger being present.

Guarded on `to_regclass('public.events')`: the pytest database only gains the
legacy tables after `migrate` (tests/conftest.py), so there this is a no-op and
the test calls `TIDY_SQL` directly. Irreversible in the only sense that
matters: the stray spaces are not worth restoring, so the reverse is a no-op.
"""

from django.db import migrations

TIDY_SQL = r"""
update public.events
   set title = btrim(regexp_replace(title, '\s+', ' ', 'g')),
       updated_at = now()
 where structured_meta ? 'canvas_assignment_id'
   and origin_guid like 'canvas:%'
   and title is distinct from btrim(regexp_replace(title, '\s+', ' ', 'g'));
"""

_GUARDED = f"""
do $do$
begin
    if to_regclass('public.events') is not null then
        {TIDY_SQL}
    end if;
end
$do$;
"""


class Migration(migrations.Migration):
    dependencies = [
        ("ingest", "0004_discussions_are_ticked_by_hand"),
    ]

    operations = [
        migrations.RunSQL(sql=_GUARDED, reverse_sql=migrations.RunSQL.noop),
    ]
