"""`/api/body/<table>` - what is true of BODY beyond the shared contract in
`tests/test_synced_contract.py`, which already runs the four routes against
all eight of these tables.

Three things live here: the estimate labelling section 4 rule 5 requires,
the columns that deliberately do not cross, and what a unique constraint
does to a write (a 400 in words, never a 500).
"""
from __future__ import annotations

import pytest

from api.body import MealLogSerializer, MealTargetSerializer, WorkoutSetLogSerializer
from legacy.models.body import SleepTarget

pytestmark = pytest.mark.django_db

MEAL_LOG = {
    "description": "two eggs",
    "logged_at": "2026-09-01T08:00:00Z",
    "trust_tier": "REPORTED",
}


def test_meal_log_macros_are_labelled_estimates(auth_client):
    """CLAUDE.md section 4 rule 5: a plate of food never prints its own
    calorie count, so these four are the model's guess and must read as
    estimates on every surface. This API's surface is its schema, so the
    label rides on the field."""
    serializer = MealLogSerializer()
    for field in ("calories_kcal", "protein_g", "carbs_g", "fat_g"):
        help_text = serializer.fields[field].help_text
        assert help_text and "estimate" in help_text.lower(), field


def test_meal_target_macros_are_not_labelled_estimates():
    """A target is a number Kevin chose, not a guess about a plate of food.
    Labelling it an estimate would be as wrong as failing to label the
    meal log's."""
    serializer = MealTargetSerializer()
    for field in ("calories_kcal", "protein_g", "carbs_g", "fat_g"):
        help_text = serializer.fields[field].help_text
        assert not help_text or "estimate" not in help_text.lower(), field


def test_a_meal_log_accepts_its_macros_and_stores_them_as_given(auth_client):
    response = auth_client.put(
        "/api/body/meal_logs/guid-1/",
        MEAL_LOG | {"calories_kcal": 180, "protein_g": 12.5, "carbs_g": 1.0, "fat_g": 14.0},
        format="json",
    )
    assert response.status_code == 200, response.data
    assert response.data["calories_kcal"] == 180
    assert response.data["protein_g"] == 12.5
    # Nullable on purpose: an extraction can legitimately fail to produce a
    # usable number, and a missing estimate must stay missing rather than
    # being filled with a zero that reads like a measurement.
    omitted = auth_client.put("/api/body/meal_logs/guid-2/", MEAL_LOG, format="json")
    assert omitted.data["calories_kcal"] is None


def test_a_workout_set_log_carries_no_source_list_item_id():
    """`WorkoutSetLog.sourceListItemId` names a phone-local Room row id in
    a table with no server counterpart; carrying it here would be a
    dangling reference. The body migration says so on that table and
    `RemoteWorkoutSetLog` says so in Kotlin."""
    fields = set(WorkoutSetLogSerializer().fields)
    assert not [field for field in fields if "list_item" in field]
    assert not [field for field in fields if "source" in field]


def test_a_duplicate_unique_key_is_400_in_words_not_500(auth_client):
    """`sleep_targets_effective_from_date_unique`. Two writers could refuse
    this and it matters which one does: `SleepTarget.effective_from_date`
    carries `unique=True` on the model (inspectdb DOES capture a unique
    index, unlike a CHECK), so DRF's own `UniqueValidator` catches it first
    and answers in a readable sentence. `api/sync.save_or_400` is still the
    backstop underneath for anything the model does not declare - a
    constraint that lives only in SQL comes back as a 400 quoting Postgres,
    never a 500 - but this particular clash never reaches it."""
    first = auth_client.put(
        "/api/body/sleep_targets/guid-1/",
        {"target_minutes": 450, "effective_from_date": "2026-09-01"},
        format="json",
    )
    assert first.status_code == 200

    clash = auth_client.put(
        "/api/body/sleep_targets/guid-2/",
        {"target_minutes": 480, "effective_from_date": "2026-09-01"},
        format="json",
    )
    assert clash.status_code == 400, clash.data
    text = str(clash.data)
    assert "effective_from_date" in text and "already exists" in text
    assert SleepTarget.objects.count() == 1


def test_the_eight_body_tables_are_all_routed(auth_client):
    """The claim this aspect exists to prove - many small tables, one
    interface - is only true if every one of them actually answers."""
    tables = [
        "bodyweight_logs",
        "meal_logs",
        "meal_targets",
        "sleep_logs",
        "sleep_targets",
        "workout_plans",
        "workout_plan_items",
        "workout_set_logs",
    ]
    for table in tables:
        response = auth_client.get(f"/api/body/{table}/")
        assert response.status_code == 200, table
        assert response.data == {"results": [], "next": None}, table
