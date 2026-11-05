"""Memory v2 (ADR 0075, memory-and-vault tickets 01, 02 and 04).

Kevin's rulings of 2026-11-05: a memory is private to the member it is about, a shared household
fact is visible to every member, the Claude worker on the Alienware may read and write, the work
laptop never touches memory, and "forget that" is a hard delete.
"""

from __future__ import annotations

import io
from datetime import timedelta

import pytest
from django.core.management import call_command
from django.utils import timezone
from rest_framework.test import APIClient

from claude_session.models import SCOPE_FULL, SCOPE_LISTED, ClaudeMachine
from household.models import DeviceToken, HouseholdMember, User
from member_memory import scoring, service
from member_memory.models import (
    DECAY_DURABLE,
    DECAY_EPISODIC,
    SOURCE_CLAUDE,
    SOURCE_PHONE,
    SOURCE_WEB,
    MemberMemory,
)
from member_memory.sensitive import looks_sensitive
from tests.conftest import _client_for, _member
from tests.test_engine_mcp import call, client_with

URL = "/api/member-memories/"


@pytest.fixture
def mia(household_a):
    return _member(household_a, "mia@example.com")


@pytest.fixture
def mia_client(mia):
    return _client_for(mia)


@pytest.fixture
def mcp_on(settings):
    settings.LEGION_MCP = True


def _save(client, text, **fields):
    response = client.post(URL, {"text": text, **fields}, format="json")
    assert response.status_code == 201, response.data
    return response.data


def _texts(client, **params) -> list[str]:
    response = client.get(URL, params)
    assert response.status_code == 200, response.data
    return [row["text"] for row in response.data["results"]]


# =============================================================================
# Privacy (ruling 2, ADR 0052)
# =============================================================================


def test_a_memory_is_private_to_the_member_it_is_about(auth_client, mia_client):
    mine = _save(auth_client, "Kevin's gym is on Main Street")
    assert mine["shared"] is False and mine["about_you"] is True
    assert _texts(mia_client) == []
    assert mia_client.get(f"{URL}{mine['id']}").status_code == 404
    assert mia_client.delete(f"{URL}{mine['id']}").status_code == 404
    assert mia_client.patch(f"{URL}{mine['id']}", {"text": "x"}, format="json").status_code == 404
    assert MemberMemory.objects.filter(pk=mine["id"]).exists()


def test_a_shared_household_fact_is_visible_to_every_member(auth_client, mia_client):
    shared = _save(auth_client, "The household's address is 1 Elm Road", shared=True)
    rows = mia_client.get(URL).data["results"]
    assert [row["text"] for row in rows] == ["The household's address is 1 Elm Road"]
    assert rows[0]["about_you"] is False
    recalled = mia_client.post(f"{URL}recall", {"query": "address"}, format="json")
    assert [row["id"] for row in recalled.data["results"]] == [shared["id"]]


def test_only_the_member_it_is_about_may_change_its_sharing(auth_client, mia_client):
    shared = _save(auth_client, "The Jeep lives in the left bay", shared=True)
    refused = mia_client.patch(f"{URL}{shared['id']}", {"shared": False}, format="json")
    assert refused.status_code == 403
    assert "Only the person a memory is about" in refused.data["detail"]
    made_private = auth_client.patch(f"{URL}{shared['id']}", {"shared": False}, format="json")
    assert made_private.status_code == 200 and made_private.data["shared"] is False
    assert _texts(mia_client) == []


def test_the_database_refuses_a_memory_private_to_someone_it_is_not_about(
    household_user, mia, household_a
):
    from django.db import IntegrityError, transaction

    with pytest.raises(IntegrityError), transaction.atomic():
        MemberMemory.objects.create(
            household=household_a, member=household_user, owner_user=mia,
            text="x", source=SOURCE_PHONE,
        )


# =============================================================================
# Forgetting is a hard delete (ruling 4); superseding (decision C)
# =============================================================================


def test_forget_deletes_the_row_for_real(auth_client):
    row = _save(auth_client, "Kevin parks on level 3")
    assert auth_client.delete(f"{URL}{row['id']}").status_code == 204
    assert not MemberMemory.objects.filter(pk=row["id"]).exists()
    assert auth_client.delete(f"{URL}{row['id']}").status_code == 404


