"""A second household member reaches none of Kevin's Claude work (`claude_session.fences`).

The hole this closes, traced 2026-11-24 before a second person joins the engine household:
`IsHouseholdMember` carries no role check and `/api/claude/` fenced by household alone, so ANY
member of the household could read every task Kevin ever sent, post `confirms: true` against a
card that was waiting for his yes, queue work on his Alienware, and heartbeat as his machine
(`_touch_machine` binds `ClaudeMachine.token` to whatever token beats, so the laptop identity
moved with it). Tenancy was never the fence here: a second member joins the SAME household, so
`scoped()` returns one member's rows to the other.

Three rules, one test class each:

- `TestAnotherMemberIsRefused`: every `/api/claude/` route, for a member of the same household
  who owns nothing. Each case sets up state Kevin would succeed on, then asserts the refusal AND
  that the database did not move, so a pass is the fence and never a missing row.
- `TestKevinStillWorks`: the whole round trip he uses - create, claim, finish, status, files,
  workflows, sessions - on his own tokens.
- `TestOnlyTheBoundWorker`: the worker's verbs belong to the worker bound to that machine, by its
  heartbeat or by its session hook, whichever reached the engine first.
  A member's app token and a SECOND worker token of the owner's own are both refused.

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
    """An ordinary member of the same household as the owner of the Claude work."""
    user = User.objects.create_user(email="member@example.com", password="correct horse battery")
    HouseholdMember.objects.create(
        user=user, household=household_a, role=HouseholdMember.MEMBER
    )
    return user


@pytest.fixture
def other(other_member):
    """That member's ordinary app token."""
    _token, raw = DeviceToken.issue(other_member, "Their phone")
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

    def test_his_machine_is_not_theirs_to_queue_work_on(self, other, his_task):
        refused = _create(other, machine=MACHINE)
        assert refused.status_code == 400, refused.data
        assert "No machine has that name here" in refused.data["detail"]
        assert ClaudeTask.objects.filter(machine=his_task.machine).count() == 1

    def test_a_members_unaddressed_task_is_never_claimed_by_his_worker(
        self, other, worker, his_task
    ):
        theirs = _create(other, request="rename my files")
        assert theirs.status_code == 201, theirs.data
        assert theirs.data["machine"] is None
        claimed = _claim(worker)
        assert claimed.status_code == 200
        assert [row["id"] for row in claimed.data] == [his_task.id]
        assert ClaudeTask.objects.get(pk=theirs.data["id"]).status == QUEUED

    def test_another_member_cannot_forge_his_yes(self, other, worker, his_task):
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

    def test_another_member_cannot_cancel_his_task(self, other, his_task):
        refused = other.post(f"{API}tasks/{his_task.id}/cancel/", format="json")
        assert refused.status_code == 404
        assert ClaudeTask.objects.get(pk=his_task.id).status == QUEUED

    def test_another_member_cannot_assign_his_unaddressed_task(self, other, token_a, worker):
        assert _beat(worker).status_code == 200
        assert _beat(worker, name="work laptop", scope="listed").status_code == 200
        his = _create(token_a)
        assert his.data["status"] == NEEDS_MACHINE
        refused = other.post(
            f"{API}tasks/{his.data['id']}/assign/", {"machine": MACHINE}, format="json"
        )
        assert refused.status_code == 404
        assert ClaudeTask.objects.get(pk=his.data["id"]).status == NEEDS_MACHINE

    def test_another_member_cannot_ask_for_a_file_of_his(self, other, his_task):
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

    @pytest.mark.parametrize("deliver", [None, "phone"])
    def test_she_cannot_download_the_bytes_of_a_file_of_his(
        self, other, token_a, worker, deliver
    ):
        """The widest door there was: `tasks/{id}/files/{file}/` served the bytes to any member
        of the household, for every `deliver` but `files`. His own GET is asserted in the same
        case, so a 404 for her is the fence and never a row that was never uploaded."""
        assert _beat(worker).status_code == 200
        created = _create(token_a, machine=MACHINE, deliver=deliver)
        assert created.status_code == 201, created.data
        task = ClaudeTask.objects.get(pk=created.data["id"])
        # `delivery.settle` may rewrite a delivery, and the `None` case is the one that matters:
        # it has to stay an ordinary send-file, not become a `files` task the old check covered.
        assert task.deliver == deliver
        assert _claim(worker).status_code == 200
        page = deliver == "phone"
        name = "page.html" if page else "notes.txt"
        row = ClaudeTaskFile.objects.create(
            household=task.household, task=task, path=f"C:/x/{name}", name=name,
            size=13, requested_at=task.created_at,
        )
        sent = SimpleUploadedFile(
            name, b"HIS OWN WORDS", content_type="text/html" if page else "text/plain"
        )
        stored = worker.post(
            f"{API}tasks/{task.id}/files/", {"file_id": row.id, "file": sent}, format="multipart"
        )
        assert stored.status_code == 201, stored.data

        refused = other.get(f"{API}tasks/{task.id}/files/{row.id}/")
        assert refused.status_code == 404, refused.status_code
        his = token_a.get(f"{API}tasks/{task.id}/files/{row.id}/")
        assert his.status_code == 200, his.status_code
        assert his.content == b"HIS OWN WORDS"

    def test_the_status_screen_shows_a_member_none_of_his_laptop(self, other, worker, his_task):
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

    def test_his_sessions_on_an_unbound_machine_are_not_hers_to_read(
        self, other, token_a, worker
    ):
        """The unbound variant of the case above. His machine is `full` so the prose is kept,
        and then its token goes (a device deleted at the database): the row keeps his session's
        name and summary, and an unbound machine is nobody's, so she reads neither."""
        assert _beat(worker).status_code == 200
        assert worker.post(
            f"{API}sessions/",
            {"machine": MACHINE, "session_id": "s-4", "project": "legion", "state": "waiting",
             "name": "his session name", "last_prompt_summary": "his private summary",
             "at": "2026-11-24T10:00:00Z"},
            format="json",
        ).status_code == 200
        ClaudeMachine.objects.filter(name=MACHINE).update(token=None)

        status = other.get(f"{API}status/")
        assert status.status_code == 200
        assert status.data["machines"] == []
        assert status.data["sessions"] == []
        # The row is still there; what changed is who may read it.
        assert ClaudeCodeSession.objects.get(session_id="s-4").name == "his session name"

    def test_her_own_session_on_a_machine_she_named_is_not_on_his_status(
        self, other_member, token_a
    ):
        """The other direction. `sessions/` makes a machine row for a name it has not seen, so a
        member with a worker-shaped token of her own may report a laptop of her own; the row is
        bound to HER token, and a machine and a session of hers are never on his status screen."""
        token, raw = DeviceToken.issue(
            other_member, "claude-session-Her laptop", scope=DeviceToken.SCOPE_CLAUDE_WORKER
        )
        reported = _client_for(raw).post(
            f"{API}sessions/",
            {"machine": "Her laptop", "session_id": "s-5", "project": "hers", "state": "waiting",
             "name": "hers", "last_prompt_summary": "her summary",
             "at": "2026-11-24T10:00:00Z"},
            format="json",
        )
        assert reported.status_code == 200, reported.data
        assert ClaudeMachine.objects.get(name="Her laptop").token_id == token.pk

        status = token_a.get(f"{API}status/")
        assert status.status_code == 200
        assert [m["name"] for m in status.data["machines"]] == []
        assert status.data["sessions"] == []

    def test_another_member_cannot_heartbeat_as_his_machine(self, other, his_task):
        before = _machine().token_id
        refused = _beat(other)
        assert refused.status_code == 403, refused.data
        # The app token is refused as a worker verb before the machine is even looked up; the
        # test below is the one that proves the ownership half.
        assert "Only a Claude worker's own token" in refused.data["detail"]
        assert _machine().token_id == before

    def test_a_members_own_worker_token_cannot_rebind_his_machine(self, other_member, his_task):
        """The name is the installer's convention, so a member could pick it. The binding is not."""
        _token, raw = DeviceToken.issue(
            other_member, f"claude-session-{MACHINE}", scope=DeviceToken.SCOPE_CLAUDE_WORKER
        )
        before = _machine().token_id
        refused = _beat(_client_for(raw))
        assert refused.status_code == 403, refused.data
        assert "another member's laptop" in refused.data["detail"]
        assert _machine().token_id == before

    def test_another_member_cannot_claim_his_queue(self, other, his_task):
        refused = _claim(other)
        assert refused.status_code in (403, 404), refused.data
        assert ClaudeTask.objects.get(pk=his_task.id).status == QUEUED

    def test_another_member_cannot_finish_his_running_task(self, other, worker, his_task):
        assert _claim(worker).status_code == 200
        refused = other.post(
            f"{API}tasks/{his_task.id}/finish/",
            {"status": "done", "summary": "I did it", "question": ""},
            format="json",
        )
        assert refused.status_code == 404
        after = ClaudeTask.objects.get(pk=his_task.id)
        assert after.status == RUNNING and after.summary is None

    def test_another_member_cannot_upload_a_file_against_his_task(self, other, worker, his_task):
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

    def test_a_members_worker_token_cannot_report_a_session_on_his_machine(
        self, other_member, his_task
    ):
        """The ownership half of the `sessions/` fence, past the worker-token half: a token this
        engine would call a worker's still may not write a session row under his machine."""
        _token, raw = DeviceToken.issue(
            other_member, f"claude-session-{MACHINE}", scope=DeviceToken.SCOPE_CLAUDE_WORKER
        )
        refused = _client_for(raw).post(
            f"{API}sessions/",
            {"machine": MACHINE, "session_id": "s-3", "project": "legion", "state": "waiting",
             "name": "theirs", "last_prompt_summary": "x", "at": "2026-11-24T10:00:00Z"},
            format="json",
        )
        assert refused.status_code == 403, refused.data
        assert "another member's laptop" in refused.data["detail"]
        assert not ClaudeCodeSession.objects.filter(session_id="s-3").exists()

    def test_another_member_cannot_report_a_session_on_his_machine(self, other, his_task):
        refused = other.post(
            f"{API}sessions/",
            {"machine": MACHINE, "session_id": "s-2", "project": "legion", "state": "waiting",
             "name": "hers", "last_prompt_summary": "x", "at": "2026-11-24T10:00:00Z"},
            format="json",
        )
        assert refused.status_code == 403, refused.data
        assert not ClaudeCodeSession.objects.filter(session_id="s-2").exists()

    def test_his_workflows_are_neither_listed_nor_runnable_by_a_member(
        self, other, worker, his_task
    ):
        assert _beat(worker, workflows=[WORKFLOW], workflows_hash="h1").status_code == 200
        listed = other.get(f"{API}workflows/")
        assert listed.status_code == 200
        assert listed.data == []
        refused = other.post(
            f"{API}workflows/run/", {"machine": MACHINE, "name": "log-audit"}, format="json"
        )
        assert refused.status_code == 400, refused.data
        assert not ClaudeTask.objects.filter(workflow="log-audit").exists()


    def test_a_workflow_phrase_finds_none_of_his_machines(self, other, worker, his_task):
        """`_resolve_workflow` reads the caller's own machines, so Alfred answering a member's
        phrase ("audit my logs") never routes it onto his laptop."""
        assert _beat(worker, workflows=[WORKFLOW], workflows_hash="h1").status_code == 200
        refused = _create(other, request="audit my logs", workflow="audit my logs")
        assert refused.status_code == 400, refused.data
        assert refused.data["workflows"] == []
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

    def test_his_hooks_put_a_machine_on_his_status_before_any_heartbeat(
        self, token_a, worker, other
    ):
        """His laptop's session hooks report while the worker service is down. The machine and the
        session are his to read from the first report, because `sessions/` binds the machine to
        the worker that reported it, and they are hers to read in neither direction."""
        reported = worker.post(
            f"{API}sessions/",
            {"machine": "Spare laptop", "session_id": "s-12", "project": "legion",
             "state": "waiting", "name": "", "last_prompt_summary": None,
             "at": "2026-11-24T10:00:00Z"},
            format="json",
        )
        assert reported.status_code == 200, reported.data

        his = token_a.get(f"{API}status/")
        assert his.status_code == 200
        assert [m["name"] for m in his.data["machines"]] == ["Spare laptop"]
        # No heartbeat has said otherwise, so it is offline and `listed`.
        assert his.data["machines"][0]["state"] == "offline"
        assert his.data["machines"][0]["last_seen_at"] is None
        assert [row["session_id"] for row in his.data["sessions"]] == ["s-12"]

        hers = other.get(f"{API}status/")
        assert hers.data["machines"] == []
        assert hers.data["sessions"] == []

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

    def test_his_own_phone_token_may_not_upload_a_file(self, token_a, worker, his_task):
        """The upload is the worker's verb too: a result's bytes come from the laptop that
        claimed the task, not from a token that merely belongs to Kevin."""
        assert _claim(worker).status_code == 200
        row = ClaudeTaskFile.objects.create(
            household=his_task.household, task=his_task, path="C:/x/a.txt", name="a.txt", size=3,
            requested_at=his_task.created_at,
        )
        refused = token_a.post(
            f"{API}tasks/{his_task.id}/files/",
            {"file_id": row.id, "file": SimpleUploadedFile("a.txt", b"abc")},
            format="multipart",
        )
        assert refused.status_code == 403, refused.data
        assert "not the worker bound" in refused.data["detail"]
        row.refresh_from_db()
        assert row.uploaded_at is None and row.content is None

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

    def test_she_cannot_queue_work_onto_a_machine_no_worker_has_bound(
        self, other, other_member, token_a, worker
    ):
        """The lock-out, in the sequence that reaches it. A task of hers on an unbound machine
        used to make `machine_refusal` read his own worker's next heartbeat as another member's.
        So she may not put work there at all, and his first heartbeat still binds. A workflow
        phrase and an assign read the same queryset, so neither reaches it either (traced).

        A hook binds the machine it reports now, so the unbound row here is the one state the API
        still produces: a machine whose token row was deleted (`SET_NULL`)."""
        hooked = worker.post(
            f"{API}sessions/",
            {"machine": "Spare laptop", "session_id": "s-9", "project": "legion",
             "state": "idle", "name": "", "last_prompt_summary": None,
             "at": "2026-11-24T10:00:00Z"},
            format="json",
        )
        assert hooked.status_code == 200, hooked.data
        ClaudeMachine.objects.filter(name="Spare laptop").update(token=None)
        unbound = ClaudeMachine.objects.get(name="Spare laptop")
        assert unbound.token_id is None

        refused = _create(other, machine="Spare laptop")
        assert refused.status_code == 400, refused.data
        assert "No machine has that name here" in refused.data["detail"]
        assert not ClaudeTask.objects.filter(machine=unbound).exists()

        # And the sequence the real worker runs: its first heartbeat binds that machine.
        beat = _beat(worker, name="Spare laptop")
        assert beat.status_code == 200, beat.data
        unbound.refresh_from_db()
        assert unbound.token_id is not None
        assert _claim(worker, name="Spare laptop").status_code in (200, 204)

    def test_an_app_token_may_not_heartbeat_at_all(self, token_a):
        refused = _beat(token_a, name="A new laptop")
        assert refused.status_code == 403, refused.data
        assert "Only a Claude worker's own token" in refused.data["detail"]
        assert not ClaudeMachine.objects.filter(name="A new laptop").exists()

    def test_a_member_cannot_bind_a_machine_that_holds_his_work(self, other_member, his_task):
        """A machine's token is `SET_NULL`, so deleting the device leaves a row that is unbound
        and not new, holding the work of the member whose laptop it is. A member heartbeating as
        it would take it over and lock his own worker out of it, every later heartbeat of his
        reading as another member's machine."""
        ClaudeMachine.objects.filter(name=MACHINE).update(token=None)
        _token, raw = DeviceToken.issue(
            other_member, f"claude-session-{MACHINE}", scope=DeviceToken.SCOPE_CLAUDE_WORKER
        )
        refused = _beat(_client_for(raw))
        assert refused.status_code == 403, refused.data
        assert "another member's laptop" in refused.data["detail"]
        assert _machine().token_id is None

    def test_her_worker_cannot_beat_first_on_a_machine_his_hook_made(
        self, other, other_member, token_a, worker
    ):
        """The last door in rule 2, in the sequence that opens it. A hooks-only machine held no
        binding and no tasks, so `machine_refusal` had nothing to refuse: her own worker token
        could heartbeat it FIRST, bind it to her, and every heartbeat and hook of his then read as
        another member's machine while his sessions became hers to read. `sessions/` binds the
        machine to the worker that reported it, so she is refused at the only door she had."""
        hooked = worker.post(
            f"{API}sessions/",
            {"machine": "Spare laptop", "session_id": "s-10", "project": "legion",
             "state": "waiting", "name": "", "last_prompt_summary": None,
             "at": "2026-11-24T10:00:00Z"},
            format="json",
        )
        assert hooked.status_code == 200, hooked.data

        _token, raw = DeviceToken.issue(
            other_member, "claude-session-Spare laptop", scope=DeviceToken.SCOPE_CLAUDE_WORKER
        )
        refused = _beat(_client_for(raw), name="Spare laptop")
        assert refused.status_code == 403, refused.data
        assert "another member's laptop" in refused.data["detail"]

        # Nothing of his moved to her, on the surface either member reads.
        hers = other.get(f"{API}status/")
        assert hers.status_code == 200
        assert hers.data["machines"] == []
        assert hers.data["sessions"] == []
        assert _beat(worker, name="Spare laptop").status_code == 200
        his = token_a.get(f"{API}status/")
        assert [m["name"] for m in his.data["machines"]] == ["Spare laptop"]
        assert _claim(worker, name="Spare laptop").status_code in (200, 204)

    def test_her_worker_cannot_beat_first_after_his_token_row_went(
        self, other_member, household_user, worker
    ):
        """Rule 2's re-bind case, from the other side. The machine was his, his worker's token row
        was deleted (`SET_NULL`), and it holds no task of his to refuse her by. His re-installed
        worker's first hook binds it again, so her heartbeat is refused rather than racing him."""
        assert _beat(worker, name="Spare laptop").status_code == 200
        DeviceToken.objects.filter(claude_machines__name="Spare laptop").delete()
        assert ClaudeMachine.objects.get(name="Spare laptop").token_id is None

        _again, raw_his = DeviceToken.issue(
            household_user, "claude-session-Spare laptop",
            scope=DeviceToken.SCOPE_CLAUDE_WORKER,
        )
        his_worker = _client_for(raw_his)
        hooked = his_worker.post(
            f"{API}sessions/",
            {"machine": "Spare laptop", "session_id": "s-11", "project": "legion",
             "state": "idle", "name": "", "last_prompt_summary": None,
             "at": "2026-11-24T10:00:00Z"},
            format="json",
        )
        assert hooked.status_code == 200, hooked.data

        _hers, raw_hers = DeviceToken.issue(
            other_member, "claude-session-Spare laptop", scope=DeviceToken.SCOPE_CLAUDE_WORKER
        )
        refused = _beat(_client_for(raw_hers), name="Spare laptop")
        assert refused.status_code == 403, refused.data
        assert "another member's laptop" in refused.data["detail"]
        assert _beat(his_worker, name="Spare laptop").status_code == 200

    def test_his_own_worker_rebinds_that_machine_after_a_new_token(
        self, household_user, his_task
    ):
        """The other half of the rule above, and the one that would break him: re-installing the
        worker issues a new token, and his own next heartbeat must bind the machine again."""
        ClaudeMachine.objects.filter(name=MACHINE).update(token=None)
        token, raw = DeviceToken.issue(
            household_user, f"claude-session-{MACHINE}", scope=DeviceToken.SCOPE_CLAUDE_WORKER
        )
        again = _client_for(raw)
        assert _beat(again).status_code == 200
        assert _machine().token_id == token.pk
        assert _claim(again).status_code == 200
