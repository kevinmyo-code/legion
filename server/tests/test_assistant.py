"""`/api/assistant/*` (web-assistant ticket 07): the token mint, the tool door, the leaks.

Google is NEVER called here. Every mint goes through a fake transport that
records what would have been sent; a test that reached the network would be
spending a real key, which no test may hold.
"""

from __future__ import annotations

import json
from datetime import UTC, datetime, timedelta

import pytest
from django.core.management import call_command
from rest_framework.test import APIClient

from assistant import live
from assistant.models import AssistantCall, Companion
from assistant.surface import WEB_TOOL_NAMES
from assistant.views import KEY_ABSENT, AssistantSessionThrottle, AssistantToolThrottle
from engine_mcp.models import Outcome
from engine_mcp.tools import TOOLS_BY_NAME
from ingest.drive import Response
from ingest.statements import GEMINI_KEY_ENV
from tests.conftest import _client_for, _member

pytestmark = pytest.mark.django_db

KEY = "AIza-test-household-key-never-sent"
TOKEN = "auth_tokens/test-token-0001"


# =============================================================================
# Fixtures
# =============================================================================


@pytest.fixture
def kevin(household_user):
    household_user.first_name = "Kevin"
    household_user.save(update_fields=["first_name"])
    return household_user


@pytest.fixture
def mia(household_a):
    user = _member(household_a, "mia@example.com")
    user.first_name = "Mia"
    user.save(update_fields=["first_name"])
    return user


def browser(user) -> APIClient:
    """A signed-in browser: the session cookie AND a CSRF token, with CSRF
    enforced the way a real browser request is."""
    client = APIClient(enforce_csrf_checks=True)
    client.force_login(user)
    client.get("/api/auth/csrf")
    client.credentials(HTTP_X_CSRFTOKEN=client.cookies["csrftoken"].value)
    return client


@pytest.fixture
def kevin_web(kevin):
    return browser(kevin)


@pytest.fixture
def mia_web(mia):
    return browser(mia)


@pytest.fixture
def google(monkeypatch):
    """A fake Google. `google.reply` is what it answers; `google.sent` is
    every request it received."""

    class Fake:
        sent: list[dict] = []
        reply = Response(200, {}, json.dumps({"name": TOKEN}).encode())

        def __call__(self, method, url, headers, body):
            self.sent.append(
                {"method": method, "url": url, "headers": headers, "body": json.loads(body)}
            )
            if isinstance(self.reply, Exception):
                raise self.reply
            return self.reply

    fake = Fake()
    fake.sent = []
    monkeypatch.setattr(live, "urllib_transport", fake)
    monkeypatch.setenv(GEMINI_KEY_ENV, KEY)
    return fake


def _start(client, **body):
    return client.post("/api/assistant/session", body, format="json")


def _tool(client, name, args=None):
    payload = {"name": name}
    if args is not None:
        payload["args"] = args
    return client.post("/api/assistant/tool", payload, format="json")


def _text(response) -> str:
    assert response.status_code == 200, (response.status_code, response.data)
    assert set(response.data) == {"name", "is_error", "outcome", "text", "response"}
    assert response.data["response"] == {
        "success": not response.data["is_error"],
        "message": response.data["text"],
    }
    return response.data["text"]


# =============================================================================
# Authentication: a session, with CSRF, and nothing else
# =============================================================================


@pytest.mark.parametrize("path", ["/api/assistant/session", "/api/assistant/tool"])
def test_no_session_is_refused(path, google):
    assert APIClient().post(path, {}, format="json").status_code == 403
    assert google.sent == []


@pytest.mark.parametrize("path", ["/api/assistant/session", "/api/assistant/tool"])
def test_a_session_without_its_csrf_token_is_refused(path, kevin, google):
    client = APIClient(enforce_csrf_checks=True)
    client.force_login(kevin)
    response = client.post(path, {"name": "list_tables"}, format="json")
    assert response.status_code == 403
    assert "CSRF" in str(response.data["detail"])
    assert google.sent == []
    assert AssistantCall.objects.count() == 0


