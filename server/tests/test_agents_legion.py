"""engine-brain ticket 02: LEGION's side of the agent runtime against the test database: the
specs, the guards, the gateway, the read twins, the turn's rows, tenancy and retention. Every
model answer is scripted (`FakeModel`); nothing reaches Google."""

from __future__ import annotations

import datetime as dt
import json

import pytest
import yaml
from django.utils import timezone

from agents.evals import fixture
from agents.evals.runner import member_request
from agents.legion import guards, redact, retention, specs
from agents.legion.gateway import LegionGateway
from agents.legion.turn import RECRUITER_REFUSAL, open_conversation, run_turn
from agents.legion.twins import TWINS
from agents.models import (
    AgentCheckpoint,
    AgentConversation,
    AgentModelCall,
    AgentToolCall,
    AgentTurn,
)
from agents.runtime import model as m
from agents.runtime.tools import ToolCall
from assistant.surface import WEB_TOOL_NAMES
from engine_mcp.tools import TOOLS_BY_NAME
from llm.models import LlmUsage

pytestmark = pytest.mark.django_db

NOW = dt.datetime(2026, 3, 10, 17, 0, tzinfo=dt.UTC)  # 11:00 in Chicago, a Tuesday
TODAY = dt.date(2026, 3, 10)


def _drive(gen):
    events = []
    try:
        while True:
            events.append(next(gen))
    except StopIteration as stop:
        return events, stop.value


@pytest.fixture
def seeded(household_user, household_a, monkeypatch):
    monkeypatch.setattr("engine_mcp.tools._now", lambda: NOW)
    fixture.seed(household_a, household_user, TODAY)
    return household_user


def _turn(user, text, model, conversation=None, observe=None):
    request = member_request(user)
    conversation = conversation or open_conversation(request, None, "test")
    events, state = _drive(
        run_turn(request, text, model, conversation=conversation, now=NOW, observe=observe)
    )
    return events, state, conversation


# =============================================================================
# Specs
# =============================================================================


def test_the_generalist_carries_todays_engine_surface_and_the_twins():
    generalist = specs.load_specs()["generalist"]
    assert set(generalist.tools) == set(WEB_TOOL_NAMES) | set(TWINS), (
        "classify the new registry tool in agents/specs/generalist.yaml",
        sorted(set(WEB_TOOL_NAMES) ^ (set(generalist.tools) - set(TWINS))),
    )
    assert generalist.taint_ok and generalist.writes_allowed
    # The specialists and the router are stage 5; `background` is the late-answer path's
    # read-only spec (`agents/legion/late.py`), used only by its runner.
    assert set(specs.load_specs()) == {"generalist", "background"}
    background = specs.load_specs()["background"]
    assert not background.writes_allowed
    assert set(background.tools) <= set(WEB_TOOL_NAMES)


def test_the_twins_stay_out_of_the_registry():
    """Dark: `/mcp`, the web surface and the recruiter list are unchanged by this stage."""
    assert not set(TWINS) & set(TOOLS_BY_NAME)


def test_the_spec_lock_matches_and_a_changed_spec_fails(tmp_path, monkeypatch):
    path = specs.SPECS_DIR / "generalist.yaml"
    specs.parse(path)
    raw = yaml.safe_load(path.read_text(encoding="utf-8"))
    with pytest.raises(specs.SpecError, match="specs.lock"):
        specs.parse(path, {"generalist": {"version": raw["version"], "sha256": "0" * 64}})


@pytest.mark.parametrize(
    "change, message",
    [
        ({"tools": {"engine": ["no_such_tool"]}}, "no such engine tool"),
        ({"tools": {"engine": ["web_search"]}, "taint_ok": False}, "taint_ok"),
        ({"aspects": ["weather"]}, "aspects"),
        ({"tools": {"engine": ["list_events"], "relay": ["play_music"]}}, "relay"),
        ({"confirm": ["delete_event"], "tools": {"engine": ["list_events"]}}, "confirm"),
    ],
)
def test_a_bad_spec_is_refused_at_load(tmp_path, monkeypatch, change, message):
    raw = yaml.safe_load((specs.SPECS_DIR / "generalist.yaml").read_text(encoding="utf-8"))
    raw.update(change)
    monkeypatch.setattr(specs, "SPECS_DIR", tmp_path)
    (tmp_path / "generalist.md").write_text("prompt", encoding="utf-8")
    path = tmp_path / "generalist.yaml"
    path.write_text(yaml.safe_dump(raw), encoding="utf-8")
    with pytest.raises(specs.SpecError, match=message):
        specs.parse(
            path, {"generalist": {"version": raw["version"], "sha256": specs.content_hash(path)}}
        )


