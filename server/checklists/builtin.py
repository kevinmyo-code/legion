"""Built-in checklists: lists the engine itself guarantees exist (Kevin,
2026-10-05: *"create groceries as a persistent list instead of a hand created
one ... since we're gonna be using it to measure and record stuff"*).

Today that is one list, Groceries (`system_key = "groceries"`). Why it is built
in: the purchase log (ADR 0055) is fed by ticks on it, and a rule of "the list
somebody happened to name Groceries" failed on the live engine - six such lists
had come and gone, the last deleted, so the hook fired on nothing and the
import found nothing. A household's Groceries list is now created with the
household, shared, plain (no schedule), and cannot be removed.

`refusal_for_change` is the one place the "cannot be deleted, archived,
renamed or made private" rule lives (ADR 0044: a business rule once, in
Django). Every refusal says what did NOT happen.
"""
from __future__ import annotations

from checklists.models import SYSTEM_KEY_GROCERIES, Checklist
from household.tenancy import VISIBILITY_PRIVATE

GROCERIES_LIST_NAME = "Groceries"


def ensure_builtin_lists(household) -> Checklist:
    """Creates the household's built-in Groceries list if it has none live.
    Idempotent. Returns the list. Starts empty, always."""
    existing = Checklist.objects.filter(
        household=household, system_key=SYSTEM_KEY_GROCERIES, deleted_at__isnull=True
    ).first()
    if existing is not None:
        return existing
    return Checklist.objects.create(
        household=household,
        name=GROCERIES_LIST_NAME,
        owner_user=None,
        system_key=SYSTEM_KEY_GROCERIES,
    )


def _label(checklist) -> str:
    if checklist.system_key == SYSTEM_KEY_GROCERIES:
        return f"The {checklist.name} list"
    return "This list"


def refusal_for_delete(checklist) -> str | None:
    if checklist.system_key is None:
        return None
    return f"{_label(checklist)} is built in, so it can't be deleted. Nothing was changed."


def refusal_for_change(checklist, validated: dict, wanted_visibility: str | None) -> str | None:
    """A sentence refusing a PATCH of a built-in list, or None. Only a change
    that would actually break the list is refused: a client that re-sends the
    list's own current name or `archived=false` (the phone's outbox pushes the
    whole row) changes nothing and passes."""
    if checklist.system_key is None:
        return None
    label = _label(checklist)
    if "name" in validated and validated["name"] != checklist.name:
        return f"{label} is built in, so it can't be renamed. Nothing was changed."
    if validated.get("archived"):
        return f"{label} is built in, so it can't be archived. Nothing was changed."
    if wanted_visibility == VISIBILITY_PRIVATE:
        return f"{label} is built in, so it can't be made private. Nothing was changed."
    return None