@pytest.mark.parametrize("path", ["/api/assistant/session", "/api/assistant/tool"])
def test_a_device_token_is_not_a_web_assistant_credential(path, kevin, google):
    """Two doors, one key each: a leaked phone token cannot mint a voice
    session on the household's key, and `/mcp` takes no cookie."""
    response = _client_for(kevin).post(path, {"name": "list_tables"}, format="json")
    assert response.status_code == 403
    assert google.sent == []


def test_mcp_still_refuses_a_browser_session(settings, kevin_web):
    settings.LEGION_MCP = True
    assert kevin_web.post("/mcp", {}, format="json").status_code == 401


# =============================================================================
# POST /api/assistant/session
# =============================================================================


def test_no_key_is_refused_in_words_and_google_is_never_asked(kevin_web, google, monkeypatch):
    monkeypatch.delenv(GEMINI_KEY_ENV)
    response = _start(kevin_web)
    assert response.status_code == 503
    assert response.data["detail"] == KEY_ABSENT
    assert response.data["detail"].startswith("The assistant isn't set up on this server.")
    assert google.sent == []
    [row] = AssistantCall.objects.all()
    assert (row.kind, row.outcome, row.tool) == ("session", Outcome.REFUSED, None)


def test_a_mint_answers_with_the_token_and_never_the_key(kevin_web, kevin, google):
    response = _start(kevin_web, utc_offset_minutes=-300)
    assert response.status_code == 200, response.data
    body = response.data
    assert body["token"] == TOKEN
    assert body["model"] == "models/gemini-3.8-live"
    assert body["ws_url"] == live.WS_URL
    assert body["ws_url"].endswith("BidiGenerateContentConstrained")
    assert body["companion_name"] == "Alfred"
    assert body["voice_name"] == "Charon"
    assert body["input_audio_mime"] == "audio/pcm;rate=16000"
    assert body["output_audio_rate"] == 24000
    assert response["Cache-Control"] == "no-store"
    assert KEY not in response.content.decode()

    [sent] = google.sent
    assert sent["method"] == "POST"
    assert sent["url"] == "https://generativelanguage.googleapis.com/v1beta/auth_tokens"
    assert sent["headers"]["x-goog-api-key"] == KEY
    assert KEY not in json.dumps(sent["body"])

    [row] = AssistantCall.objects.all()
    assert (row.kind, row.outcome, row.user_id) == ("session", Outcome.OK, kevin.pk)
    assert row.household_id == kevin.household.id
    stored = json.dumps(list(row.__dict__.values()), default=str)
    assert KEY not in stored and TOKEN not in stored and "Alfred" not in stored


def test_the_token_locks_the_whole_setup(kevin_web, google):
    """Ticket 02: the browser cannot add, remove or reword a tool, unstamp
    BLOCKING, or edit the prompt. A setup with no fieldMask is taken whole
    and the connection's own setup ignored (AuthToken.fieldMask)."""
    before = datetime.now(UTC).replace(microsecond=0)
    assert _start(kevin_web, utc_offset_minutes=-300).status_code == 200
    after = datetime.now(UTC) + timedelta(seconds=1)
    body = google.sent[0]["body"]

    assert set(body) == {"uses", "expireTime", "newSessionExpireTime", "bidiGenerateContentSetup"}
    assert body["uses"] == 1
    expire = datetime.fromisoformat(body["expireTime"])
    connect = datetime.fromisoformat(body["newSessionExpireTime"])
    assert before + timedelta(minutes=30) <= expire <= after + timedelta(minutes=30)
    assert before + timedelta(seconds=60) <= connect <= after + timedelta(seconds=60)

    setup = body["bidiGenerateContentSetup"]
    assert setup["model"] == "models/gemini-3.8-live"
    assert setup["generationConfig"]["responseModalities"] == ["AUDIO"]
    voice = setup["generationConfig"]["speechConfig"]["voiceConfig"]["prebuiltVoiceConfig"]
    assert voice == {"voiceName": "Charon"}
    assert setup["inputAudioTranscription"] == {}
    assert setup["outputAudioTranscription"] == {}
    assert setup["contextWindowCompression"]["triggerTokens"] == 32_000
    assert "sessionResumption" not in setup
    [tools] = setup["tools"]
    assert set(tools) == {"functionDeclarations"}, "no googleSearch on the web surface"
    declarations = tools["functionDeclarations"]
    assert [d["name"] for d in declarations] == list(WEB_TOOL_NAMES)
    assert {d["behavior"] for d in declarations} == {"BLOCKING"}
    for declaration in declarations:
        assert "device token" not in declaration["description"], declaration["name"]
        if TOOLS_BY_NAME[declaration["name"]].writes:
            assert "committed" in declaration["description"], declaration["name"]
        assert "additionalProperties" not in json.dumps(declaration["parameters"])
    prompt = setup["systemInstruction"]["parts"][0]["text"]
    assert "You are Alfred" in prompt
    assert "UTC-05:00" in prompt
    assert "You are speaking with Kevin" in prompt


