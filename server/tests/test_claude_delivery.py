"""Where a free-text Claude task's result goes once its machine is known (claude_session.delivery).

The 2026-11-22 failure, as tests: a file asked for "as a PDF" with `deliver: email_self` went to a
laptop that cannot send mail, so nothing was emailed and nothing reached the phone. Invented
requests only.
"""

from __future__ import annotations

import pytest

from claude_session.delivery import asks_for_made_file, in_task_folder
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
    # Re-review r2: a topic word that is not about the format still asks for the file.
    "make a comparison of three laptops as a PDF",
    "make a guide for the dog sitter as a PDF",
    "write up a study guide for my exam as a PDF",
    "make me a list of apps for budgeting as a PDF",
    "write me steps to change the oil, as a PDF",
    # RED r3: "how" alone is not a how-to.
    "write up how my week went as a PDF",
    "make a summary of how the trip went as a PDF",
    "make a plan for how we spend Saturday as a PDF",
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
    "find the receipts I put into a spreadsheet",
    "where did I save the lease as a PDF?",
    "find the file I had to print as a PDF last week",
    "can you check whether I need to save it as a PDF before sending",
    "make sure the invoice I got is attached to a document",
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


@pytest.mark.parametrize("words", [
    "Write a page on how to convert a Word doc into a PDF",
    "Make a page explaining how to export my notes to Excel",
    "Make me a page comparing tools that turn scans into a PDF",
    "create a page, I will print it as a PDF later",
    "Build a guide page on how to print a spreadsheet as a PDF",
    "Write a page about the best way to put receipts into a spreadsheet",
    "a researched write-up on garden walks",
    "a page about PDF tools",
    # Known and accepted (RED r3): "page on how the IRS wants returns sent in PDF format" flips;
    # a flipped task sends only what it made in its own folder (made_only), never his files.
])
def test_a_page_about_a_file_format_stays_a_page(token_a, words):
    """RED review 2026-11-22: the format word is the topic, not the deliverable."""
    _beat(token_a)
    assert _deliver(_create(token_a, words, deliver="phone")["id"]) == "phone"


@pytest.mark.parametrize("words", [
    "make a one-page weekend plan for Austin as a PDF",
    "write up the itinerary and make it a PDF",
    "send me a PDF of the reading list",
    "a PDF version of the packing list",
    "make a comparison of three laptops as a PDF",
    "research the best tools for tiling a bathroom and make it a PDF",
    "turn my notes into a PDF",
])
def test_a_page_asked_for_as_a_pdf_becomes_the_pdf_file(token_a, words):
    """Task 32 (2026-11-22): the phone sent page=true for a plan "as a PDF"; a page is HTML, so
    the helper made HTML and no PDF reached the phone."""
    _beat(token_a)
    assert _deliver(_create(token_a, words, deliver="phone")["id"]) == "files"


def test_a_pdf_page_on_the_work_laptop_is_refused_not_flipped(token_a):
    _beat(token_a, "work laptop", scope="listed")
    body = {"request": "make the notes as a PDF", "machine": "work laptop", "parent": None,
            "origin": "named", "deliver": "phone"}
    r = token_a.post(f"{API}tasks/", body, format="json")
    assert r.status_code == 400


def _finish_found(client, task_id, found):
    client.post(f"{API}tasks/claim/", {"name": "Alienware"}, format="json")
    r = client.post(f"{API}tasks/{task_id}/finish/",
                    {"status": "done", "summary": "Done.", "found": found}, format="json")
    assert r.status_code == 200, r.data
    return {f["name"]: f["requested"] for f in r.data["files"]}


def test_a_made_file_task_sends_only_what_it_made_in_its_own_folder(token_a):
    """Made means made: a file the helper found elsewhere (his own scan) is never sent."""
    _beat(token_a)
    made = _create(token_a, "make a one-page weekend plan for Austin as a PDF", deliver="phone")
    assert ClaudeTask.objects.get(pk=made["id"]).made_only is True
    token_a.post(f"{API}tasks/claim/", {"name": "Alienware"}, format="json")
    finished = token_a.post(
        f"{API}tasks/{made['id']}/finish/",
        {"status": "done", "summary": "Your plan is ready.",
         "found": [{"path": f"C:/w/work/task-{made['id']}/austin.pdf", "name": "austin.pdf",
                    "size": 9000},
                   {"path": "C:/Users/k/Documents/scan.pdf", "name": "scan.pdf", "size": 900},
                   {"path": f"C:/w/work/task-{made['id']}0/other.pdf", "name": "other.pdf",
                    "size": 900}]},
        format="json",
    )
    assert finished.status_code == 200
    assert {f["name"]: f["requested"] for f in finished.data["files"]} == {
        "austin.pdf": True, "scan.pdf": False, "other.pdf": False}


