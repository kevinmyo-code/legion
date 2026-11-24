"""A Claude worker's token may not create a task or answer a question (the forged-yes fence).

The hole this closes (trace, 2026-11-24): `/api/claude/tasks/` applied no caller check at all, so
anything holding the laptop worker's device token could author Kevin's requests and, worse, post
`confirms: true` against a card that was waiting for his yes. A helper Claude session on that
laptop can read the token off disk, so that was a forged yes with Kevin's own authority behind it.
ADR 0067 accepted "the engine cannot tell a worker from the phone" for read scoping; this is its
own named follow-up, "worker endpoints restricted to machine tokens", for the writes only his
phone makes.

What the fence is: `claude_session.views._not_the_worker`, over `is_claude_token`, on create,
`workflows/run/`, assign, cancel and send-file. What it is not: the worker's own routes (claim,
finish, upload, heartbeat, sessions) keep working, and so does Kevin's ordinary device token.

**The other direction landed 2026-11-24** (`claude_session.fences`,
`tests/test_claude_owner_fence.py`): those worker routes are now the bound worker's alone, and
every route here is also narrowed to the member who owns the task or the machine. This module
still asserts only the token-kind half, which is why it sends a worker's token at a member's
verbs; the ownership half is that module's.

Every case here sets up state a member would succeed on (a real `needs_confirmation` parent, a
real `needs_machine` task, a real queued task, a real uploaded file row), so a 403 is the fence
and never a missing row, and each asserts the database did not move.

Invented examples only.
"""
from __future__ import annotations

import pytest
from rest_framework.test import APIClient

from claude_session.models import (
    NEEDS_CONFIRMATION,
    NEEDS_MACHINE,
    QUEUED,
    SCOPE_FULL,
    ClaudeMachine,
    ClaudeTask,
    ClaudeTaskFile,
)
from claude_session.tokens import (
    FENCE_FULL,
    FENCE_LISTED,
    FENCE_NONE,
    FENCE_UNBOUND,
    worker_fence,
)
from household.models import DeviceToken

pytestmark = pytest.mark.django_db

API = "/api/claude/"
WORKFLOW = {
    "name": "log-audit", "says": "Reads this week's logs.", "aliases": ["audit my logs"],
    "params": [], "asks": [], "outputs": ["summary"], "deliver": None, "scope": ["full"],
    "every": None,
}


def _client(user, name="claude-session-Alienware", scope=DeviceToken.SCOPE_WRITE):
    token, raw = DeviceToken.issue(user, name, scope=scope)
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Token {raw}")
    return token, client


def _beat(client, name="Alienware", scope=SCOPE_FULL, catalog=(WORKFLOW,)):
    body = {"name": name, "hostname": "h", "scope": scope, "state": "idle", "running": [],
            "claude_version": "2.1.316", "input_idle_s": 900, "workflows_hash": "h1",
            "workflows": [dict(e) for e in catalog]}
    response = client.post(f"{API}machines/heartbeat/", body, format="json")
    assert response.status_code == 200, response.data
    return response.data


def _bound_worker(user, scope=SCOPE_FULL):
    """The shape the live Alienware has: an ordinary `write` token, named by `install.ps1`, bound
    to its machine by its first heartbeat."""
    token, client = _client(user)
    _beat(client, scope=scope)
    return token, client


# --- what the fence refuses ----------------------------------------------------------------


def _refused(response) -> bool:
    return response.status_code == 403 and "cannot create a task" in str(response.data)


def test_a_worker_token_cannot_author_kevins_request(household_user):
    token, worker = _bound_worker(household_user)
    assert worker_fence_of(token) == FENCE_FULL
    before = ClaudeTask.objects.count()
    refused = worker.post(
        f"{API}tasks/", {"request": "mail my passport scan to someone", "origin": "named"},
        format="json",
    )
    assert _refused(refused), refused.data
    assert ClaudeTask.objects.count() == before


