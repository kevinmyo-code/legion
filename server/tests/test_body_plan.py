"""The plan (body coach 2, Kevin 2026-11-06): drafted with Alfred, locked in, adjusted as he goes;
Alfred sets the calorie target. Synthetic people and numbers only.
"""

from __future__ import annotations

import pytest
from django.db import IntegrityError, transaction

from body_coach import energy, plans
from body_coach.models import BodyPlan, BodyProfile, ProgrammeProposal, TrainingProgramme
from legacy.models.pantry import MealTarget
from tests.test_body_coach import THIS_YEAR, target, weigh

pytestmark = pytest.mark.django_db

BASE = "/api/body/coach"


def _profile(client, **extra):
    body = {"height_cm": 180, "sex": "male", "birth_year": THIS_YEAR - 30,
            "activity_level": "light", **extra}
    r = client.put(f"{BASE}/profile/", body, format="json")
    assert r.status_code == 200, r.data


def _ready(client, household, lbs=200.0):
    _profile(client)
    weigh(household, 1, lbs)


def test_the_rule_is_half_a_percent_and_the_profile_default_follows():
    assert plans.LEAN_KEEP_MUSCLE_RATE_BP == 50
    assert BodyProfile._meta.get_field("target_rate_bp").default == 50
    # At 0.5% a week the deficit is 5.5 kcal per kg a day: 200 lb loses about 1 lb a week.
    kg = 200 * 0.45359237
    assert round(energy.deficit_kcal(0.5, kg)) == 499


def test_an_incomplete_profile_makes_no_plan_and_says_what_is_missing(auth_client):
    body = auth_client.get(f"{BASE}/plan/").data
    assert body["active"] is None and body["draft"] is None
    assert "complete profile" in body["words"]
    r = auth_client.post(f"{BASE}/plan/draft/", {}, format="json")
    assert r.status_code == 400 and r.data["detail"].startswith("Nothing was drafted")
    assert BodyPlan.objects.count() == 0


def test_the_first_draft_is_built_on_read_and_writes_no_target(auth_client, household_a):
    _ready(auth_client, household_a)
    target(household_a, 2300, protein=150.0)  # a stale target from elsewhere
    body = auth_client.get(f"{BASE}/plan/").data
    d = body["draft"]
    assert d is not None and body["active"] is None
    assert d["rate_pct"] == 0.5 and d["target_set_by"] == "alfred" and d["reason"] == "first plan"
    est = auth_client.get(f"{BASE}/energy/").data
    weight_kg = est["weight_lbs"] * 0.45359237
    expected = round(est["maintenance_kcal"] - energy.deficit_kcal(0.5, weight_kg))
    assert d["kcal_target"] == expected and d["protein_g"] == est["protein_g"]
    assert d["lbs_per_week"] == 1.0
    assert "Nothing is live until you lock it in" in body["words"]
    assert "first plan" in body["words"]
    assert MealTarget.objects.filter(household=household_a).count() == 1
    # Read again: still one draft, not a second.
    auth_client.get(f"{BASE}/plan/")
    assert BodyPlan.objects.count() == 1


def test_lock_makes_the_target_and_programme_live_and_says_so_after(auth_client, household_a):
    _ready(auth_client, household_a)
    target(household_a, 2300, protein=150.0)
    d = auth_client.post(f"{BASE}/plan/draft/", {"sessions_per_week": 4}, format="json").data["draft"]
    assert d["programme"]["template"] == "upper_lower"
    r = auth_client.post(f"{BASE}/plan/{d['id']}/lock/")
    assert r.status_code == 200, r.data
    assert r.data["words"].startswith("Locked in, plan version 1. Your target is now about")
    assert "lose about 1 lb a week (0.50% of bodyweight)" in r.data["words"]
    left = auth_client.get(f"{BASE}/meal-options/").data
    assert left["target"]["kcal"] == d["kcal_target"]
    assert left["target"]["protein_g"] == d["protein_g"]
    assert TrainingProgramme.objects.get().template == "upper_lower"
    assert BodyProfile.objects.get().target_rate_bp == 50
    again = auth_client.post(f"{BASE}/plan/{d['id']}/lock/")
    assert again.status_code == 409


def test_a_target_he_sets_himself_stays_his_until_alfred_is_asked(auth_client, household_a):
    _ready(auth_client, household_a)
    d = auth_client.post(f"{BASE}/plan/draft/", {}, format="json").data["draft"]
    auth_client.post(f"{BASE}/plan/{d['id']}/lock/")
    # His own, after the lock: a same-day edit keeps the row the lock wrote.
    MealTarget.objects.filter(household=household_a).update(calories_kcal=1900, protein_g=140.0)
    mine = auth_client.post(f"{BASE}/plan/draft/", {"cardio": True}, format="json").data
    assert mine["draft"]["target_set_by"] == "member" and mine["draft"]["kcal_target"] == 1900
    assert "your own and stays" in mine["words"]
    alfred = auth_client.post(
        f"{BASE}/plan/draft/", {"alfred_sets_target": True}, format="json"
    ).data["draft"]
    assert alfred["target_set_by"] == "alfred" and alfred["kcal_target"] == d["kcal_target"]


