"""Where a free-text Claude task's result goes once its machine is known (claude_session.delivery).

The 2026-11-22 failure, as tests: a file asked for "as a PDF" with `deliver: email_self` went to a
laptop that cannot send mail, so nothing was emailed and nothing reached the phone. Invented
requests only.
"""

from __future__ import annotations

import pytest

from claude_session.delivery import asks_for_made_file
from claude_session.models import ClaudeMachine, ClaudeTask

pytestmark = pytest.mark.django_db

API = "/api/claude/"


def _beat(client, name="Alienware", scope="full", **extra):
    body = {"name": name, "hostname": f"{name}-host", "scope": scope, "state": "idle",
            "running": [], "claude_version": "2.1.316", **extra}
    response = client.post(f"{API}machines/heartbeat/", body, format="json")
    assert response.status_code == 200, response.data
    return response.data


def _create(client, request, **extra):
    body = {"request": request, "machine": None, "parent": None, "origin": "named", **extra}
    response = client.post(f"{API}tasks/", body, format="json")
    assert response.status_code == 201, response.data
    return response.data


def _deliver(task_id):
    return ClaudeTask.objects.get(pk=task_id).deliver


@pytest.mark.parametrize("words", [
    "Make a one-day garden walk for Springfield as a PDF",
    "put the packing list into a spreadsheet",
    "make me a PDF of the hiking plan",
    "create an Excel sheet with the tyre prices",
    "export the notes as an Excel file",
    "turn the budget into a spreadsheet",
    "save it as a PDF for me",
    "write it up as a Word document",
])
def test_a_result_asked_for_as_a_file_is_a_made_file(words):
    assert asks_for_made_file(words)


@pytest.mark.parametrize("words", [
    "find the AW4 wiring PDF in Downloads",
    "summarise the PDF in my Downloads folder",
    "what does the lease document say about pets",
    "how big is the spreadsheet on my desktop",
    "write a summary of a PDF in my Downloads",
    "make a note about a document on my desktop",
    "save a document I scanned",
    "make sure a PDF of the lease is in Documents",
    "find the lease I saved as a PDF",
    "read the report as a PDF and tell me the total",
    "is the invoice stored as a document or a photo?",
    "what's in the folder I saved as csv",
    "",
])
def test_finding_or_reading_a_file_is_not(words):
    assert not asks_for_made_file(words)


def test_heartbeat_stores_can_mail_and_an_older_worker_leaves_it_unknown(token_a):
    _beat(token_a, can_mail=False)
    assert ClaudeMachine.objects.get(name="Alienware").can_mail is False
    _beat(token_a, can_mail=True)
    assert ClaudeMachine.objects.get(name="Alienware").can_mail is True
    _beat(token_a)  # a worker from before the field
    assert ClaudeMachine.objects.get(name="Alienware").can_mail is None


def test_mail_where_none_can_be_sent_goes_to_the_phone_and_the_pdf_is_requested(
    token_a,
):
    _beat(token_a, can_mail=False)
    created = _create(token_a, "Make a one-day garden walk for Springfield as a PDF",
                      deliver="email_self")
    assert _deliver(created["id"]) == "files"
    claimed = token_a.post(f"{API}tasks/claim/", {"name": "Alienware"}, format="json")
    assert claimed.data[0]["deliver"] == "files"
    finished = token_a.post(
        f"{API}tasks/{created['id']}/finish/",
        {"status": "done", "summary": "Your garden walk is ready as a PDF.",
         "found": [{"path": "C:/w/task-1/garden-walk.pdf", "name": "garden-walk.pdf",
                    "size": 9000}]},
        format="json",
    )
    assert finished.status_code == 200
    assert [f["requested"] for f in finished.data["files"]] == [True]
    # The worker reads the request on its next heartbeat as well.
    assert [f["path"] for f in _beat(token_a, can_mail=False)["files_requested"]] == [
        "C:/w/task-1/garden-walk.pdf"
    ]


def test_mail_stays_mail_where_it_can_be_sent_or_where_nobody_said(token_a):
    _beat(token_a, can_mail=True)
    mailed = _create(token_a, "make the rates sheet as a PDF", deliver="email_self")
    assert _deliver(mailed["id"]) == "email_self"
    _beat(token_a)  # unknown: as asked
    assert _deliver(_create(token_a, "the rates", deliver="email_self")["id"]) == "email_self"


def test_a_pdf_asked_for_with_no_delivery_goes_to_the_phone_on_a_full_machine_only(token_a):
    _beat(token_a)
    assert _deliver(_create(token_a, "make me a PDF of the hiking plan")["id"]) == "files"
    assert _deliver(_create(token_a, "find the AW4 wiring PDF in Downloads")["id"]) is None
    _beat(token_a, "work laptop", scope="listed", can_mail=False)
    listed = _create(token_a, "make the build notes as a PDF", machine="work laptop",
                     deliver="email_self")
    assert _deliver(listed["id"]) is None  # summaries only: no file, and no mail it cannot send
    plain = _create(token_a, "make the build notes as a PDF", machine="work laptop")
    assert _deliver(plain["id"]) is None


def test_settled_when_the_machine_is_picked_later(token_a):
    _beat(token_a, can_mail=False)
    _beat(token_a, "work laptop", scope="listed")
    waiting = _create(token_a, "make the garden walk as a PDF")
    assert waiting["status"] == "needs_machine"
    assert _deliver(waiting["id"]) is None
    assigned = token_a.post(f"{API}tasks/{waiting['id']}/assign/", {"machine": "Alienware"},
                            format="json")
    assert assigned.status_code == 200, assigned.data
    assert _deliver(waiting["id"]) == "files"


def test_settled_at_claim_for_a_task_queued_with_no_machine(token_a):
    queued = _create(token_a, "Make the trip itinerary", deliver="email_self")
    assert queued["machine"] is None and _deliver(queued["id"]) == "email_self"
    _beat(token_a, can_mail=False)
    claimed = token_a.post(f"{API}tasks/claim/", {"name": "Alienware"}, format="json")
    assert claimed.data[0]["deliver"] == "files"
    assert _deliver(queued["id"]) == "files"
