"""The household's categorisation rules, applied to a ledger line at the one
moment the server is allowed to set its category: the INSERT.

**Why only at insert.** `ledger_transactions` is the section 4 gate's output
and `private.forbid_mutation_of_facts` refuses every UPDATE on it, category
included (`supabase/migrations/20260825000200_conventions.sql`, and the same
trigger in `tests/legacy_test_schema.py`). So a category the rules can supply
has to be on the row the first time it is written. A row stored before this
module existed keeps `category NULL` on the server; nothing here tries to
change it, and nothing here should - see the ticket
`.scratch/backend-etl/issues/14-ledger-rows-reach-the-phone.md` for the open
decision about those rows.

**The match is the phone's, ported exactly** -
`app/.../ledger/LedgerController.applyCategoryRules`:

- Rules are the household's ACTIVE rules (`deleted_at IS NULL`), oldest first
  by `created_at_client` - the instant the rule was written on the phone,
  which is `CategoryRule.createdAt` there and what `CategoryRuleDao.getAll`
  sorts by. The server's own `created_at` is its insert stamp and would
  reorder rules by when they happened to sync.
- A rule matches when the description, uppercased, contains the rule's
  substring, uppercased (`description.uppercase().contains(rule.substring
  .uppercase())`). Python's `str.upper` and Kotlin's locale-free
  `String.uppercase` agree on the full Unicode case map, `ß` to `SS`
  included.
- The phone applies rules one at a time and each only touches rows still
  uncategorised, so **the oldest matching rule wins**. `first_match` below is
  the same outcome for one line.
- A rule whose category names a category that no longer exists still fires,
  exactly as on the phone. The phone does not check, and a second opinion
  here would make the two disagree about the same row.

Ties on `created_at_client` are broken by `id`. The phone's SQL leaves a tie
unordered, so the two can differ only when two rules were written in the same
millisecond AND both match one description; stated rather than hidden.
"""
from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass

from legacy.models.ledger import CategoryRule


@dataclass(frozen=True)
class Rule:
    substring_upper: str
    category: str


def household_rules(household) -> list[Rule]:
    """The household's active rules in the order the phone applies them.

    Scoped by household (ADR 0045): another household's rule is not a fact
    about this household's merchants."""
    rows = (
        CategoryRule.objects.filter(household=household, deleted_at__isnull=True)
        .order_by("created_at_client", "id")
        .values_list("substring", "category")
    )
    return [Rule(substring_upper=substring.upper(), category=category) for substring, category in rows]


def first_match(description: str, rules: Sequence[Rule]) -> str | None:
    """The category of the oldest rule matching `description`, or None."""
    upper = description.upper()
    for rule in rules:
        if rule.substring_upper in upper:
            return rule.category
    return None


def category_for_insert(
    stated: str | None, description: str, rules: Sequence[Rule]
) -> tuple[str | None, bool]:
    """`(category, category_pending)` for a line about to be inserted.

    A category the caller stated is kept as stated and never overridden by a
    rule - that is someone's decision, and the phone's categoriser likewise
    touches only `category IS NULL` rows. With none stated, the rules decide.
    `category_pending` is true exactly when the row is left uncategorised,
    matching what the phone writes (`categoryPending = false` on a rule hit).
    """
    if stated is not None:
        return stated, False
    matched = first_match(description, rules)
    return matched, matched is None
