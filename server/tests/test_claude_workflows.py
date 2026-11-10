"""Worker workflows, engine half (map worker-workflows tickets 03 and 04, ADR 0067 amended
2026-11-10).

The catalog on the heartbeat (stored only when its hash moves, bad entries dropped and counted),
`POST tasks/ {workflow}` resolution (phrase, machine, scope, one at a time, follow-ups), `GET
workflows/`, `POST workflows/run/`, the status names, and the catch-up rule minted at claim.
Invented workflows only. Time moves by back-dating rows, never by patching the clock.
"""

from __future__ import annotations

import copy
import datetime
import json
from pathlib import Path

import pytest
from django.utils import timezone
from rest_framework.test import APIClient

from claude_session import workflows as wf
from claude_session.models import ClaudeMachine, ClaudeTask
from household.models import DeviceToken
from tests.conftest import _client_for, _member

pytestmark = pytest.mark.django_db

API = "/api/claude/"
MATCH_TABLE = (
    Path(__file__).resolve().parents[2]
    / "tools" / "claude_session" / "test_fixtures" / "workflows" / "match_cases.json"
)

LOG_AUDIT = {
    "name": "log-audit", "says": "Audits the phone's recent logs and lists numbered findings.",
    "aliases": ["audit my logs", "log audit", "check my logs"],
    "params": [{"name": "window", "says": "which stretch of time", "required": False}],
    "asks": [], "outputs": ["summary", "report"], "deliver": None, "scope": ["full", "listed"],
    "every": None,
}
TIDY = {
    "name": "tidy-downloads", "says": "Sorts the Downloads folder into dated subfolders.",
    "aliases": ["tidy my downloads"], "params": [],
    "asks": [{"at": "move", "says": "Move the files into dated folders?"}],
    "outputs": ["summary", "report"], "deliver": None, "scope": ["full"],
    "every": {"hours": 24, "idle_minutes": 10, "force_after_hours": 36, "retry_after_hours": 6},
}
DIGEST = {
    "name": "weekly-digest", "says": "Writes a weekly digest and mails it to Kevin.",
    "aliases": ["weekly digest"], "params": [], "asks": [],
    "outputs": ["summary", "report", "page"], "deliver": "email_self", "scope": ["full"],
    "every": {"hours": 168, "idle_minutes": 10, "force_after_hours": 216, "retry_after_hours": 24},
}
PAGE = {
    "name": "rates-page", "says": "Builds this week's rates as a page.",
    "aliases": ["rates page"], "params": [], "asks": [],
    "outputs": ["summary", "page"], "deliver": None, "scope": ["full"],
    "every": {"hours": 24, "idle_minutes": 0, "force_after_hours": 24, "retry_after_hours": 6},
}


def _worker(user, name="Alienware", scope="full", catalog=(LOG_AUDIT, TIDY), idle=900,
            wf_hash="h1"):
    """A worker client for its own machine: its own token, bound by its first heartbeat."""
    _token, raw = DeviceToken.issue(user, f"claude-session-{name}")
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Token {raw}")
    reply = _beat(client, name, scope, catalog=catalog, idle=idle, wf_hash=wf_hash)
    assert reply["workflows_dropped"] == 0
    return client


def _beat(client, name="Alienware", scope="full", *, catalog=None, idle=900, wf_hash=None):
    body = {"name": name, "scope": scope, "state": "idle", "progress": {}}
    if idle is not None:
        body["input_idle_s"] = idle
    if wf_hash is not None:
        body["workflows_hash"] = wf_hash
    if catalog is not None:
        body["workflows"] = [copy.deepcopy(e) for e in catalog]
    response = client.post(f"{API}machines/heartbeat/", body, format="json")
    assert response.status_code == 200, response.data
    return response.data


def _create(client, phrase, **extra):
    body = {"request": phrase, "workflow": phrase, "origin": "named", **extra}
    return client.post(f"{API}tasks/", body, format="json")


