"""Canvas grades and what-I-need (Kevin, 2026-11-12).

Three layers, each tested on its own:

- **The read and the write.** `manage.py canvas_poll` over the recorded Canvas JSON in
  `tests/canvas_fixtures/` (the same `FakeCanvas` `tests/test_canvas_poll.py` uses): totals as
  Canvas stated them, NULL where the course hides them, the groups and the grading scheme, one row
  per course for the member whose login was read, nothing from a partial read.
- **The calculator** (`grades/calculator.py`), pure, on hand-made courses with figures worked out
  by hand in the comments.
- **The doors**: `/api/grades/` and the engine tools `canvas_grades` and `what_i_need`, and that
  another member sees none of it.

Owed and not provable here: a live run against Kevin's Canvas. The fields read
(`include[]=total_scores`'s `computed_*`, `apply_assignment_group_weights`, `group_weight`,
`rules`, `grading_standard_id`) are Canvas's documented ones, written into the fixtures by hand.
"""

from __future__ import annotations

import io
from fractions import Fraction

import pytest
from cryptography.fernet import Fernet
from django.core.management import call_command
from django.db import IntegrityError, transaction
from django.utils import timezone

from engine_mcp.tools import TOOLS_BY_NAME
from grades import calculator as calc
from grades.calculator import Course, Group, Item, Refusal, what_i_need
from grades.models import CourseGrade
from ingest import canvas, vault
from ingest.drive import Response
from ingest.models import Outcome
from tests.conftest import _client_for, _member
from tests.test_canvas_poll import BASE, FakeCanvas, cookie_jar, groups_url, last_run, task
from tests.test_engine_mcp import call, client_with

pytestmark = pytest.mark.django_db


# =============================================================================
# Fixtures
# =============================================================================


@pytest.fixture
def fake(monkeypatch):
    fake = FakeCanvas()
    monkeypatch.setattr(canvas, "urllib_transport", fake)
    monkeypatch.setattr(canvas, "_sleep", lambda seconds: None)
    return fake


@pytest.fixture
def vault_key(monkeypatch):
    monkeypatch.setenv(vault.VAULT_KEY_ENV, Fernet.generate_key().decode())


@pytest.fixture
def kevins_login(vault_key, household_a, household_user):
    return vault.store(
        household_a, "canvas", cookie_jar(), config={"base_url": BASE}, user=household_user
    )


@pytest.fixture
def unattributed_login(vault_key, household_a):
    return vault.store(household_a, "canvas", cookie_jar(), config={"base_url": BASE})


@pytest.fixture
def mia(household_a):
    return _member(household_a, "mia@example.com")


def poll() -> str:
    out = io.StringIO()
    call_command("canvas_poll", stdout=out)
    return out.getvalue()


def grade(course_id: int) -> CourseGrade:
    return CourseGrade.objects.get(canvas_course_id=course_id)


# =============================================================================
# The read: what Canvas said, stored as Canvas said it
# =============================================================================


def test_the_poll_stores_each_course_s_totals_groups_and_scheme(
    fake, kevins_login, household_a, household_user
):
    poll()
    assert last_run(household_a).outcome == Outcome.OK
    assert CourseGrade.objects.count() == 2
    math = grade(101)
    assert math.owner_user_id == household_user.pk
    assert math.household_id == household_a.pk
    assert math.course_label == "MATH 3391 Probability and Statistics I"
    assert math.course_code == "MATH 3391"
    assert (math.current_score, math.current_grade) == (90, "A-")
    assert (math.final_score, math.final_grade) == (27, "F")
    assert math.apply_weights is True
    assert [(g["id"], g["name"], g["weight"]) for g in math.groups] == [
        (11, "Quizzes", 30.0),
        (12, "Homework", 30.0),
        (13, "Discussions", 20.0),
        (14, "Final Exam", 20.0),
    ]
    assert math.groups[1]["rules"] == {"drop_lowest": 1}
    assert math.grading_scheme is None

    python = grade(202)
    # The course hides its totals: stored NULL, never worked out and called Canvas's.
    assert python.current_score is None and python.current_grade is None
    assert python.final_score is None and python.final_grade is None
    assert python.apply_weights is False
    assert python.grading_scheme["title"] == "COSC scale"
    assert python.grading_scheme["levels"][1] == {"name": "B", "value": 0.83}