def test_the_web_surface_is_the_whole_registry_plus_tick_history():
    assert set(WEB_TOOL_NAMES) == set(TOOLS_BY_NAME)
    for name in ("last_ticked", "log_purchase", "last_bought", "list_purchases"):
        assert name in WEB_TOOL_NAMES


def test_mia_talks_to_dorothy(mia_web, google):
    response = _start(mia_web)
    assert response.status_code == 200, response.data
    assert response.data["companion_name"] == "Dorothy"
    assert response.data["voice_name"] == "Vindemiatrix"
    setup = google.sent[0]["body"]["bidiGenerateContentSetup"]
    prompt = setup["systemInstruction"]["parts"][0]["text"]
    assert prompt.startswith("You are Dorothy, the housekeeper")
    assert "You are speaking with Mia" in prompt
    assert "offset is unknown" in prompt


def test_a_stored_companion_wins_over_the_seed(kevin, kevin_web, google):
    call_command("set_companion", kevin.email, "kratos", "--name", "K", "--voice", "Puck")
    response = _start(kevin_web)
    assert (response.data["companion_name"], response.data["voice_name"]) == ("K", "Puck")
    prompt = google.sent[0]["body"]["bidiGenerateContentSetup"]["systemInstruction"]["parts"][0]
    assert prompt["text"].startswith("You are K. You were a god once.")


@pytest.mark.parametrize("offset", [-721, 841, "soon"])
def test_a_bad_offset_is_refused_in_words(kevin_web, google, offset):
    response = _start(kevin_web, utc_offset_minutes=offset)
    assert response.status_code == 400
    assert response.data["detail"].startswith("Nothing was started.")
    assert google.sent == []


@pytest.mark.parametrize(
    ("reply", "status", "words"),
    [
        (Response(403, {}, b'{"error": {"status": "PERMISSION_DENIED"}}'), 502, "refused this"),
        (Response(401, {}, b"{}"), 502, "LEGION_GEMINI_KEY"),
        (Response(429, {}, b"{}"), 503, "busy or down"),
        (Response(500, {}, b"not json"), 503, "busy or down"),
        (Response(400, {}, b'{"error": {"message": "bad setup"}}'), 502, "bad setup"),
        (Response(200, {}, b"{}"), 502, "without a token"),
        (TimeoutError(), 503, "did not answer"),
    ],
)
def test_google_failing_is_said_in_words(kevin_web, google, reply, status, words):
    google.reply = reply
    response = _start(kevin_web)
    assert response.status_code == status
    assert words in response.data["detail"]
    assert response.data["detail"].startswith("The assistant could not start")
    assert KEY not in response.content.decode()
    assert AssistantCall.objects.get().outcome == Outcome.FAILED