def _claim(client, name="Alienware"):
    return client.post(f"{API}tasks/claim/", {"name": name}, format="json")


def _finish(client, task_id, **body):
    body.setdefault("status", "done")
    return client.post(f"{API}tasks/{task_id}/finish/", body, format="json")


def _ago(task_id, hours):
    stamp = timezone.now() - datetime.timedelta(hours=hours)
    ClaudeTask.objects.filter(pk=task_id).update(created_at=stamp, finished_at=stamp)


def _first_seen(name, machine="Alienware", hours=0.0):
    m = ClaudeMachine.objects.get(name=machine)
    for e in m.workflows:
        if e["name"] == name:
            e["seen_at"] = (timezone.now() - datetime.timedelta(hours=hours)).isoformat()
    m.save(update_fields=["workflows"])


def _age_machine(name, seconds):
    ClaudeMachine.objects.filter(name=name).update(
        last_seen_at=timezone.now() - datetime.timedelta(seconds=seconds)
    )


@pytest.fixture
def worker(household_user):
    return _worker(household_user)


# --- the matcher is the worker's ---------------------------------------------------------------


def test_the_engine_matcher_runs_the_shared_phrase_table():
    table = json.loads(MATCH_TABLE.read_text(encoding="utf-8"))
    for case in table["cases"]:
        got = wf.match(case["phrase"], table["catalog"], table["machines"])
        if case["expect"] is None:
            assert got is None, case
        else:
            assert got is not None, case
            assert (got[0]["name"], got[1]) == (case["expect"], case["params"]), case


# --- the catalog on the heartbeat (ticket 03) -------------------------------------------------


def test_the_catalog_is_stored_on_a_hash_change_only(household_user):
    client = _worker(household_user)
    machine = ClaudeMachine.objects.get(name="Alienware")
    assert [e["name"] for e in machine.workflows] == ["log-audit", "tidy-downloads"]
    assert machine.workflows_hash == "h1" and machine.workflows_at is not None
    assert all("seen_at" in e for e in machine.workflows)

    # Same hash, different list: the engine keeps what it has (the worker's hash is the truth).
    reply = _beat(client, catalog=[LOG_AUDIT], wf_hash="h1")
    assert reply["workflows_hash"] == "h1"
    machine.refresh_from_db()
    assert len(machine.workflows) == 2

    # Hash moved but no list: nothing stored, the old hash echoed so the worker sends the list.
    reply = _beat(client, wf_hash="h2")
    assert reply["workflows_hash"] == "h1"

    reply = _beat(client, catalog=[LOG_AUDIT], wf_hash="h2")
    assert reply["workflows_hash"] == "h2"
    machine.refresh_from_db()
    assert [e["name"] for e in machine.workflows] == ["log-audit"]


def test_a_worker_that_predates_workflows_leaves_the_catalog_alone(household_user):
    client = _worker(household_user)
    reply = _beat(client)  # no workflows_hash at all: today's live worker
    assert reply["workflows_hash"] == "h1" and reply["workflows_dropped"] == 0
    assert len(ClaudeMachine.objects.get(name="Alienware").workflows) == 2


def test_seen_at_survives_a_new_catalog(household_user):
    client = _worker(household_user)
    _first_seen("log-audit", hours=48)
    before = next(e for e in ClaudeMachine.objects.get().workflows if e["name"] == "log-audit")
    _beat(client, catalog=[LOG_AUDIT, TIDY, DIGEST], wf_hash="h9")
    after = next(e for e in ClaudeMachine.objects.get().workflows if e["name"] == "log-audit")
    assert after["seen_at"] == before["seen_at"]


