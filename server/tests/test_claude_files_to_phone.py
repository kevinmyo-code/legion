"""Files to phone (ADR 0080, Kevin 2026-11-09): `deliver: files`.

Naming the files in the request is the yes, so at a `done` finish every found file is requested at
once, within 20 files and 100 MB. The bytes wait in the private bucket (a fake store here) or, with
no bucket set, in the row; only the member who asked may download them; `received/` deletes them;
24 h after upload they are refused at read time and purged by `claude_files_prune`. The work laptop
never takes it. Made-up file names only.
"""

from __future__ import annotations

import datetime

import pytest
from django.core.files.uploadedfile import SimpleUploadedFile
from django.core.management import call_command
from django.utils import timezone

from claude_session import storage, views
from claude_session.management.commands import claude_files_prune
from claude_session.models import ClaudeTask, ClaudeTaskFile
from claude_session.storage import StorageError
from tests.conftest import _client_for, _member

pytestmark = pytest.mark.django_db

API = "/api/claude/"
SCAN = b"%PDF-1.7 not a real document"
PHOTO = b"\xff\xd8\xff\xe0 not a real photo"


class FakeStore:
    def __init__(self):
        self.objects: dict[str, tuple[bytes, str]] = {}
        self.refuse = False

    def put(self, key, blob, content_type):
        if self.refuse:
            raise StorageError("the bucket refused the upload (Fake).")
        self.objects[key] = (blob, content_type)

    def get(self, key):
        if self.refuse:
            raise StorageError("the bucket refused the read (Fake).")
        return self.objects[key][0]

    def delete(self, key):
        if self.refuse:
            raise StorageError("the bucket refused the delete (Fake).")
        self.objects.pop(key, None)


@pytest.fixture
def bucket(monkeypatch):
    store = FakeStore()
    monkeypatch.setattr(views, "get_store", lambda: store)
    return store


def _beat(client, name="Alienware", scope="full"):
    body = {"name": name, "hostname": "h", "scope": scope, "state": "idle", "running": [],
            "claude_version": "2"}
    response = client.post(f"{API}machines/heartbeat/", body, format="json")
    assert response.status_code == 200, response.data
    return response.data


def _create(client, machine="Alienware", deliver="files", **extra):
    body = {"request": "find the scanned form and the two photos and send them to my phone",
            "machine": machine, "parent": None, "origin": "named", "deliver": deliver, **extra}
    return client.post(f"{API}tasks/", body, format="json")


def _finish(client, task_id, found, status="done", question=""):
    body = {"status": status, "summary": "Found them.", "question": question, "found": found}
    response = client.post(f"{API}tasks/{task_id}/finish/", body, format="json")
    assert response.status_code == 200, response.data
    return response.data


def _found(n=2, size=1000):
    names = ["form-scan.pdf", "photo-1.jpg", "photo-2.jpg"]
    return [{"path": f"D:/files/{i}/{names[i % 3]}", "name": names[i % 3], "size": size}
            for i in range(n)]


def _claimed(client, deliver="files", **extra):
    _beat(client)
    task_id = _create(client, deliver=deliver, **extra).data["id"]
    claimed = client.post(f"{API}tasks/claim/", {"name": "Alienware"}, format="json")
    assert claimed.status_code == 200
    return task_id


def _upload(client, task_id, file_id, blob=SCAN, name="form-scan.pdf", ctype="application/pdf"):
    return client.post(
        f"{API}tasks/{task_id}/files/",
        {"file_id": file_id, "file": SimpleUploadedFile(name, blob, content_type=ctype)},
        format="multipart",
    )


def _delivered(client):
    """A finished files task with two found files, both requested, the first uploaded."""
    task_id = _claimed(client)
    files = _finish(client, task_id, _found(2))["files"]
    assert _upload(client, task_id, files[0]["id"]).status_code == 201
    return task_id, files


# --- the yes, and the caps ---------------------------------------------------------------


