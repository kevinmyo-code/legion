"""`deliver: phone` (claude-session ticket 09, Kevin 2026-11-03).

The page is requested at finish (asking was the yes), uploaded by the worker
as text/html under 5 MB, listed on every task row under `artifacts` with a
signed 10 minute link, and served by `PageView` with the recruiter report's
sandbox headers and no token header. The work laptop never takes one. The
link names one household and one file; it does not open another.
"""

from __future__ import annotations

import datetime

import pytest
from django.core import signing
from django.core.files.uploadedfile import SimpleUploadedFile
from django.utils import timezone
from rest_framework.test import APIClient

from claude_session import views
from claude_session.models import ClaudeMachine, ClaudeTaskFile

pytestmark = pytest.mark.django_db

API = "/api/claude/"
PAGE = (
    b"<!doctype html><html><head><title>Atlanta &amp; back: a day trip</title></head>"
    b"<body><script>document.title='x'</script><h1>Itinerary</h1></body></html>"
)


def _beat(client, name="Alienware", scope="full"):
    body = {"name": name, "hostname": "h", "scope": scope, "state": "idle", "running": [],
            "claude_version": "2"}
    response = client.post(f"{API}machines/heartbeat/", body, format="json")
    assert response.status_code == 200, response.data
    return response.data


def _create(client, machine="Alienware", deliver="phone"):
    body = {"request": "make me an itinerary for a day trip into Atlanta", "machine": machine,
            "parent": None, "origin": "offered", "deliver": deliver}
    return client.post(f"{API}tasks/", body, format="json")


def _upload(client, task_id, file_id, blob=PAGE, name="atlanta.html", ctype="text/html"):
    return client.post(
        f"{API}tasks/{task_id}/files/",
        {"file_id": file_id, "file": SimpleUploadedFile(name, blob, content_type=ctype)},
        format="multipart",
    )


def _finished_with_page(client, deliver="phone"):
    _beat(client)
    task_id = _create(client, deliver=deliver).data["id"]
    claimed = client.post(f"{API}tasks/claim/", {"name": "Alienware"}, format="json")
    assert claimed.status_code == 200
    found = [
        {"path": "C:/w/41/atlanta.html", "name": "atlanta.html", "size": len(PAGE)},
        {"path": "C:/w/41/notes.txt", "name": "notes.txt", "size": 3},
    ]
    finished = client.post(
        f"{API}tasks/{task_id}/finish/",
        {"status": "done", "summary": "Your Atlanta day trip is ready.", "found": found},
        format="json",
    )
    assert finished.status_code == 200, finished.data
    return task_id, finished.data["files"]


def test_the_page_is_requested_at_finish_and_rides_the_heartbeat(token_a):
    task_id, files = _finished_with_page(token_a)
    page, notes = files
    assert page["requested"] is True and page["uploaded"] is False
    # Only the page: any other file still waits for Kevin's yes.
    assert notes["requested"] is False
    asked = _beat(token_a)["files_requested"]
    assert asked == [{"task": task_id, "file_id": page["id"], "path": "C:/w/41/atlanta.html"}]
    # Not there yet: no artifact listed.
    assert token_a.get(f"{API}tasks/{task_id}/").data["artifacts"] == []


def test_without_deliver_phone_an_html_file_is_only_listed(token_a):
    task_id, files = _finished_with_page(token_a, deliver=None)
    assert all(f["requested"] is False for f in files)
    assert _beat(token_a)["files_requested"] == []


def test_upload_lists_the_artifact_with_its_title_and_a_signed_link(token_a):
    task_id, files = _finished_with_page(token_a)
    stored = _upload(token_a, task_id, files[0]["id"])
    assert stored.status_code == 201, stored.data
    row = token_a.get(f"{API}tasks/{task_id}/").data
    [artifact] = row["artifacts"]
    assert artifact["filename"] == "atlanta.html"
    assert artifact["title"] == "Atlanta & back: a day trip"
    assert artifact["expires_in"] == 600
    assert artifact["url"].startswith(f"/api/claude/pages/{files[0]['id']}/?t=")
    # The list carries it too.
    assert token_a.get(f"{API}tasks/").data[0]["artifacts"][0]["id"] == files[0]["id"]