def test_the_course_list_asks_canvas_for_total_scores(fake, kevins_login):
    poll()
    urls = [url for url, _ in fake.calls]
    assert any("include%5B%5D=total_scores" in url for url in urls)
    assert groups_url(101) in urls and groups_url(202) in urls
    assert f"{BASE}/api/v1/courses/202/grading_standards/7" in urls


def test_each_task_carries_its_assignment_group(fake, kevins_login, household_a):
    poll()
    assert task(household_a, 5001).structured_meta["assignment_group_id"] == 11
    assert task(household_a, 6002).structured_meta["assignment_group_id"] == 21
    assert "omit_from_final_grade" in task(household_a, 5001).structured_meta


def test_an_identical_second_run_changes_no_grade_row_and_a_new_total_updates_it(
    fake, kevins_login, household_a
):
    poll()
    assert last_run(household_a).rows_written == 9  # seven tasks and two courses
    before = grade(101).updated_at
    poll()
    assert last_run(household_a).rows_written == 0
    assert grade(101).updated_at == before
    fake.courses[0]["enrollments"][0]["computed_current_score"] = 95.5
    poll()
    assert last_run(household_a).rows_written == 1
    assert float(grade(101).current_score) == 95.5
    assert grade(101).updated_at > before


def test_a_failed_groups_read_fails_the_run_and_writes_nothing(fake, kevins_login, household_a):
    fake.queued[groups_url(101)] = [Response(500, {}, b"oops")]
    poll()
    run = last_run(household_a)
    assert run.outcome == Outcome.FAILED
    assert not CourseGrade.objects.exists()
    from legacy.models.dates import Event

    assert not Event.objects.exists()


def test_groups_canvas_refuses_keep_that_course_s_grades_and_write_the_rest(
    fake, kevins_login, household_a
):
    poll()
    fake.courses[0]["enrollments"][0]["computed_current_score"] = 50.0
    fake.queued[groups_url(101)] = [Response(403, {}, b"{}")]
    out = poll()
    assert last_run(household_a).outcome == Outcome.OK
    assert grade(101).current_score == 90  # left exactly as it was
    assert "Canvas refused its assignment groups" in out


def test_groups_missing_beside_assignments_is_a_partial_answer_and_keeps_the_row(
    fake, kevins_login, household_a
):
    poll()
    fake.courses[0]["enrollments"][0]["computed_current_score"] = 50.0
    fake.groups[101] = []
    out = poll()
    assert last_run(household_a).outcome == Outcome.OK
    assert grade(101).current_score == 90
    assert "no assignment groups" in out


def test_an_assignment_naming_a_group_canvas_did_not_return_keeps_the_row(
    fake, kevins_login, household_a
):
    fake.groups[101] = [g for g in fake.groups[101] if g["id"] != 13]
    out = poll()
    assert not CourseGrade.objects.filter(canvas_course_id=101).exists()
    assert CourseGrade.objects.filter(canvas_course_id=202).exists()
    assert "name a group Canvas did not return" in out


def test_a_score_that_is_not_a_number_fails_the_run_and_writes_nothing(
    fake, kevins_login, household_a
):
    fake.courses[0]["enrollments"][0]["computed_current_score"] = "ninety"
    poll()
    run = last_run(household_a)
    assert run.outcome == Outcome.FAILED
    assert "not a number" in run.error
    assert not CourseGrade.objects.exists()


def test_an_unattributed_login_stores_no_grades_and_says_why(fake, unattributed_login):
    out = poll()
    assert not CourseGrade.objects.exists()
    assert "Grades were not stored" in out