def test_a_bad_entry_is_dropped_and_counted_never_stored(household_user):
    bad = [
        dict(LOG_AUDIT, name="Bad Name"),
        dict(LOG_AUDIT, name="pathy", says="Reads C:/Users/example/notes.txt", aliases=["pathy"]),
        dict(LOG_AUDIT, name="mailer", aliases=["mail someone@example.com"]),
        dict(LOG_AUDIT, name="spender", says="Spends $40 a week", aliases=["spend"]),
        dict(LOG_AUDIT, name="extra", aliases=["extra"], sneaky=True),
        dict(LOG_AUDIT, name="dupe-phrase", aliases=["audit my logs"]),  # LOG_AUDIT's
        dict(TIDY, name="too-long", aliases=["too long"], every=dict(TIDY["every"], hours=721)),
        "not an object",
    ]
    client = _worker(household_user, catalog=())
    reply = _beat(client, catalog=[LOG_AUDIT, *bad], wf_hash="h5")
    assert reply["workflows_dropped"] == len(bad)
    stored = ClaudeMachine.objects.get().workflows
    assert [e["name"] for e in stored] == ["log-audit"]
    assert "C:/Users" not in json.dumps(stored) and "@" not in json.dumps(stored)


# --- POST tasks/ with a workflow (ticket 03) ------------------------------------------------


def test_a_known_phrase_is_a_workflow_task(worker, token_a):
    created = _create(token_a, "Have Claude audit my logs from this morning")
    assert created.status_code == 201, created.data
    assert created.data["workflow"] == "log-audit" and created.data["asks"] == []
    assert created.data["machine"]["name"] == "Alienware"
    task = ClaudeTask.objects.get(pk=created.data["id"])
    assert (task.kind, task.workflow, task.params) == ("workflow", "log-audit", "from this morning")
    assert task.request == "Have Claude audit my logs from this morning"  # Kevin's words kept
    row = token_a.get(f"{API}tasks/{task.id}/").data
    assert (row["kind"], row["workflow"], row["params"]) == (
        "workflow", "log-audit", "from this morning"
    )


def test_the_created_reply_names_where_it_stops(worker, token_a):
    created = _create(token_a, "tidy my downloads")
    assert created.data["asks"] == ["Move the files into dated folders?"]


def test_an_unknown_phrase_is_refused_with_the_names_there_are(worker, token_a):
    created = _create(token_a, "run a log autopsy")
    assert created.status_code == 400
    assert created.data["workflows"] == ["log audit", "tidy downloads"]
    assert "log audit, tidy downloads" in created.data["detail"]
    assert ClaudeTask.objects.count() == 0


def test_a_named_machine_without_it_says_so(household_user, token_a):
    _worker(household_user)
    _worker(household_user, "work laptop", "listed", catalog=(), wf_hash="e")
    created = _create(token_a, "audit my logs", machine="work laptop")
    assert created.status_code == 400
    assert created.data["detail"].startswith("Nothing was sent. The work laptop has no workflows")


def test_a_workflow_is_refused_on_a_machine_its_scope_excludes(household_user, token_a):
    _worker(household_user, "work laptop", "listed", catalog=(TIDY,), wf_hash="w")
    created = _create(token_a, "tidy my downloads", machine="work laptop")
    assert created.status_code == 400
    assert "does not run on the work laptop" in created.data["detail"]


def test_two_machines_with_it_online_need_a_machine_and_assign_checks_the_catalog(
    household_user, token_a
):
    _worker(household_user)
    _worker(household_user, "work laptop", "listed", catalog=(LOG_AUDIT,), wf_hash="w")
    created = _create(token_a, "audit my logs")
    assert created.data["status"] == "needs_machine"
    task_id = created.data["id"]
    assert ClaudeTask.objects.get(pk=task_id).workflow == "log-audit"
    assigned = token_a.post(f"{API}tasks/{task_id}/assign/", {"machine": "work laptop"},
                            format="json")
    assert assigned.status_code == 200 and assigned.data["workflow"] == "log-audit"


def test_assign_refuses_a_machine_without_the_workflow(household_user, token_a):
    _worker(household_user)
    _worker(household_user, "work laptop", "listed", catalog=(LOG_AUDIT,), wf_hash="w")
    _worker(household_user, "spare", "full", catalog=(TIDY,), wf_hash="s")
    task_id = _create(token_a, "audit my logs").data["id"]
    refused = token_a.post(f"{API}tasks/{task_id}/assign/", {"machine": "spare"}, format="json")
    assert refused.status_code == 400
    assert ClaudeTask.objects.get(pk=task_id).status == "needs_machine"