def test_accepted_proposals_make_new_versions_with_reasons(auth_client, household_a):
    _ready(auth_client, household_a)
    d = auth_client.post(f"{BASE}/plan/draft/", {}, format="json").data["draft"]
    auth_client.post(f"{BASE}/plan/{d['id']}/lock/")
    p = auth_client.post(f"{BASE}/workout/proposals/", {"avoid": ["knee"]}, format="json").data
    ok = auth_client.post(f"{BASE}/workout/proposals/{p['proposal']['id']}/accept/")
    assert ok.status_code == 200, ok.data
    body = auth_client.get(f"{BASE}/plan/").data
    assert body["active"]["version"] == 2
    assert body["active"]["programme"]["avoid"] == ["knee"]
    assert [h["version"] for h in body["history"]] == [2, 1]
    assert body["history"][0]["reason"].startswith("Proposed:")
    assert "Version 2" in body["history_words"] and "Version 1" in body["history_words"]
    assert BodyPlan.objects.filter(status="superseded").count() == 1


def test_an_energy_proposal_accepted_versions_the_plan(auth_client, household_a):
    from datetime import timedelta

    from body_coach.models import EnergyProposal
    from tests.test_body_coach import today

    _ready(auth_client, household_a)
    d = auth_client.post(f"{BASE}/plan/draft/", {}, format="json").data["draft"]
    auth_client.post(f"{BASE}/plan/{d['id']}/lock/")
    proposal = EnergyProposal.objects.create(
        household=household_a, window_start=today() - timedelta(days=28),
        window_end=today() - timedelta(days=1), intake_days=28, weigh_in_days=4,
        mean_intake_kcal=2400.0, trend_start_lbs=200.0, trend_end_lbs=200.0,
        observed_maintenance_kcal=2400, observed_rate_bp=0, target_rate_bp=50,
        current_target_kcal=d["kcal_target"], proposed_target_kcal=d["kcal_target"] - 150,
    )
    r = auth_client.post(f"{BASE}/proposals/{proposal.id}/accept/")
    assert r.status_code == 200, r.data
    active = auth_client.get(f"{BASE}/plan/").data["active"]
    assert active["version"] == 2 and active["kcal_target"] == d["kcal_target"] - 150
    assert active["reason"].startswith("calorie target")
    # The adjusted target is the plan's, not "his own".
    assert "your own" not in auth_client.get(f"{BASE}/plan/").data["words"]


def test_discard_changes_nothing_and_a_lock_expires_a_stale_programme_proposal(auth_client, household_a):
    _ready(auth_client, household_a)
    d = auth_client.post(f"{BASE}/plan/draft/", {}, format="json").data["draft"]
    r = auth_client.post(f"{BASE}/plan/{d['id']}/discard/")
    assert r.status_code == 200 and "nothing changed" in r.data["words"]
    assert not MealTarget.objects.filter(household=household_a).exists()
    pending = auth_client.post(f"{BASE}/workout/proposals/", {"cardio": True}, format="json").data
    d2 = auth_client.post(f"{BASE}/plan/draft/", {"sessions_per_week": 2}, format="json").data["draft"]
    auth_client.post(f"{BASE}/plan/{d2['id']}/lock/")
    assert ProgrammeProposal.objects.get(pk=pending["proposal"]["id"]).status == "expired"


def test_the_checklist_carries_the_draft_line(auth_client, household_a):
    _ready(auth_client, household_a)
    line = auth_client.get(f"{BASE}/workout/").data["plan_draft"]
    assert line is not None and line["words"].startswith("Draft plan:")


@pytest.mark.parametrize("bad", [{"rate_pct": 2}, {"rate_pct": "fast"}, {"sessions_per_week": 9},
                                 {"colour": "red"}])
def test_choices_outside_the_rules_refuse(auth_client, household_a, bad):
    _ready(auth_client, household_a)
    r = auth_client.post(f"{BASE}/plan/draft/", bad, format="json")
    assert r.status_code == 400 and r.data["detail"].startswith("Nothing was drafted")


def test_one_draft_and_one_active_per_member_held_by_the_database(household_a, household_user):
    BodyPlan.objects.create(household=household_a, owner_user=household_user, programme={})
    with pytest.raises(IntegrityError), transaction.atomic():
        BodyPlan.objects.create(household=household_a, owner_user=household_user, programme={})


def test_a_plan_is_private_to_its_member(auth_client, household_a):
    from tests.conftest import _client_for
    from tests.test_visibility import _member

    mia = _member(household_a, "mia@example.com")

    _ready(auth_client, household_a)
    assert auth_client.get(f"{BASE}/plan/").data["draft"] is not None
    theirs = _client_for(mia).get(f"{BASE}/plan/").data
    assert theirs["draft"] is None and theirs["active"] is None


@pytest.fixture
def mcp(settings, household_user):
    from tests.test_engine_mcp import client_with

    settings.LEGION_MCP = True
    return client_with(household_user)


def test_the_tools_draft_read_back_and_lock_only_through_the_engine(mcp, auth_client, household_a):
    from tests.test_engine_mcp import call

    is_error, text, _ = call(mcp, "lock_body_plan", {})
    assert is_error and "no draft plan" in text
    _ready(auth_client, household_a)
    is_error, text, _ = call(mcp, "draft_body_plan", {"sessions_per_week": 3})
    assert not is_error and "Read this draft back" in text and "nothing is live" in text
    assert not MealTarget.objects.filter(household=household_a).exists()
    is_error, text, _ = call(mcp, "lock_body_plan", {})
    assert not is_error and "Your target is now about" in text and "committed" in text
    is_error, text, _ = call(mcp, "get_body_plan", {})
    assert not is_error and "version 1" in text
