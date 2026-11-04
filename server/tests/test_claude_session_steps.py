"""Step history on a Claude session task (Kevin 2026-11-03, "I should be able to
see how many sessions are running etc.").

The worker's heartbeat already carries each running task's current step
(`progress`). The engine now also keeps the last 20 distinct ones per task,
with when each arrived, and serves them newest first as `steps` on every task
row. The history outlives the finish (the current line is cleared), is never
kept for a `listed` machine (ruling 5), goes with its task when the prune
deletes it, and is scoped by household like everything else here.
"""

from __future__ import annotations

import datetime

import pytest
from django.core.management import call_command
from django.utils import timezone

from claude_session.models import STEPS_MAX, ClaudeMachine, ClaudeTask

pytestmark = pytest.mark.django_db

API = "/api/claude/"


def _beat(client, name="Alienware", scope="full", progress=None):
    body = {"name": name, "hostname": f"{name}-host", "scope": scope, "state": "busy",
            "running": [], "claude_version": "2.1.316", "progress": progress or {}}
    response = client.post(f"{API}machines/heartbeat/", body, format="json")
    assert response.status_code == 200, response.data
    return response.data


def _running(client, name="Alienware", scope="full") -> int:
    _beat(client, name, scope)
    body = {"request": "find the AW4 wiring PDF", "machine": name, "parent": None,
            "origin": "named"}
    task_id = client.post(f"{API}tasks/", body, format="json").data["id"]
    claimed = client.post(f"{API}tasks/claim/", {"name": name}, format="json")
    assert claimed.status_code == 200, claimed.data
    return task_id


def _steps(client, task_id) -> list[str]:
    return [s["text"] for s in client.get(f"{API}tasks/{task_id}/").data["steps"]]


def test_history_grows_newest_first_with_times(token_a):
    a = _running(token_a)
    for text in ("reading Downloads", "opening wiring.pdf", "writing the summary"):
        _beat(token_a, progress={str(a): text})
    row = token_a.get(f"{API}tasks/{a}/").data
    assert [s["text"] for s in row["steps"]] == [
        "writing the summary", "opening wiring.pdf", "reading Downloads",
    ]
    times = [datetime.datetime.fromisoformat(s["at"].replace("Z", "+00:00")) for s in row["steps"]]
    assert times == sorted(times, reverse=True)
    # The list carries the same history, so the phone reads it without a detail call.
    listed = token_a.get(f"{API}tasks/").data
    assert [s["text"] for s in listed[0]["steps"]] == [s["text"] for s in row["steps"]]


def test_identical_consecutive_lines_are_kept_once(token_a):
    a = _running(token_a)
    for text in ("reading Downloads", "reading Downloads", "opening wiring.pdf",
                 "opening wiring.pdf", "reading Downloads"):
        _beat(token_a, progress={str(a): text})
    # Only a CHANGE is a step; a line that comes back later is a new step.
    assert _steps(token_a, a) == ["reading Downloads", "opening wiring.pdf", "reading Downloads"]


def test_a_blank_line_is_not_a_step(token_a):
    a = _running(token_a)
    _beat(token_a, progress={str(a): "reading Downloads"})
    _beat(token_a, progress={str(a): ""})
    _beat(token_a, progress={str(a): "reading Downloads"})
    assert _steps(token_a, a) == ["reading Downloads"]


def test_history_is_capped_at_20_dropping_the_oldest(token_a):
    a = _running(token_a)
    for n in range(STEPS_MAX + 5):
        _beat(token_a, progress={str(a): f"step {n}"})
    steps = _steps(token_a, a)
    assert STEPS_MAX == 20
    assert len(steps) == 20
    assert steps[0] == "step 24" and steps[-1] == "step 5"
    assert len(ClaudeTask.objects.get(pk=a).steps) == 20


def test_history_survives_the_finish_and_the_current_line_does_not(token_a):
    a = _running(token_a)
    _beat(token_a, progress={str(a): "reading Downloads"})
    _beat(token_a, progress={str(a): "found it"})
    token_a.post(f"{API}tasks/{a}/finish/", {"status": "done", "summary": "AW4 rev C"},
                 format="json")
    row = token_a.get(f"{API}tasks/{a}/").data
    assert row["status"] == "done"
    assert row["progress"] is None and row["progress_at"] is None
    assert [s["text"] for s in row["steps"]] == ["found it", "reading Downloads"]
    # A finished task takes no more steps, even if a late heartbeat names it.
    _beat(token_a, progress={str(a): "late line"})
    assert _steps(token_a, a) == ["found it", "reading Downloads"]


def test_a_listed_machine_keeps_the_current_line_but_no_history(token_a):
    w = _running(token_a, "work laptop", scope="listed")
    _beat(token_a, "work laptop", scope="listed", progress={str(w): "reading the repo"})
    _beat(token_a, "work laptop", scope="listed", progress={str(w): "running tests"})
    row = token_a.get(f"{API}tasks/{w}/").data
    assert row["progress"] == "running tests"
    assert row["steps"] == []
    assert ClaudeTask.objects.get(pk=w).steps == []


def test_a_machine_whose_scope_narrowed_serves_no_old_history(token_a):
    a = _running(token_a)
    _beat(token_a, progress={str(a): "reading Downloads"})
    ClaudeMachine.objects.filter(name="Alienware").update(scope="listed")
    assert token_a.get(f"{API}tasks/{a}/").data["steps"] == []


def test_another_machines_task_and_a_queued_task_take_no_steps(token_a):
    a = _running(token_a)
    other = _running(token_a, "work laptop", scope="listed")
    body = {"request": "later", "machine": "Alienware", "parent": None, "origin": "named"}
    queued = token_a.post(f"{API}tasks/", body, format="json").data["id"]
    _beat(token_a, progress={str(a): "mine", str(other): "not mine", str(queued): "not running"})
    assert _steps(token_a, a) == ["mine"]
    assert ClaudeTask.objects.get(pk=other).steps == []
    assert ClaudeTask.objects.get(pk=queued).steps == []


def test_steps_are_scoped_by_household(token_a, token_b):
    a = _running(token_a)
    _beat(token_a, progress={str(a): "reading Downloads"})
    # B's own Alienware naming A's task id changes nothing on A's task.
    _beat(token_b, progress={str(a): "planted by B"})
    assert _steps(token_a, a) == ["reading Downloads"]
    assert token_b.get(f"{API}tasks/{a}/").status_code == 404
    assert token_b.get(f"{API}tasks/").data == []


def test_the_prune_takes_the_history_with_its_task(token_a):
    a = _running(token_a)
    _beat(token_a, progress={str(a): "reading Downloads"})
    ClaudeTask.objects.filter(pk=a).update(
        created_at=timezone.now() - datetime.timedelta(days=31)
    )
    call_command("claude_session_prune")
    assert not ClaudeTask.objects.filter(pk=a).exists()


def test_the_task_string_carries_no_step_text(token_a):
    a = _running(token_a)
    _beat(token_a, progress={str(a): "reading Downloads"})
    assert "Downloads" not in str(ClaudeTask.objects.get(pk=a))
