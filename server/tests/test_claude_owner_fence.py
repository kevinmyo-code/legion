"""A second household member reaches none of Kevin's Claude work (`claude_session.fences`).

The hole this closes, traced 2026-11-24 before Kevin adds his wife to his household:
`IsHouseholdMember` carries no role check and `/api/claude/` fenced by household alone, so ANY
member of the household could read every task Kevin ever sent, post `confirms: true` against a
card that was waiting for his yes, queue work on his Alienware, and heartbeat as his machine
(`_touch_machine` binds `ClaudeMachine.token` to whatever token beats, so the laptop identity
moved with it). Tenancy was never the fence here: the wife joins Kevin's OWN household, so
`scoped()` returns his rows to her.

Three rules, one test class each:

- `TestAnotherMemberIsRefused`: every `/api/claude/` route, for a member of the same household
  who owns nothing. Each case sets up state Kevin would succeed on, then asserts the refusal AND
  that the database did not move, so a pass is the fence and never a missing row.
- `TestKevinStillWorks`: the whole round trip he uses - create, claim, finish, status, files,
  workflows, sessions - on his own tokens.
- `TestOnlyTheBoundWorker`: the worker's verbs belong to the worker the machine's heartbeat bound.
  A member's app token and a SECOND worker token of Kevin's own are both refused.

Invented examples only; no personal data.
"""
from __future__ import annotations

import pytest
from django.core.files.uploadedfile import SimpleUploadedFile
from rest_framework.test import APIClient

from claude_session.models import (
    DELIVER_FILES,
    NEEDS_CONFIRMATION,
    NEEDS_MACHINE,
    QUEUED,
    RUNNING,
    ClaudeCodeSession,
    ClaudeMachine,
    ClaudeTask,
    ClaudeTaskFile,
)
from household.models import DeviceToken, HouseholdMember, User

pytestmark = pytest.mark.django_db

API = "/api/claude/"
MACHINE = "Alienware"
WORKFLOW = {
    "name": "log-audit", "says": "Reads this week's logs.", "aliases": ["audit my logs"],
    "params": [], "asks": [], "outputs": ["summary"], "deliver": None, "scope": ["full"],
    "every": None,
}


def _client_for(token_raw: str) -> APIClient:
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Token {token_raw}")
    return client


@pytest.fixture
def other_member(household_a):
    """An ordinary member of Kevin's own household: the shape his wife's account will have."""
    user = User.objects.create_user(email="member@example.com", password="correct horse battery")
    HouseholdMember.objects.create(
        user=user, household=household_a, role=HouseholdMember.MEMBER
    )
    return user


@pytest.fixture
def other(other_member):
    """That member's ordinary app token."""
    _token, raw = DeviceToken.issue(other_member, "Her phone")
    return _client_for(raw)


@pytest.fixture
def worker(household_user):
    """Kevin's laptop worker: a second token of his own, the one the heartbeat binds."""
    _token, raw = DeviceToken.issue(
        household_user, f"claude-session-{MACHINE}", scope=DeviceToken.SCOPE_CLAUDE_WORKER
    )
    return _client_for(raw)


def _beat(client, name=MACHINE, scope="full", state="idle", **extra):
    body = {"name": name, "hostname": f"{name}-host", "scope": scope, "state": state,
            "running": [], "claude_version": "2.1.316", **extra}
    return client.post(f"{API}machines/heartbeat/", body, format="json")


def _create(client, request="find the AW4 wiring diagram", **extra):
    body = {"request": request, "machine": None, "parent": None, "origin": "named", **extra}
    return client.post(f"{API}tasks/", body, format="json")


def _claim(client, name=MACHINE):
    return client.post(f"{API}tasks/claim/", {"name": name}, format="json")


def _machine() -> ClaudeMachine:
    return ClaudeMachine.objects.get(name=MACHINE)


@pytest.fixture
def his_task(token_a, worker):
    """Kevin's machine beaten by his worker, and one task of his queued on it."""
    assert _beat(worker).status_code == 200
    created = _create(token_a, machine=MACHINE)
    assert created.status_code == 201, created.data
    return ClaudeTask.objects.get(pk=created.data["id"])


