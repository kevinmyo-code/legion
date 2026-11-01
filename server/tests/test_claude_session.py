"""`/api/claude/` (claude-session ticket 03, ADR 0067).

The state machine, claim routing (ruling 4), the read-time clock rules (90 s
offline, 30 min cap, 10 min silence), cancel both ways, the send-file round
trip through the heartbeat, the work laptop's `listed` scope (ruling 5), the
`deliver` flag (ruling 17), tenancy, and the 30 day prune.

Time is moved by back-dating rows, never by patching the clock: the views
read `timezone.now()` and `created_at` is the database's own `now()`.
"""

from __future__ import annotations

import datetime

import pytest
from django.core.files.uploadedfile import SimpleUploadedFile
from django.core.management import call_command
from django.utils import timezone

from claude_session.models import (
    FILE_MAX_BYTES,
    ClaudeCodeSession,
    ClaudeMachine,
    ClaudeTask,
    ClaudeTaskFile,
)

pytestmark = pytest.mark.django_db

API = "/api/claude/"


def _beat(client, name="Alienware", scope="full", state="idle", **extra):
    body = {"name": name, "hostname": f"{name}-host", "scope": scope, "state": state,
            "running": [], "claude_version": "2.1.316", **extra}
    response = client.post(f"{API}machines/heartbeat/", body, format="json")
    assert response.status_code == 200, response.data
    return response.data


def _create(client, request="find the AW4 wiring PDF in Downloads", **extra):
    body = {"request": request, "machine": None, "parent": None, "origin": "named", **extra}
    return client.post(f"{API}tasks/", body, format="json")


def _claim(client, name="Alienware"):
    return client.post(f"{API}tasks/claim/", {"name": name}, format="json")


def _finish(client, task_id, **body):
    body.setdefault("status", "done")
    return client.post(f"{API}tasks/{task_id}/finish/", body, format="json")


def _age_machine(name, seconds):
    ClaudeMachine.objects.filter(name=name).update(
        last_seen_at=timezone.now() - datetime.timedelta(seconds=seconds)
    )


# --- create and claim -----------------------------------------------------------


def test_unauthenticated_is_refused(db):
    from rest_framework.test import APIClient

    assert APIClient().get(f"{API}status/").status_code == 401
    assert APIClient().post(f"{API}tasks/", {}, format="json").status_code == 401


def test_a_named_task_is_queued_for_that_machine_and_claimed_by_it(token_a):
    _beat(token_a, "Alienware")
    _beat(token_a, "work laptop", scope="listed")
    created = _create(token_a, machine="alienware")
    assert created.status_code == 201, created.data
    assert created.data["status"] == "queued"
    assert created.data["machine"]["name"] == "Alienware"
    assert created.data["machine"]["state"] == "idle"

    assert _claim(token_a, "work laptop").status_code == 204
    claimed = _claim(token_a, "Alienware")
    assert claimed.status_code == 200
    assert [t["id"] for t in claimed.data] == [created.data["id"]]
    assert claimed.data[0]["status"] == "running"
    assert claimed.data[0]["request"] == "find the AW4 wiring PDF in Downloads"
    # Claimed once: a second claim finds nothing.
    assert _claim(token_a, "Alienware").status_code == 204


def test_unnamed_goes_to_the_only_machine_online(token_a):
    _beat(token_a, "Alienware")
    _beat(token_a, "work laptop", scope="listed")
    _age_machine("work laptop", 120)  # offline
    created = _create(token_a)
    assert created.data["status"] == "queued"
    assert created.data["machine"]["name"] == "Alienware"


def test_unnamed_with_two_online_needs_a_machine_and_nobody_claims_it(token_a):
    _beat(token_a, "Alienware")
    _beat(token_a, "work laptop", scope="listed")
    created = _create(token_a)
    assert created.status_code == 201
    assert created.data["status"] == "needs_machine"
    assert created.data["machine"] is None
    assert _claim(token_a, "Alienware").status_code == 204
    assert _claim(token_a, "work laptop").status_code == 204


