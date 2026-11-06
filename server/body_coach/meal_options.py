"""What to eat from what's left, the rows side (body coach 2, 2026-11-06): today's meals, the
target, saved meals and recent groceries in; `suggest.py`'s ranking out. And "log that": an
option logged by its id, with figures recomputed from the rows, never taken from the caller.

**On hand** has no inventory behind it: nothing records what was eaten from a grocery run. It is
"bought in the last 14 days" (decision 13), each item as bought, and the words say so.
"""

from __future__ import annotations

import uuid
from datetime import datetime, timedelta

from django.utils import timezone

from body_coach import service, suggest
from body_coach.models import SavedMeal
from household.tenancy import scoped
from legacy.models.pantry import MealLog, ReceiptLineItem

ON_HAND_DAYS = 14


class OptionRefused(Exception):
    def __init__(self, message: str, code: int = 400):
        super().__init__(message)
        self.message = message
        self.code = code


def _f(x) -> float | None:
    return None if x is None else float(x)


def _positive(x) -> float | None:
    return float(x) if x is not None and x > 0 else None


def today_eaten(request, zone, today) -> suggest.Eaten:
    start = datetime.combine(today, datetime.min.time(), tzinfo=zone)
    rows = scoped(MealLog, request).filter(
        deleted_at__isnull=True, logged_at__gte=start, logged_at__lt=start + timedelta(days=1)
    )
    kcal = protein = 0.0
    carbs = fat = None
    meals = 0
    for k, p, c, f in rows.values_list("calories_kcal", "protein_g", "carbs_g", "fat_g"):
        meals += 1
        kcal += k or 0
        protein += p or 0
        if c is not None:
            carbs = (carbs or 0.0) + c
        if f is not None:
            fat = (fat or 0.0) + f
    return suggest.Eaten(int(kcal), protein, carbs, fat, meals)


def last_week(request, zone, today) -> suggest.Week:
    """The last 7 local days, today included, with a meal's kcal logged: their mean."""
    start = datetime.combine(today - timedelta(days=6), datetime.min.time(), tzinfo=zone)
    rows = scoped(MealLog, request).filter(
        deleted_at__isnull=True,
        calories_kcal__isnull=False,
        logged_at__gte=start,
        logged_at__lt=start + timedelta(days=7),
    )
    by_day: dict = {}
    for logged_at, kcal in rows.values_list("logged_at", "calories_kcal"):
        day = logged_at.astimezone(zone).date()
        by_day[day] = by_day.get(day, 0) + kcal
    if not by_day:
        return suggest.Week()
    return suggest.Week(days=len(by_day), mean_kcal=sum(by_day.values()) / len(by_day))


def targets(request, today) -> suggest.Targets:
    """The meal target wins; the energy estimate fills what it does not set. kcal and protein
    only (Kevin, 2026-11-06): a meal target's carbs and fat are not targets."""
    row = service.current_target(request, today)
    kcal = row.calories_kcal if row is not None and row.calories_kcal > 0 else None
    protein = _positive(row.protein_g) if row is not None else None
    basis = "meal target" if kcal is not None else None
    if kcal is None or protein is None:
        est = service.energy_body(request, propose=False)
        if kcal is None and est.get("suggested_target_kcal") is not None:
            kcal, basis = est["suggested_target_kcal"], "target suggested by the energy estimate"
        if protein is None and est.get("protein_g") is not None:
            protein = float(est["protein_g"])
    return suggest.Targets(
        kcal=kcal,
        kcal_basis=basis,
        protein_g=protein,
    )


def saved_candidates(request) -> list[suggest.Candidate]:
    return [
        suggest.Candidate(
            id=f"{suggest.SOURCE_SAVED}:{m.id}",
            source=suggest.SOURCE_SAVED,
            name=m.name,
            kcal=m.calories_kcal,
            protein_g=m.protein_g,
            carbs_g=m.carbs_g,
            fat_g=m.fat_g,
            basis=suggest.BASIS_SAVED,
            detail=m.description,
        )
        for m in scoped(SavedMeal, request).order_by("name")
    ]


def _live_items(request):
    """Line items that are neither a reversal nor reversed."""
    items = scoped(ReceiptLineItem, request)
    reversed_ids = items.filter(reversal_of__isnull=False).values_list("reversal_of_id", flat=True)
    return items.filter(reversal_of__isnull=True).exclude(pk__in=reversed_ids)