def test_the_link_opens_without_a_token_and_is_sandboxed(token_a):
    task_id, files = _finished_with_page(token_a)
    _upload(token_a, task_id, files[0]["id"])
    url = token_a.get(f"{API}tasks/{task_id}/").data["artifacts"][0]["url"]
    response = APIClient().get(url)
    assert response.status_code == 200
    assert response.content == PAGE
    assert response["Content-Type"].startswith("text/html")
    assert response["Content-Security-Policy"] == "sandbox allow-scripts"
    assert response["X-Content-Type-Options"] == "nosniff"
    assert response["Cache-Control"] == "private, no-store"


def test_an_expired_tampered_or_borrowed_link_opens_nothing(token_a, monkeypatch):
    task_id, files = _finished_with_page(token_a)
    _upload(token_a, task_id, files[0]["id"])
    url = token_a.get(f"{API}tasks/{task_id}/").data["artifacts"][0]["url"]
    client = APIClient()
    assert client.get(url[:-2] + "zz").status_code == 404
    assert client.get(f"{API}pages/{files[0]['id']}/").status_code == 404
    # A good signature for one file does not open another id.
    token = url.split("?t=")[1]
    assert client.get(f"{API}pages/{files[1]['id']}/?t={token}").status_code == 404
    # Past ten minutes.
    real = signing.TimestampSigner.unsign

    def expired(self, value, max_age=None):
        return real(self, value, max_age=-1)

    monkeypatch.setattr(signing.TimestampSigner, "unsign", expired)
    assert client.get(url).status_code == 404


def test_a_signature_naming_another_household_opens_nothing(token_a, household_b):
    task_id, files = _finished_with_page(token_a)
    _upload(token_a, task_id, files[0]["id"])
    signer = signing.TimestampSigner(salt=views.PAGE_SALT)
    forged = signer.sign(f"{household_b.id}:{files[0]['id']}")
    assert APIClient().get(f"{API}pages/{files[0]['id']}/?t={forged}").status_code == 404


def test_a_page_must_be_html_and_under_5mb(token_a, monkeypatch):
    task_id, files = _finished_with_page(token_a)
    wrong = _upload(token_a, task_id, files[0]["id"], ctype="application/octet-stream")
    assert wrong.status_code == 415
    monkeypatch.setattr(views, "PAGE_MAX_BYTES", 16)
    big = _upload(token_a, task_id, files[0]["id"])
    assert big.status_code == 413
    assert ClaudeTaskFile.objects.get(pk=files[0]["id"]).uploaded_at is None


def test_the_work_laptop_never_takes_a_page(token_a):
    _beat(token_a, "work laptop", scope="listed")
    refused = _create(token_a, machine="work laptop")
    assert refused.status_code == 400
    assert "summaries only" in refused.data["detail"]
    # Unnamed with only the work laptop on: refused too, nothing stored.
    refused = _create(token_a, machine=None)
    assert refused.status_code == 400


def test_an_unaddressed_page_task_waits_for_a_machine_that_can_send_it(token_a):
    created = _create(token_a, machine=None)  # nothing online yet
    assert created.status_code == 201 and created.data["machine"] is None
    _beat(token_a, "work laptop", scope="listed")
    laptop = token_a.post(f"{API}tasks/claim/", {"name": "work laptop"}, format="json")
    assert laptop.status_code == 204
    ClaudeMachine.objects.filter(name="work laptop").update(
        last_seen_at=timezone.now() - datetime.timedelta(seconds=300)
    )
    _beat(token_a, "Alienware")
    claimed = token_a.post(f"{API}tasks/claim/", {"name": "Alienware"}, format="json")
    assert claimed.status_code == 200
    assert claimed.data[0]["id"] == created.data["id"]


def test_assign_refuses_the_work_laptop_for_a_page(token_a):
    _beat(token_a, "Alienware")
    _beat(token_a, "work laptop", scope="listed")
    created = _create(token_a, machine=None)
    assert created.data["status"] == "needs_machine"
    refused = token_a.post(f"{API}tasks/{created.data['id']}/assign/",
                           {"machine": "work laptop"}, format="json")
    assert refused.status_code == 400
    ok = token_a.post(f"{API}tasks/{created.data['id']}/assign/",
                      {"machine": "Alienware"}, format="json")
    assert ok.status_code == 200


def test_another_household_cannot_see_the_artifact(token_a, token_b):
    task_id, files = _finished_with_page(token_a)
    _upload(token_a, task_id, files[0]["id"])
    assert token_b.get(f"{API}tasks/{task_id}/").status_code == 404
    assert token_b.get(f"{API}tasks/").data == []
