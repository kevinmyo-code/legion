"""Kevin's first voice session with body coach 2 (2026-11-06): one number for the target, a lock
that replaces a target the model set by mistake, no carried carbs and fat, the profile the
estimate stands on, and no routine medical disclaimer. Synthetic people and numbers only.
"""

from __future__ import annotations

import pytest

from body_coach import energy
from legacy.models.pantry import MealTarget
from tests.test_body_coach import THIS_YEAR, target, weigh

pytestmark = pytest.mark.django_db

BASE = "/api/body/coach"


@pytest.fixture
def mcp(settings, household_user):
    from tests.test_engine_mcp import client_with

    settings.LEGION_MCP = True
    return client_with(household_user)


def _ready(client, household, *, rate_pct=None, lbs=210.0):
    body = {"height_cm": 175.3, "sex": "male", "birth_year": THIS_YEAR - 41,
            "activity_level": "light"}
    if rate_pct is not None:
        body["target_rate_pct"] = rate_pct
    r = client.put(f"{BASE}/profile/", body, format="json")
    assert r.status_code == 200, r.data
    weigh(household, 1, lbs)


def test_the_estimate_says_the_drafts_number_not_the_profiles_old_rate(auth_client, household_a):
    _ready(auth_client, household_a, rate_pct=0.75)
    before = auth_client.get(f"{BASE}/energy/").data
    assert before["target_rate_pct"] == 0.75 and before["plan"] is None
    draft = auth_client.get(f"{BASE}/plan/").data["draft"]
    assert draft["rate_pct"] == 0.5
    est = auth_client.get(f"{BASE}/energy/").data
    # ONE number: the read says what the checklist line says, at the draft's rate.
    assert est["suggested_target_kcal"] == draft["kcal_target"]
    assert est["target_rate_pct"] == 0.5
    assert est["suggested_target_kcal"] != before["suggested_target_kcal"]
    assert est["plan"]["status"] == "draft" and est["plan"]["kcal_target"] == draft["kcal_target"]
    assert "not locked in yet" in est["words"]
    weight_kg = est["weight_lbs"] * 0.45359237
    expected = round(est["maintenance_kcal"] - energy.deficit_kcal(0.5, weight_kg))
    assert draft["kcal_target"] == expected


def test_after_the_lock_the_estimate_reads_the_locked_plans_rate(auth_client, household_a):
    _ready(auth_client, household_a, rate_pct=0.75)
    draft = auth_client.get(f"{BASE}/plan/").data["draft"]
    assert auth_client.post(f"{BASE}/plan/{draft['id']}/lock/").status_code == 200
    est = auth_client.get(f"{BASE}/energy/").data
    assert est["plan"]["status"] == "active"
    assert est["target_rate_pct"] == 0.5
    assert est["current_target"]["calories_kcal"] == draft["kcal_target"]


def test_locking_replaces_a_target_set_before_any_lock_and_unsets_carbs_and_fat(
    auth_client, household_a
):
    _ready(auth_client, household_a, rate_pct=0.75)
    target(household_a, 2300, days_ago=40, protein=180.0, carbs=200.0, fat=70.0)
    draft = auth_client.get(f"{BASE}/plan/").data["draft"]
    # The bad call: a figure the model read back, written as today's target with the old row's
    # carbs and fat, after the draft was made and before it was locked.
    target(household_a, 1777, days_ago=0, protein=180.0, carbs=200.0, fat=70.0)
    # An explicit lock is consent: it replaces that target.
    r = auth_client.post(f"{BASE}/plan/{draft['id']}/lock/")
    assert r.status_code == 200, r.data
    assert "Your target is now about" in r.data["words"]
    today_rows = MealTarget.objects.filter(
        household=household_a, calories_kcal=draft["kcal_target"]
    )
    assert today_rows.count() == 1
    row = today_rows.get()
    assert row.protein_g == draft["protein_g"]
    assert row.carbs_g == 0.0 and row.fat_g == 0.0
    assert not MealTarget.objects.filter(household=household_a, calories_kcal=1777).exists()


def test_a_lock_with_no_target_today_writes_no_carried_carbs_or_fat(auth_client, household_a):
    _ready(auth_client, household_a)
    target(household_a, 2300, days_ago=40, protein=180.0, carbs=200.0, fat=70.0)
    draft = auth_client.post(f"{BASE}/plan/draft/", {}, format="json").data["draft"]
    auth_client.post(f"{BASE}/plan/{draft['id']}/lock/")
    rows = MealTarget.objects.filter(household=household_a)
    newest = rows.order_by("-effective_from_date").first()
    assert newest.calories_kcal == draft["kcal_target"]
    assert (newest.carbs_g, newest.fat_g) == (0.0, 0.0)


def test_the_estimate_carries_the_profile_it_stands_on(auth_client, household_a):
    _ready(auth_client, household_a)
    facts = auth_client.get(f"{BASE}/energy/").data["profile"]
    assert facts["height_cm"] == 175.3 and facts["height_ft_in"] == "5 ft 9 in"
    assert facts["birth_year"] == THIS_YEAR - 41 and facts["age_about"] == 41
    assert facts["sex"] == "male" and facts["activity_level"] == "light"


def test_the_engine_tool_offers_a_profile_update_and_adds_no_disclaimer(
    mcp, auth_client, household_a
):
    from tests.test_engine_mcp import call

    _ready(auth_client, household_a, rate_pct=0.75)
    auth_client.get(f"{BASE}/plan/")  # the first draft
    is_error, text, structured = call(mcp, "get_energy_estimate", {})
    assert not is_error
    assert "Built on the profile: height 5 ft 9 in (175.3 cm)" in text
    assert "ask whether to update it; update only after a yes (set_body_profile)" in text
    assert "no medical disclaimer on a routine figure" in text
    assert "800 kcal" in text and "disordered eating" in text
    assert "'lock it in' is lock_body_plan, never a meal target" in text
    assert "not locked in yet" in text
    assert "medical advice" not in text.lower()


def test_lock_tool_names_the_spoken_phrases():
    from engine_mcp.tools import TOOLS_BY_NAME

    desc = TOOLS_BY_NAME["lock_body_plan"].description
    assert '"lock it in"' in desc and '"lock that in"' in desc
    assert "replacing today's meal target" in desc