def test_a_superseding_fact_retires_the_old_one_and_forgetting_takes_both(auth_client):
    old = _save(auth_client, "Kevin's gym is on Main Street")
    new = _save(auth_client, "Kevin's gym is on Elm Street", supersedes=old["id"])
    assert new["supersedes"] == old["id"]
    assert _texts(auth_client) == ["Kevin's gym is on Elm Street"]
    assert len(_texts(auth_client, include_superseded="1")) == 2
    recalled = auth_client.post(f"{URL}recall", {"query": "gym"}, format="json").data["results"]
    assert [row["id"] for row in recalled] == [new["id"]]
    assert auth_client.delete(f"{URL}{new['id']}").status_code == 204
    assert MemberMemory.objects.count() == 0


def test_forgetting_a_shared_fact_never_deletes_an_older_private_version(
    auth_client, mia_client
):
    private = _save(auth_client, "Kevin's gym is on Main Street")
    shared = _save(
        auth_client, "Kevin's gym is on Elm Street", shared=True, supersedes=private["id"]
    )
    assert mia_client.delete(f"{URL}{shared['id']}").status_code == 204
    assert not MemberMemory.objects.filter(pk=shared["id"]).exists()
    assert MemberMemory.objects.filter(pk=private["id"]).exists()


def test_a_member_cannot_supersede_a_shared_fact_about_someone_else(auth_client, mia_client):
    shared = _save(auth_client, "The Jeep lives in the left bay", shared=True)
    response = mia_client.post(
        URL, {"text": "The Jeep lives outside", "supersedes": shared["id"]}, format="json"
    )
    assert response.status_code == 403
    assert "Only the person a memory is about" in response.data["detail"]
    assert MemberMemory.objects.get(pk=shared["id"]).superseded_at is None
    assert MemberMemory.objects.count() == 1


def test_superseding_another_members_private_memory_is_refused(auth_client, mia_client):
    theirs = _save(mia_client, "Mia likes oat milk")
    response = auth_client.post(URL, {"text": "x y z", "supersedes": theirs["id"]}, format="json")
    assert response.status_code == 400
    assert MemberMemory.objects.get(pk=theirs["id"]).superseded_at is None


# =============================================================================
# Recall: the phone's score with two decay classes (decision A)
# =============================================================================


def test_a_durable_fact_outlives_an_equally_old_episodic_one(auth_client, household_user):
    durable = _save(auth_client, "Kevin's Jeep needs an engine swap eventually")
    episodic = _save(
        auth_client, "Kevin had tacos with the Jeep club", decay_class=DECAY_EPISODIC
    )
    old = timezone.now() - timedelta(days=7)
    MemberMemory.objects.filter(pk__in=[durable["id"], episodic["id"]]).update(created_at=old)
    best = auth_client.post(f"{URL}recall", {"query": "jeep"}, format="json").data["results"]
    assert [row["id"] for row in best] == [durable["id"], episodic["id"]]
    assert best[0]["score"] > best[1]["score"]
    now = timezone.now()
    assert scoring.recency(DECAY_DURABLE, old, now) > 0.8
    assert scoring.recency(DECAY_EPISODIC, old, now) < 0.2


def test_recall_gates_on_relevance_and_rehearses_what_it_returns(auth_client):
    gym = _save(auth_client, "Kevin's gym is on Main Street")
    _save(auth_client, "Kevin's sister lives in Austin")
    best = auth_client.post(f"{URL}recall", {"query": "where is my gym"}, format="json").data
    assert [row["id"] for row in best["results"]] == [gym["id"]]
    row = MemberMemory.objects.get(pk=gym["id"])
    assert row.recall_count == 1 and row.last_recalled_at is not None
    everything = auth_client.post(f"{URL}recall", {}, format="json").data["results"]
    assert len(everything) == 2


# =============================================================================
# Vault identifiers never become memories (decision E)
# =============================================================================


@pytest.mark.parametrize(
    "text",
    [
        "Kevin's SSN is 123-45-6789",
        "passport number E1234567",
        "NRIC S1234567D",
        "A-number A123456789",
        "checking account 123456789012",
        "card 4111 1111 1111 1111",
    ],
)
def test_a_vault_identifier_is_refused_in_words(auth_client, text):
    assert looks_sensitive(text)
    response = auth_client.post(URL, {"text": text}, format="json")
    assert response.status_code == 400
    assert "ID vault" in response.data["detail"]
    assert MemberMemory.objects.count() == 0


@pytest.mark.parametrize(
    "text",
    [
        "The Jeep's plate is ABC 1234",
        "The Jeep's VIN is 1J4FA49S1YP123456",
        "Renew the passport in March",
        "Mom's number is 713 555 1234",
    ],
)
def test_ordinary_identifiers_pass(text):
    assert not looks_sensitive(text)


# =============================================================================
# Who may use memory: recruiters never, the work laptop never (decision G)
# =============================================================================