def test_naming_the_files_requests_every_found_file_at_finish(token_a):
    task_id = _claimed(token_a)
    files = _finish(token_a, task_id, _found(3))["files"]
    assert [f["requested"] for f in files] == [True, True, True]
    asked = _beat(token_a)["files_requested"]
    assert [a["file_id"] for a in asked] == [f["id"] for f in files]


def test_without_deliver_files_nothing_is_requested(token_a):
    task_id = _claimed(token_a, deliver=None)
    files = _finish(token_a, task_id, _found(2))["files"]
    assert all(not f["requested"] for f in files)


def test_a_question_requests_nothing(token_a):
    task_id = _claimed(token_a)
    files = _finish(token_a, task_id, _found(2), status="needs_confirmation",
                    question="I found a different form than the one you named. Send it?")["files"]
    assert all(not f["requested"] for f in files)


@pytest.mark.parametrize("n,size", [(21, 10), (2, 51 * 1024 * 1024)])
def test_over_twenty_files_or_a_hundred_mb_waits_for_a_yes(token_a, n, size):
    task_id = _claimed(token_a)
    result = _finish(token_a, task_id, _found(n, size))
    assert all(not f["requested"] for f in result["files"])
    assert "needs Kevin's yes" in result["detail"]


def test_the_yes_to_the_question_inherits_files_and_lifts_the_caps(token_a):
    parent = _claimed(token_a)
    _finish(token_a, parent, _found(21, 10), status="needs_confirmation",
            question="That is 21 files, 210 bytes. Send them all?")
    follow = token_a.post(
        f"{API}tasks/",
        {"request": "yes", "parent": parent, "origin": "named", "confirms": True}, format="json",
    )
    assert follow.status_code == 201, follow.data
    child = ClaudeTask.objects.get(pk=follow.data["id"])
    assert child.deliver == "files"
    token_a.post(f"{API}tasks/claim/", {"name": "Alienware"}, format="json")
    files = _finish(token_a, child.id, _found(21, 10))["files"]
    assert all(f["requested"] for f in files)


def test_a_plain_follow_up_does_not_inherit_files(token_a):
    parent = _claimed(token_a)
    _finish(token_a, parent, _found(1))
    follow = token_a.post(
        f"{API}tasks/",
        {"request": "what else is in that folder?", "parent": parent, "origin": "named"},
        format="json",
    )
    assert ClaudeTask.objects.get(pk=follow.data["id"]).deliver is None


def test_the_work_laptop_never_takes_files(token_a):
    _beat(token_a, name="work laptop", scope="listed")
    refused = _create(token_a, machine="work laptop")
    assert refused.status_code == 400
    assert "cannot send files" in refused.data["detail"]
    assert not ClaudeTask.objects.exists()


def test_an_unaddressed_files_task_is_not_claimed_by_the_work_laptop(token_a):
    # Sent while no machine was online, so it waits unaddressed; then only the work laptop wakes.
    task_id = _create(token_a, machine=None).data["id"]
    _beat(token_a, name="work laptop", scope="listed")
    claimed = token_a.post(f"{API}tasks/claim/", {"name": "work laptop"}, format="json")
    assert claimed.status_code == 204
    assert ClaudeTask.objects.get(pk=task_id).status == "queued"


# --- storage --------------------------------------------------------------------------


def test_upload_goes_to_the_bucket_under_a_key_with_no_name(token_a, bucket):
    task_id, files = _delivered(token_a)
    row = ClaudeTaskFile.objects.get(pk=files[0]["id"])
    assert row.content is None
    assert row.storage_key in bucket.objects
    assert "form-scan" not in row.storage_key and row.storage_key.startswith("claude-files/")
    assert bucket.objects[row.storage_key] == (SCAN, "application/pdf")
    listed = token_a.get(f"{API}tasks/{task_id}/").data["files"][0]
    assert listed["uploaded"] is True and listed["content_type"] == "application/pdf"
    assert listed["gone"] is False and listed["received"] is False