def test_the_mint_is_throttled_per_member_and_audited(kevin_web, mia_web, google, monkeypatch):
    monkeypatch.setattr(AssistantSessionThrottle, "rate", "2/min", raising=False)
    assert _start(kevin_web).status_code == 200
    assert _start(kevin_web).status_code == 200
    third = _start(kevin_web)
    assert third.status_code == 429
    assert third.data["detail"].startswith("Nothing was read, written or started.")
    assert _start(mia_web).status_code == 200, "one member's budget is not the other's"
    assert len(google.sent) == 3
    assert AssistantCall.objects.filter(outcome=Outcome.THROTTLED).count() == 1


def test_the_dry_run_command_prints_the_body_and_never_a_key(kevin, google, capsys):
    call_command("mint_assistant_token", "--email", kevin.email, "--dry-run")
    out = capsys.readouterr().out
    assert "auth_tokens" in out and '"bidiGenerateContentSetup"' in out
    assert KEY not in out
    assert google.sent == []


def test_the_manual_mint_command_sends_the_same_locked_setup(kevin, google, capsys):
    call_command("mint_assistant_token", "--email", kevin.email, "--utc-offset-minutes", "-300")
    out = capsys.readouterr().out
    assert TOKEN in out and KEY not in out
    assert google.sent[0]["body"]["bidiGenerateContentSetup"]["model"] == live.MODEL


# =============================================================================
# GET /api/assistant/companion
# =============================================================================


def test_each_member_reads_their_own_companion(kevin_web, mia_web, kevin):
    assert Companion.objects.count() == 0, "members made after the seed are computed, not stored"
    assert mia_web.get("/api/assistant/companion").data == {
        "name": "Dorothy",
        "persona": "dorothy",
        "voice_name": "Vindemiatrix",
        "custom_register": False,
        "stored": False,
    }
    assert Companion.objects.count() == 0, "reading never writes"
    call_command("set_companion", kevin.email, "alfred")
    assert kevin_web.get("/api/assistant/companion").data["stored"] is True


def test_set_companion_refuses_in_words(kevin):
    from django.core.management.base import CommandError

    with pytest.raises(CommandError, match="Nothing was changed"):
        call_command("set_companion", "nobody@example.com", "alfred")


def test_the_seeding_migration_gives_mia_dorothy(kevin, mia):
    import importlib

    from django.apps import apps

    seed = importlib.import_module("assistant.migrations.0002_seed_companions").seed
    seed(apps, None)
    seed(apps, None)  # idempotent
    rows = {row.user_id: row for row in Companion.objects.all()}
    assert (rows[mia.pk].persona_key, rows[mia.pk].name) == ("dorothy", "Dorothy")
    assert (rows[kevin.pk].persona_key, rows[kevin.pk].name) == ("alfred", "Alfred")
    assert rows[mia.pk].household_id == mia.household.id
    assert len(rows) == 2


# =============================================================================
# POST /api/assistant/tool: the same registry as /mcp
# =============================================================================


def test_a_tool_call_runs_the_registry_and_answers_ready_to_forward(kevin_web, kevin):
    created = _tool(
        kevin_web, "add_event", {"fields": {"title": "Dentist", "starts_at": "2026-10-06T15:00Z"}}
    )
    text = _text(created)
    assert created.data["is_error"] is False
    assert text.startswith("Written a new event. The engine committed it")
    listed = _text(_tool(kevin_web, "list_events", {"from": "2026-10-01", "to": "2026-10-10"}))
    assert "Dentist" in listed
    rows = list(AssistantCall.objects.order_by("at").values_list("kind", "tool", "outcome"))
    assert rows == [("tool", "add_event", Outcome.OK), ("tool", "list_events", Outcome.OK)]
    stored = json.dumps(list(AssistantCall.objects.values()), default=str)
    assert "Dentist" not in stored, "arguments are never audited"


def test_an_empty_read_says_it_is_empty(kevin_web):
    text = _text(_tool(kevin_web, "list_purchases"))
    assert "real empty result" in text


def test_bad_arguments_are_refused_in_words_not_by_status(kevin_web):
    response = _tool(kevin_web, "list_events", {"from": "someday"})
    assert response.data["is_error"] is True
    assert response.data["outcome"] == Outcome.REFUSED
    assert _text(response).startswith("Nothing was read.")