def test_the_system_prompt_starts_with_the_locked_text():
    from agents.legion.turn import system_for
    from agents.specs.locked import LOCKED
    from assistant.shared_clauses import CANNOT_CLAUSE, CRISIS

    text = system_for(specs.spec("generalist"), None)
    assert text.startswith(LOCKED)
    assert CANNOT_CLAUSE.strip() in text and CRISIS.strip() in text


# =============================================================================
# The read twins (DueList / readList ports) on the invented household
# =============================================================================


def _read(user, name, args):
    from engine_mcp.tools import run_engine_tool

    result = run_engine_tool(member_request(user), TWINS[name], args)
    assert not result.is_error, result.text
    return json.loads(result.text)


def test_read_calendar_says_what_is_due_today_with_overdue_first(seeded):
    out = _read(seeded, "read_calendar", {"from": "today", "to": "today"})
    due = out["due"]
    assert due["today"] == "Tuesday 2026-03-10"
    assert due["open"] == 4 and due["overdue"] == 1 and due["done"] == 1
    assert due["says"].startswith(
        "1 OVERDUE (past due, not done): MATH 110 homework 7 "
        "(due yesterday, Mon Mar 9, 2026 23:59, calendar)."
    )
    assert "3 open and due, not done, not overdue yet: " in due["says"]
    assert "HIST 101 reading quiz (due today, Tue Mar 10, 2026 23:59, calendar)" in due["says"]
    assert "Call the landlord (due today, Tue Mar 10, 2026 18:00, todo)" in due["says"]
    assert "1 already done." in due["says"]
    assert due["open_items"][0]["id"] == str(fixture.rid("MATH 110 homework 7"))
    assert out["window"] == "today (Tue 2026-03-10)"
    # Open tasks first, then the events; reminders are not calendar rows.
    assert {r["title"] for r in out["events"][:2]} == {
        "HIST 101 reading quiz",
        "ENGL 102 discussion post",
    }
    assert "Call the landlord" not in [r["title"] for r in out["events"]]


def test_read_calendar_tomorrow_reads_the_all_day_row_on_its_own_date(seeded):
    out = _read(seeded, "read_calendar", {"from": "2026-03-11", "to": "2026-03-11"})
    titles = [r["title"] for r in out["events"]]
    assert "Campus career fair" in titles and "PHYS 130 lecture" in titles
    # The window reaches today or later, so yesterday's homework still rides as overdue.
    assert out["due"]["overdue"] == 1
    assert "reading quiz" not in json.dumps(out["events"])


def test_a_window_in_the_past_carries_no_overdue(seeded):
    out = _read(seeded, "read_calendar", {"from": "2026-03-01", "to": "2026-03-05"})
    assert out["due"]["says"] == "Nothing open is due in this window."


def test_read_calendar_refuses_a_backwards_window(seeded):
    from engine_mcp.tools import run_engine_tool

    result = run_engine_tool(
        member_request(seeded), TWINS["read_calendar"], {"from": "2026-03-10", "to": "2026-03-01"}
    )
    assert result.is_error and result.text.startswith("Nothing was read")


def test_read_list_reads_reminders_and_the_todo_lines(seeded):
    out = _read(seeded, "read_list", {})
    texts = [i["text"] for i in out["items"]]
    assert texts == ["Call the landlord", "Renew the car registration"]
    assert out["items"][0]["due"] == "today, Tue Mar 10, 2026 18:00"
    assert [line["text"] for line in out["todo_list"]] == ["Buy printer ink", "Return library book"]
    assert out["todo_list"][0]["item_id"] == str(fixture.rid("Buy printer ink"))
    assert out["todo_checklist_id"] == str(fixture.rid("Todo"))
    assert out["items"][0]["id"] == str(fixture.rid("Call the landlord"))


def test_the_twins_never_read_another_members_private_reminder(seeded, household_a):
    from tests.conftest import _member

    mia = _member(household_a, "mia-agents@example.com")
    from legacy.models.dates import Event

    Event.objects.filter(title="Call the landlord").update(owner_user=mia)
    out = _read(seeded, "read_list", {})
    assert "Call the landlord" not in json.dumps(out)
    assert "Call the landlord" not in json.dumps(
        _read(seeded, "read_calendar", {"from": "today", "to": "today"})
    )


# =============================================================================
# Guards and the gateway
# =============================================================================