def test_dry_run_writes_no_grade_rows(fake, kevins_login):
    out = io.StringIO()
    call_command("canvas_poll", "--dry-run", stdout=out)
    assert "grades · MATH 3391" in out.getvalue()
    assert not CourseGrade.objects.exists()


# =============================================================================
# The calculator, on hand-made courses
# =============================================================================

HW, EXAMS = 1, 2


def _course(*, weighted=True, drop=0, canvas=Fraction(78), scale=None, weights=(40, 60)):
    return Course(
        label="STAT 101",
        apply_weights=weighted,
        groups=(
            Group(HW, "Homework", Fraction(weights[0]), drop_lowest=drop),
            Group(EXAMS, "Exams", Fraction(weights[1])),
        ),
        canvas_current_score=canvas,
        scale=scale,
    )


def _items(*extra):
    return [
        Item(1, "Homework 1", Fraction(100), Fraction(90), HW),
        Item(2, "Homework 2", Fraction(100), None, HW),
        Item(3, "Midterm", Fraction(100), Fraction(70), EXAMS),
        Item(4, "Final Exam", Fraction(100), None, EXAMS),
        *extra,
    ]


def test_weighted_final_only():
    # Homework 90% x 40 = 36; exams (70 + 100p) / 200 x 60 = 21 + 30p. 57 + 30p = 80: p = 23/30.
    answer = what_i_need(_course(), _items(), 80, remaining=[4], remaining_words="Final Exam")
    assert answer.status == calc.STATUS_NEEDED
    assert answer.needed_fraction == Fraction(23, 30)
    body = answer.as_dict()
    assert body["needed_percent"] == 76.67  # rounded up, never down
    assert body["needed_points"] == 76.67
    assert body["anchor"] == calc.ANCHOR_MATCHES  # 36 + 42 = 78, Canvas says 78
    assert body["estimate"] is False
    assert "1 other ungraded assignment(s) are left out" in body["words"]
    assert "Worked out here" in body["words"]


def test_weighted_all_remaining_by_default():
    # (90 + 100p)/200 x 40 + (70 + 100p)/200 x 60 = 39 + 50p = 80: p = 41/50.
    answer = what_i_need(_course(), _items(), "80%")
    assert answer.needed_fraction == Fraction(41, 50)
    assert answer.as_dict()["needed_points"] == 164.0
    assert answer.remaining_ids == [2, 4]
    assert answer.assumptions == []


def test_unweighted_is_plain_points():
    # (160 + 100p) / 300 = 75%: p = 65/100.
    course = _course(weighted=False, canvas=Fraction(80))
    answer = what_i_need(course, _items(), 75, remaining=[4])
    assert answer.needed_fraction == Fraction(65, 100)
    assert answer.anchor == calc.ANCHOR_MATCHES  # 160 / 200


def test_already_guaranteed_says_so():
    answer = what_i_need(_course(), _items(), 55, remaining=[4], remaining_words="Final Exam")
    assert answer.status == calc.STATUS_GUARANTEED
    assert answer.as_dict()["worst_reachable_percent"] == 57.0
    assert "already guaranteed: even 0 on Final Exam leaves the course at 57%" in answer.words()


def test_impossible_says_the_best_reachable():
    answer = what_i_need(_course(), _items(), "A", remaining=[4], remaining_words="Final Exam")
    assert answer.status == calc.STATUS_IMPOSSIBLE
    assert answer.as_dict()["best_reachable_percent"] == 87.0
    assert "out of reach: 100% on Final Exam reaches at most 87%" in answer.words()


def test_excused_work_is_neither_graded_nor_remaining():
    excused = Item(5, "Lab", Fraction(100), None, HW, excused=True)
    with_it = what_i_need(_course(), _items(excused), 80)
    without = what_i_need(_course(), _items(), 80)
    assert 5 not in with_it.remaining_ids
    assert with_it.needed_fraction == without.needed_fraction


