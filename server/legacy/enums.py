"""Shared choice sets mirroring Postgres enum types in `public`.

Both `public.provenance` and `public.ingest_state` are real Postgres ENUM
types (`pg_type` / `pg_enum`), not CHECK-constrained text columns.
`inspectdb` reports a column using one of these as `data_type =
'USER-DEFINED'` with `udt_name` naming the type, and cannot generate a
field for it at all - hand-correction, per ticket 02. Django has no
first-class Postgres-enum field, so every column of this type is modeled
as a plain `TextField` with `choices=` set from the `TextChoices` below for
self-documentation.

**This paragraph used to end "psycopg hands back the enum's text label on a
read, which is all this read-only app ever does". The second half stopped
being true with ticket 03**, which makes `ingest/views.py` a WRITER of
`provenance` and `ingest_state`. The read behaviour is unchanged; what is
new is that a plain Python `str` also goes out on the wire for these
columns, and Postgres resolves it against the enum on the way in. A value
outside the enum is refused by Postgres, not by Django - `choices=` on a
`TextField` is documentation, not a constraint. The CHECK a real writer
would need to respect is likewise not reproduced here as a
`CheckConstraint`, because `legacy` models are `managed = False` and never
emit DDL - Postgres enforces these enums directly, regardless of what
Django thinks a column allows.
"""
from __future__ import annotations

from django.db import models


class Provenance(models.TextChoices):
    """`public.provenance`. CLAUDE.md section 4 rule 4: every row that
    carries this column tags one of these four. Labels match
    `pg_enum.enumlabel` in the live schema (`enumsortorder` 1-4)."""

    DETERMINISTIC = "DETERMINISTIC"
    LLM_RECONCILED = "LLM_RECONCILED"
    UNRECONCILED = "UNRECONCILED"
    USER = "USER"


class IngestState(models.TextChoices):
    """`public.ingest_state`. The state `ingested_files.state` walks through
    on its way through the section 4 reconciliation gate."""

    NEW = "NEW"
    INGESTED = "INGESTED"
    QUARANTINED = "QUARANTINED"
    UNREADABLE = "UNREADABLE"
    DUPLICATE_CONTENT = "DUPLICATE_CONTENT"
    NEEDS_LLM = "NEEDS_LLM"