def _gateway_state(user, seq=1):
    from agents.graph.state import TurnState

    return TurnState(
        turn_id="t",
        conversation_id="c",
        seq=seq,
        household_id=str(user.household.id),
        member_id=str(user.pk),
        client="test",
        transcript="x",
    )


def test_a_tool_outside_the_spec_is_refused_and_does_not_run(seeded):
    spec = specs.spec("generalist")
    narrow = spec.__class__(**{**spec.__dict__, "tools": ("read_list",)})
    seen = []
    gateway = LegionGateway(member_request(seeded), observe=seen.append)
    out = gateway.call(_gateway_state(seeded), narrow, ToolCall("1", "list_events", {}), 6)
    assert not out.ok and out.guard == "not_allowed"
    assert out.response["message"].startswith("Nothing was read or written")


def test_every_write_refuses_after_third_party_text_in_the_same_turn(seeded):
    """ADR 0090: `web_search` taints the turn BEFORE it runs (here it fails, offline); the write
    after it is refused in read_through's words and nothing changes."""
    from checklists.models import ChecklistItem

    spec = specs.spec("generalist")
    state = _gateway_state(seeded)
    marks = []

    class Recorder:
        def mark_taint(self, st):
            marks.append(list(st.taint))
            return True

        def tool_call(self, *a, **k):
            pass

    gateway = LegionGateway(member_request(seeded), recorder=Recorder())
    gateway.call(state, spec, ToolCall("1", "web_search", {"query": "weather"}), 6)
    assert state.tainted and marks == [["web_search"]]
    before = ChecklistItem.objects.count()
    out = gateway.call(
        state,
        spec,
        ToolCall(
            "2", "add_checklist_item", {"checklist_id": str(fixture.rid("Todo")), "text": "x"}
        ),
        6,
    )
    assert out.guard == "tainted" and not out.ok
    assert out.response["message"].startswith(
        "Nothing was changed: add_checklist_item did not run."
    )
    assert ChecklistItem.objects.count() == before


def test_a_taint_that_cannot_be_saved_stops_the_third_party_tool(seeded):
    class Broken:
        def mark_taint(self, st):
            return False

        def tool_call(self, *a, **k):
            pass

    gateway = LegionGateway(member_request(seeded), recorder=Broken())
    state = _gateway_state(seeded)
    out = gateway.call(
        state, specs.spec("generalist"), ToolCall("1", "web_search", {"query": "x"}), 6
    )
    assert out.guard == "tainted" and out.response["message"].startswith("Nothing was read")
    assert state.tainted  # writes stay refused for the rest of the turn


def test_a_destructive_write_waits_for_a_yes_in_a_later_turn(seeded):
    from legacy.models.dates import Event

    target = str(fixture.rid("Dentist cleaning"))
    delete = m.call("delete_event", {"id": target})
    model = m.FakeModel([delete, m.say("Shall I delete the dentist cleaning?")])
    events, state, conversation = _turn(seeded, "delete the dentist", model)
    assert Event.objects.get(pk=target).deleted_at is None
    assert state.pending_confirm and state.pending_confirm["tool"] == "delete_event"
    assert AgentConversation.objects.get(pk=conversation.pk).pending_confirm["args_sha256"]
    # The same call with the same arguments, after a yes, runs once.
    model = m.FakeModel([m.call("delete_event", {"id": target}), m.say("Deleted.")])
    _, state2, _ = _turn(seeded, "yes", model, conversation)
    assert Event.objects.get(pk=target).deleted_at is not None
    assert state2.writes_ran and not state2.honesty_flag


def test_a_yes_does_not_release_a_different_write(seeded):
    from legacy.models.dates import Event

    dentist = str(fixture.rid("Dentist cleaning"))
    lecture = str(fixture.rid("PHYS 130 lecture"))
    _, _, conversation = _turn(
        seeded, "delete it", m.FakeModel([m.call("delete_event", {"id": dentist}), m.say("Sure?")])
    )
    _turn(
        seeded,
        "yes",
        m.FakeModel([m.call("delete_event", {"id": lecture}), m.say("Hm.")]),
        conversation,
    )
    assert Event.objects.get(pk=lecture).deleted_at is None
    assert Event.objects.get(pk=dentist).deleted_at is None


