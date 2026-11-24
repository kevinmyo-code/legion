"""Voice notes.

`ItemList` and `ListItem` lived here until the lists redesign slice 1a
(2026-11-24) removed them. They were `managed = False` models over
`public.item_lists` and `public.list_items` with no viewset, no route and no
reader anywhere on the engine: `read_list` sounds like one and is not, since it
reads `events` and `checklists` (`agents/legion/twins.py`). The phone stopped
reading and writing those tables in backend-erp ticket 15 step 4 and retired
their legacy Supabase sync in the same slice that deleted these models.

The TABLES are untouched by that removal - they are still in
`household.tenancy.TENANT_TABLES` and still carry `household_id`, which
`tests/test_tenancy.py` checks against `information_schema`. Dropping them is a
separate, destructive step with Kevin's word on it, and the `TENANT_TABLES`
entries move in that same commit so the two never disagree.

The `legacy` app has no `migrations/` package, so removing a `managed = False`
model here needs no migration at all, state-only or otherwise.
"""
from __future__ import annotations

from django.db import models

from legacy.models.tenancy import household_field


class VoiceNote(models.Model):
    """`provenance` here is NOT the shared `public.provenance` enum used
    everywhere else in this schema - it is a plain `text` column pinned by
    its own CHECK to the single literal `'LLM_DERIVED'`
    (`voice_notes_provenance_check`). Modeled with its own one-value
    `choices=` rather than `legacy.enums.Provenance` so the model does not
    claim a column can hold `DETERMINISTIC`/`USER`/etc. when the database
    itself refuses every value but one. Hand-correction, ticket 02.
    """

    PROVENANCE_CHOICES = [("LLM_DERIVED", "LLM_DERIVED")]

    id = models.UUIDField(primary_key=True)
    started_at = models.DateTimeField()
    ended_at = models.DateTimeField(null=True)
    title = models.TextField(null=True)
    summary = models.TextField(null=True)
    transcript = models.TextField(null=True)
    kind = models.TextField()  # CHECK: SOLO | MEETING
    provenance = models.TextField(choices=PROVENANCE_CHOICES, default="LLM_DERIVED")
    interrupted = models.BooleanField()
    created_at = models.DateTimeField()
    updated_at = models.DateTimeField()
    deleted_at = models.DateTimeField(null=True)

    household = household_field()

    class Meta:
        managed = False
        db_table = "voice_notes"
