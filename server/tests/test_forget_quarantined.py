"""`forget_quarantined`: only a QUARANTINED record goes, and only on purpose."""
from __future__ import annotations

import io
import uuid

import pytest
from django.core.management import call_command
from django.core.management.base import CommandError
from django.utils import timezone

from legacy.enums import IngestState
from legacy.models.ingest import IngestedFile


def seen(household, name: str, state: str) -> IngestedFile:
    now = timezone.now()
    return IngestedFile.objects.create(
        id=uuid.uuid4(),
        content_sha256=uuid.uuid4().hex,
        display_name=name,
        state=state,
        quarantine_reason="parser refused" if state == IngestState.QUARANTINED else None,
        first_seen_at=now,
        last_attempt_at=now,
        household=household,
    )


@pytest.mark.django_db
def test_a_quarantined_statement_is_forgotten(household_a):
    seen(household_a, "eStmt_2026-09-04.pdf", IngestState.QUARANTINED)
    out = io.StringIO()
    call_command("forget_quarantined", "eStmt_2026-09-04.pdf", stdout=out)
    assert not IngestedFile.objects.filter(display_name="eStmt_2026-09-04.pdf").exists()
    assert "fresh copy" in out.getvalue()


@pytest.mark.django_db
def test_a_committed_statement_is_never_forgotten(household_a):
    seen(household_a, "eStmt_2026-09-05.pdf", IngestState.INGESTED)
    with pytest.raises(CommandError, match="not quarantined"):
        call_command("forget_quarantined", "eStmt_2026-09-05.pdf")
    assert IngestedFile.objects.filter(display_name="eStmt_2026-09-05.pdf").exists()


@pytest.mark.django_db
def test_an_unknown_name_changes_nothing(household_a):
    seen(household_a, "other.pdf", IngestState.QUARANTINED)
    with pytest.raises(CommandError, match="Nothing was changed"):
        call_command("forget_quarantined", "missing.pdf")
    assert IngestedFile.objects.count() == 1


@pytest.mark.django_db
def test_two_households_need_one_named(household_a, household_b):
    seen(household_a, "same.pdf", IngestState.QUARANTINED)
    seen(household_b, "same.pdf", IngestState.QUARANTINED)
    with pytest.raises(CommandError, match="--household"):
        call_command("forget_quarantined", "same.pdf")
    call_command("forget_quarantined", "same.pdf", "--household", str(household_b.id), stdout=io.StringIO())
    assert list(IngestedFile.objects.values_list("household_id", flat=True)) == [household_a.id]