def test_only_the_machine_that_has_it_takes_it(household_user, token_a):
    _worker(household_user)
    _worker(household_user, "work laptop", "listed", catalog=(), wf_hash="e")
    created = _create(token_a, "tidy my downloads")
    assert created.data["status"] == "queued"
    assert created.data["machine"]["name"] == "Alienware"


def test_an_offline_machine_that_has_it_takes_it_and_it_waits(household_user, token_a):
    _worker(household_user)
    _worker(household_user, "work laptop", "listed", catalog=(), wf_hash="e")
    _age_machine("Alienware", 600)
    created = _create(token_a, "tidy my downloads")
    assert created.data["status"] == "queued"
    assert created.data["machine"] == {
        "name": "Alienware", "state": "offline", "last_seen_at": created.data["machine"]["last_seen_at"]
    }


def test_one_run_at_a_time(worker, token_a):
    assert _create(token_a, "audit my logs").status_code == 201
    again = _create(token_a, "audit my logs again")
    assert again.status_code == 409
    assert "already queued, running or waiting" in again.data["detail"]


def test_deliver_comes_from_the_catalog_when_kevin_did_not_set_one(household_user, token_a):
    _worker(household_user, catalog=(DIGEST, PAGE))
    mail = _create(token_a, "weekly digest")
    assert ClaudeTask.objects.get(pk=mail.data["id"]).deliver == "email_self"
    page = _create(token_a, "rates page")
    assert ClaudeTask.objects.get(pk=page.data["id"]).deliver == "phone"


def test_a_follow_up_stays_its_workflow_and_confirms_works_as_today(worker, token_a):
    task_id = _create(token_a, "tidy my downloads").data["id"]
    _claim(worker)
    _finish(worker, task_id, status="needs_confirmation", question="Move 40 files?")
    yes = token_a.post(f"{API}tasks/", {"request": "yes", "parent": task_id, "origin": "named",
                                        "confirms": True}, format="json")
    assert yes.status_code == 201, yes.data
    child = ClaudeTask.objects.get(pk=yes.data["id"])
    assert (child.kind, child.workflow, child.params, child.confirms) == (
        "workflow", "tidy-downloads", None, True
    )
    assert child.machine.name == "Alienware"


def test_a_follow_up_cannot_start_another_workflow(worker, token_a):
    task_id = _create(token_a, "audit my logs").data["id"]
    refused = token_a.post(f"{API}tasks/", {"request": "x", "workflow": "tidy my downloads",
                                            "parent": task_id, "origin": "named"}, format="json")
    # The parent is a workflow task, so the follow-up is that workflow's, whatever it names.
    assert refused.status_code == 201
    assert ClaudeTask.objects.get(pk=refused.data["id"]).workflow == "log-audit"
    plain = token_a.post(f"{API}tasks/", {"request": "find a file", "origin": "named"},
                         format="json")
    refused = token_a.post(f"{API}tasks/", {"request": "x", "workflow": "audit my logs",
                                            "parent": plain.data["id"], "origin": "named"},
                           format="json")
    assert refused.status_code == 400


def test_cancel_is_unchanged_for_a_workflow_task(worker, token_a):
    task_id = _create(token_a, "audit my logs").data["id"]
    _claim(worker)
    cancelled = token_a.post(f"{API}tasks/{task_id}/cancel/")
    assert cancelled.data["status"] == "cancel_requested"
    assert _beat(worker)["cancel_requested"] == [task_id]