def test_unnamed_with_none_online_waits_unaddressed_for_a_sole_claimer(token_a):
    created = _create(token_a)
    assert created.data["status"] == "queued" and created.data["machine"] is None
    _beat(token_a, "Alienware")
    claimed = _claim(token_a, "Alienware")
    assert claimed.status_code == 200
    assert claimed.data[0]["machine"] == "Alienware"


def test_an_unaddressed_task_is_not_claimed_while_two_are_online(token_a):
    created = _create(token_a)
    _beat(token_a, "Alienware")
    _beat(token_a, "work laptop", scope="listed")
    assert _claim(token_a, "Alienware").status_code == 204
    assert ClaudeTask.objects.get(pk=created.data["id"]).status == "queued"


def test_an_unknown_machine_name_is_refused_and_nothing_stored(token_a):
    _beat(token_a, "Alienware")
    response = _create(token_a, machine="toaster")
    assert response.status_code == 400
    assert "Known machines: Alienware" in response.data["detail"]
    assert ClaudeTask.objects.count() == 0


def test_claim_before_any_heartbeat_is_refused(token_a):
    assert _claim(token_a, "Alienware").status_code == 404


def test_a_follow_up_goes_to_its_parents_machine(token_a):
    _beat(token_a, "Alienware")
    _beat(token_a, "work laptop", scope="listed")
    first = _create(token_a, machine="Alienware").data["id"]
    follow = _create(token_a, request="now email it to me", parent=first)
    assert follow.data["status"] == "queued"
    assert follow.data["machine"]["name"] == "Alienware"
    row = token_a.get(f"{API}tasks/{follow.data['id']}/").data
    assert row["parent"] == first


def test_a_request_over_4kb_is_refused_without_echoing_it(token_a):
    secret = "x" * (4 * 1024 + 1)
    response = _create(token_a, request=secret)
    assert response.status_code == 400
    assert "over 4 KB" in str(response.data["detail"])
    assert "xxxx" not in str(response.data)
    assert ClaudeTask.objects.count() == 0


def test_deliver_email_self_round_trips_and_is_refused_for_a_listed_machine(token_a):
    _beat(token_a, "Alienware")
    _beat(token_a, "work laptop", scope="listed")
    ok = _create(token_a, machine="Alienware", deliver="email_self")
    assert ok.status_code == 201
    assert token_a.get(f"{API}tasks/{ok.data['id']}/").data["deliver"] == "email_self"
    assert _claim(token_a).data[0]["deliver"] == "email_self"
    refused = _create(token_a, machine="work laptop", deliver="email_self")
    assert refused.status_code == 400
    assert _create(token_a, machine="Alienware", deliver="whatsapp_self").status_code == 400


# --- finish and the state machine -----------------------------------------------


def test_the_full_state_machine_done(token_a):
    _beat(token_a)
    task_id = _create(token_a, machine="Alienware").data["id"]
    assert token_a.get(f"{API}tasks/{task_id}/").data["status"] == "queued"
    _claim(token_a)
    assert token_a.get(f"{API}tasks/{task_id}/").data["status"] == "running"
    finished = _finish(
        token_a, task_id, summary="found it, AW4 wiring rev C", turns=4, duration_ms=9000,
        found=[{"path": "C:/Users/kevin/Downloads/aw4.pdf", "name": "aw4.pdf", "size": 2_000_000}],
    )
    assert finished.status_code == 200, finished.data
    row = token_a.get(f"{API}tasks/{task_id}/").data
    assert row["status"] == "done"
    assert row["summary"] == "found it, AW4 wiring rev C"
    assert row["finished_at"] is not None
    assert row["files"] == [
        {"id": row["files"][0]["id"], "name": "aw4.pdf", "size": 2_000_000,
         "uploaded": False, "requested": False}
    ]
    # Terminal: a second finish and a cancel are both refused.
    assert _finish(token_a, task_id).status_code == 409
    assert token_a.post(f"{API}tasks/{task_id}/cancel/").status_code == 409