def test_work_canvas_leaves_out_of_the_grade_does_not_count():
    omitted = Item(6, "Practice", Fraction(50), Fraction(0), HW, counts=False)
    assert what_i_need(_course(), _items(omitted), 80).needed_fraction == Fraction(41, 50)


def test_a_drop_rule_makes_it_an_estimate_in_words():
    answer = what_i_need(_course(drop=1), _items(), 80)
    assert answer.estimate
    words = answer.words()
    assert "This is an ESTIMATE" in words
    assert "Homework drops its lowest 1" in words
    assert answer.as_dict()["estimate"] is True


def test_a_total_that_disagrees_with_canvas_is_an_estimate():
    answer = what_i_need(_course(canvas=Fraction(80)), _items(), 80)
    assert answer.anchor == calc.ANCHOR_DIFFERS
    assert answer.estimate
    assert "Canvas's own current score is 80%" in answer.words()
    assert "worked out from the scores on file it is 78%" in answer.words()


def test_a_course_showing_no_total_is_said_and_not_filled_in():
    answer = what_i_need(_course(canvas=None), _items(), 80)
    assert answer.anchor == calc.ANCHOR_NOT_SHOWN
    assert not answer.estimate
    assert "Canvas shows no total for this course" in answer.words()


def test_a_letter_uses_the_default_scale_and_says_so():
    answer = what_i_need(_course(), _items(), "b")
    assert answer.target.percent == 80 and answer.target.letter == "B"
    assert "a default scale (A 90%, B 80%, C 70%, D 60%)" in answer.words()
    with pytest.raises(Refusal, match="its grades are A, B, C, D"):
        what_i_need(_course(), _items(), "B+")


def test_a_letter_uses_the_course_s_own_scheme_when_on_file():
    scale = (("A", Fraction(93)), ("B+", Fraction(87)), ("B", Fraction(83)))
    answer = what_i_need(_course(scale=scale), _items(), "B+")
    assert answer.target.percent == 87
    assert "the course's own grading scheme, where B+ starts at 87%" in answer.words()


def test_weights_under_100_are_scaled_up_and_the_empty_group_is_named():
    course = Course(
        label="X",
        apply_weights=True,
        groups=(
            Group(HW, "Homework", Fraction(30)),
            Group(EXAMS, "Exams", Fraction(30)),
            Group(3, "Project", Fraction(40)),
        ),
        canvas_current_score=Fraction(80),
    )
    # Only homework and exams hold work: (30 x .9 + 30 x .7) x 100 / 60 = 80.
    answer = what_i_need(course, _items(), 80)
    assert answer.current_worked_out == 80
    assert answer.anchor == calc.ANCHOR_MATCHES
    assert "Project (40%)" in answer.words()


def test_a_weighted_course_with_an_assignment_in_no_group_refuses():
    stray = Item(7, "Bonus", Fraction(10), Fraction(10), None)
    with pytest.raises(Refusal, match="have no group on file"):
        what_i_need(_course(), _items(stray), 80)


def test_others_at_assumes_the_other_ungraded_work():
    # Homework 2 at 100%: homework 190/200 x 40 = 38; 38 + 21 + 30p = 80: p = 7/10.
    answer = what_i_need(_course(), _items(), 80, remaining=[4], others_at=100)
    assert answer.needed_fraction == Fraction(7, 10)
    assert "assumed to score 100%" in answer.words()


def test_nothing_left_says_where_the_course_stands():
    graded = [
        Item(1, "Homework 1", Fraction(100), Fraction(90), HW),
        Item(3, "Midterm", Fraction(100), Fraction(70), EXAMS),
    ]
    answer = what_i_need(_course(), graded, 80)
    assert answer.status == calc.STATUS_NOTHING_LEFT
    assert "stands at 78%, which is below 80%" in answer.words()


