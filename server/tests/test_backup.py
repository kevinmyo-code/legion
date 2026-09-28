"""backend-etl ticket 03: `backup_nightly` and `restore_backup`.

Drive and the Postgres client tools are faked here. What is owed, and cannot
be proved in this file: a real run landing a dated folder on Kevin's Drive,
and a real restore drill against live (the ticket's two verification items).
"""

from __future__ import annotations

import datetime
import io
import json
import os
import re
import subprocess
from pathlib import Path
from types import SimpleNamespace

import pytest
from cryptography.fernet import Fernet
from django.conf import settings
from django.core.management import call_command
from django.core.management.base import CommandError

from ingest import backup, drive, vault
from ingest.drive import FOLDER_MIME, DriveClient, DriveError, DriveRefused, Response
from ingest.freshness import freshness_for
from ingest.models import IngestRun, Outcome, Source, SourceCredential

DAY = datetime.date(2026, 9, 27)
DRIVE_SECRET = {
    "refresh_token": "1//refresh-token-value",
    "client_id": "client.apps.googleusercontent.com",
    "client_secret": "client-secret-value",
}


# =============================================================================
# An in-memory Drive
# =============================================================================


class FakeDrive:
    """Just enough of Drive for the job. `list_files` honours the parent and
    `trashed=false` clauses and IGNORES appProperties clauses on purpose, so
    the job's own re-check is what keeps a person's folder safe."""

    def __init__(self):
        self.files: dict[str, dict] = {}
        self.contents: dict[str, bytes] = {}
        self.trashed: list[str] = []
        self._n = 0

    def _add(self, name, mime, parent, props, content=None):
        self._n += 1
        file_id = f"f{self._n}"
        self.files[file_id] = {
            "id": file_id,
            "name": name,
            "mimeType": mime,
            "parents": [parent] if parent else [],
            "appProperties": dict(props or {}),
            "createdTime": f"2026-01-01T{self._n:08d}",  # sorts in creation order
            "trashed": False,
        }
        if content is not None:
            self.contents[file_id] = content
        return self.files[file_id]

    def list_files(self, query, *, order_by=None):
        parent = re.search(r"'([^']+)' in parents", query)
        out = [f for f in self.files.values() if not f["trashed"]]
        if parent:
            out = [f for f in out if parent.group(1) in f["parents"]]
        if f"mimeType='{FOLDER_MIME}'" in query:
            out = [f for f in out if f["mimeType"] == FOLDER_MIME]
        return sorted((dict(f) for f in out), key=lambda f: f["createdTime"])

    def create_folder(self, name, *, parent, app_properties):
        return dict(self._add(name, FOLDER_MIME, parent, app_properties))

    def upload_resumable(self, path, *, name, parent, mime_type, app_properties, **_):
        entry = self._add(name, mime_type, parent, app_properties, Path(path).read_bytes())
        return {"id": entry["id"], "name": name}

    def trash(self, file_id):
        self.files[file_id]["trashed"] = True
        self.trashed.append(file_id)

    def download(self, file_id, destination):
        destination.write_bytes(self.contents[file_id])
        return len(self.contents[file_id])

    def live(self, **props):
        return [
            f
            for f in self.files.values()
            if not f["trashed"] and all(f["appProperties"].get(k) == v for k, v in props.items())
        ]


# =============================================================================
# Fixtures
# =============================================================================


@pytest.fixture
def vault_key(monkeypatch):
    monkeypatch.setenv(vault.VAULT_KEY_ENV, Fernet.generate_key().decode())


@pytest.fixture
def drive_login(vault_key, household_a):
    """The operator household's Drive login, with backups switched on."""
    return vault.store(household_a, "drive", DRIVE_SECRET, config={"backup": True})


def _dumper(path: Path):
    path.write_bytes(b"PGDMP fake custom-format dump")