def test_needs_confirmation_carries_the_question(token_a):
    _beat(token_a)
    task_id = _create(token_a, machine="Alienware").data["id"]
    _claim(token_a)
    _finish(token_a, task_id, status="needs_confirmation", summary="Not deleted.",
            question="OK to delete work/victim.txt?")
    row = token_a.get(f"{API}tasks/{task_id}/").data
    assert row["status"] == "needs_confirmation"
    assert row["question"] == "OK to delete work/victim.txt?"


def test_finish_on_a_queued_task_is_refused(token_a):
    _beat(token_a)
    task_id = _create(token_a, machine="Alienware").data["id"]
    assert _finish(token_a, task_id).status_code == 409


def test_a_summary_over_16kb_is_refused(token_a):
    _beat(token_a)
    task_id = _create(token_a, machine="Alienware").data["id"]
    _claim(token_a)
    response = _finish(token_a, task_id, summary="y" * (16 * 1024 + 1))
    assert response.status_code == 400
    assert "yyyy" not in str(response.data)
    assert ClaudeTask.objects.get(pk=task_id).status == "running"


def test_list_is_newest_first_and_filters(token_a):
    _beat(token_a)
    _beat(token_a, "work laptop", scope="listed")
    a = _create(token_a, machine="Alienware").data["id"]
    b = _create(token_a, machine="work laptop").data["id"]
    c = _create(token_a, machine="Alienware").data["id"]
    ids = [t["id"] for t in token_a.get(f"{API}tasks/").data]
    assert ids == [c, b, a]
    assert [t["id"] for t in token_a.get(f"{API}tasks/?limit=1").data] == [c]
    assert [t["id"] for t in token_a.get(f"{API}tasks/?machine=work laptop").data] == [b]
    _claim(token_a)
    assert [t["id"] for t in token_a.get(f"{API}tasks/?status=running").data] == [c, a]


# --- the clock, computed at read time -------------------------------------------


def test_a_machine_silent_for_90_seconds_is_offline(token_a):
    _beat(token_a, state="busy")
    assert token_a.get(f"{API}status/").data["machines"][0]["state"] == "busy"
    _age_machine("Alienware", 89)
    assert token_a.get(f"{API}status/").data["machines"][0]["state"] == "busy"
    _age_machine("Alienware", 91)
    assert token_a.get(f"{API}status/").data["machines"][0]["state"] == "offline"


def test_a_task_running_past_30_minutes_is_failed_at_read_time(token_a):
    _beat(token_a)
    task_id = _create(token_a, machine="Alienware").data["id"]
    _claim(token_a)
    ClaudeTask.objects.filter(pk=task_id).update(
        started_at=timezone.now() - datetime.timedelta(minutes=31)
    )
    _beat(token_a)  # the machine is alive; only the cap applies
    row = token_a.get(f"{API}tasks/{task_id}/").data
    assert row["status"] == "failed"
    assert "30 minute" in row["summary"]
    # Never written: the stored row still says running.
    assert ClaudeTask.objects.get(pk=task_id).status == "running"
    # A late finish does not resurrect it.
    assert _finish(token_a, task_id).status_code == 409
    assert [t["id"] for t in token_a.get(f"{API}tasks/?status=failed").data] == [task_id]
    assert token_a.get(f"{API}status/").data["machines"][0]["running"] == []


def test_a_task_on_a_machine_silent_for_10_minutes_is_failed(token_a):
    _beat(token_a)
    task_id = _create(token_a, machine="Alienware").data["id"]
    _claim(token_a)
    ClaudeTask.objects.filter(pk=task_id).update(
        started_at=timezone.now() - datetime.timedelta(minutes=12)
    )
    _age_machine("Alienware", 9 * 60)
    assert token_a.get(f"{API}tasks/{task_id}/").data["status"] == "running"
    _age_machine("Alienware", 11 * 60)
    row = token_a.get(f"{API}tasks/{task_id}/").data
    assert row["status"] == "failed"
    assert "went silent" in row["summary"]


