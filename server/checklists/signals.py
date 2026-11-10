"""A household gets its built-in lists the moment it exists (Kevin,
2026-10-05). Covers every way a household is made - signup, the
`create_household` command, the admin - without each remembering to ask."""
from __future__ import annotations

from django.db.models.signals import post_save
from django.dispatch import receiver

from checklists.builtin import ensure_builtin_lists
from household import rls
from household.models import Household


@receiver(post_save, sender=Household)
def create_builtin_lists(sender, instance: Household, created: bool, raw: bool = False, **kwargs):
    # `raw` is a fixture load, which brings its own rows.
    if created and not raw:
        # Row-level security (household/rls.py): the lists belong to the household this request
        # just made, which is not (yet) the request's own. A no-op outside RLS.
        with rls.as_household(instance.id):
            ensure_builtin_lists(instance)