def _job(fake: FakeDrive, **overrides):
    def job(run):
        kwargs = {
            "today": DAY,
            "client_factory": lambda secret: fake,
            "dumper": _dumper,
            "version_check": lambda: "17.6",
            "left_out": lambda: ["auth", "storage"],
        }
        kwargs.update(overrides)
        return backup.run_backup(run, **kwargs)

    return job


def _fresh(household, source=Source.BACKUP):
    runs = IngestRun.objects.filter(household=household)
    return freshness_for(runs, source, datetime.datetime.now(datetime.UTC))


# =============================================================================
# backup_nightly
# =============================================================================


@pytest.mark.django_db
def test_a_backup_lands_a_dated_folder_and_says_media_is_not_in_it(drive_login, household_a):
    fake = FakeDrive()
    lines = []

    runs = backup.run_nightly(_job(fake), write=lines.append)

    assert [r.outcome for r in runs] == [Outcome.OK]
    (root,) = fake.live(legion_backup="root")
    assert root["name"] == "LEGION backups" and root["parents"] == []
    (day,) = fake.live(legion_backup="day")
    assert day["name"] == "2026-09-27" and day["parents"] == [root["id"]]
    (dump,) = fake.live(legion_backup="dump")
    assert dump["name"] == "legion-2026-09-27.dump" and dump["parents"] == [day["id"]]
    assert fake.contents[dump["id"]] == b"PGDMP fake custom-format dump"

    run = runs[0]
    assert run.rows_written == 1
    assert "Media (photos, recordings" in run.watermark and "NOT in this backup" in run.watermark
    assert "Not dumped: auth, storage." in run.watermark
    assert dump["id"] in run.watermark

    entry = _fresh(household_a)
    assert entry["stale"] is False and entry["last_outcome"] == Outcome.OK
    assert entry["sentence"].endswith("Photos and recordings are not in it yet.")


@pytest.mark.django_db
def test_every_other_household_gets_a_mirrored_row_without_the_operators_details(
    drive_login, household_a, household_b
):
    fake = FakeDrive()
    runs = backup.run_nightly(_job(fake), write=lambda line: None)

    assert [r.household_id for r in runs] == [household_a.pk, household_b.pk]
    mirrored = runs[1]
    assert mirrored.outcome == Outcome.OK
    assert mirrored.watermark.startswith(backup.MIRRORED_OK)
    assert "NOT in this backup" in mirrored.watermark
    # The operator's Drive file id stays with the operator.
    (dump,) = fake.live(legion_backup="dump")
    assert dump["id"] not in mirrored.watermark
    assert _fresh(household_b)["stale"] is False
    # Exactly one dump, not one per household.
    assert len(fake.live(legion_backup="dump")) == 1


@pytest.mark.django_db
def test_a_failure_mirrors_as_a_plain_failure(drive_login, household_a, household_b):
    def broken_dumper(path):
        raise backup.BackupError("pg_dump exited 1; nothing was uploaded. connection refused")

    fake = FakeDrive()
    runs = backup.run_nightly(_job(fake, dumper=broken_dumper), write=lambda line: None)

    assert runs[0].outcome == Outcome.FAILED
    assert "pg_dump exited 1" in runs[0].error
    assert runs[1].outcome == Outcome.FAILED
    assert runs[1].error == backup.MIRRORED_FAILED
    assert fake.files == {}