def test_status_lists_running_task_ids_per_machine(token_a):
    _beat(token_a)
    _beat(token_a, "work laptop", scope="listed")
    a = _create(token_a, machine="Alienware").data["id"]
    b = _create(token_a, machine="Alienware").data["id"]
    _claim(token_a)
    machines = {m["name"]: m for m in token_a.get(f"{API}status/").data["machines"]}
    assert machines["Alienware"]["running"] == [a, b]
    assert machines["Alienware"]["scope"] == "full"
    assert machines["work laptop"]["running"] == []
    assert machines["work laptop"]["scope"] == "listed"


# --- cancel ---------------------------------------------------------------------


def test_cancel_a_queued_task_is_immediate(token_a):
    _beat(token_a)
    task_id = _create(token_a, machine="Alienware").data["id"]
    response = token_a.post(f"{API}tasks/{task_id}/cancel/")
    assert response.status_code == 200
    assert response.data["status"] == "cancelled"
    assert _claim(token_a).status_code == 204


def test_cancel_a_needs_machine_task(token_a):
    _beat(token_a)
    _beat(token_a, "work laptop", scope="listed")
    task_id = _create(token_a).data["id"]
    assert token_a.post(f"{API}tasks/{task_id}/cancel/").data["status"] == "cancelled"


def test_cancel_a_running_task_goes_through_the_heartbeat(token_a):
    _beat(token_a)
    task_id = _create(token_a, machine="Alienware").data["id"]
    _claim(token_a)
    response = token_a.post(f"{API}tasks/{task_id}/cancel/")
    assert response.data["status"] == "cancel_requested"
    assert _beat(token_a)["cancel_requested"] == [task_id]
    # Asking twice is harmless.
    assert token_a.post(f"{API}tasks/{task_id}/cancel/").status_code == 200
    finished = _finish(token_a, task_id, status="cancelled")
    assert finished.status_code == 200
    assert token_a.get(f"{API}tasks/{task_id}/").data["status"] == "cancelled"
    assert _beat(token_a)["cancel_requested"] == []


def test_a_running_task_may_not_finish_as_cancelled_unasked(token_a):
    _beat(token_a)
    task_id = _create(token_a, machine="Alienware").data["id"]
    _claim(token_a)
    assert _finish(token_a, task_id, status="cancelled").status_code == 400


# --- files ----------------------------------------------------------------------


def _done_with_a_found_file(client, machine="Alienware"):
    task_id = _create(client, machine=machine).data["id"]
    _claim(client, machine)
    finished = _finish(
        client, task_id, summary="found it",
        found=[{"path": "D:/manuals/aw4.pdf", "name": "aw4.pdf", "size": 11}],
    )
    files = finished.data["files"]
    return task_id, (files[0]["id"] if files else None)


def test_send_file_round_trip_through_the_heartbeat(token_a):
    _beat(token_a)
    task_id, file_id = _done_with_a_found_file(token_a)
    assert _beat(token_a)["files_requested"] == []

    asked = token_a.post(f"{API}tasks/{task_id}/send-file/", {"file_id": file_id}, format="json")
    assert asked.status_code == 200, asked.data
    assert asked.data["requested"] is True and asked.data["uploaded"] is False
    assert _beat(token_a)["files_requested"] == [
        {"task": task_id, "file_id": file_id, "path": "D:/manuals/aw4.pdf"}
    ]
    # Not uploaded yet: nothing to download.
    assert token_a.get(f"{API}tasks/{task_id}/files/{file_id}/").status_code == 404

    upload = token_a.post(
        f"{API}tasks/{task_id}/files/",
        {"file_id": file_id,
         "file": SimpleUploadedFile("aw4.pdf", b"%PDF-1.7 hi", content_type="application/pdf")},
        format="multipart",
    )
    assert upload.status_code == 201, upload.data
    assert _beat(token_a)["files_requested"] == []

    got = token_a.get(f"{API}tasks/{task_id}/files/{file_id}/")
    assert got.status_code == 200
    assert got.content == b"%PDF-1.7 hi"
    assert got["Content-Type"] == "application/pdf"
    assert 'filename="aw4.pdf"' in got["Content-Disposition"]
    row = token_a.get(f"{API}tasks/{task_id}/").data["files"][0]
    assert row["uploaded"] is True and row["size"] == 11