def test_a_recruiter_is_refused_at_the_route_and_by_the_gateway(household_a):
    from household.models import HouseholdMember, User

    household_a.is_demo_visit = True
    household_a.save(update_fields=["is_demo_visit"])
    visitor = User.objects.create_user(email="visitor-agents@example.com", password=None)
    HouseholdMember.objects.create(
        user=visitor, household=household_a, role=HouseholdMember.RECRUITER
    )
    model = m.FakeModel([])
    events, state, _ = _turn(visitor, "what is due", model)
    assert model.requests == [] and state.status == "refused"
    assert state.words == RECRUITER_REFUSAL
    # Past the route, the gateway still holds the recruiter to the web door's allowlist.
    out = LegionGateway(member_request(visitor)).call(
        _gateway_state(visitor),
        specs.spec("generalist"),
        ToolCall("1", "read_calendar", {"from": "today", "to": "today"}),
        6,
    )
    assert not out.ok and out.guard == "recruiter"


def test_a_member_over_the_daily_cap_is_refused_before_the_model(seeded, monkeypatch):
    from llm import cap

    monkeypatch.setattr(cap, "over_cap", lambda user: True)
    model = m.FakeModel([])
    _, state, _ = _turn(seeded, "what is due", model)
    assert model.requests == [] and state.words == cap.USED_UP


def test_the_honesty_check_reads_claims_not_facts():
    assert guards.claims_unbacked_outcome("Done. Added it.", [])
    assert guards.claims_unbacked_outcome("I've added buy stamps.", [])
    assert not guards.claims_unbacked_outcome("I couldn't add it; nothing was added.", [])
    assert not guards.claims_unbacked_outcome("Your essay draft is already done.", [])
    assert not guards.claims_unbacked_outcome("Added.", [{"writes": True, "ok": True}])


# =============================================================================
# A whole turn: rows, history, spend, cancel
# =============================================================================


def test_a_turn_writes_its_rows_privately_and_the_next_turn_sees_its_history(seeded):
    model = m.FakeModel(
        [
            m.call("read_calendar", {"from": "today", "to": "today"}),
            m.say("You have a quiz and a discussion post due today."),
        ]
    )
    events, state, conversation = _turn(seeded, "what's due today", model)
    assert [e.type for e in events][0] == "turn" and events[-1].type == "done"
    turn = AgentTurn.objects.get(conversation=conversation)
    assert turn.status == "done" and turn.final_text.startswith("You have a quiz")
    assert turn.owner_user_id == seeded.pk and turn.household_id == seeded.household.id
    assert turn.first_sentence_ms is not None and turn.cost_micro_usd > 0
    assert [c.node for c in AgentCheckpoint.objects.filter(turn=turn).order_by("step")] == [
        "route",
        "agent",
        "finish",
    ]
    assert AgentModelCall.objects.filter(turn=turn).count() == 2
    call = AgentToolCall.objects.get(turn=turn)
    assert call.tool == "read_calendar" and call.outcome == "ok" and len(call.result_trimmed) <= 400
    assert LlmUsage.objects.filter(user=seeded, feature="agent_turn").count() == 2
    # The context line is in the first user message, never in the system prompt.
    first_user = model.requests[0].contents[-1]["parts"][0]["text"]
    assert "Today is Tuesday 2026-03-10; tomorrow is Wednesday 2026-03-11." in first_user
    # Turn 2 carries turn 1's history.
    model2 = m.FakeModel([m.say("Tomorrow is lighter.")])
    _turn(seeded, "and tomorrow?", model2, conversation)
    texts = json.dumps(model2.requests[0].contents)
    assert "what's due today" in texts and "and tomorrow?" in texts


def test_a_cancelled_turn_starts_no_further_round(seeded):
    request = member_request(seeded)
    conversation = open_conversation(request, None, "test")
    model = m.FakeModel([m.call("read_list", {}), m.say("never")])
    gen = run_turn(request, "reminders", model, conversation=conversation, now=NOW)
    first = next(gen)
    AgentTurn.objects.filter(pk=first.data["turn_id"]).update(cancel_requested=True)
    _, state = _drive(gen)
    assert state.status == "cancelled" and len(model.requests) == 0


def test_another_members_conversation_id_starts_a_new_one(seeded, household_a):
    from tests.conftest import _member

    mia = _member(household_a, "mia-conv@example.com")
    mine = open_conversation(member_request(seeded), None, "test")
    theirs = open_conversation(member_request(mia), mine.pk, "test")
    assert theirs.pk != mine.pk and theirs.owner_user_id == mia.pk