@pytest.mark.django_db
def test_retention_keeps_ninety_and_trashes_only_folders_this_job_made(drive_login):
    fake = FakeDrive()
    root = fake.create_folder(
        "LEGION backups", parent=None, app_properties={"legion_backup": "root"}
    )
    start = DAY - datetime.timedelta(days=92)
    for offset in range(92):  # 2026-06-27 .. 2026-09-26
        day = (start + datetime.timedelta(days=offset)).isoformat()
        fake.create_folder(
            day,
            parent=root["id"],
            app_properties={"legion_backup": "day", "legion_backup_date": day},
        )
    person_made = fake.create_folder("2020-01-01", parent=root["id"], app_properties={})
    look_alike = fake.create_folder(
        "old", parent=root["id"], app_properties={"legion_backup": "day", "legion_backup_date": "x"}
    )
    elsewhere = fake.create_folder(
        "2019-01-01",
        parent=None,
        app_properties={"legion_backup": "day", "legion_backup_date": "2019-01-01"},
    )

    (run,) = backup.run_nightly(_job(fake), write=lambda line: None)

    assert run.outcome == Outcome.OK
    days = sorted(
        f["appProperties"]["legion_backup_date"]
        for f in fake.live(legion_backup="day")
        if f["parents"] == [root["id"]] and f["appProperties"]["legion_backup_date"] != "x"
    )
    assert len(days) == 90
    assert days[0] == "2026-06-30" and days[-1] == "2026-09-27"
    trashed_dates = sorted(
        fake.files[i]["appProperties"].get("legion_backup_date") for i in fake.trashed
    )
    assert trashed_dates == ["2026-06-27", "2026-06-28", "2026-06-29"]
    for survivor in (person_made, look_alike, elsewhere):
        assert fake.files[survivor["id"]]["trashed"] is False
    assert "Retention trashed 3 day folder(s): 2026-06-27, 2026-06-28, 2026-06-29." in run.watermark


@pytest.mark.django_db
def test_a_second_run_the_same_day_replaces_the_first_dump_after_uploading(drive_login):
    fake = FakeDrive()
    backup.run_nightly(_job(fake), write=lambda line: None)
    (first,) = fake.live(legion_backup="dump")

    backup.run_nightly(_job(fake), write=lambda line: None)

    (second,) = fake.live(legion_backup="dump")
    assert second["id"] != first["id"]
    assert fake.trashed == [first["id"]]
    assert len(fake.live(legion_backup="day")) == 1


# -- pg_dump must match the server --------------------------------------------


def _version_run(output, returncode=0):
    def run(args, **kwargs):
        return SimpleNamespace(stdout=output, stderr="", returncode=returncode)

    return run


@pytest.mark.parametrize(
    "output, expected",
    [
        ("pg_dump (PostgreSQL) 17.6\n", "17.6"),
        ("pg_dump (PostgreSQL) 17.6 (Debian 17.6-1.pgdg13+1)\n", "17.6"),
        ("pg_restore (PostgreSQL) 16.4\n", "16.4"),
    ],
)
def test_the_client_version_is_read_from_its_banner(output, expected):
    assert backup.client_version("pg_dump", run=_version_run(output)) == expected


def test_a_major_version_mismatch_is_refused_with_both_versions():
    with pytest.raises(backup.BackupError) as caught:
        backup.check_versions(
            "/usr/bin/pg_dump", run=_version_run("pg_dump (PostgreSQL) 16.4\n"), server="17.6"
        )
    message = str(caught.value)
    assert "16.4" in message and "17.6" in message and "nothing was dumped" in message
    assert (
        backup.check_versions(
            "pg_dump", run=_version_run("pg_dump (PostgreSQL) 17.2\n"), server="17.6"
        )
        == "17.6"
    )


@pytest.mark.django_db
def test_a_version_mismatch_records_failed_and_writes_nothing(drive_login, household_a):
    dumped = []

    def mismatch():
        return backup.check_versions(
            "pg_dump", run=_version_run("pg_dump (PostgreSQL) 16.4\n"), server="17.6"
        )

    fake = FakeDrive()
    (run,) = backup.run_nightly(
        _job(fake, version_check=mismatch, dumper=dumped.append), write=lambda line: None
    )

    assert run.outcome == Outcome.FAILED
    assert "16.4" in run.error and "17.6" in run.error
    assert dumped == [] and fake.files == {}


@pytest.mark.django_db
def test_the_server_version_is_read_from_the_database():
    assert backup.major_of(backup.server_version()) >= 13