def test_an_unrequested_upload_is_refused(token_a):
    _beat(token_a)
    task_id, file_id = _done_with_a_found_file(token_a)
    response = token_a.post(
        f"{API}tasks/{task_id}/files/",
        {"file_id": file_id, "file": SimpleUploadedFile("aw4.pdf", b"x")},
        format="multipart",
    )
    assert response.status_code == 409
    assert ClaudeTaskFile.objects.get(pk=file_id).uploaded_at is None


def test_an_upload_over_25mb_is_refused_413(token_a, monkeypatch):
    monkeypatch.setattr("claude_session.views.FILE_MAX_BYTES", 1024)
    assert FILE_MAX_BYTES == 25 * 1024 * 1024
    _beat(token_a)
    task_id, file_id = _done_with_a_found_file(token_a)
    token_a.post(f"{API}tasks/{task_id}/send-file/", {"file_id": file_id}, format="json")
    response = token_a.post(
        f"{API}tasks/{task_id}/files/",
        {"file_id": file_id, "file": SimpleUploadedFile("aw4.pdf", b"z" * 2048)},
        format="multipart",
    )
    assert response.status_code == 413
    assert ClaudeTaskFile.objects.get(pk=file_id).uploaded_at is None


# --- the work laptop: scope `listed` (ruling 5) -----------------------------------


def test_a_listed_machine_keeps_no_found_files_and_refuses_send_file(token_a):
    _beat(token_a, "work laptop", scope="listed")
    task_id, file_id = _done_with_a_found_file(token_a, "work laptop")
    assert file_id is None
    assert ClaudeTaskFile.objects.count() == 0
    # Even a file row that got there some other way is refused.
    row = ClaudeTaskFile.objects.create(
        household=ClaudeTask.objects.get(pk=task_id).household,
        task_id=task_id, path="C:/repos/x.py", name="x.py",
    )
    refused = token_a.post(f"{API}tasks/{task_id}/send-file/", {"file_id": row.id}, format="json")
    assert refused.status_code == 403
    assert "summaries only" in refused.data["detail"]
    row.refresh_from_db()
    assert row.requested_at is None
    upload = token_a.post(
        f"{API}tasks/{task_id}/files/",
        {"file_id": row.id, "file": SimpleUploadedFile("x.py", b"print()")},
        format="multipart",
    )
    assert upload.status_code == 403


# --- interactive sessions -------------------------------------------------------


def test_sessions_from_a_full_machine_keep_the_prompt_summary(token_a):
    _beat(token_a)
    body = {"machine": "Alienware", "session_id": "s-1", "project": "legion",
            "state": "working", "last_prompt_summary": "fixing the heartbeat",
            "at": "2026-11-01T15:00:00Z"}
    response = token_a.post(f"{API}sessions/", body, format="json")
    assert response.status_code == 200, response.data
    assert response.data["summary_kept"] is True
    token_a.post(f"{API}sessions/", {**body, "state": "waiting"}, format="json")
    sessions = token_a.get(f"{API}status/").data["sessions"]
    assert sessions == [
        {"machine": "Alienware", "project": "legion", "state": "waiting",
         "last_prompt_summary": "fixing the heartbeat",
         "last_seen_at": sessions[0]["last_seen_at"]}
    ]
    assert ClaudeCodeSession.objects.count() == 1