def test_mail_never_reaches_a_trace_or_the_stored_history():
    """ADR 0099: the hook is in before mail lands (stage 4)."""
    assert redact.redact_args("read_mail", {"query": "from:bank"}) == {}
    assert redact.trim_result("gmail_search", "Dear Sam, your code is 123") == redact.MAIL_STUB
    stored = redact.history_for_storage(
        [
            {
                "role": "model",
                "parts": [{"functionCall": {"name": "read_mail", "args": {"q": "x"}}}],
            },
            {
                "role": "user",
                "parts": [
                    {
                        "functionResponse": {
                            "name": "read_mail",
                            "response": {"message": "secret body"},
                        }
                    }
                ],
            },
        ]
    )
    assert "secret body" not in json.dumps(stored) and '"q"' not in json.dumps(stored)
    assert not redact.is_mail_tool("list_events")


def test_retention_prunes_checkpoints_at_7_days_content_at_30_and_rows_at_90(seeded):
    _, _, conversation = _turn(
        seeded, "hi", m.FakeModel([m.call("read_list", {}), m.say("Hello.")])
    )
    turn = AgentTurn.objects.get(conversation=conversation)
    later = timezone.now() + dt.timedelta(days=8)
    assert retention.prune(later)["checkpoints"] == 3
    out = retention.prune(timezone.now() + dt.timedelta(days=31))
    assert out["tool_calls"] == 1 and out["blanked"] == 1
    turn.refresh_from_db()
    assert (
        turn.transcript == ""
        and turn.history == []
        and AgentModelCall.objects.filter(turn=turn).exists()
    )
    assert retention.prune(timezone.now() + dt.timedelta(days=91))["turns"] == 1
    assert not AgentModelCall.objects.exists()


def test_rest_declarations_carry_no_live_only_field(seeded):
    decls = LegionGateway(member_request(seeded)).declarations(specs.spec("generalist"))
    assert decls and all("behavior" not in d for d in decls)
    assert {d["name"] for d in decls} >= {"read_calendar", "read_list", "update_event"}


def test_a_trace_keeps_dates_and_masks_id_shaped_numbers():
    args = redact.redact_args("read_calendar", {"from": "2026-03-11", "to": "2026-03-12"})
    assert args == {"from": "2026-03-11", "to": "2026-03-12"}
    assert (
        redact.redact_args("write_record", {"note": "ssn 123-45-6789"})["note"] == "ssn ###########"
    )
    assert redact.redact_args("x", {"token": "abc"}) == {"token": "[redacted]"}


def test_rest_schemas_take_one_type_each():
    from agents.legion.gateway import _rest_schema

    out = _rest_schema({"type": "object", "properties": {"a": {"type": ["string", "null"]}}})
    assert out["properties"]["a"] == {"type": "string", "nullable": True}

    def walk(node):
        if isinstance(node, dict):
            assert not isinstance(node.get("type"), list), node
            for v in node.values():
                walk(v)
        elif isinstance(node, list):
            for v in node:
                walk(v)

    from agents.legion.gateway import rest_declarations
    from agents.legion.twins import TWINS
    from assistant.surface import web_tools

    walk(rest_declarations([*web_tools(), *TWINS.values()]))


def test_a_tracker_draft_cannot_be_committed_in_the_same_turn(seeded):
    """Ticket 15: create_aspect drafts freely, but its `confirm: true` call is held for a yes in
    a LATER turn (`guards.CONFIRM_FLAG_TOOLS`), so a model cannot draft and commit at once."""
    from homes.models import UserAspect
    from homes.records_tools import CREATE_ASPECT, RECORD_TOOLS

    # The records tools, plus backlog intake (`test_board_ideas.py` covers its later yes).
    assert guards.CONFIRM_FLAG_TOOLS - {"add_backlog_idea"} <= {t.name for t in RECORD_TOOLS}
    draft = {
        "aspect_name": "Test reading",
        "record_types": [{"name": "Book", "fields": [{"name": "title", "type": "TEXT"}]}],
    }
    token = CREATE_ASPECT.handler(member_request(seeded), draft).structured["draft_token"]
    commit = {**draft, "confirm": True, "draft_token": token}
    same_turn = m.FakeModel(
        [m.call("create_aspect", draft), m.call("create_aspect", commit), m.say("Shall I?")]
    )
    _, state, conversation = _turn(seeded, "track my reading, title only", same_turn)
    assert state.pending_confirm and state.pending_confirm["tool"] == "create_aspect"
    assert not UserAspect.objects.filter(name="Test reading").exists()
    after_yes = m.FakeModel([m.call("create_aspect", commit), m.say("Created.")])
    _turn(seeded, "yes", after_yes, conversation)
    assert UserAspect.objects.filter(name="Test reading").exists()