def test_pg_dump_passes_the_password_in_the_environment_never_argv(monkeypatch, tmp_path):
    seen = {}

    def run(args, *, env, **kwargs):
        seen["args"], seen["env"] = args, env
        Path(tmp_path / "out.dump").write_bytes(b"PGDMP")
        return SimpleNamespace(returncode=0, stderr="", stdout="")

    monkeypatch.delenv(backup.SCHEMAS_ENV, raising=False)
    backup.pg_dump(tmp_path / "out.dump", run=run, binary="pg_dump")

    password = settings.DATABASES["default"]["PASSWORD"]
    assert seen["env"]["PGPASSWORD"] == password
    assert all(password not in arg for arg in seen["args"] if password)
    assert "--format=custom" in seen["args"]
    assert [a for a in seen["args"] if a.startswith("--schema=")] == [
        "--schema=django",
        "--schema=public",
        "--schema=private",
    ]


def test_a_failed_pg_dump_is_a_failure_in_words(tmp_path):
    def run(args, **kwargs):
        return SimpleNamespace(returncode=1, stderr="pg_dump: error: connection failed", stdout="")

    with pytest.raises(backup.BackupError, match="pg_dump exited 1; nothing was uploaded"):
        backup.pg_dump(tmp_path / "out.dump", run=run, binary="pg_dump")


def test_a_missing_binary_says_where_it_looked(monkeypatch, tmp_path):
    monkeypatch.setenv(backup.PG_BIN_ENV, str(tmp_path))
    with pytest.raises(backup.BackupError, match="pg_dump is not installed in LEGION_PG_BIN_DIR"):
        backup.pg_binary("pg_dump")


# -- not set up ---------------------------------------------------------------


@pytest.mark.django_db
def test_no_drive_login_records_skipped_for_every_household(vault_key, household_a, household_b):
    lines = []
    runs = backup.run_nightly(_job(FakeDrive()), write=lines.append)

    assert [r.outcome for r in runs] == [Outcome.SKIPPED, Outcome.SKIPPED]
    assert "not set up" in lines[0] and "connect_session.py drive --backup" in lines[0]
    assert _fresh(household_a)["sentence"] == "The backup is not set up."


@pytest.mark.django_db
def test_a_drive_login_without_backup_switched_on_is_not_set_up(vault_key, household_a):
    vault.store(household_a, "drive", DRIVE_SECRET, config={"folder_id": "abc"})
    (run,) = backup.run_nightly(_job(FakeDrive()), write=lambda line: None)
    assert run.outcome == Outcome.SKIPPED


@pytest.mark.django_db
def test_another_households_drive_never_receives_the_whole_database(
    vault_key, household_a, household_b
):
    """The dump holds every household's rows (ADR 0045). A non-operator
    household switching backups on must not pull it into its own Drive."""
    vault.store(household_b, "drive", DRIVE_SECRET, config={"backup": True})
    fake = FakeDrive()
    runs = backup.run_nightly(_job(fake), write=lambda line: None)
    assert {r.outcome for r in runs} == {Outcome.SKIPPED}
    assert fake.files == {}


@pytest.mark.django_db
def test_the_command_runs_end_to_end_and_exits_zero_when_not_set_up(household_a):
    out = io.StringIO()
    call_command("backup_nightly", stdout=out)
    assert "not set up" in out.getvalue()
    assert IngestRun.objects.get(household=household_a, source=Source.BACKUP).outcome == (
        Outcome.SKIPPED
    )


# -- a refused Drive login ----------------------------------------------------


def _token_refused(method, url, headers, body):
    assert url == drive.TOKEN_URI
    return Response(
        400,
        {},
        json.dumps(
            {"error": "invalid_grant", "error_description": "Token has been expired"}
        ).encode(),
    )