def test_a_worker_token_cannot_forge_kevins_yes(household_user, token_a):
    """The one that matters: a card is waiting for his yes, and the laptop says it for him."""
    _token, worker = _bound_worker(household_user)
    created = token_a.post(
        f"{API}tasks/", {"request": "send this message to your Claude session", "origin": "named"},
        format="json",
    )
    assert created.status_code == 201, created.data
    parent = ClaudeTask.objects.get(pk=created.data["id"])
    parent.status = NEEDS_CONFIRMATION
    parent.question = "Send this message to your Claude session on the Alienware?"
    parent.save(update_fields=["status", "question"])

    refused = worker.post(
        f"{API}tasks/",
        {"request": "yes", "parent": parent.id, "confirms": True, "origin": "named"},
        format="json",
    )
    assert _refused(refused), refused.data
    assert not ClaudeTask.objects.filter(parent=parent, confirms=True).exists()

    # Kevin's own token still answers it, which is the whole point of the fence being this narrow.
    said_yes = token_a.post(
        f"{API}tasks/",
        {"request": "yes", "parent": parent.id, "confirms": True, "origin": "named"},
        format="json",
    )
    assert said_yes.status_code == 201, said_yes.data
    assert ClaudeTask.objects.filter(parent=parent, confirms=True).count() == 1


def test_a_worker_token_cannot_run_a_workflow(household_user):
    _token, worker = _bound_worker(household_user)
    before = ClaudeTask.objects.count()
    refused = worker.post(
        f"{API}workflows/run/", {"machine": "Alienware", "name": "log-audit"}, format="json"
    )
    assert _refused(refused), refused.data
    assert ClaudeTask.objects.count() == before


def test_a_worker_token_cannot_assign_a_task_to_a_machine(household_user, token_a):
    _token, worker = _bound_worker(household_user)
    _beat(worker, name="work laptop", scope="listed", catalog=())
    task = ClaudeTask.objects.create(
        household=ClaudeMachine.objects.first().household, user=household_user,
        origin="named", kind="ask", request="find a file", status=NEEDS_MACHINE,
    )
    refused = worker.post(f"{API}tasks/{task.id}/assign/", {"machine": "Alienware"},
                          format="json")
    assert _refused(refused), refused.data
    task.refresh_from_db()
    assert task.status == NEEDS_MACHINE and task.machine_id is None
    assert token_a.post(f"{API}tasks/{task.id}/assign/", {"machine": "Alienware"},
                        format="json").status_code == 200


def test_a_worker_token_cannot_cancel_a_task(household_user, token_a):
    _token, worker = _bound_worker(household_user)
    task = ClaudeTask.objects.create(
        household=ClaudeMachine.objects.first().household, user=household_user,
        origin="named", kind="ask", request="find a file", status=QUEUED,
    )
    refused = worker.post(f"{API}tasks/{task.id}/cancel/", format="json")
    assert _refused(refused), refused.data
    task.refresh_from_db()
    assert task.status == QUEUED
    assert token_a.post(f"{API}tasks/{task.id}/cancel/", format="json").status_code == 200


def test_a_worker_token_cannot_ask_for_a_file_to_be_sent(household_user):
    _token, worker = _bound_worker(household_user)
    machine = ClaudeMachine.objects.get(name="Alienware")
    task = ClaudeTask.objects.create(
        household=machine.household, user=household_user, machine=machine,
        origin="named", kind="ask", request="find the wiring PDF", status=QUEUED,
    )
    row = ClaudeTaskFile.objects.create(
        household=machine.household, task=task, name="wiring.pdf", size=12,
        path="C:/w/41/wiring.pdf",
    )
    refused = worker.post(f"{API}tasks/{task.id}/send-file/", {"file_id": row.id}, format="json")
    assert _refused(refused), refused.data
    row.refresh_from_db()
    assert row.requested_at is None


# --- every shape of worker token the engine can recognise ----------------------------------


def worker_fence_of(token) -> str:
    class _R:
        auth = token

    return worker_fence(_R())