def test_the_arithmetic_is_exact_not_float():
    # In floats (0.1 + 0.2 + 0.3p) / 0.9 = 0.5 gives p a hair over 0.5, and a ceiling would
    # show 50.01%. Exact, it is 50%.
    course = Course(label="X", apply_weights=False)
    items = [
        Item(1, "a", calc.exact(0.3), calc.exact(0.1)),
        Item(2, "b", calc.exact(0.3), calc.exact(0.2)),
        Item(3, "c", calc.exact(0.3), None),
    ]
    answer = what_i_need(course, items, 50)
    assert answer.needed_fraction == Fraction(1, 2)
    assert answer.as_dict()["needed_percent"] == 50.0


def test_display_rounding_never_flatters():
    assert calc.up(Fraction(100, 3)) == 33.34
    assert calc.down(Fraction(200, 3)) == 66.66
    assert calc.shown(Fraction(200, 3)) == 66.67


def test_choosing_what_is_remaining():
    course, items = _course(), _items()
    assert calc.select_remaining(course, items) == ([2, 4], "the remaining ungraded work")
    assert calc.select_remaining(course, items, assignment="final") == ([4], "Final Exam")
    assert calc.select_remaining(course, items, assignment=4) == ([4], "Final Exam")
    assert calc.select_remaining(course, items, group="exam") == (
        [4],
        "the ungraded work in Exams",
    )
    with pytest.raises(Refusal, match="matches 2 assignments"):
        calc.select_remaining(course, items, assignment="homework")
    with pytest.raises(Refusal, match="already has a score on Canvas"):
        calc.select_remaining(course, items, assignment="midterm")
    with pytest.raises(Refusal, match="No assignment group"):
        calc.select_remaining(course, items, group="labs")


def test_a_target_that_is_not_a_grade_refuses_in_words():
    for bad in ("", "lots", 0, "-5"):
        with pytest.raises(Refusal, match="^Nothing was worked out"):
            what_i_need(_course(), _items(), bad)


# =============================================================================
# The doors: REST and the engine tools, over a real poll
# =============================================================================


def test_the_api_lists_the_member_s_courses_with_scores(fake, kevins_login, auth_client):
    poll()
    response = auth_client.get("/api/grades/")
    assert response.status_code == 200, response.data
    by_id = {c["canvas_course_id"]: c for c in response.data["results"]}
    math = by_id[101]
    assert math["canvas_current_score"] == 90.0 and math["canvas_current_grade"] == "A-"
    assert "Canvas says your current grade is 90%, A-" in math["canvas_says"]
    assert math["current_worked_out_percent"] == 90.0
    quiz = next(a for a in math["assignments"] if a["id"] == 5001)
    assert (quiz["score"], quiz["points_possible"], quiz["graded"]) == (9.0, 10.0, True)
    assert by_id[202]["canvas_current_score"] is None
    assert "Canvas shows no total" in by_id[202]["canvas_says"]
    detail = auth_client.get("/api/grades/202")
    assert detail.status_code == 200 and detail.data["course"].startswith("COSC 3318")


def test_the_api_works_out_what_is_needed(fake, kevins_login, auth_client):
    poll()
    # Quizzes 9/10 x 30 = 27; homework and discussions take p on 20 points each (weights 30 and
    # 20); the final exam group holds nothing, so 80 of weight scales to 100:
    # (27 + 50p) x 100 / 80 = 80 gives p = 0.74.
    response = auth_client.get("/api/grades/101/what-i-need?target=B")
    assert response.status_code == 200, response.data
    body = response.data
    assert body["needed_percent"] == 74.0
    assert body["anchor"] == "matches"
    assert body["estimate"] is True  # homework drops its lowest
    assert "Final Exam (20%)" in body["words"]
    assert "a default scale" in body["scale"]

    # COSC hides its total and has its own scheme, where B is 83%.
    cosc = auth_client.get("/api/grades/202/what-i-need", {"target": "B"}).data
    assert cosc["needed_percent"] == 83.0
    assert cosc["anchor"] == "nothing_graded"
    assert cosc["estimate"] is False
    assert "the course's own grading scheme (COSC scale)" in cosc["scale"]

    missing = auth_client.get("/api/grades/101/what-i-need")
    assert missing.status_code == 400
    assert missing.data["detail"].startswith("Nothing was worked out")
    graded = auth_client.get("/api/grades/101/what-i-need?target=B&assignment=quiz")
    assert graded.status_code == 400 and "already has a score" in graded.data["detail"]