@pytest.mark.parametrize("name", ["play_music", "ask_mail", "get_obd_data", "summarise_inbox"])
def test_a_phone_only_or_unknown_tool_is_a_400_in_words(kevin_web, name):
    response = _tool(kevin_web, name, {})
    assert response.status_code == 400
    assert response.data["detail"].startswith("Nothing was read or written.")
    assert "phone app" in response.data["detail"]
    assert response.data["response"] == {"success": False, "message": response.data["detail"]}
    row = AssistantCall.objects.get()
    assert (row.tool, row.outcome) == (name, Outcome.REFUSED)


def test_a_body_that_does_not_fit_is_a_400_in_words(kevin_web):
    response = kevin_web.post("/api/assistant/tool", {"args": {}}, format="json")
    assert response.status_code == 400
    assert response.data["detail"].startswith("Nothing was read or written.")
    response = _tool(kevin_web, "list_tables", ["not", "an", "object"])
    assert response.status_code == 400


def test_tool_calls_are_throttled_per_member(kevin_web, mia_web, monkeypatch):
    monkeypatch.setattr(AssistantToolThrottle, "rate", "1/min", raising=False)
    assert _tool(kevin_web, "list_tables").status_code == 200
    second = _tool(kevin_web, "list_tables")
    assert second.status_code == 429
    assert _tool(mia_web, "list_tables").status_code == 200
    throttled = AssistantCall.objects.get(outcome=Outcome.THROTTLED)
    assert throttled.tool == "list_tables"


# =============================================================================
# last_ticked: ADR 0049 on the engine
# =============================================================================


def _list_with(client, name, *lines, visibility="shared"):
    checklist = client.post(
        "/api/checklists/", {"name": name, "visibility": visibility}, format="json"
    ).data
    items = []
    for line in lines:
        items.append(
            client.post(
                f"/api/checklists/{checklist['id']}/items", {"text": line}, format="json"
            ).data
        )
    return checklist, items


def _tick(client, checklist, item, day):
    response = client.post(
        f"/api/checklists/{checklist['id']}/items/{item['id']}/tick", {"day": day}, format="json"
    )
    assert response.status_code in (200, 201), response.data
    return response.data


def test_last_ticked_says_ticked_reads_through_tombstones_and_matches_narrowly(
    auth_client, kevin_web
):
    pantry, (toothpaste, colgate) = _list_with(
        auth_client, "Pantry run", "Toothpaste ", "Colgate toothpaste"
    )
    _tick(auth_client, pantry, toothpaste, 20360)  # Sep 29, 2025
    _tick(auth_client, pantry, colgate, 20365)
    weekly, (again,) = _list_with(auth_client, "Weekly", "toothpaste")
    _tick(auth_client, weekly, again, 20362)
    # A cleared list still answers (ADR 0049: read THROUGH tombstones).
    assert auth_client.delete(f"/api/checklists/{weekly['id']}").status_code == 204

    text = _text(_tool(kevin_web, "last_ticked", {"item": "TOOTHPASTE"}))
    assert "'toothpaste' was last ticked off 'Weekly' on Oct 1, 2025" in text
    assert "Sep 29, 2025 (Pantry run)" in text
    assert "Colgate" not in text, "a near match is never an answer"
    assert "ticked" in text
    assert "you bought" not in text.lower()


def test_last_ticked_with_no_match_is_an_absent_record(kevin_web):
    text = _text(_tool(kevin_web, "last_ticked", {"item": "shampoo"}))
    assert text.startswith("I have no record of 'shampoo' being ticked")
    assert "absent record" in text
    assert "never bought" not in text


def test_an_unticked_line_does_not_count(auth_client, kevin_web):
    checklist, (item,) = _list_with(auth_client, "Groceries-ish", "milk")
    _tick(auth_client, checklist, item, 20360)
    untick = auth_client.delete(f"/api/checklists/{checklist['id']}/items/{item['id']}/tick/20360")
    assert untick.status_code == 204
    assert "no record" in _text(_tool(kevin_web, "last_ticked", {"item": "milk"}))