def _grocery(item, purchased_on) -> suggest.Candidate:
    return suggest.Candidate(
        id=f"{suggest.SOURCE_GROCERY}:{item.id}",
        source=suggest.SOURCE_GROCERY,
        name=item.name,
        kcal=int(round(float(item.estimated_calories_kcal))),
        protein_g=_f(item.estimated_protein_g) or 0.0,
        carbs_g=_f(item.estimated_carbs_g),
        fat_g=_f(item.estimated_fat_g),
        basis=suggest.BASIS_AS_BOUGHT,
        detail=f"bought {purchased_on.strftime('%a %d %b')}",
    )


def grocery_candidates(request, today) -> list[suggest.Candidate]:
    """Groceries bought in the last 14 days with an estimate, newest first, one per name."""
    rows = (
        _live_items(request)
        .filter(
            receipt__purchase_date__gte=today - timedelta(days=ON_HAND_DAYS - 1),
            receipt__purchase_date__lte=today,
            estimated_calories_kcal__isnull=False,
            estimated_calories_kcal__gt=0,
        )
        .select_related("receipt")
        .order_by("-receipt__purchase_date", "name", "id")
    )
    seen: set[str] = set()
    out = []
    for item in rows:
        key = item.name.strip().lower()
        if key in seen:
            continue
        seen.add(key)
        out.append(_grocery(item, item.receipt.purchase_date))
    return out


def options_body(request) -> dict:
    zone = service.zone_of(request)
    now = timezone.now().astimezone(zone)
    today = now.date()
    return suggest.suggest(
        clock=now.time(),
        targets=targets(request, today),
        eaten=today_eaten(request, zone, today),
        saved=saved_candidates(request),
        groceries=grocery_candidates(request, today),
        week=last_week(request, zone, today),
    )


# =============================================================================
# "Log that"
# =============================================================================


def _uuid(text: str, option_id: str) -> uuid.UUID:
    try:
        return uuid.UUID(text)
    except ValueError as exc:
        raise OptionRefused(f"Nothing was logged. {option_id!r} is not an option id.") from exc


def _item(request, text: str, option_id: str):
    item = _live_items(request).filter(pk=_uuid(text, option_id)).select_related("receipt").first()
    if item is None or item.estimated_calories_kcal is None:
        raise OptionRefused(
            "Nothing was logged. That grocery is not on file with an estimate any more.", 404
        )
    return item


def log_option(request, option_id) -> tuple[MealLog, str]:
    """Log one option by its id. The figures come from the rows now, never from the caller."""
    if not isinstance(option_id, str) or ":" not in option_id:
        raise OptionRefused(
            "Nothing was logged. option_id must be an id from the meal options, e.g. "
            "saved_meal:<uuid>."
        )
    source, _, rest = option_id.partition(":")
    if source == suggest.SOURCE_SAVED:
        meal = scoped(SavedMeal, request).filter(pk=_uuid(rest, option_id)).first()
        if meal is None:
            raise OptionRefused("Nothing was logged. There is no saved meal with that id.", 404)
        c = suggest.Candidate(
            option_id,
            source,
            meal.name,
            meal.calories_kcal,
            meal.protein_g,
            meal.carbs_g,
            meal.fat_g,
            detail=meal.description,
        )
        description = meal.description or meal.name
    elif source == suggest.SOURCE_GROCERY:
        item = _item(request, rest, option_id)
        c = _grocery(item, item.receipt.purchase_date)
        description = f"{c.name} (whole item as bought)"
    elif source == suggest.SOURCE_COMBO:
        ids = rest.split("+")
        if len(ids) != 2:
            raise OptionRefused(f"Nothing was logged. {option_id!r} is not an option id.")
        a, b = (
            _grocery(i, i.receipt.purchase_date)
            for i in (_item(request, x, option_id) for x in ids)
        )
        c = suggest.Candidate(
            option_id,
            source,
            f"{a.name} + {b.name}",
            a.kcal + b.kcal,
            a.protein_g + b.protein_g,
            suggest._add(a.carbs_g, b.carbs_g),
            suggest._add(a.fat_g, b.fat_g),
        )
        description = f"{c.name} (each whole item as bought)"
    else:
        raise OptionRefused(f"Nothing was logged. {option_id!r} is not an option id.")
    row = service.log_meal(
        request,
        description=description,
        calories_kcal=c.kcal,
        protein_g=round(c.protein_g, 1),
        carbs_g=None if c.carbs_g is None else round(c.carbs_g, 1),
        fat_g=None if c.fat_g is None else round(c.fat_g, 1),
    )
    words = (
        f"Logged {c.name}: {c.kcal} kcal and {c.protein_g:.0f} g protein, estimates, not "
        f"measurements."
    )
    return row, words