class TestAnotherMemberIsRefused:
    def test_his_tasks_are_not_listed_or_readable(self, other, his_task):
        listed = other.get(f"{API}tasks/")
        assert listed.status_code == 200
        assert listed.data == []
        one = other.get(f"{API}tasks/{his_task.id}/")
        assert one.status_code == 404
        assert str(his_task.id) in one.data["detail"]

    def test_his_machine_is_not_hers_to_queue_work_on(self, other, his_task):
        refused = _create(other, machine=MACHINE)
        assert refused.status_code == 400, refused.data
        assert "No machine has that name here" in refused.data["detail"]
        assert ClaudeTask.objects.filter(machine=his_task.machine).count() == 1

    def test_her_unaddressed_task_is_never_claimed_by_his_worker(self, other, worker, his_task):
        hers = _create(other, request="rename my files")
        assert hers.status_code == 201, hers.data
        assert hers.data["machine"] is None
        claimed = _claim(worker)
        assert claimed.status_code == 200
        assert [row["id"] for row in claimed.data] == [his_task.id]
        assert ClaudeTask.objects.get(pk=hers.data["id"]).status == QUEUED

    def test_she_cannot_forge_his_yes(self, other, worker, his_task):
        claimed = _claim(worker)
        assert claimed.status_code == 200
        finished = worker.post(
            f"{API}tasks/{his_task.id}/finish/",
            {"status": "needs_confirmation", "summary": "", "question": "Send this message?"},
            format="json",
        )
        assert finished.status_code == 200, finished.data
        assert ClaudeTask.objects.get(pk=his_task.id).status == NEEDS_CONFIRMATION

        forged = _create(other, request="yes", parent=his_task.id, confirms=True)
        assert forged.status_code == 400, forged.data
        assert "no task" in forged.data["detail"]
        assert not ClaudeTask.objects.filter(parent=his_task, confirms=True).exists()
        assert ClaudeTask.objects.get(pk=his_task.id).status == NEEDS_CONFIRMATION

    def test_she_cannot_cancel_his_task(self, other, his_task):
        refused = other.post(f"{API}tasks/{his_task.id}/cancel/", format="json")
        assert refused.status_code == 404
        assert ClaudeTask.objects.get(pk=his_task.id).status == QUEUED

    def test_she_cannot_assign_his_unaddressed_task(self, other, token_a, worker):
        assert _beat(worker).status_code == 200
        assert _beat(worker, name="work laptop", scope="listed").status_code == 200
        his = _create(token_a)
        assert his.data["status"] == NEEDS_MACHINE
        refused = other.post(
            f"{API}tasks/{his.data['id']}/assign/", {"machine": MACHINE}, format="json"
        )
        assert refused.status_code == 404
        assert ClaudeTask.objects.get(pk=his.data["id"]).status == NEEDS_MACHINE

    def test_she_cannot_ask_for_a_file_of_his(self, other, his_task):
        row = ClaudeTaskFile.objects.create(
            household=his_task.household, task=his_task, path="C:/x/notes.txt",
            name="notes.txt", size=12,
        )
        refused = other.post(
            f"{API}tasks/{his_task.id}/send-file/", {"file_id": row.id}, format="json"
        )
        assert refused.status_code == 404
        row.refresh_from_db()
        assert row.requested_at is None

    def test_the_status_screen_shows_her_none_of_his_laptop(self, other, worker, his_task):
        assert worker.post(
            f"{API}sessions/",
            {"machine": MACHINE, "session_id": "s-1", "project": "legion", "state": "waiting",
             "name": "a session", "last_prompt_summary": "a summary", "at": "2026-11-24T10:00:00Z"},
            format="json",
        ).status_code == 200
        status = other.get(f"{API}status/")
        assert status.status_code == 200
        assert status.data["machines"] == []
        assert status.data["sessions"] == []

    def test_she_cannot_heartbeat_as_his_machine(self, other, his_task):
        before = _machine().token_id
        refused = _beat(other)
        assert refused.status_code == 403, refused.data
        # Her app token is refused as a worker verb before the machine is even looked up; the
        # test below is the one that proves the ownership half.
        assert "Only a Claude worker's own token" in refused.data["detail"]
        assert _machine().token_id == before

    def test_her_own_worker_token_still_cannot_rebind_his_machine(self, other_member, his_task):
        """The name is the installer's convention, so a member could pick it. The binding is not."""
        _token, raw = DeviceToken.issue(
            other_member, f"claude-session-{MACHINE}", scope=DeviceToken.SCOPE_CLAUDE_WORKER
        )
        before = _machine().token_id
        refused = _beat(_client_for(raw))
        assert refused.status_code == 403, refused.data
        assert "another member's laptop" in refused.data["detail"]
        assert _machine().token_id == before

    def test_she_cannot_claim_his_queue(self, other, his_task):
        refused = _claim(other)
        assert refused.status_code in (403, 404), refused.data
        assert ClaudeTask.objects.get(pk=his_task.id).status == QUEUED

    def test_she_cannot_finish_his_running_task(self, other, worker, his_task):
        assert _claim(worker).status_code == 200
        refused = other.post(
            f"{API}tasks/{his_task.id}/finish/",
            {"status": "done", "summary": "I did it", "question": ""},
            format="json",
        )
        assert refused.status_code == 404
        after = ClaudeTask.objects.get(pk=his_task.id)
        assert after.status == RUNNING and after.summary is None

    def test_she_cannot_upload_a_file_against_his_task(self, other, worker, his_task):
        assert _claim(worker).status_code == 200
        row = ClaudeTaskFile.objects.create(
            household=his_task.household, task=his_task, path="C:/x/notes.txt",
            name="notes.txt", size=12, requested_at=his_task.created_at,
        )
        refused = other.post(
            f"{API}tasks/{his_task.id}/files/",
            {"file_id": row.id, "file": SimpleUploadedFile("notes.txt", b"not his words")},
            format="multipart",
        )
        assert refused.status_code == 404
        row.refresh_from_db()
        assert row.uploaded_at is None and row.content is None

    def test_she_cannot_report_a_session_on_his_machine(self, other, his_task):
        refused = other.post(
            f"{API}sessions/",
            {"machine": MACHINE, "session_id": "s-2", "project": "legion", "state": "waiting",
             "name": "hers", "last_prompt_summary": "x", "at": "2026-11-24T10:00:00Z"},
            format="json",
        )
        assert refused.status_code == 403, refused.data
        assert not ClaudeCodeSession.objects.filter(session_id="s-2").exists()

    def test_his_workflows_are_neither_listed_nor_runnable_by_her(self, other, worker, his_task):
        assert _beat(worker, workflows=[WORKFLOW], workflows_hash="h1").status_code == 200
        listed = other.get(f"{API}workflows/")
        assert listed.status_code == 200
        assert listed.data == []
        refused = other.post(
            f"{API}workflows/run/", {"machine": MACHINE, "name": "log-audit"}, format="json"
        )
        assert refused.status_code == 400, refused.data
        assert not ClaudeTask.objects.filter(workflow="log-audit").exists()