@pytest.mark.django_db
def test_a_refused_refresh_token_records_needs_login_and_marks_the_login(
    drive_login, household_a, household_b
):
    factory = lambda secret: DriveClient(secret, transport=_token_refused)  # noqa: E731
    runs = backup.run_nightly(_job(None, client_factory=factory), write=lambda line: None)

    assert runs[0].outcome == Outcome.NEEDS_LOGIN
    assert "connect_session.py drive" in runs[0].error
    assert DRIVE_SECRET["refresh_token"] not in runs[0].error
    drive_login.refresh_from_db()
    assert drive_login.invalid_since is not None
    # Kevin's parents are not sent to a laptop to fix it.
    assert runs[1].outcome == Outcome.FAILED and runs[1].error == backup.MIRRORED_FAILED
    assert _fresh(household_a)["sentence"] == (
        "The backup needs you to log in again: run tools/connect_session.py drive"
    )


# =============================================================================
# DriveClient over a scripted transport
# =============================================================================


class ScriptedTransport:
    def __init__(self, replies):
        self.replies = list(replies)
        self.calls = []

    def __call__(self, method, url, headers, body):
        self.calls.append(SimpleNamespace(method=method, url=url, headers=headers, body=body))
        return self.replies.pop(0)


def _token_ok():
    return Response(200, {}, json.dumps({"access_token": "ya29.access"}).encode())


def test_a_resumable_upload_sends_chunks_and_resumes_from_what_drive_has(tmp_path):
    path = tmp_path / "big.dump"
    path.write_bytes(bytes(range(256)) * 3072)  # 786,432 bytes: three 256 KiB chunks
    quantum = drive.CHUNK_QUANTUM
    transport = ScriptedTransport(
        [
            _token_ok(),
            Response(200, {"Location": "https://upload.example/session"}, b""),
            Response(308, {"Range": f"bytes=0-{quantum - 1}"}, b""),
            # Drive kept only half of the second chunk: the client must resume there.
            Response(308, {"Range": f"bytes=0-{quantum + quantum // 2 - 1}"}, b""),
            Response(308, {"Range": f"bytes=0-{2 * quantum + quantum // 2 - 1}"}, b""),
            Response(200, {}, json.dumps({"id": "file123", "name": "big.dump"}).encode()),
        ]
    )
    client = DriveClient(DRIVE_SECRET, transport=transport)

    result = client.upload_resumable(
        path,
        name="big.dump",
        parent="folder1",
        mime_type="application/octet-stream",
        app_properties={"legion_backup": "dump"},
        chunk_size=quantum,
    )

    assert result["id"] == "file123"
    start = transport.calls[1]
    assert "uploadType=resumable" in start.url
    assert start.headers["X-Upload-Content-Length"] == "786432"
    assert json.loads(start.body)["parents"] == ["folder1"]
    ranges = [c.headers["Content-Range"] for c in transport.calls[2:]]
    assert ranges == [
        f"bytes 0-{quantum - 1}/786432",
        f"bytes {quantum}-{2 * quantum - 1}/786432",
        f"bytes {quantum + quantum // 2}-{2 * quantum + quantum // 2 - 1}/786432",
        f"bytes {2 * quantum + quantum // 2}-786431/786432",
    ]
    sent = b"".join(c.body for c in transport.calls[2:])
    # Every byte reached Drive, the re-sent half-chunk included.
    assert sent[:quantum] == path.read_bytes()[:quantum]
    assert all(c.headers["Authorization"] == "Bearer ya29.access" for c in transport.calls[1:])


def test_a_403_from_drive_is_a_failure_not_a_dead_login():
    transport = ScriptedTransport(
        [_token_ok(), Response(403, {}, json.dumps({"error": {"message": "Rate Limit"}}).encode())]
    )
    with pytest.raises(DriveError, match="Rate Limit"):
        DriveClient(DRIVE_SECRET, transport=transport).list_files("trashed=false")


def test_a_401_from_drive_is_a_refused_login():
    transport = ScriptedTransport([_token_ok(), Response(401, {}, b"{}")])
    with pytest.raises(DriveRefused):
        DriveClient(DRIVE_SECRET, transport=transport).trash("f1")


