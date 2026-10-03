"""`public.upsert_canvas_task` gains `p_owner_user` (ADR 0052, web-revamp
ticket 06, spec D3 "Canvas").

A row this function INSERTS carries `owner_user_id = p_owner_user` and
`created_by_id = p_owner_user`: Canvas coursework is private to the member
whose Canvas login the poller read. Null inserts a shared row, as before.

**An existing row's owner is never touched.** A person who made a Canvas row
shared keeps it shared on every later poll; making old rows private is
`manage.py make_private`'s job, run once. Resurrection keeps the owner the
tombstone had.

The body is 0005's, unchanged apart from those two columns, derived from it
by exact text substitution so the two cannot drift apart unnoticed: each
substitution asserts it matched exactly as often as expected. The old
three-argument function is dropped rather than left beside the new one, so
a caller that forgets the owner fails loudly instead of quietly inserting
shared coursework. The reverse restores 0005's body exactly.
"""

import importlib

from django.db import migrations

_PREVIOUS = importlib.import_module("ingest.migrations.0005_canvas_never_ticks")._CREATE


def _derive(previous: str) -> str:
    body = previous
    for old, new, count in (
        (
            "    p_read_at timestamptz\n) returns jsonb",
            "    p_read_at timestamptz,\n    p_owner_user uuid\n) returns jsonb",
            1,
        ),
        (
            "            origin_guid, structured_meta, kind\n        ) values (",
            "            origin_guid, structured_meta, kind, owner_user_id, created_by_id\n"
            "        ) values (",
            2,
        ),
        (
            "'task'\n        );",
            "'task', p_owner_user, p_owner_user\n        );",
            2,
        ),
    ):
        found = body.count(old)
        if found != count:
            raise RuntimeError(
                f"Nothing was migrated. 0005's upsert_canvas_task no longer has {count} "
                f"copies of {old!r} (found {found}), so this migration cannot derive the "
                f"owner-aware body from it safely."
            )
        body = body.replace(old, new)
    return (
        "drop function if exists public.upsert_canvas_task(uuid, jsonb, timestamptz);\n" + body
    )


_CREATE = _derive(_PREVIOUS)

_REVERSE = (
    "drop function if exists public.upsert_canvas_task(uuid, jsonb, timestamptz, uuid);\n"
    + _PREVIOUS
)


class Migration(migrations.Migration):
    dependencies = [
        ("ingest", "0011_source_credentials_user"),
    ]

    operations = [
        migrations.RunSQL(sql=_CREATE, reverse_sql=_REVERSE),
    ]