def _worker(user, name, scope):
    token, raw = DeviceToken.issue(user, f"claude-session-{name}")
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Token {raw}")
    response = client.post(
        "/api/claude/machines/heartbeat/",
        {"name": name, "scope": scope, "state": "idle", "progress": {}},
        format="json",
    )
    assert response.status_code == 200, response.data
    return token, raw, client


def test_the_work_laptops_token_is_refused_and_the_alienwares_is_not(household_user):
    _token, _raw, laptop = _worker(household_user, "work laptop", SCOPE_LISTED)
    refused = laptop.get(URL)
    assert refused.status_code == 403 and "work laptop" in refused.data["detail"]
    assert laptop.post(URL, {"text": "x"}, format="json").status_code == 403
    _token, _raw, alienware = _worker(household_user, "Alienware", SCOPE_FULL)
    assert alienware.post(URL, {"text": "Kevin's gym is on Elm"}, format="json").status_code == 201
    assert ClaudeMachine.objects.get(name="Alienware").token is not None


def test_a_worker_token_that_never_named_its_machine_is_refused(household_user):
    _token, raw = DeviceToken.issue(household_user, "claude-session-new")
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Token {raw}")
    response = client.get(URL)
    assert response.status_code == 403 and "heartbeat" in response.data["detail"]


def test_the_work_laptop_is_refused_the_memory_tools_on_mcp(household_user, mcp_on):
    from rest_framework.test import APIClient as Client

    _token, raw, _client = _worker(household_user, "work laptop", SCOPE_LISTED)
    mcp = Client()
    mcp.credentials(HTTP_AUTHORIZATION=f"Token {raw}")
    for name, args in (("recall_memory", {}), ("remember", {"text": "x"})):
        is_error, text, _ = call(mcp, name, args)
        assert is_error and "work laptop" in text, (name, text)
    assert MemberMemory.objects.count() == 0


def test_a_recruiter_is_refused_every_memory_route(db):
    from datetime import timedelta as td

    from household.models import Household
    from household.permissions import DEMO_TOKEN_SESSION_KEY

    visit = Household.objects.create(name="Visit", is_demo_visit=True)
    visitor = User.objects.create_user(email="visitor-x@demo.invalid")
    HouseholdMember.objects.create(user=visitor, household=visit, role=HouseholdMember.RECRUITER)
    token, _raw = DeviceToken.issue(
        visitor, "Demo visit", DeviceToken.SCOPE_READ, expires_at=timezone.now() + td(hours=2)
    )
    client = APIClient()
    client.force_login(visitor)
    session = client.session
    session[DEMO_TOKEN_SESSION_KEY] = token.pk
    session.save()
    for method, path in (("get", URL), ("post", f"{URL}recall"), ("post", f"{URL}import")):
        response = getattr(client, method)(path, {}, format="json")
        assert response.status_code == 403, (path, response.status_code)
        assert "memory of a household member" in str(response.data)


# =============================================================================
# The tools (ticket 02): one store, three doors
# =============================================================================


def test_claude_on_mcp_reads_and_writes_memory(household_user, mcp_on):
    claude = client_with(household_user)
    is_error, text, structured = call(claude, "remember", {"text": "Kevin's gym is on Elm"})
    assert not is_error and "Remembered, private to you" in text
    memory_id = structured["memory"]["id"]
    assert MemberMemory.objects.get(pk=memory_id).source == SOURCE_CLAUDE
    is_error, text, _ = call(claude, "recall_memory", {"query": "gym"})
    assert not is_error and "Kevin's gym is on Elm" in text and "guess" in text
    is_error, text, _ = call(claude, "list_memories", {})
    assert not is_error and memory_id in text
    is_error, text, _ = call(claude, "forget_memory", {"id": memory_id})
    assert not is_error and "Deleted for good" in text
    assert not MemberMemory.objects.filter(pk=memory_id).exists()
    is_error, text, _ = call(claude, "forget_memory", {"id": memory_id})
    assert is_error and text.startswith("Nothing was changed")


def test_remember_refuses_a_vault_identifier_on_mcp(household_user, mcp_on):
    is_error, text, _ = call(client_with(household_user), "remember", {"text": "SSN 123-45-6789"})
    assert is_error and "ID vault" in text
    assert MemberMemory.objects.count() == 0


def test_a_read_scoped_token_cannot_remember_or_forget(household_user, mcp_on):
    reader = client_with(household_user, DeviceToken.SCOPE_READ)
    for name, args in (
        ("remember", {"text": "x"}),
        ("forget_memory", {"id": "00000000-0000-0000-0000-000000000001"}),
    ):
        is_error, text, _ = call(reader, name, args)
        assert is_error and "read-only" in text
    assert not call(reader, "recall_memory", {})[0]


