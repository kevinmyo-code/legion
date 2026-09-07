"""`GET /api/ingest/files/` - the per-file ingestion ledger, read-only.

Ticket 04's exceptions table gives this table one line: "`ingested_files` |
GET only". It is the table both gated aspects hang off - `statements.ingested_file_id`
and `receipts.ingested_file_id` point into it - and it is the only place a
QUARANTINE is recorded at all. Section 4 rule 2 says a document that does not
reconcile writes nothing; `ingest.views._quarantine`'s own comment draws the
line that makes that liveable: "the prohibition is on partial DATA, never on
recording that a document was rejected". This route is how a client reads that
record. Without it, a quarantined statement is indistinguishable from one
nobody ever sent.

## Where this is mounted, and why it looks like it is in two places

The path is `/api/ingest/files/`, which sits under the same prefix as
`/api/ingest/statement` and `/api/ingest/receipt` - but those two come from
`ingest/urls.py` and this one comes from `api/urls.py`, generated from
`api/registry.py` with every other synced table. That is deliberate rather
than a leftover. The registry is the ONE list `api/urls.py` and
`api/changes.py` both read, and it exists so a table cannot be routable and
invisible in the changes feed, or the reverse. Moving this route into
`ingest/urls.py` to keep the prefix tidy would have cost that guarantee, which
is worth more than the tidiness. `legion/urls.py` mounts `api.urls` before
`ingest.urls`, and Django's resolver falls through a non-matching `include`
to the next pattern, so `/api/ingest/statement` still reaches the gate view;
`tests/test_ingest_api.py` would catch it the moment that stopped being true.

## There is no gate endpoint for THIS table, and the 405 says so

`gate_endpoint` on the other four gated viewsets names one address. A file row
is not committed on its own - it appears as a side effect of posting a
document to either gate endpoint - so this one names both, and the sentence
reads as the truth rather than as a half-answer.

## `last_attempt_at` is the cursor, and `first_seen_at` is not

`ingest.views._upsert_file` writes `last_attempt_at` on every commit and every
retry (its `update_fields` list names it) and leaves `first_seen_at` at the
value the first insert set. So `first_seen_at` is fixed and `last_attempt_at`
moves. A `?since=` feed keyed on the fixed one would return a file's row once,
at first sight, and then never again - so a client would never learn that a
file went from `NEW` to `QUARANTINED`, which is the single state change this
table exists to publish. Traced through that function rather than inferred
from the column names.

**This table has no `updated_at` and no `deleted_at`** (live schema,
2026-09-07), which is why `has_tombstones` is False here as on the four gated
tables. It also has no `provenance`: a file is not a fact the gate produced,
it is the document the gate read.
"""
from __future__ import annotations

from api.synced import GatedReadSerializer, GatedReadViewSet
from legacy.models.ingest import IngestedFile


class IngestedFileSerializer(GatedReadSerializer):
    """One `public.ingested_files` row.

    `content_sha256` is the idempotency key the whole ingest path turns on: a
    second post of the same bytes is a no-op, which is what makes a lost
    acknowledgement retryable rather than ambiguous
    (`ingest.views._already_committed`'s own doc comment). `quarantine_reason`
    is the sentence the gate wrote when it refused, in the same words the
    caller was given at the time.
    """

    class Meta:
        model = IngestedFile
        fields = [
            "id",
            "content_sha256",
            "source_file_id",
            "display_name",
            "size_bytes",
            "state",
            "quarantine_reason",
            "first_seen_at",
            "last_attempt_at",
        ]
        extra_kwargs = {
            "state": {
                "help_text": (
                    "Where this document got to. INGESTED means the section 4 gate ran and "
                    "committed it; QUARANTINED means the gate ran and REFUSED it, and "
                    "`quarantine_reason` says why in words; UNREADABLE, DUPLICATE_CONTENT and "
                    "NEEDS_LLM are the three ways it never reached the gate at all. A "
                    "QUARANTINED file wrote no rows anywhere - that is the point of it."
                )
            },
            "quarantine_reason": {
                "help_text": (
                    "The gate's own sentence, or null. Written to be shown to a person: it "
                    "says which figures did not agree, not merely that something failed."
                )
            },
            "first_seen_at": {
                "help_text": (
                    "The first time this content was posted. FIXED after the first insert - "
                    "not a change watermark. Page this feed with `since` against "
                    "`last_attempt_at`, which is what the `next` cursor carries."
                )
            },
            "last_attempt_at": {
                "help_text": (
                    "The last time this content was posted, rewritten on every commit and "
                    "every retry. The column this feed's `?since=` and `next` are keyed on."
                )
            },
        }


class IngestedFileViewSet(GatedReadViewSet):
    aspect = "ingest"
    table = "ingested_files"
    # `/api/ingest/files/`, this ticket's own path. The changes-feed key stays
    # `ingested_files`.
    url_segment = "files"
    serializer_class = IngestedFileSerializer
    cursor_field = "last_attempt_at"
    gate_endpoint = "POST /api/ingest/statement or POST /api/ingest/receipt"


INGEST_VIEWSETS = [IngestedFileViewSet]