def test_with_no_bucket_the_bytes_wait_in_the_row(token_a):
    task_id, files = _delivered(token_a)
    row = ClaudeTaskFile.objects.get(pk=files[0]["id"])
    assert bytes(row.content) == SCAN and row.storage_key == ""


def test_a_bucket_that_refuses_stores_nothing(token_a, bucket):
    task_id = _claimed(token_a)
    files = _finish(token_a, task_id, _found(1))["files"]
    bucket.refuse = True
    response = _upload(token_a, task_id, files[0]["id"])
    assert response.status_code == 503
    assert "form-scan" not in response.data["detail"]
    assert ClaudeTaskFile.objects.get(pk=files[0]["id"]).uploaded_at is None


def test_a_page_task_still_keeps_its_page_in_the_row(token_a, bucket):
    task_id = _claimed(token_a, deliver="phone")
    files = _finish(token_a, task_id, [{"path": "C:/w/p.html", "name": "p.html", "size": 10}])
    _upload(token_a, task_id, files["files"][0]["id"], blob=b"<title>t</title>", name="p.html",
            ctype="text/html")
    assert bucket.objects == {}


# --- download, ownership, received --------------------------------------------------------


def test_the_asker_downloads_it_from_the_bucket(token_a, bucket):
    task_id, files = _delivered(token_a)
    response = token_a.get(f"{API}tasks/{task_id}/files/{files[0]['id']}/")
    assert response.status_code == 200
    assert response.content == SCAN
    assert response["Content-Type"] == "application/pdf"
    assert response["Cache-Control"] == "private, no-store"


def test_another_member_of_the_household_cannot_download_or_delete_it(
    token_a, household_a, bucket
):
    task_id, files = _delivered(token_a)
    other = _client_for(_member(household_a, "other-member@example.com"))
    assert other.get(f"{API}tasks/{task_id}/files/{files[0]['id']}/").status_code == 404
    assert other.post(f"{API}tasks/{task_id}/files/{files[0]['id']}/received/").status_code == 404
    assert len(bucket.objects) == 1


def test_another_household_cannot_see_it(token_a, token_b, bucket):
    task_id, files = _delivered(token_a)
    assert token_b.get(f"{API}tasks/{task_id}/files/{files[0]['id']}/").status_code == 404
    assert token_b.post(f"{API}tasks/{task_id}/files/{files[0]['id']}/received/").status_code == 404


def test_received_deletes_the_engine_copy_and_a_second_download_is_gone(token_a, bucket):
    task_id, files = _delivered(token_a)
    url = f"{API}tasks/{task_id}/files/{files[0]['id']}/"
    done = token_a.post(f"{url}received/")
    assert done.status_code == 200 and done.data["deleted"] is True
    assert bucket.objects == {}
    row = ClaudeTaskFile.objects.get(pk=files[0]["id"])
    assert row.received_at is not None and row.purged_at is not None and row.storage_key == ""
    assert token_a.get(url).status_code == 410
    again = token_a.post(f"{url}received/")
    assert again.status_code == 200 and again.data["detail"] == "Already deleted."
    listed = token_a.get(f"{API}tasks/{task_id}/").data["files"][0]
    assert listed["received"] is True and listed["gone"] is True and listed["name"]


def test_received_in_the_row_clears_the_column(token_a):
    task_id, files = _delivered(token_a)
    assert token_a.post(f"{API}tasks/{task_id}/files/{files[0]['id']}/received/").status_code == 200
    assert ClaudeTaskFile.objects.get(pk=files[0]["id"]).content is None


def test_received_before_upload_deletes_nothing(token_a):
    task_id, files = _delivered(token_a)
    response = token_a.post(f"{API}tasks/{task_id}/files/{files[1]['id']}/received/")
    assert response.status_code == 409


def test_received_when_the_bucket_refuses_keeps_it_for_the_prune(token_a, bucket):
    task_id, files = _delivered(token_a)
    bucket.refuse = True
    response = token_a.post(f"{API}tasks/{task_id}/files/{files[0]['id']}/received/")
    assert response.status_code == 503
    row = ClaudeTaskFile.objects.get(pk=files[0]["id"])
    assert row.purged_at is None and row.storage_key