def test_sessions_from_a_listed_machine_drop_the_prompt_summary(token_a):
    _beat(token_a, "work laptop", scope="listed")
    body = {"machine": "work laptop", "session_id": "w-1", "project": "erp",
            "state": "working", "last_prompt_summary": "something from work"}
    response = token_a.post(f"{API}sessions/", body, format="json")
    assert response.data["summary_kept"] is False
    assert ClaudeCodeSession.objects.get().last_prompt_summary is None
    assert token_a.get(f"{API}status/").data["sessions"][0]["last_prompt_summary"] is None


def test_a_session_before_any_heartbeat_makes_a_listed_machine(token_a):
    body = {"machine": "Alienware", "session_id": "s-1", "project": "legion",
            "state": "working", "last_prompt_summary": "early"}
    assert token_a.post(f"{API}sessions/", body, format="json").data["summary_kept"] is False
    machine = ClaudeMachine.objects.get()
    assert machine.scope == "listed" and machine.last_seen_at is None
    _beat(token_a, "Alienware", scope="full")
    assert ClaudeMachine.objects.count() == 1
    assert token_a.post(f"{API}sessions/", body, format="json").data["summary_kept"] is True


def test_ended_and_stale_sessions_leave_the_status_screen(token_a):
    _beat(token_a)
    base = {"machine": "Alienware", "project": "p", "state": "working"}
    token_a.post(f"{API}sessions/", {**base, "session_id": "a"}, format="json")
    token_a.post(f"{API}sessions/", {**base, "session_id": "b", "state": "ended"}, format="json")
    token_a.post(f"{API}sessions/", {**base, "session_id": "c"}, format="json")
    ClaudeCodeSession.objects.filter(session_id="c").update(
        last_seen_at=timezone.now() - datetime.timedelta(hours=25)
    )
    assert len(token_a.get(f"{API}status/").data["sessions"]) == 1


# --- tenancy --------------------------------------------------------------------


def test_another_household_sees_none_of_it(token_a, token_b, household_a, household_b):
    _beat(token_a, "Alienware")
    task_id = _create(token_a, machine="Alienware").data["id"]
    _claim(token_a)
    file_id = _finish(
        token_a, task_id, found=[{"path": "D:/a.pdf", "name": "a.pdf", "size": 1}]
    ).data["files"][0]["id"]
    token_a.post(f"{API}sessions/", {"machine": "Alienware", "session_id": "s",
                                     "state": "working"}, format="json")

    # B's own machine of the same name is its own row.
    _beat(token_b, "Alienware")
    assert ClaudeMachine.objects.filter(household=household_a).count() == 1
    assert ClaudeMachine.objects.filter(household=household_b).count() == 1

    assert token_b.get(f"{API}tasks/").data == []
    assert token_b.get(f"{API}tasks/{task_id}/").status_code == 404
    assert token_b.post(f"{API}tasks/{task_id}/cancel/").status_code == 404
    assert _finish(token_b, task_id).status_code == 404
    assert token_b.post(
        f"{API}tasks/{task_id}/send-file/", {"file_id": file_id}, format="json"
    ).status_code == 404
    assert token_b.get(f"{API}tasks/{task_id}/files/{file_id}/").status_code == 404
    status_b = token_b.get(f"{API}status/").data
    assert status_b["sessions"] == []
    assert [m["running"] for m in status_b["machines"]] == [[]]
    # B's heartbeat never carries A's requests.
    token_a.post(f"{API}tasks/{task_id}/send-file/", {"file_id": file_id}, format="json")
    assert _beat(token_b, "Alienware")["files_requested"] == []
    # B cannot follow up A's task.
    assert _create(token_b, parent=task_id).status_code == 400
    # And B's claim never takes A's queued work.
    _create(token_a, machine="Alienware")
    assert _claim(token_b, "Alienware").status_code == 204


# --- retention ------------------------------------------------------------------


