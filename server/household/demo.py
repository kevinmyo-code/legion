"""The demo household recruiters read (recruiter-demo ticket 03).

One household on the live engine holds FAKE data only - invented names,
merchants and amounts, nothing copied from any real household - so a recruiter
session scoped to it by ordinary tenancy (ADR 0045) can never reach a real one.
`manage.py seed_demo_household` builds it; this module is how everything else
finds it.

**Found by name, and the name is a setting** (`LEGION_DEMO_HOUSEHOLD_NAME`,
default "Demo"). `Household.name` is not unique, so the lookup refuses in words
when two households carry the demo name rather than picking one: a recruiter
put into the wrong one of two same-named households is exactly the failure this
whole map exists to make impossible.

This module creates no users and no members. Recruiter visitor users and their
role belong to tickets 01 and 02.

**Visitors no longer join this household (ticket 13, ADR 0069).** Each visit
gets its own copy, seeded from the same data by `demo_seed.seed_household`
(`demo/views.py`). The shared household stays for previewing the seed and for
tests; the lookup below ignores every visit copy.
"""
from __future__ import annotations

from django.conf import settings

from household.models import Household


class DemoHouseholdError(RuntimeError):
    """The demo household is missing, or its name is ambiguous. Said in words."""


def demo_household_name() -> str:
    return settings.LEGION_DEMO_HOUSEHOLD_NAME


def find_demo_household() -> Household | None:
    """The demo household, or None if it has not been seeded. Raises when the
    name matches more than one household."""
    name = demo_household_name()
    # A per-visit copy (`is_demo_visit`, ticket 13) is never THE demo
    # household, whatever it is called: the seed command must never refresh
    # one, and a visit copy must never make the name look ambiguous.
    matches = list(
        Household.objects.filter(name=name, is_demo_visit=False).order_by("created_at")[:2]
    )
    if len(matches) > 1:
        raise DemoHouseholdError(
            f"More than one household is named {name!r}, so there is no single demo "
            f"household. Nothing was read. Rename the real one, or set "
            f"LEGION_DEMO_HOUSEHOLD_NAME to a name only the demo household carries."
        )
    return matches[0] if matches else None


def demo_household() -> Household:
    """The demo household. Raises `DemoHouseholdError` if it has not been seeded
    or if its name is ambiguous - never returns some other household."""
    household = find_demo_household()
    if household is None:
        raise DemoHouseholdError(
            f"There is no household named {demo_household_name()!r} on this engine. "
            f"Run `manage.py seed_demo_household` to build it."
        )
    return household