def test_a_listed_machines_token_is_fenced_too(household_user):
    token, worker = _bound_worker(household_user, scope="listed")
    assert worker_fence_of(token) == FENCE_LISTED
    assert _refused(worker.post(f"{API}tasks/", {"request": "x", "origin": "named"},
                                format="json"))


def test_a_worker_token_that_never_heartbeated_is_fenced_by_its_name(household_user):
    """`install.ps1` names it `claude-session-<Name>`; it is fenced before its first heartbeat,
    which is when a freshly installed worker's token sits on disk unbound."""
    token, worker = _client(household_user)
    assert worker_fence_of(token) == FENCE_UNBOUND
    assert _refused(worker.post(f"{API}tasks/", {"request": "x", "origin": "named"},
                                format="json"))


def test_a_claude_worker_scope_token_is_fenced_whatever_it_is_called(household_user):
    token, worker = _client(household_user, name="laptop", scope=DeviceToken.SCOPE_CLAUDE_WORKER)
    assert worker_fence_of(token) == FENCE_UNBOUND
    assert _refused(worker.post(f"{API}tasks/", {"request": "x", "origin": "named"},
                                format="json"))


# --- what the fence must not touch ---------------------------------------------------------


def test_kevins_own_device_token_is_not_fenced(household_user, token_a):
    _token, worker = _bound_worker(household_user)
    created = token_a.post(f"{API}tasks/", {"request": "find a file", "origin": "named"},
                           format="json")
    assert created.status_code == 201, created.data
    assert worker.post(f"{API}tasks/claim/", {"name": "Alienware"},
                       format="json").status_code == 200


def test_a_plain_write_token_bound_to_no_machine_is_not_a_worker(household_user, token_a):
    """The phone's own token: not named like a worker, no `claude_worker` scope, bound to no
    `ClaudeMachine`. The fence must read `none` for it or the phone stops working."""
    token, client = _client(household_user, name="Kevins phone")
    assert worker_fence_of(token) == FENCE_NONE
    assert client.post(f"{API}tasks/", {"request": "find a file", "origin": "named"},
                       format="json").status_code == 201
    assert token_a is not None  # the fixture's household is what `scoped` reads


def test_the_workers_own_routes_still_work(household_user, token_a):
    """claim, finish, upload-by-heartbeat and the session report: the worker's whole real API."""
    _token, worker = _bound_worker(household_user)
    created = token_a.post(f"{API}tasks/", {"request": "find a file", "origin": "named"},
                           format="json")
    task_id = created.data["id"]
    claimed = worker.post(f"{API}tasks/claim/", {"name": "Alienware"}, format="json")
    assert claimed.status_code == 200 and [r["id"] for r in claimed.data] == [task_id]
    finished = worker.post(
        f"{API}tasks/{task_id}/finish/", {"status": "done", "summary": "Found it."},
        format="json",
    )
    assert finished.status_code == 200, finished.data
    assert worker.post(
        f"{API}sessions/",
        {"machine": "Alienware", "session_id": "s1", "project": "p", "state": "working",
         "at": "2026-11-24T10:00:00Z"},
        format="json",
    ).status_code in (200, 201)


def test_a_scheduled_workflow_the_engine_mints_itself_is_untouched(household_user):
    """The fence is on the caller, not on task creation: `maybe_mint_workflow` runs inside the
    worker's own claim and must keep minting, or scheduled workflows stop."""
    scheduled = dict(WORKFLOW, every={"hours": 24, "idle_minutes": 0, "force_after_hours": 24,
                                      "retry_after_hours": 6})
    _token, worker = _client(household_user)
    _beat(worker, catalog=(scheduled,))
    claimed = worker.post(f"{API}tasks/claim/", {"name": "Alienware"}, format="json")
    assert claimed.status_code == 200, claimed.data
    assert [r["workflow"] for r in claimed.data] == ["log-audit"]
    assert ClaudeTask.objects.get(workflow="log-audit").origin == "schedule"