def test_an_unclaimed_workflow_task_never_goes_to_another_machine(household_user, token_a):
    alien = _worker(household_user, catalog=(LOG_AUDIT,))
    _worker(household_user, "work laptop", "listed", catalog=(LOG_AUDIT,), wf_hash="w")
    _create(token_a, "audit my logs")  # needs_machine
    laptop_client = _client_for(household_user)
    _age_machine("Alienware", 600)
    assert _claim(laptop_client, "work laptop").status_code == 204
    assert _claim(alien).status_code == 204


# --- lists ---------------------------------------------------------------------------------


def test_status_lists_each_machines_workflow_names(worker, token_a):
    machine = token_a.get(f"{API}status/").data["machines"][0]
    assert machine["workflows"] == [
        {"name": "log-audit", "says": LOG_AUDIT["says"]},
        {"name": "tidy-downloads", "says": TIDY["says"]},
    ]


def test_the_workflows_list_with_last_run_waiting_and_due(worker, token_a):
    rows = token_a.get(f"{API}workflows/").data
    assert [r["name"] for r in rows] == ["log-audit", "tidy-downloads"]
    tidy = rows[1]
    assert tidy["spoken"] == "tidy downloads" and tidy["asks"] == [TIDY["asks"][0]["says"]]
    assert tidy["params"] == [] and rows[0]["params"][0]["name"] == "window"
    assert tidy["last_run"] is None and tidy["due"] is True and tidy["waiting"] is False
    assert rows[0]["due"] is False  # no schedule

    task_id = _create(token_a, "tidy my downloads").data["id"]
    tidy = token_a.get(f"{API}workflows/?machine=alienware").data[1]
    assert tidy["last_run"]["task"] == task_id and tidy["last_run"]["status"] == "queued"
    assert tidy["waiting"] is True and tidy["due"] is False

    _age_machine("Alienware", 600)
    assert token_a.get(f"{API}workflows/").data[0]["machine_state"] == "offline"
    assert token_a.get(f"{API}workflows/?machine=nope").data == []


def test_tasks_filter_by_origin_and_kind(worker, token_a):
    _create(token_a, "audit my logs")
    token_a.post(f"{API}tasks/", {"request": "find a file", "origin": "named"}, format="json")
    assert [t["kind"] for t in token_a.get(f"{API}tasks/?kind=workflow").data] == ["workflow"]
    assert len(token_a.get(f"{API}tasks/?kind=ask").data) == 1
    assert token_a.get(f"{API}tasks/?origin=schedule").data == []


def test_another_household_sees_no_workflows_and_cannot_run_ours(worker, token_b):
    assert token_b.get(f"{API}workflows/").data == []
    assert _create(token_b, "audit my logs").status_code == 400
    ran = token_b.post(f"{API}workflows/run/", {"name": "log-audit"}, format="json")
    assert ran.status_code == 400
    assert ClaudeTask.objects.count() == 0


# --- POST workflows/run/ ---------------------------------------------------------------------


def test_run_queues_by_exact_name_once(worker, token_a):
    ran = token_a.post(f"{API}workflows/run/", {"name": "log-audit", "params": "yesterday"},
                       format="json")
    assert ran.status_code == 201, ran.data
    assert ran.data["detail"] == "Queued log audit on the Alienware."
    task = ClaudeTask.objects.get(pk=ran.data["id"])
    assert (task.kind, task.workflow, task.params, task.origin) == (
        "workflow", "log-audit", "yesterday", "named"
    )
    again = token_a.post(f"{API}workflows/run/", {"name": "log-audit"}, format="json")
    assert again.status_code == 409
    unknown = token_a.post(f"{API}workflows/run/", {"name": "log-autopsy"}, format="json")
    assert unknown.status_code == 400 and unknown.data["workflows"]


def test_run_with_two_machines_needs_a_name(household_user, token_a):
    _worker(household_user)
    _worker(household_user, "work laptop", "listed", catalog=(LOG_AUDIT,), wf_hash="w")
    refused = token_a.post(f"{API}workflows/run/", {"name": "log-audit"}, format="json")
    assert refused.status_code == 400 and "name one" in refused.data["detail"]
    ran = token_a.post(f"{API}workflows/run/", {"name": "log-audit", "machine": "work laptop"},
                       format="json")
    assert ran.status_code == 201