def test_received_is_only_for_delivered_files(token_a):
    task_id = _claimed(token_a, deliver=None)
    files = _finish(token_a, task_id, _found(1))["files"]
    token_a.post(f"{API}tasks/{task_id}/send-file/", {"file_id": files[0]["id"]}, format="json")
    _upload(token_a, task_id, files[0]["id"])
    assert token_a.post(f"{API}tasks/{task_id}/files/{files[0]['id']}/received/").status_code == 404
    # The send_file path is unchanged: 30 days in the row, any member's download.
    assert token_a.get(f"{API}tasks/{task_id}/files/{files[0]['id']}/").status_code == 200


# --- expiry ------------------------------------------------------------------------------


def _age(file_id, hours):
    ClaudeTaskFile.objects.filter(pk=file_id).update(
        uploaded_at=timezone.now() - datetime.timedelta(hours=hours)
    )


def test_a_day_after_upload_it_is_refused_before_the_prune_runs(token_a, bucket):
    task_id, files = _delivered(token_a)
    _age(files[0]["id"], 24)
    response = token_a.get(f"{API}tasks/{task_id}/files/{files[0]['id']}/")
    assert response.status_code == 410
    assert "24 hours" in response.data["detail"]


def test_the_prune_deletes_bytes_past_24_hours_and_keeps_younger_ones(token_a, bucket):
    task_id, files = _delivered(token_a)
    _upload(token_a, task_id, files[1]["id"], blob=PHOTO, name="photo-1.jpg", ctype="image/jpeg")
    _age(files[0]["id"], 25)
    counts = claude_files_prune.prune()
    assert counts == {"deleted": 1, "failed": 0}
    old = ClaudeTaskFile.objects.get(pk=files[0]["id"])
    young = ClaudeTaskFile.objects.get(pk=files[1]["id"])
    assert old.purged_at is not None and old.storage_key == "" and old.received_at is None
    assert young.purged_at is None and young.storage_key in bucket.objects
    assert len(bucket.objects) == 1
    # The row itself stays, for the task's 30 days.
    assert ClaudeTaskFile.objects.count() == 2


def test_the_prune_purges_row_bytes_too_and_counts_a_refusal(token_a, bucket):
    task_id, files = _delivered(token_a)
    _age(files[0]["id"], 30)
    bucket.refuse = True
    assert claude_files_prune.prune() == {"deleted": 0, "failed": 1}
    bucket.refuse = False
    assert claude_files_prune.prune() == {"deleted": 1, "failed": 0}


def test_the_prune_leaves_ordinary_task_files_alone(token_a):
    task_id = _claimed(token_a, deliver=None)
    files = _finish(token_a, task_id, _found(1))["files"]
    token_a.post(f"{API}tasks/{task_id}/send-file/", {"file_id": files[0]["id"]}, format="json")
    _upload(token_a, task_id, files[0]["id"])
    _age(files[0]["id"], 48)
    assert claude_files_prune.prune() == {"deleted": 0, "failed": 0}
    assert ClaudeTaskFile.objects.get(pk=files[0]["id"]).content is not None


def test_the_command_prints_counts_only(token_a, capsys):
    task_id, files = _delivered(token_a)
    _age(files[0]["id"], 25)
    call_command("claude_files_prune")
    out = capsys.readouterr().out
    assert "deleted 1 delivered files" in out
    assert "form-scan" not in out and "D:/" not in out


def test_the_crontab_runs_the_prune_hourly():
    from pathlib import Path

    crontab = Path(__file__).resolve().parents[2] / "deploy" / "crontab"
    assert "50 * * * * python manage.py claude_files_prune" in crontab.read_text().splitlines()


def test_object_keys_never_carry_a_name():
    key = storage.new_key("hh", 7, 9)
    assert key.startswith("claude-files/hh/7/9/") and len(key.rsplit("/", 1)[1]) == 32
    assert storage.new_key("hh", 7, 9) != key