def test_prune_deletes_rows_and_files_older_than_30_days(token_a, capsys):
    _beat(token_a)
    old_id, old_file = _done_with_a_found_file(token_a)
    new_id, _ = _done_with_a_found_file(token_a)
    token_a.post(f"{API}sessions/", {"machine": "Alienware", "session_id": "old",
                                     "state": "idle"}, format="json")
    token_a.post(f"{API}sessions/", {"machine": "Alienware", "session_id": "new",
                                     "state": "idle"}, format="json")
    long_ago = timezone.now() - datetime.timedelta(days=31)
    ClaudeTask.objects.filter(pk=old_id).update(created_at=long_ago)
    ClaudeCodeSession.objects.filter(session_id="old").update(last_seen_at=long_ago)

    call_command("claude_session_prune")
    out = capsys.readouterr().out
    assert "deleted 1 tasks, 1 files, 1 session rows" in out
    assert "aw4" not in out
    assert list(ClaudeTask.objects.values_list("id", flat=True)) == [new_id]
    assert not ClaudeTaskFile.objects.filter(pk=old_file).exists()
    assert list(ClaudeCodeSession.objects.values_list("session_id", flat=True)) == ["new"]
    assert ClaudeMachine.objects.count() == 1


def test_model_strings_carry_no_request_or_file_name(token_a):
    _beat(token_a)
    task_id, _ = _done_with_a_found_file(token_a)
    task = ClaudeTask.objects.get(pk=task_id)
    assert "AW4" not in str(task)
    assert "aw4" not in str(ClaudeTaskFile.objects.get())
    assert "Alienware" not in str(ClaudeMachine.objects.get())


# --- status extras (Kevin 2026-11-01, "yes to status extras") ---------------------


def test_heartbeat_progress_lands_on_the_running_task_and_clears_on_finish(token_a):
    _beat(token_a)
    _beat(token_a, "work laptop", scope="listed")
    a = _create(token_a, machine="Alienware").data["id"]
    other = _create(token_a, machine="work laptop").data["id"]
    _claim(token_a)
    _claim(token_a, "work laptop")
    queued = _create(token_a, machine="Alienware").data["id"]

    _beat(token_a, progress={str(a): "reading Downloads", str(other): "not mine",
                             str(queued): "not running", "junk": "ignored"})
    row = token_a.get(f"{API}tasks/{a}/").data
    assert row["progress"] == "reading Downloads" and row["progress_at"] is not None
    # Another machine's task and a queued one are untouched.
    assert ClaudeTask.objects.get(pk=other).progress is None
    assert ClaudeTask.objects.get(pk=queued).progress is None

    machine = token_a.get(f"{API}status/").data["machines"][0]
    assert machine["name"] == "Alienware"
    assert machine["progress"] == [
        {"task": a, "progress": "reading Downloads", "progress_at": row["progress_at"]}
    ]

    _finish(token_a, a, summary="found it")
    row = token_a.get(f"{API}tasks/{a}/").data
    assert row["progress"] is None and row["progress_at"] is None


def test_progress_over_120_characters_is_refused_without_echo(token_a):
    _beat(token_a)
    a = _create(token_a, machine="Alienware").data["id"]
    _claim(token_a)
    body = {"name": "Alienware", "scope": "full", "state": "busy",
            "progress": {str(a): "q" * 121}}
    response = token_a.post(f"{API}machines/heartbeat/", body, format="json")
    assert response.status_code == 400
    assert "qqqq" not in str(response.data)
    assert ClaudeTask.objects.get(pk=a).progress is None


def test_waiting_sessions_sort_first(token_a):
    _beat(token_a)
    _beat(token_a, "work laptop", scope="listed")
    for machine, sid, state in (("Alienware", "a", "working"), ("work laptop", "w", "waiting"),
                                ("Alienware", "b", "waiting")):
        response = token_a.post(f"{API}sessions/", {"machine": machine, "session_id": sid,
                                                    "project": sid, "state": state}, format="json")
        assert response.status_code == 200
    sessions = token_a.get(f"{API}status/").data["sessions"]
    assert [(s["machine"], s["project"], s["state"]) for s in sessions] == [
        ("Alienware", "b", "waiting"),
        ("work laptop", "w", "waiting"),
        ("Alienware", "a", "working"),
    ]