# --- catch-up runs, minted at claim (ticket 04) ----------------------------------------------


def _minted():
    return list(ClaudeTask.objects.filter(origin="schedule").order_by("id"))


def test_a_due_workflow_is_minted_at_claim_with_no_personal_text(worker):
    claimed = _claim(worker)
    assert claimed.status_code == 200
    [task] = _minted()
    assert (task.kind, task.workflow, task.params, task.deliver, task.status) == (
        "workflow", "tidy-downloads", None, None, "running"
    )
    assert task.request == "Scheduled run of tidy downloads."
    assert claimed.data[0]["workflow"] == "tidy-downloads"
    # Running now: the next claim mints nothing.
    assert _claim(worker).status_code == 204


def test_not_due_until_every_hours_after_the_last_done(worker):
    _claim(worker)
    [task] = _minted()
    _finish(worker, task.id)
    _ago(task.id, 23)
    assert _claim(worker).status_code == 204
    _ago(task.id, 25)
    assert _claim(worker).status_code == 200
    assert len(_minted()) == 2


def test_a_done_follow_up_counts_as_the_run(worker, token_a):
    _claim(worker)
    [task] = _minted()
    _finish(worker, task.id, status="needs_confirmation", question="Move them?")
    assert _claim(worker).status_code == 204  # waiting on Kevin
    yes = token_a.post(f"{API}tasks/", {"request": "yes", "parent": task.id, "origin": "named",
                                        "confirms": True}, format="json")
    claimed = _claim(worker)
    assert [t["id"] for t in claimed.data] == [yes.data["id"]]
    _finish(worker, yes.data["id"])
    assert _claim(worker).status_code == 204  # the chain ran: not due for 24 hours


def test_an_unanswered_question_blocks_the_next_run(worker):
    _claim(worker)
    [task] = _minted()
    _finish(worker, task.id, status="needs_confirmation", question="Move them?")
    _ago(task.id, 100)
    assert _claim(worker).status_code == 204


def test_a_failure_waits_retry_after_hours(worker):
    _claim(worker)
    [task] = _minted()
    _finish(worker, task.id, status="failed", summary="It broke.")
    _ago(task.id, 5)
    assert _claim(worker).status_code == 204
    _ago(task.id, 7)
    assert _claim(worker).status_code == 200


def test_a_running_task_the_clock_failed_does_not_block_forever(worker):
    _claim(worker)
    [task] = _minted()
    _ago(task.id, 40)  # running for 40 hours: failed by the 30 minute cap, read time
    ClaudeTask.objects.filter(pk=task.id).update(
        started_at=timezone.now() - datetime.timedelta(hours=40), finished_at=None
    )
    assert _claim(worker).status_code == 200
    assert len(_minted()) == 2


def test_kill_switches(settings, worker):
    settings.LEGION_WORKFLOW_SCHEDULES = "off"
    assert _claim(worker).status_code == 204
    settings.LEGION_WORKFLOW_SCHEDULES = "on"
    settings.LEGION_WORKFLOW_SCHEDULES_OFF = "something-else, tidy-downloads"
    assert _claim(worker).status_code == 204
    settings.LEGION_WORKFLOW_SCHEDULES_OFF = "something-else"
    assert _claim(worker).status_code == 200


def test_the_idle_gate_and_force_after(household_user):
    client = _worker(household_user, idle=60)  # one minute idle, the workflow wants ten
    assert _claim(client).status_code == 204
    _first_seen("tidy-downloads", hours=37)  # past force_after_hours, never ran
    assert _claim(client).status_code == 200


def test_a_worker_with_no_idle_reading_is_never_minted_one(household_user):
    client = _worker(household_user, idle=None)
    _first_seen("tidy-downloads", hours=100)
    assert _claim(client).status_code == 204


