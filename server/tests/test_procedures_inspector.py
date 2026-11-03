"""procedures ticket 14: the run inspector and the branch report (`procedures/views.py`).

Rulings: ADR 0070; `.scratch/procedures/issues/06` (traces), `07` (recruiters see their own
visit's runs only, never another household's, never a cross-household report). Runs come from
the real tool door where the shape matters (ticket 13's runner writes them) and are written
directly where only scoping is under test.
"""

from __future__ import annotations

import uuid

import pytest
from django.utils import timezone

from household.models import HouseholdMember, User
from household.recruiter_routes import RECRUITER_READ_ONLY
from procedures.models import ProcedureRun, ProcedureStep, RunStatus
from tests import test_procedures_runner as runner_tests
from tests import test_recruiter as recruiter_tests
from tests.test_procedures_runner import install, run, three_steps, with_check

# Fixtures, shared with the suites that define them (a parameter of the same name uses them).
_functions = runner_tests._functions
member_web = runner_tests.member_web
demo_household = recruiter_tests.demo_household
visitor = recruiter_tests.visitor
recruiter_web = recruiter_tests.recruiter_web

pytestmark = pytest.mark.django_db


def _member_of(household, email):
    user = User.objects.create_user(email=email, password="correct horse battery")
    HouseholdMember.objects.create(user=user, household=household, role=HouseholdMember.OWNER)
    return user


def _direct_run(version, household, user, caller_kind="web", status=RunStatus.OK, steps=()):
    """A run row written straight to the table, for scoping tests."""
    trace = ProcedureRun.objects.create(
        household=household,
        user=user,
        version=version,
        caller_kind=caller_kind,
        status=status,
        started_at=timezone.now(),
        ms=12,
        result="An answer.",
    )
    for seq, (step_id, kind, outcome, branch, chosen_by) in enumerate(steps):
        ProcedureStep.objects.create(
            run=trace,
            household=household,
            seq=seq,
            step_id=step_id,
            kind=kind,
            outcome=outcome,
            branch=branch,
            chosen_by=chosen_by,
            started_at=timezone.now(),
            ms=3,
        )
    return trace


# =============================================================================
# The runs list and one run
# =============================================================================


def test_the_list_and_detail_show_a_run_its_steps_and_what_did_not_run(member_web):
    install(with_check())
    run(member_web, "checked", inputs={"n": 0})
    listing = member_web.get("/api/procedures/runs")
    assert listing.status_code == 200, listing.data
    (row,) = listing.data["results"]
    assert row["procedure"] == "checked" and row["version"] == 1
    assert row["who"] == "You" and row["caller"] == "Web assistant"
    assert row["status"] == "ok" and row["step_count"] == 1
    assert listing.data["more"] is False and listing.data["retention_days"] == 60

    detail = member_web.get(f"/api/procedures/runs/{row['id']}")
    assert detail.status_code == 200, detail.data
    (step,) = detail.data["steps"]
    assert step["step_id"] == "positive" and step["kind"] == "check"
    assert step["branch"] == "fail" and step["result"] == "Nothing to report."
    assert step["chosen_by_label"] == "Code chose"
    assert step["args"] == '{"n": 0}'
    assert detail.data["not_run"] == ["answer"]
    assert detail.data["not_run_sentence"] == "Not on this run's path, so they did not run: answer."


def test_a_model_choice_is_labelled_a_model_chose(member_web, household_a, household_user):
    version = install(three_steps())
    trace = _direct_run(
        version, household_a, household_user,
        steps=[("lists", "decide", "ok", "short", "model")],
    )
    detail = member_web.get(f"/api/procedures/runs/{trace.id}")
    assert detail.data["steps"][0]["chosen_by_label"] == "A model chose"


def test_filters_narrow_the_list_and_a_bad_filter_refuses_in_words(member_web):
    install(three_steps())
    install(with_check())
    run(member_web, "checklist_check")
    run(member_web, "checked", inputs={"n": 1})
    assert len(member_web.get("/api/procedures/runs").data["results"]) == 2
    only = member_web.get("/api/procedures/runs?procedure=checked").data["results"]
    assert [r["procedure"] for r in only] == ["checked"]
    assert member_web.get("/api/procedures/runs?status=failed").data["results"] == []
    assert len(member_web.get("/api/procedures/runs?caller_kind=web").data["results"]) == 2
    limited = member_web.get("/api/procedures/runs?limit=1").data
    assert len(limited["results"]) == 1 and limited["more"] is True
    bad = member_web.get("/api/procedures/runs?status=nonsense")
    assert bad.status_code == 400 and bad.data["detail"].startswith("Nothing was read.")


def test_with_no_runs_the_list_is_empty_not_an_error(member_web):
    body = member_web.get("/api/procedures/runs")
    assert body.status_code == 200 and body.data["results"] == []


# =============================================================================
# Scoping (ADR 0045) and recruiters (ruling 07)
# =============================================================================


def test_another_households_runs_are_never_listed_or_readable(
    member_web, household_a, household_b, household_user
):
    version = install(three_steps())  # a built-in: one version, shared by both households
    mine = _direct_run(version, household_a, household_user)
    theirs = _direct_run(version, household_b, _member_of(household_b, "b@example.com"))
    ids = [r["id"] for r in member_web.get("/api/procedures/runs").data["results"]]
    assert ids == [str(mine.id)]
    refused = member_web.get(f"/api/procedures/runs/{theirs.id}")
    assert refused.status_code == 404 and "Nothing was read." in refused.data["detail"]
    assert member_web.get(f"/api/procedures/runs/{uuid.uuid4()}").status_code == 404