def test_listing_follows_pages():
    transport = ScriptedTransport(
        [
            _token_ok(),
            Response(200, {}, json.dumps({"files": [{"id": "a"}], "nextPageToken": "p2"}).encode()),
            Response(200, {}, json.dumps({"files": [{"id": "b"}]}).encode()),
        ]
    )
    files = DriveClient(DRIVE_SECRET, transport=transport).list_files("trashed=false")
    assert [f["id"] for f in files] == ["a", "b"]
    assert "pageToken=p2" in transport.calls[2].url


# =============================================================================
# restore_backup
# =============================================================================


@pytest.mark.django_db
@pytest.mark.parametrize("variant", [str.lower, str.upper])
def test_restore_refuses_the_live_database_name_before_touching_anything(
    drive_login, monkeypatch, variant
):
    live = settings.DATABASES["default"]["NAME"]

    def touched(*args, **kwargs):
        raise AssertionError("the restore touched something after naming the live database")

    for name in ("create_database", "drop_database", "pg_restore", "find_dump", "pg_binary"):
        monkeypatch.setattr(backup, name, touched)

    with pytest.raises(CommandError, match="that is the live database"):
        call_command("restore_backup", "2026-09-27", target=variant(live))


def test_dropping_the_live_database_is_refused_even_if_called_directly():
    with pytest.raises(backup.BackupError, match="live database"):
        backup.drop_database(settings.DATABASES["default"]["NAME"])


@pytest.fixture
def restore_fakes(drive_login, monkeypatch):
    fake = FakeDrive()
    root = fake.create_folder(
        "LEGION backups", parent=None, app_properties={"legion_backup": "root"}
    )
    day = fake.create_folder(
        "2026-09-27",
        parent=root["id"],
        app_properties={"legion_backup": "day", "legion_backup_date": "2026-09-27"},
    )
    fake._add(
        "legion-2026-09-27.dump",
        "application/octet-stream",
        day["id"],
        {"legion_backup": "dump", "legion_backup_date": "2026-09-27"},
        b"PGDMP",
    )
    calls = SimpleNamespace(created=[], dropped=[], restored=[])
    monkeypatch.setattr("ingest.management.commands.restore_backup.DriveClient", lambda s: fake)
    monkeypatch.setattr(backup, "pg_binary", lambda name: name)
    monkeypatch.setattr(backup, "check_versions", lambda binary: "17.6")
    monkeypatch.setattr(backup, "create_database", calls.created.append)
    monkeypatch.setattr(backup, "drop_database", calls.dropped.append)
    monkeypatch.setattr(backup, "prepare_extensions", lambda name, write: None)

    def fake_restore(path, target, binary=None):
        calls.restored.append((Path(path).read_bytes(), target))
        return ""

    monkeypatch.setattr(backup, "pg_restore", fake_restore)
    calls.live = {"public.a": 3, "public.b": 5}
    calls.restored_counts = {"public.a": 3, "public.b": 5}
    monkeypatch.setattr(backup, "live_counts", lambda schemas: calls.live)
    monkeypatch.setattr(backup, "restored_counts", lambda target, schemas: calls.restored_counts)
    return calls


@pytest.mark.django_db
def test_restore_drills_into_a_throwaway_database_and_drops_it(restore_fakes):
    out = io.StringIO()
    call_command("restore_backup", "2026-09-27", stdout=out)

    (target,) = restore_fakes.created
    assert target.startswith("legion_restore_20260927_")
    assert target != settings.DATABASES["default"]["NAME"]
    assert restore_fakes.restored == [(b"PGDMP", target)]
    assert restore_fakes.dropped == [target]
    assert "All 2 tables match live." in out.getvalue()