class TestKevinStillWorks:
    def test_the_round_trip_he_uses(self, token_a, worker):
        assert _beat(worker).status_code == 200
        created = _create(token_a, machine=MACHINE)
        assert created.status_code == 201, created.data
        task_id = created.data["id"]

        assert [row["id"] for row in token_a.get(f"{API}tasks/").data] == [task_id]
        assert token_a.get(f"{API}tasks/{task_id}/").status_code == 200

        claimed = _claim(worker)
        assert claimed.status_code == 200
        assert [row["id"] for row in claimed.data] == [task_id]
        assert _beat(worker, state="busy", progress={"task": task_id, "line": "reading"}
                     ).status_code == 200
        finished = worker.post(
            f"{API}tasks/{task_id}/finish/",
            {"status": "done", "summary": "Found one file.", "question": ""},
            format="json",
        )
        assert finished.status_code == 200, finished.data
        assert ClaudeTask.objects.get(pk=task_id).summary == "Found one file."

        status = token_a.get(f"{API}status/")
        assert [m["name"] for m in status.data["machines"]] == [MACHINE]

    def test_he_can_cancel_assign_and_ask_for_a_file(self, token_a, worker):
        assert _beat(worker).status_code == 200
        assert _beat(worker, name="work laptop", scope="listed").status_code == 200
        unaddressed = _create(token_a)
        assert unaddressed.data["status"] == NEEDS_MACHINE
        assigned = token_a.post(
            f"{API}tasks/{unaddressed.data['id']}/assign/", {"machine": MACHINE}, format="json"
        )
        assert assigned.status_code == 200, assigned.data
        cancelled = token_a.post(f"{API}tasks/{unaddressed.data['id']}/cancel/", format="json")
        assert cancelled.status_code == 200, cancelled.data

        task = ClaudeTask.objects.get(pk=unaddressed.data["id"])
        row = ClaudeTaskFile.objects.create(
            household=task.household, task=task, path="C:/x/a.txt", name="a.txt", size=3
        )
        asked = token_a.post(f"{API}tasks/{task.id}/send-file/", {"file_id": row.id}, format="json")
        assert asked.status_code == 200, asked.data
        row.refresh_from_db()
        assert row.requested_at is not None

    def test_his_worker_lists_and_runs_his_workflows(self, token_a, worker):
        assert _beat(worker, workflows=[WORKFLOW], workflows_hash="h1").status_code == 200
        listed = token_a.get(f"{API}workflows/")
        assert [row["name"] for row in listed.data] == ["log-audit"]
        run = token_a.post(
            f"{API}workflows/run/", {"machine": MACHINE, "name": "log-audit"}, format="json"
        )
        assert run.status_code == 201, run.data

    def test_his_worker_uploads_the_file_he_asked_for(self, token_a, worker):
        assert _beat(worker).status_code == 200
        created = _create(token_a, machine=MACHINE, deliver=DELIVER_FILES)
        task = ClaudeTask.objects.get(pk=created.data["id"])
        assert _claim(worker).status_code == 200
        row = ClaudeTaskFile.objects.create(
            household=task.household, task=task, path="C:/x/a.txt", name="a.txt", size=3,
            requested_at=task.created_at,
        )
        stored = worker.post(
            f"{API}tasks/{task.id}/files/",
            {"file_id": row.id, "file": SimpleUploadedFile("a.txt", b"abc")},
            format="multipart",
        )
        assert stored.status_code == 201, stored.data
        row.refresh_from_db()
        assert row.uploaded_at is not None