def test_the_web_assistant_remembers_with_source_web(household_user):
    client = APIClient()
    client.force_login(household_user)
    response = client.post(
        "/api/assistant/tool",
        {"name": "remember", "args": {"text": "Kevin prefers window seats"}},
        format="json",
    )
    assert response.status_code == 200, response.data
    assert response.data["is_error"] is False, response.data
    assert MemberMemory.objects.get().source == SOURCE_WEB


def test_the_member_web_prompt_carries_memory_and_the_recruiter_prompt_does_not():
    from assistant import prompt
    from assistant.companions import ResolvedCompanion

    companion = ResolvedCompanion(
        name="Alfred", persona_key="alfred", clause="You are Alfred.", delivery="",
        voice_name="x", stored=False,
    )
    now = timezone.now()
    member = prompt.build_system_prompt(
        companion=companion, now=now, first_name="Kevin", utc_offset_minutes=0
    )
    visitor = prompt.build_system_prompt(
        companion=companion, now=now, first_name="", utc_offset_minutes=0, recruiter=True
    )
    assert "recall_memory" in member and "no memory of earlier" not in member
    assert "recall_memory" not in visitor


# =============================================================================
# The one-time import (ticket 04)
# =============================================================================


def _rows():
    return [
        {
            "store": "companion_memories", "origin_guid": "g1", "text": "Kevin drives a Jeep XJ",
            "category": "car_anchored", "source": "consolidated", "importance": 8,
            "logged_at": "2026-09-01T12:00:00Z", "last_accessed_at": "2026-10-01T12:00:00Z",
        },
        {
            "store": "companion_memories", "origin_guid": "g2", "text": "Talked about tacos",
            "category": "driver", "source": "consolidated", "importance": 3,
            "logged_at": "2026-09-02T12:00:00Z",
        },
        {
            "store": "memories", "origin_guid": "g3", "text": "Remember the oil change",
            "logged_at": "2026-09-03T12:00:00Z",
        },
        {
            "store": "memories", "origin_guid": "g4", "text": "my SSN is 123-45-6789",
            "logged_at": "2026-09-03T12:00:00Z",
        },
    ]


def test_the_import_is_private_mapped_and_idempotent(auth_client, mia_client):
    first = auth_client.post(f"{URL}import", {"rows": _rows()}, format="json")
    assert first.status_code == 200, first.data
    assert first.data == {
        "imported": 3, "already_here": 0, "refused_sensitive": 1, "refused_invalid": 0,
    }
    second = auth_client.post(f"{URL}import", {"rows": _rows()}, format="json").data
    assert second["imported"] == 0 and second["already_here"] == 3
    by_text = {row.text: row for row in MemberMemory.objects.all()}
    jeep = by_text["Kevin drives a Jeep XJ"]
    assert (jeep.kind, jeep.decay_class, jeep.importance) == ("fact", DECAY_DURABLE, 8)
    assert jeep.created_at.date().isoformat() == "2026-09-01"
    assert by_text["Talked about tacos"].decay_class == DECAY_EPISODIC
    assert by_text["Remember the oil change"].importance == 5
    assert all(not row.shared for row in by_text.values())
    assert _texts(mia_client) == []


def test_the_import_command_reads_the_engines_legacy_replica(household_user, household_a):
    import uuid

    from legacy.models.memory import CompanionMemory, Memory

    now = timezone.now()
    CompanionMemory.objects.create(
        id=uuid.uuid4(), household=household_a, vehicle_id="car:x", text="Kevin's Jeep is red",
        category="car_anchored", source="stated", importance=6, logged_at=now,
        provenance="USER", created_at=now, updated_at=now, origin_guid="c1",
    )
    Memory.objects.create(
        id=uuid.uuid4(), household=household_a, text="Kevin likes dark roast", logged_at=now,
        provenance="USER", created_at=now, updated_at=now, origin_guid="m1",
    )
    out = io.StringIO()
    call_command("import_legacy_memories", "--member", household_user.email, stdout=out)
    assert "Imported 2 of 2" in out.getvalue()
    assert set(MemberMemory.objects.values_list("text", flat=True)) == {
        "Kevin's Jeep is red", "Kevin likes dark roast",
    }
    out = io.StringIO()
    call_command("import_legacy_memories", "--member", household_user.email, stdout=out)
    assert "Imported 0 of 2" in out.getvalue()


def test_service_refusal_is_none_for_a_member(rf, household_user):
    request = rf.get("/")
    request.user = household_user
    assert service.refusal(request) is None