def test_a_busy_machine_is_never_minted_one(worker, token_a):
    token_a.post(f"{API}tasks/", {"request": "find a file", "origin": "named"}, format="json")
    claimed = _claim(worker)
    assert [t["kind"] for t in claimed.data] == ["ask"]
    assert _claim(worker).status_code == 204


def test_one_mint_per_claim_longest_overdue_first(household_user):
    other = dict(TIDY, name="sort-photos", aliases=["sort my photos"])
    client = _worker(household_user, catalog=(TIDY, other))
    _first_seen("sort-photos", hours=30)
    _first_seen("tidy-downloads", hours=2)
    claimed = _claim(client)
    assert [t["workflow"] for t in claimed.data] == ["sort-photos"]
    _finish(client, claimed.data[0]["id"])
    claimed = _claim(client)
    assert [t["workflow"] for t in claimed.data] == ["tidy-downloads"]


def test_a_week_of_silence_then_one_claim_mints_exactly_one(household_user):
    client = _worker(household_user, catalog=(TIDY,))
    _claim(client)
    [first] = _minted()
    _finish(client, first.id)
    _ago(first.id, 24 * 7)
    claimed = _claim(client)
    assert len(claimed.data) == 1
    assert len(_minted()) == 2
    assert _claim(client).status_code == 204


def test_a_listed_machine_is_never_minted_a_full_only_workflow(household_user):
    client = _worker(household_user, "work laptop", "listed", catalog=(TIDY,), wf_hash="w")
    _first_seen("tidy-downloads", machine="work laptop", hours=100)
    assert _claim(client, "work laptop").status_code == 204


def test_another_tokens_claim_never_mints(household_user):
    _worker(household_user)
    stranger = _client_for(household_user)  # a phone's token, not the one bound to the machine
    assert _claim(stranger).status_code == 204
    assert _minted() == []


def test_a_scheduled_run_carries_only_the_standing_email_yes(household_user):
    client = _worker(household_user, catalog=(DIGEST,))
    _claim(client)
    [task] = _minted()
    assert task.deliver == "email_self"  # R3's one standing yes
    page_client = _worker(household_user, "spare", catalog=(PAGE,), wf_hash="p")
    _claim(page_client, "spare")
    page_task = [t for t in _minted() if t.workflow == "rates-page"][0]
    assert page_task.deliver is None  # a page is asked for per run, never standing


def test_the_memory_review_mint_takes_the_claims_one_mint(monkeypatch, household_user):
    client = _worker(household_user)
    calls = []

    def review(request, machine, now):
        calls.append(machine.pk)
        return ClaudeTask.objects.create(
            household=machine.household, user=request.user, machine=machine, origin="schedule",
            kind="memory_review", request="Run the daily memory review.", status="queued",
        )

    monkeypatch.setattr("member_memory.review.maybe_mint", review)
    claimed = _claim(client)
    assert [t["kind"] for t in claimed.data] == ["memory_review"]
    assert calls and not ClaudeTask.objects.filter(kind="workflow").exists()


def test_a_schedule_beyond_retention_is_refused_at_intake(household_user):
    long = dict(TIDY, every=dict(TIDY["every"], hours=24 * 31, force_after_hours=24 * 31))
    client = _worker(household_user, catalog=())
    assert _beat(client, catalog=[long], wf_hash="l")["workflows_dropped"] == 1


def test_scheduled_runs_are_found_by_origin(worker, token_a):
    _claim(worker)
    rows = token_a.get(f"{API}tasks/?origin=schedule").data
    assert [r["workflow"] for r in rows] == ["tidy-downloads"]
    assert rows[0]["origin"] == "schedule"


def test_the_other_member_machine_mints_for_its_own_token_only(household_a):
    other = _member(household_a, "other@example.com")
    client = _worker(other, "desk", catalog=(TIDY,), wf_hash="d")
    claimed = _claim(client, "desk")
    assert claimed.status_code == 200
    assert ClaudeTask.objects.get(origin="schedule").user_id == other.pk