class TestOnlyTheBoundWorker:
    def test_his_own_phone_token_may_not_claim_or_finish(self, token_a, worker, his_task):
        refused = _claim(token_a)
        assert refused.status_code == 403, refused.data
        assert "not the worker bound" in refused.data["detail"]
        assert ClaudeTask.objects.get(pk=his_task.id).status == QUEUED

        assert _claim(worker).status_code == 200
        finished = token_a.post(
            f"{API}tasks/{his_task.id}/finish/",
            {"status": "done", "summary": "from the phone", "question": ""},
            format="json",
        )
        assert finished.status_code == 403, finished.data
        assert ClaudeTask.objects.get(pk=his_task.id).summary is None

    def test_a_second_worker_token_of_his_cannot_claim_that_machine(
        self, household_user, worker, his_task
    ):
        """Two laptops, two tokens. The work laptop's token is a Claude token and still may not
        take the Alienware's queue: the fence is the binding, not the kind of token."""
        _token, raw = DeviceToken.issue(
            household_user, "claude-session-work laptop", scope=DeviceToken.SCOPE_CLAUDE_WORKER
        )
        other_worker = _client_for(raw)
        refused = _claim(other_worker)
        assert refused.status_code == 403, refused.data
        assert ClaudeTask.objects.get(pk=his_task.id).status == QUEUED

    def test_an_app_token_may_not_heartbeat_at_all(self, token_a):
        refused = _beat(token_a, name="A new laptop")
        assert refused.status_code == 403, refused.data
        assert "Only a Claude worker's own token" in refused.data["detail"]
        assert not ClaudeMachine.objects.filter(name="A new laptop").exists()