def test_a_member_sees_another_members_run_by_name(member_web, household_a):
    version = install(three_steps())
    mia = _member_of(household_a, "mia@example.com")
    mia.first_name = "Mia"
    mia.save()
    _direct_run(version, household_a, mia)
    (row,) = member_web.get("/api/procedures/runs").data["results"]
    assert row["who"] == "Mia"


def test_a_recruiter_sees_only_their_own_visits_runs(
    recruiter_web, visitor, demo_household, household_a, household_user
):
    version = install(three_steps())
    kevins = _direct_run(version, household_a, household_user)
    own = _direct_run(version, demo_household, visitor, caller_kind="recruiter")
    # Another visit, another visitor: never visible to this one.
    other_visit = demo_household.__class__.objects.create(name="Other visit", is_demo_visit=True)
    stranger = User.objects.create_user(email="other-visitor@demo.invalid")
    HouseholdMember.objects.create(
        user=stranger, household=other_visit, role=HouseholdMember.RECRUITER
    )
    others = _direct_run(version, other_visit, stranger, caller_kind="recruiter")

    listing = recruiter_web.get("/api/procedures/runs")
    assert listing.status_code == 200, listing.data
    assert [r["id"] for r in listing.data["results"]] == [str(own.id)]
    assert recruiter_web.get(f"/api/procedures/runs/{own.id}").status_code == 200
    for run_id in (kevins.id, others.id):
        assert recruiter_web.get(f"/api/procedures/runs/{run_id}").status_code == 404
    report = recruiter_web.get("/api/procedures/report?name=checklist_check").data
    assert report["runs"] == 1


def test_the_inspector_routes_are_read_only_for_a_recruiter(recruiter_web):
    for key in (
        "procedures.views.ProcedureListView",
        "procedures.views.RunListView",
        "procedures.views.RunDetailView",
        "procedures.views.BranchReportView",
    ):
        assert key in RECRUITER_READ_ONLY
    refused = recruiter_web.post("/api/procedures/runs", {}, format="json")
    assert refused.status_code == 403 and refused.data["detail"].startswith("Nothing was written.")


# =============================================================================
# The branch report
# =============================================================================


def test_the_report_counts_taken_never_taken_and_failed(
    member_web, household_a, household_b, household_user
):
    version = install(with_check())
    run(member_web, "checked", inputs={"n": 1})
    run(member_web, "checked", inputs={"n": 2})
    report = member_web.get("/api/procedures/report?name=checked")
    assert report.status_code == 200, report.data
    body = report.data
    assert body["runs"] == 2 and body["window_days"] == 60
    positive, answer = body["steps"]
    assert positive["step_id"] == "positive" and positive["reached"] == 2
    by_branch = {b["branch"]: b for b in positive["branches"]}
    assert by_branch["pass"]["taken"] == 2 and by_branch["pass"]["target"] == "answer"
    assert by_branch["fail"]["never_taken"] is True and by_branch["fail"]["target"] is None
    assert answer["reached"] == 2 and answer["branches"] == []
    assert body["never_taken"] == ["positive:fail"]
    assert "never taken: positive:fail" in body["summary"]

    # Another household's runs of the same built-in are never counted here.
    _direct_run(
        version, household_b, _member_of(household_b, "b2@example.com"),
        steps=[("positive", "check", "ok", "fail", "code")],
    )
    again = member_web.get("/api/procedures/report?name=checked").data
    assert again["runs"] == 2 and again["never_taken"] == ["positive:fail"]

    # A failed step counts against the step.
    _direct_run(
        version, household_a, household_user, status=RunStatus.FAILED,
        steps=[("positive", "check", "failed", "", "code")],
    )
    failed = member_web.get("/api/procedures/report?name=checked").data
    assert failed["steps"][0]["failed"] == 1 and failed["steps"][0]["last_failed_at"]


def test_a_report_with_no_runs_says_so_and_an_unknown_name_is_a_404(member_web):
    install(three_steps())
    body = member_web.get("/api/procedures/report?name=checklist_check").data
    assert body["runs"] == 0
    assert body["summary"].startswith("checklist_check v1 has not run in this household")
    missing = member_web.get("/api/procedures/report?name=nope")
    assert missing.status_code == 404 and "Nothing was read." in missing.data["detail"]
    assert member_web.get("/api/procedures/report").status_code == 400


def test_another_households_own_procedure_has_no_report_here(member_web, household_b):
    install(three_steps("their_check"), household=household_b)
    assert member_web.get("/api/procedures/report?name=their_check").status_code == 404
    names = [v["procedure"] for v in member_web.get("/api/procedures/").data]
    assert "their_check" not in names


def test_the_procedure_list_carries_this_households_run_counts(member_web):
    install(three_steps())
    run(member_web, "checklist_check")
    (row,) = member_web.get("/api/procedures/").data
    assert row["procedure"] == "checklist_check" and row["runs"] == 1
    assert row["is_active"] is True and row["last_run_at"]
