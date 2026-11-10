"""Ticked Groceries lines leave the list a day after the tick (Kevin, 2026-11-10).
Made-up items only."""

from __future__ import annotations

import datetime
from pathlib import Path

import pytest
from django.core.management import call_command

from checklists.builtin import ensure_builtin_lists
from checklists.models import Checklist, ChecklistItem, ChecklistTick
from checklists.ticked_prune import TICKED_ITEM_TTL, prune_ticked

pytestmark = pytest.mark.django_db

NOW = datetime.datetime(2026, 3, 10, 12, 0, tzinfo=datetime.UTC)


def _line(checklist, text, ticked_hours_ago=None, tick_deleted=False):
    item = ChecklistItem.objects.create(checklist=checklist, text=text)
    if ticked_hours_ago is not None:
        tick = ChecklistTick.objects.create(item=item, day=20_000)
        ChecklistTick.objects.filter(pk=tick.pk).update(
            ticked_at=NOW - datetime.timedelta(hours=ticked_hours_ago),
            deleted_at=NOW if tick_deleted else None,
        )
    return item


def _live(checklist):
    return set(
        ChecklistItem.objects.filter(checklist=checklist, deleted_at__isnull=True).values_list(
            "text", flat=True
        )
    )


def test_the_window_is_one_day():
    assert TICKED_ITEM_TTL == datetime.timedelta(hours=24)


def test_groceries_lines_ticked_over_a_day_ago_leave_and_keep_their_ticks(household_a):
    groceries = ensure_builtin_lists(household_a)
    old = _line(groceries, "rice crackers", ticked_hours_ago=30)
    _line(groceries, "lime", ticked_hours_ago=3)
    _line(groceries, "tortillas")
    _line(groceries, "honey", ticked_hours_ago=40, tick_deleted=True)  # unticked: not ticked

    assert prune_ticked(now=NOW) == 1

    assert _live(groceries) == {"lime", "tortillas", "honey"}
    assert ChecklistTick.objects.filter(item=old, deleted_at__isnull=True).count() == 1
    # Idempotent: a second run removes nothing more.
    assert prune_ticked(now=NOW) == 0


def test_routines_and_hand_made_lists_are_left_alone(household_a):
    routine = Checklist.objects.create(
        household=household_a, name="Evenings", schedule_kind="DAILY"
    )
    plain = Checklist.objects.create(household=household_a, name="Garage sale")
    _line(routine, "stretch", ticked_hours_ago=50)
    _line(plain, "price stickers", ticked_hours_ago=50)

    assert prune_ticked(now=NOW) == 0
    assert _live(routine) == {"stretch"}
    assert _live(plain) == {"price stickers"}


def test_the_command_prints_a_count_only(household_a, capsys):
    groceries = ensure_builtin_lists(household_a)
    item = ChecklistItem.objects.create(checklist=groceries, text="oat bars")
    ChecklistTick.objects.create(item=item, day=20_000)  # ticked just now: stays
    call_command("prune_ticked_items")
    out = capsys.readouterr().out
    assert "removed 0" in out and "oat bars" not in out


def test_the_crontab_runs_it_hourly():
    crontab = Path(__file__).resolve().parents[2] / "deploy" / "crontab"
    assert "15 * * * * python manage.py prune_ticked_items" in crontab.read_text().splitlines()
