"""Plaid's own category for a bank row, and the one table that maps Plaid's
taxonomy onto the household's category names (ADR 0057, Kevin 2026-11-23).

**Why this is fact, not a guess.** The bank feed is fact with no gate (ADR
0057, CLAUDE.md section 4 as amended), and `personal_finance_category` arrives
inside that same feed, on every added and modified transaction, with no option
to ask for. So a category taken from it is stored like the amount and the
description: plain, no tag, no estimate wording. Kevin, 2026-11-23: *"accept
plaid's category"*.

**His rules still win.** `category_rules.category_for_insert` applies this only
when no rule of his matched, so a rule he wrote is never outranked by Plaid.

**The evidence is kept (section 4 rule 8).** The raw object Plaid sent
(`primary`, `detailed`, `confidence_level`, `version`) is stored on the row in
`plaid_category`, on every bank row that carries one, whether or not its
category was used. A category derived from a value nobody kept is a row nobody
can re-verify, which is the mistake rule 8 was written from, twice.
`category_origin` says which of the two sources wrote the row's category.

## The map

`BY_PRIMARY` is the default for each of Plaid's sixteen primary categories;
`BY_DETAILED` overrides it where one primary spans more than one of his
(FOOD_AND_DRINK is three of them). A `None` in `BY_DETAILED` means **do not
guess**: the primary would map, but this particular value is not plainly one of
his categories, so the row is left uncategorised. Anything the table does not
name is left uncategorised too and listed in the sync report, never guessed.

The map is data, in one place, so changing it is a one-line decision and not a
code change. It is also deliberately short of Plaid's full taxonomy: the rows
left out are the ones where Plaid's intent and his category do not line up
(loan payments, most transfers, government, education), and which of those
becomes which of his categories is his ruling to make, not an engineering
choice.

**A name this table gives is used only when the household actually has that
category, and only when that category counts as spending.** The names below
are LEGION's starter set (`app/.../data/local/CategorySeed.kt`); a household
that renamed or deleted one would otherwise get a category that no screen
offers. And a category the household flagged `excluded_from_spend`
(`ingest/category_flags.py`, the ONE definition of what is not spending) is
never written from this map: filing a row there does not mislabel it, it takes
the money out of every spend total, and Plaid's taxonomy does not know which of
the household's categories are money moving between its own places. Which
Plaid values belong in an excluded category is his ruling to make, not this
map's, so such a row is left uncategorised and named in the sync report.
The guard reads the FLAG, never a name: a name compared in two places drifts
the day someone renames the category.

## Versions

Plaid ships this taxonomy as PFCv1 and PFCv2; v2 adds detailed values under the
same sixteen primaries. The lookup reads `detailed` first and falls back to
`primary`, so a v2 value this table has never seen still lands on its primary's
category instead of being thrown away, and nothing here depends on which
version the account is on.
"""
from __future__ import annotations

from typing import Any, NamedTuple

from legacy.models.ledger import Category

# The raw object, exactly the four fields Plaid documents on it.
EVIDENCE_FIELDS = ("primary", "detailed", "confidence_level", "version")

BY_PRIMARY: dict[str, str] = {
    "INCOME": "Income",
    "BANK_FEES": "Fees",
    "ENTERTAINMENT": "Entertainment",
    "FOOD_AND_DRINK": "Dining Out",
    "GENERAL_MERCHANDISE": "Shopping",
    "HOME_IMPROVEMENT": "Housing",
    "MEDICAL": "Health",
    "TRANSPORTATION": "Transport",
    "TRAVEL": "Travel",
    "RENT_AND_UTILITIES": "Utilities",
}

BY_DETAILED: dict[str, str | None] = {
    "FOOD_AND_DRINK_GROCERIES": "Groceries",
    "FOOD_AND_DRINK_COFFEE": "Coffee & Snacks",
    "FOOD_AND_DRINK_VENDING_MACHINES": "Coffee & Snacks",
    # A liquor store is not dining out and not groceries by his list.
    "FOOD_AND_DRINK_BEER_WINE_AND_LIQUOR": None,
    "RENT_AND_UTILITIES_RENT": "Housing",
    "GENERAL_SERVICES_INSURANCE": "Insurance",
    "GENERAL_MERCHANDISE_PET_SUPPLIES": "Pets",
    "MEDICAL_VETERINARY_SERVICES": "Pets",
    "PERSONAL_CARE_GYMS_AND_FITNESS_CENTERS": "Health",
}

# Deliberately NOT mapped at all, though they look like "Transfers": Plaid's own
# descriptions for `TRANSFER_IN/OUT_ACCOUNT_TRANSFER` say only "transfers from
# another account" / "to another account", which covers money sent to another
# PERSON as readily as money moved between his own accounts, and the SAVINGS
# rows do not say whose savings. That is his ruling to make, so the table stays
# silent. (The separate guard below refuses ANY mapped name the household has
# flagged `excluded_from_spend`, whatever it is called, so a map entry added
# later cannot quietly remove money from his spending either.)


def _plain(value: Any) -> str | None:
    """A field of Plaid's object as a plain string, or None.

    `plaid-python` hands enums over as model objects with a `value`. Anything
    else is dropped rather than stringified: this goes into a `jsonb` column on
    the same `bulk_create` as the row itself, so a value that cannot be
    serialised would not spoil one category, it would lose the whole sync."""
    if isinstance(value, str):
        return value or None
    inner = getattr(value, "value", None)
    return inner if isinstance(inner, str) and inner else None


def evidence(raw: Any) -> dict | None:
    """What Plaid said about this transaction's intent, as plain JSON, or None
    when it said nothing this code recognises."""
    if not isinstance(raw, dict):
        return None
    kept = {name: _plain(raw.get(name)) for name in EVIDENCE_FIELDS}
    kept = {name: value for name, value in kept.items() if value is not None}
    return kept or None


def category_for(stored: dict | None) -> tuple[str | None, str | None]:
    """`(the household category this maps to or None, the Plaid value it was
    read from)`. The second is for the sentence that reports what mapped to
    nothing, so it is returned either way."""
    if not stored:
        return None, None
    detailed = stored.get("detailed")
    primary = stored.get("primary")
    key = detailed or primary or None
    if detailed in BY_DETAILED:
        return BY_DETAILED[detailed], key
    return BY_PRIMARY.get(primary or ""), key


class HouseholdCategories(NamedTuple):
    """Which of the household's live category names this map may write, and
    which it must refuse.

    `writable` is what a mapped name is checked against: a name the household
    does not have is not written, because no screen offers it and no budget
    counts it. `excluded` is the subset flagged `excluded_from_spend`
    (`ingest/category_flags.py`): Plaid's word alone never files a row there,
    and it is kept apart from the names that simply do not exist so the sync
    report can say which of the two happened."""

    writable: frozenset[str]
    excluded: frozenset[str]


def household_categories(household) -> HouseholdCategories:
    """The household's live category names, split by the spend flag. Reads the
    FLAG, never a name (`ingest/category_flags.py`)."""
    rows = Category.objects.filter(household=household, deleted_at__isnull=True).values_list(
        "name", "excluded_from_spend"
    )
    excluded = frozenset(name for name, flagged in rows if flagged)
    # A duplicate name with the flag set on one row only fails closed: it is in
    # `excluded`, and `writable` leaves it out.
    writable = frozenset(name for name, _flagged in rows) - excluded
    return HouseholdCategories(writable, excluded)
