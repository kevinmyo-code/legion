"""Voice notes, item lists (shopping lists, checklists, todo lists), and
their items."""
from __future__ import annotations

from django.db import models

from legacy.enums import Provenance
from legacy.models.tenancy import household_field, household_unique


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


class ItemList(models.Model):
    id = models.UUIDField(primary_key=True)
    name = models.TextField()
    tickable = models.BooleanField()
    sort_order = models.IntegerField()
    last_used_at = models.DateTimeField()
    archived = models.BooleanField()
    created_at_client = models.DateTimeField()
    provenance = models.TextField(choices=Provenance.choices)
    created_at = models.DateTimeField()
    updated_at = models.DateTimeField()
    deleted_at = models.DateTimeField(null=True)
    # **The one `origin_guid` in this app that stays globally unique, and it
    # is not an oversight.** ADR 0045 re-keys every per-server unique key to
    # `(household_id, <col>)`; this one cannot be, because `ListItem` below is
    # a foreign key to THIS COLUMN (`to_field="origin_guid"`, see its
    # docstring). Postgres will not let a unique constraint another table's
    # foreign key depends on be dropped, and Django refuses the model outright
    # with `fields.E311: 'ItemList.origin_guid' must be unique because it is
    # referenced by a foreign key`. `household/tenancy_sql.py` reaches the same
    # conclusion from the catalog and skips it with a note.
    #
    # What that costs, stated rather than glossed: two households cannot use
    # the same `origin_guid` on `item_lists`. It is a client-minted uuid, so
    # in practice they never will - but the isolation here is arithmetic, not
    # architecture, and that is worth knowing. `list_items` is scoped by its
    # OWN `household_id` like every other table; a child list item's household
    # must equal its parent list's, which `tests/test_tenancy.py` asserts
    # rather than assumes.
    origin_guid = models.TextField(unique=True)

    household = household_field()

    class Meta:
        managed = False
        db_table = "item_lists"


class ListItem(models.Model):
    """`list_origin_guid` is a foreign key to `ItemList.origin_guid`, not to
    its primary key - `to_field` says so explicitly. Note also that this
    table's `repeat_end_date` is `timestamptz`, while `Event.repeat_end_date`
    (a near-identical repeat-schedule shape in `legacy/models/dates.py`) is
    a plain `date`. That mismatch is real, confirmed against
    `information_schema.columns` for both tables independently - not a
    transcription slip in this file."""

    id = models.UUIDField(primary_key=True)
    item_list = models.ForeignKey(
        ItemList,
        db_column="list_origin_guid",
        to_field="origin_guid",
        on_delete=models.DO_NOTHING,
        related_name="+",
    )
    text = models.TextField()
    done = models.BooleanField()
    done_at = models.DateTimeField(null=True)
    sort_order = models.IntegerField()
    created_at_client = models.DateTimeField()
    starts_at = models.DateTimeField(null=True)
    ends_at = models.DateTimeField(null=True)
    all_day = models.BooleanField()
    trigger_place_label = models.TextField(null=True)
    repeat_kind = models.TextField(null=True)
    repeat_every = models.IntegerField(null=True)
    repeat_days_of_week = models.TextField(null=True)
    repeat_day = models.IntegerField(null=True)
    repeat_month = models.IntegerField(null=True)
    repeat_end_kind = models.TextField(null=True)
    repeat_end_date = models.DateTimeField(null=True)  # timestamptz, not date - see docstring
    repeat_end_count = models.IntegerField(null=True)
    exact = models.BooleanField()
    exact_downgraded = models.BooleanField()
    missed_at = models.DateTimeField(null=True)
    missed_dismissed_at = models.DateTimeField(null=True)
    logged_at = models.DateTimeField(null=True)
    provenance = models.TextField(choices=Provenance.choices)
    created_at = models.DateTimeField()
    updated_at = models.DateTimeField()
    deleted_at = models.DateTimeField(null=True)
    origin_guid = models.TextField()

    household = household_field()

    class Meta:
        managed = False
        db_table = "list_items"
        constraints = [
            household_unique("list_items", "origin_guid"),
        ]