def test_the_engine_tools_read_the_member_s_own_grades(
    fake, kevins_login, household_user, settings
):
    settings.LEGION_MCP = True
    poll()
    mcp = client_with(household_user)
    is_error, text, structured = call(mcp, "canvas_grades", {})
    assert not is_error, text
    assert "Canvas says your current grade is 90%, A-" in text
    assert "Canvas shows no total for COSC 3318" in text
    is_error, text, _ = call(mcp, "canvas_grades", {"course": "math"})
    assert not is_error and "Quiz 9 of 10" in text and "Homework 30% (has a drop rule)" in text
    is_error, text, structured = call(mcp, "what_i_need", {"course": "MATH", "target": "B"})
    assert not is_error, text
    assert "you need at least 74%" in text
    assert "This is an ESTIMATE" in text
    assert structured["needed_percent"] == 74.0
    is_error, text, _ = call(mcp, "what_i_need", {"target": "B"})
    assert is_error and text.startswith("Nothing was read. You have several courses")


def test_the_tools_say_figures_from_canvas_are_canvas_s_and_projections_may_be_estimates():
    assert "Canvas's own figures" in TOOLS_BY_NAME["canvas_grades"].description
    assert "never a figure Canvas stated" in TOOLS_BY_NAME["what_i_need"].description
    assert "ESTIMATE" in TOOLS_BY_NAME["what_i_need"].description
    assert not TOOLS_BY_NAME["canvas_grades"].writes
    assert not TOOLS_BY_NAME["what_i_need"].writes


def test_no_grades_on_file_is_a_real_empty_result(household_user, auth_client, settings):
    settings.LEGION_MCP = True
    assert auth_client.get("/api/grades/").data == {"results": []}
    is_error, text, _ = call(client_with(household_user), "canvas_grades", {})
    assert not is_error and "real empty result" in text
    is_error, text, _ = call(client_with(household_user), "what_i_need", {"target": "B"})
    assert is_error and text.startswith("Nothing was worked out")


# =============================================================================
# Privacy: another member, another household
# =============================================================================


def test_another_member_sees_none_of_it(fake, kevins_login, mia, settings):
    settings.LEGION_MCP = True
    poll()
    mia_client = _client_for(mia)
    assert mia_client.get("/api/grades/").data == {"results": []}
    assert mia_client.get("/api/grades/101").status_code == 404
    assert mia_client.get("/api/grades/101/what-i-need?target=B").status_code == 404
    is_error, text, _ = call(client_with(mia), "canvas_grades", {"course": "math"})
    assert not is_error and "No Canvas grades are on file for you" in text
    assert "MATH" not in text


def test_another_household_sees_none_of_it(fake, kevins_login, user_b, settings):
    settings.LEGION_MCP = True
    poll()
    b = _client_for(user_b)
    assert b.get("/api/grades/").data == {"results": []}
    assert b.get("/api/grades/101").status_code == 404
    is_error, text, _ = call(client_with(user_b), "what_i_need", {"target": "B"})
    assert is_error and "MATH" not in text


def test_the_database_refuses_a_grade_row_owned_by_a_non_member(household_a, user_b):
    with pytest.raises(IntegrityError), transaction.atomic():
        CourseGrade.objects.create(
            household=household_a,
            owner_user=user_b,
            canvas_course_id=1,
            course_label="x",
            read_at=timezone.now(),
        )