# =============================================================================
# Leaks: ADR 0052 (a member's private rows) and ADR 0045 (another household)
# =============================================================================


def _kevins_private_world(client):
    event = client.post(
        "/api/events",
        {"title": "secret-event", "starts_at": "2026-10-05T09:00:00Z", "visibility": "private"},
        format="json",
    ).data
    checklist, (item,) = _list_with(client, "secret-list", "secret-item", visibility="private")
    _tick(client, checklist, item, 20366)
    purchase = client.post(
        "/api/purchases/",
        {"item": "secret-soap", "bought_on": 20365, "visibility": "private"},
        format="json",
    ).data
    return {"event": event["id"], "checklist": checklist["id"], "purchase": purchase["id"]}


def test_mia_never_reads_kevins_private_rows_through_the_tool_door(auth_client, mia_web, kevin):
    ids = _kevins_private_world(auth_client)
    marks = ("secret", *ids.values())

    def reads(name, args):
        text = _text(_tool(mia_web, name, args))
        leaked = [mark for mark in marks if mark in text]
        assert not leaked, (name, leaked, text)
        return text

    reads("list_events", {"from": "2026-10-01", "to": "2026-10-10", "include_done": True})
    reads("list_checklists", {"date": "2025-10-05", "include_archived": True})
    # A no-record answer echoes the query, so the query is spelled to match
    # (matching ignores case) without containing the lower-case mark.
    assert "no record" in reads("last_ticked", {"item": "Secret-Item"})
    reads("list_purchases", {})
    reads("last_bought", {"item": "Secret Soap"})
    for table in ("places", "voice_notes", "receipts"):
        reads("read_records", {"table": table, "active_only": False})

    # Writes that name Kevin's private rows: refused, and nothing changed.
    for name, args in (
        ("update_event", {"id": ids["event"], "fields": {"title": "seen"}}),
        ("delete_event", {"id": ids["event"]}),
        ("delete_purchase", {"id": ids["purchase"]}),
        ("add_checklist_item", {"checklist_id": ids["checklist"], "text": "x"}),
    ):
        response = _tool(mia_web, name, args)
        assert response.data["is_error"] is True, (name, response.data)
        assert response.data["text"].startswith("Nothing was"), (name, response.data)

    # And the control: Kevin, through the same door, sees his own.
    kevin_web = browser(kevin)
    assert "secret-item" in _text(_tool(kevin_web, "last_ticked", {"item": "secret-item"}))
    assert "secret-soap" in _text(_tool(kevin_web, "list_purchases", {}))
    assert "secret-event" in _text(
        _tool(kevin_web, "list_events", {"from": "2026-10-01", "to": "2026-10-10"})
    )


def test_another_household_never_reads_ours_through_the_tool_door(auth_client, user_b):
    shared = _list_with(auth_client, "alpha-list", "alpha-item")
    _tick(auth_client, shared[0], shared[1][0], 20366)
    auth_client.post("/api/purchases/", {"item": "alpha-soap", "bought_on": 20365}, format="json")
    auth_client.put(
        "/api/places/alpha-place/", {"latitude": 1.0, "longitude": 2.0}, format="json"
    )
    other = browser(user_b)
    for name, args in (
        ("list_checklists", {"date": "2025-10-06"}),
        ("last_ticked", {"item": "Alpha-Item"}),
        ("list_purchases", {}),
        ("last_bought", {"item": "Alpha Soap"}),
        ("read_records", {"table": "places"}),
        ("list_tables", {}),
    ):
        text = _text(_tool(other, name, args))
        assert "alpha" not in text, (name, text)
    response = _tool(
        other, "write_record", {"table": "places", "identity": "alpha-place", "fields": {}}
    )
    assert response.status_code == 200
    ours = auth_client.get("/api/places/").data["results"]
    assert [row["latitude"] for row in ours if row["label"] == "alpha-place"] == [1.0]
    assert not AssistantCall.objects.filter(user=user_b).exclude(household=user_b.household)