@pytest.mark.django_db
def test_restore_reports_every_difference_and_exits_non_zero(restore_fakes):
    restore_fakes.live = {"public.a": 4, "public.b": 5, "public.c": 1}
    out = io.StringIO()
    with pytest.raises(CommandError, match="2 of 3 tables differ from live: public.a, public.c"):
        call_command("restore_backup", "2026-09-27", stdout=out)
    text = out.getvalue()
    assert "public.a: restored 3, live 4 (live has 1 more, possibly written since the dump)" in text
    assert "public.b: 5 = live" in text
    assert "public.c: MISSING from the restore (live has 1)" in text
    assert len(restore_fakes.dropped) == 1


@pytest.mark.django_db
def test_a_restore_that_restored_nothing_is_not_a_pass(restore_fakes):
    restore_fakes.restored_counts = {}
    with pytest.raises(CommandError, match="produced no tables"):
        call_command("restore_backup", "2026-09-27", stdout=io.StringIO())
    assert len(restore_fakes.dropped) == 1


@pytest.mark.django_db
def test_restore_of_a_day_with_no_backup_says_so(restore_fakes):
    with pytest.raises(CommandError, match="No backup folder for 2026-09-20"):
        call_command("restore_backup", "2026-09-20", stdout=io.StringIO())
    assert restore_fakes.created == []


@pytest.mark.django_db
def test_restore_without_backups_set_up_says_so(household_a):
    with pytest.raises(CommandError, match="not set up"):
        call_command("restore_backup", "2026-09-27", stdout=io.StringIO())


# =============================================================================
# Wiring, and the real client tools when asked for (opt-in)
# =============================================================================


def test_the_crontab_runs_backup_nightly_at_three():
    crontab = Path(__file__).resolve().parents[2] / "deploy" / "crontab"
    assert "0 3 * * * python manage.py backup_nightly" in crontab.read_text().splitlines()


def test_the_image_installs_the_client_for_the_servers_major():
    dockerfile = (Path(__file__).resolve().parents[1] / "Dockerfile").read_text()
    assert "ARG PG_MAJOR=17" in dockerfile
    assert '"postgresql-client-${PG_MAJOR}"' in dockerfile


def test_subprocess_is_the_default_runner():
    # Guards against a test double leaking into the module defaults.
    assert backup.pg_dump.__kwdefaults__["run"] is subprocess.run
    assert backup.pg_restore.__kwdefaults__["run"] is subprocess.run


@pytest.mark.django_db
@pytest.mark.skipif(
    os.environ.get("LEGION_BACKUP_DRILL") != "1",
    reason="Opt-in: creates and drops a database on the test server. LEGION_BACKUP_DRILL=1.",
)
def test_a_real_dump_restores_into_a_throwaway_database_with_equal_counts(tmp_path):
    """The real client tools against the TEST database (never live): pg_dump,
    CREATE DATABASE, extensions, pg_restore, counts, DROP. Proves the
    mechanics; the drill against live is still owed by hand."""
    assert settings.DATABASES["default"]["NAME"].startswith("test_"), "never against live"
    dump_bin, restore_bin = backup.pg_binary("pg_dump"), backup.pg_binary("pg_restore")
    backup.check_versions(dump_bin)
    path = tmp_path / "drill.dump"
    backup.pg_dump(path, binary=dump_bin)
    target = backup.throwaway_name("2026-09-27")
    backup.create_database(target)
    try:
        backup.prepare_extensions(target, write=print)
        stderr = backup.pg_restore(path, target, binary=restore_bin)
        print(stderr)
        restored = backup.restored_counts(target, backup.schemas())
        live = backup.live_counts(backup.schemas())
    finally:
        backup.drop_database(target)
    lines, mismatched = backup.compare(restored, live)
    for line in lines:
        print(line)
    assert restored, "the restore produced no tables"
    assert mismatched == []


def test_source_credential_config_carries_only_the_switch(vault_key, household_a, db):
    credential = vault.store(household_a, "drive", DRIVE_SECRET, config={"backup": True})
    stored = SourceCredential.objects.get(pk=credential.pk)
    assert stored.config == {"backup": True}
    assert DRIVE_SECRET["refresh_token"].encode() not in bytes(stored.ciphertext)