def test_made_only_comes_from_the_engines_own_flip_not_the_words(token_a):
    """RED review r2: "send me the lease PDF" arrives as files from the phone. The engine did not
    flip it, so the lease it found in Documents is sent, as on base."""
    _beat(token_a)
    sent = _create(token_a, "send me the lease PDF", deliver="files")
    assert ClaudeTask.objects.get(pk=sent["id"]).made_only is None
    got = _finish_found(token_a, sent["id"],
                        [{"path": "C:/Users/k/Documents/lease.pdf", "name": "lease.pdf",
                          "size": 900}])
    assert got == {"lease.pdf": True}


def test_a_no_delivery_flip_is_made_only_too_and_takes_a_windows_path(token_a):
    _beat(token_a)
    made = _create(token_a, "make a guide for the dog sitter as a PDF")
    assert ClaudeTask.objects.get(pk=made["id"]).made_only is True
    tid = made["id"]
    got = _finish_found(token_a, tid, [
        {"path": rf"C:\Users\k\AppData\Roaming\legion-claude\work\task-{tid}\guide.pdf",
         "name": "guide.pdf", "size": 900},
        {"path": r"C:\Users\k\Documents\scan.pdf", "name": "scan.pdf", "size": 900}])
    assert got == {"guide.pdf": True, "scan.pdf": False}


def test_a_flip_at_assign_is_made_only(token_a):
    _beat(token_a)
    _beat(token_a, "work laptop", scope="listed")
    waiting = _create(token_a, "make the garden walk as a PDF", deliver="phone")
    assert waiting["status"] == "needs_machine"
    row = ClaudeTask.objects.get(pk=waiting["id"])
    assert (row.deliver, row.made_only) == ("phone", None)
    r = token_a.post(f"{API}tasks/{waiting['id']}/assign/", {"machine": "Alienware"},
                     format="json")
    assert r.status_code == 200, r.data
    row.refresh_from_db()
    assert (row.deliver, row.made_only) == ("files", True)


def test_a_flip_at_claim_is_made_only(token_a):
    queued = _create(token_a, "make the garden walk as a PDF")
    row = ClaudeTask.objects.get(pk=queued["id"])
    assert (row.machine_id, row.deliver, row.made_only) == (None, None, None)
    _beat(token_a)
    claimed = token_a.post(f"{API}tasks/claim/", {"name": "Alienware"}, format="json")
    assert claimed.data[0]["deliver"] == "files"
    row.refresh_from_db()
    assert row.made_only is True


def test_mail_on_a_laptop_without_mail_is_files_but_not_made_only(token_a):
    _beat(token_a, can_mail=False)
    t = _create(token_a, "email me my resume", deliver="email_self")
    row = ClaudeTask.objects.get(pk=t["id"])
    assert (row.deliver, row.made_only) == ("files", None)


def test_a_plain_files_task_still_sends_what_it_found(token_a):
    _beat(token_a)
    t = _create(token_a, "send me the form scan from my Documents", deliver="files")
    token_a.post(f"{API}tasks/claim/", {"name": "Alienware"}, format="json")
    finished = token_a.post(
        f"{API}tasks/{t['id']}/finish/",
        {"status": "done", "summary": "Found it.",
         "found": [{"path": "C:/Users/k/Documents/scan.pdf", "name": "scan.pdf", "size": 900}]},
        format="json",
    )
    assert [f["requested"] for f in finished.data["files"]] == [True]


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


@pytest.mark.parametrize(("path", "inside"), [
    (r"C:\Users\k\AppData\Roaming\legion-claude\work\task-32\sf.pdf", True),
    (r"\\?\C:\w\work\task-32\sf.pdf", True),
    ("C:/w/work/task-32/sf.pdf", True),
    (r"C:\w\work\task-320\sf.pdf", False),
    (r"C:\w\work\task-32\sub\sf.pdf", False),
    (r"C:\Users\k\Documents\sf.pdf", False),
])
def test_in_task_folder_backslash_and_slash_paths(path, inside):
    assert in_task_folder(32, path) is inside
