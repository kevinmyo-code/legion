"""`manage.py prune_ticked_items`: Groceries lines ticked more than `TICKED_ITEM_TTL`
ago leave the list (`checklists/ticked_prune.py`). Run hourly from `deploy/crontab`.
Prints a count only, never an item."""

from __future__ import annotations

from django.core.management.base import BaseCommand

from checklists.ticked_prune import TICKED_ITEM_TTL, prune_ticked


class Command(BaseCommand):
    help = f"Remove Groceries lines ticked more than {TICKED_ITEM_TTL} ago (ticks are kept)."

    def handle(self, *args, **options):
        removed = prune_ticked()
        self.stdout.write(f"prune_ticked_items: removed {removed} ticked line(s).")
