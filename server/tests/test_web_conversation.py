"""The web assistant's conversation record (2026-11-03, Kevin: "recorded to improve ... and seeing
what tool calls etc went wrong"; `devicelogs/web_conversation.py`).

Pinned here: the mint opens a record, the tool door writes each call into it, the browser's
upload adds the words, the record renders with the phone's parser, nobody reaches another
person's record, a recruiter's record survives the visit's household and goes after 30 days.
"""

from __future__ import annotations

import gzip
import json
import uuid
from datetime import UTC, datetime, timedelta
from io import StringIO

import pytest
from django.core.management import call_command
from django.test import Client
from django.utils import timezone

from demo.models import DemoVisit
from demo.visit_cleanup import delete_visit_household
from devicelogs.models import VISIT_RETENTION_DAYS, DeviceLog
from devicelogs.render import render
from household.models import Household, HouseholdMember, User
from tests.conftest import _member
from tests.test_assistant import _start, browser, google  # noqa: F401 - fixture
from tests.test_recruiter import _recruiter_browser, _visitor_token

pytestmark = [pytest.mark.django_db, pytest.mark.usefixtures("google")]

URL = "/api/assistant/conversation"
CLIENT_NOW = 1_800_000_000_000  # the browser's clock at the mint, epoch ms


def _body(row: DeviceLog) -> str:
    return gzip.decompress(bytes(row.body_gz)).decode()


def _mint(client) -> str:
    response = _start(client, utc_offset_minutes=-300, client_now_ms=CLIENT_NOW)
    assert response.status_code == 200, response.data
    return str(response.data["conversation_id"])


def _upload(client, conversation_id, events, **extra):
    return client.post(
        URL,
        {"conversation_id": conversation_id, "events": json.dumps(events), **extra},
        format="multipart",
    )


def _tool(client, conversation_id, name="list_checklists", args=None, call_id="c1"):
    return client.post(
        "/api/assistant/tool",
        {"name": name, "args": args or {}, "conversation_id": conversation_id, "call_id": call_id},
        format="json",
    )


def _turn(offset_ms=0):
    t = CLIENT_NOW + offset_ms
    return [
        {"kind": "latency", "at_ms": t + 900, "metric": "cold_start", "ms": 900},
        {"kind": "user", "at_ms": t + 2000, "text": "What lists do we have?", "typed": True},
        {"kind": "latency", "at_ms": t + 3100, "metric": "reply", "ms": 1100},
        {"kind": "assistant", "at_ms": t + 3100, "text": "You have a Groceries list."},
        {"kind": "turn_complete", "at_ms": t + 3500},
    ]


@pytest.fixture
def member(household_user):
    return household_user


@pytest.fixture
def member_web(member):
    return browser(member)


@pytest.fixture
def other_web(household_a):
    return browser(_member(household_a, "other@example.com"))


def _visit(ref="acme"):
    household = Household.objects.create(name=f"Demo visit {ref}", is_demo_visit=True)
    user = User.objects.create_user(email=f"visitor-{uuid.uuid4().hex[:12]}@demo.invalid")
    user.set_unusable_password()
    user.save()
    HouseholdMember.objects.create(user=user, household=household, role=HouseholdMember.RECRUITER)
    token, _raw = _visitor_token(user)
    visit = DemoVisit.objects.create(
        user=user,
        token=token,
        household=household,
        ref=ref,
        expires_at=timezone.now() + timedelta(hours=2),
    )
    return visit, _recruiter_browser(user, token)


# =============================================================================
# A member's conversation
# =============================================================================


def test_the_mint_opens_a_web_conversation_record(member_web, member):
    cid = _mint(member_web)
    row = DeviceLog.objects.get(batch_id=cid)
    assert (row.device_label, row.reason, row.user, row.demo_visitor) == (
        "web", "conversation", member, False,
    )  # fmt: skip
    assert row.household_id is not None
    assert row.clock_offset_ms is not None
    assert "LIVE_AUDIT: session_start model=" in _body(row)


def test_tool_calls_and_the_upload_make_one_record_that_renders(member_web):
    cid = _mint(member_web)
    response = _tool(member_web, cid, args={"include_ticked": False})
    assert response.status_code == 200, response.data
    stored = _upload(member_web, cid, _turn())
    assert stored.status_code == 200, stored.data
    assert stored.data["stored_lines"] == 5

    row = DeviceLog.objects.get(batch_id=cid)
    body = _body(row)
    # The writer is in the tid column: 1 the browser, 2 the engine.
    assert '  0  1 I LIVE_AUDIT: user final=true typed=true text="What lists do we have?"' in body
    assert (
        "  0  2 I LIVE_AUDIT: tool_call id=c1 name=list_checklists async=false "
        'args={"include_ticked":false}'
    ) in body
    ok = "true" if not response.data["is_error"] else "false"
    assert f"LIVE_AUDIT: tool_result id=c1 name=list_checklists success={ok}" in body
    assert f'response={{"success":{ok},"outcome":"{response.data["outcome"]}","message":' in body
    assert "LIVE_LATENCY: tool_ms=" in body
    assert row.line_count == len(body.splitlines())

    timeline = render(body)
    assert timeline[0].startswith("== session 1  model=")
    kinds = [line.split("  ", 1)[1] for line in timeline[1:] if "  " in line]
    assert any(k.startswith("USER: (typed) What lists do we have?") for k in kinds)
    assert any(k.startswith("TOOL -> list_checklists(") for k in kinds)
    assert any(k.startswith("TOOL <- list_checklists") for k in kinds)
    assert any(k.startswith("ASSISTANT: You have a Groceries list.") for k in kinds)
    assert any(k.startswith("cold start 900ms") for k in kinds)
    assert any(k.startswith("reply 1100ms") for k in kinds)


def test_browser_lines_are_put_on_the_engines_clock(member_web):
    cid = _mint(member_web)
    row = DeviceLog.objects.get(batch_id=cid)
    _upload(member_web, cid, [{"kind": "turn_complete", "at_ms": CLIENT_NOW}])
    engine_at = datetime.fromtimestamp((CLIENT_NOW + row.clock_offset_ms) / 1000, tz=UTC)
    assert f"{engine_at:%m-%d %H:%M:%S}" in _body(DeviceLog.objects.get(batch_id=cid))


def test_a_retried_upload_is_stored_once(member_web):
    cid = _mint(member_web)
    assert _upload(member_web, cid, _turn()).data["stored_lines"] == 5
    again = _upload(member_web, cid, _turn())
    assert again.status_code == 200 and again.data["stored_lines"] == 0
    assert DeviceLog.objects.get(batch_id=cid).line_count == 6


def test_the_browser_cannot_write_a_tool_line(member_web):
    cid = _mint(member_web)
    forged = _upload(member_web, cid, [{"kind": "tool_result", "at_ms": CLIENT_NOW, "name": "x"}])
    assert forged.status_code == 400
    assert forged.data["detail"].startswith("Nothing was stored.")
    assert DeviceLog.objects.get(batch_id=cid).line_count == 1


def test_a_refused_tool_call_is_on_the_record_with_its_words(member_web):
    cid = _mint(member_web)
    refused = _tool(member_web, cid, name="not_a_tool")
    assert refused.status_code == 400
    body = _body(DeviceLog.objects.get(batch_id=cid))
    assert "tool_result id=c1 name=not_a_tool success=false" in body
    assert '"outcome":"refused"' in body


def test_a_member_cannot_upload_to_or_tool_into_another_members_record(member_web, other_web):
    cid = _mint(member_web)
    refused = _upload(other_web, cid, _turn())
    assert refused.status_code == 404
    assert refused.data["detail"] == "Nothing was stored. No conversation with that id is yours."
    # The other member's tool call runs, and does not reach this record.
    assert _tool(other_web, cid).status_code == 200
    assert DeviceLog.objects.get(batch_id=cid).line_count == 1


def test_there_is_no_read_door(member_web):
    cid = _mint(member_web)
    assert member_web.get(URL, {"conversation_id": cid}).status_code == 405


def test_the_beacon_shape_passes_csrf_with_the_token_in_the_form(member):
    """`navigator.sendBeacon` cannot set `X-CSRFToken`: the token rides as a form field."""
    client = Client(enforce_csrf_checks=True)
    client.force_login(member)
    client.get("/api/auth/csrf")
    csrf = client.cookies["csrftoken"].value
    cid = _mint(browser(member))
    without = client.post(URL, {"conversation_id": cid, "events": json.dumps(_turn())})
    assert without.status_code == 403
    response = client.post(
        URL,
        {"conversation_id": cid, "events": json.dumps(_turn()), "csrfmiddlewaretoken": csrf},
    )
    assert response.status_code == 200, response.content
    assert response.json()["stored_lines"] == 5


def test_a_members_records_follow_device_log_retention_not_the_visit_prune(member_web):
    cid = _mint(member_web)
    DeviceLog.objects.filter(batch_id=cid).update(
        received_at=timezone.now() - timedelta(days=VISIT_RETENTION_DAYS + 5)
    )
    call_command("prune_demo_visitors", stdout=StringIO())
    assert DeviceLog.objects.filter(batch_id=cid).exists()


# =============================================================================
# A recruiter's conversation
# =============================================================================


def test_a_recruiters_record_names_the_visit_and_has_no_household():
    visit, web = _visit("acme")
    cid = _mint(web)
    row = DeviceLog.objects.get(batch_id=cid)
    assert (row.household_id, row.demo_visitor, row.visit, row.visit_ref) == (
        None, True, visit, "acme",
    )  # fmt: skip
    assert _tool(web, cid).status_code == 200
    stored = _upload(web, cid, _turn())
    assert stored.status_code == 200, stored.data
    body = _body(DeviceLog.objects.get(batch_id=cid))
    assert "tool_call id=c1 name=list_checklists" in body
    assert "What lists do we have?" in body


def test_a_recruiter_cannot_upload_to_another_visit_or_a_member(member_web):
    _visit_a, web_a = _visit("a")
    _visit_b, web_b = _visit("b")
    cid_a = _mint(web_a)
    member_cid = _mint(member_web)
    assert _upload(web_b, cid_a, _turn()).status_code == 404
    assert _upload(web_a, member_cid, _turn()).status_code == 404
    assert _upload(member_web, cid_a, _turn()).status_code == 404
    # B's tool call with A's id runs for B and writes nothing into A's record.
    assert _tool(web_b, cid_a).status_code == 200
    assert DeviceLog.objects.get(batch_id=cid_a).line_count == 1
    assert DeviceLog.objects.get(batch_id=member_cid).line_count == 1


def test_a_recruiter_still_cannot_read_or_post_device_logs():
    _visit_a, web = _visit()
    for method in ("get", "post"):
        response = getattr(web, method)("/api/device-logs/", {}, format="multipart")
        assert response.status_code == 403, (method, response.status_code)
    assert web.get(URL).status_code == 405


def test_a_recruiters_record_outlives_the_visit_household_and_goes_after_30_days():
    visit, web = _visit("acme")
    cid = _mint(web)
    _upload(web, cid, _turn())
    old_visit, old_web = _visit("old")
    old_cid = _mint(old_web)

    delete_visit_household(visit.household)
    assert DeviceLog.objects.filter(batch_id=cid).exists(), "kept after the household went"
    visit.refresh_from_db()
    assert visit.household is None

    DeviceLog.objects.filter(batch_id=old_cid).update(
        received_at=timezone.now() - timedelta(days=VISIT_RETENTION_DAYS, minutes=1)
    )
    DeviceLog.objects.filter(batch_id=cid).update(
        received_at=timezone.now() - timedelta(days=VISIT_RETENTION_DAYS - 1)
    )
    dry = StringIO()
    call_command("prune_demo_visitors", "--dry-run", stdout=dry)
    assert "1 visit conversation record(s) older than 30 days" in dry.getvalue()
    assert DeviceLog.objects.filter(batch_id=old_cid).exists()

    out = StringIO()
    call_command("prune_demo_visitors", stdout=out)
    assert "1 visit conversation record(s) older than 30 days" in out.getvalue()
    assert not DeviceLog.objects.filter(batch_id=old_cid).exists()
    assert DeviceLog.objects.filter(batch_id=cid).exists()


def test_the_read_command_renders_a_web_record_with_its_visit():
    _visit_a, web = _visit("acme")
    cid = _mint(web)
    _tool(web, cid)
    _upload(web, cid, _turn())
    out = StringIO()
    call_command(
        "device_logs", "--last", "1", "--reason", "conversation", "--device", "web", "--render",
        stdout=out,
    )  # fmt: skip
    text = out.getvalue()
    assert f"=== batch {DeviceLog.objects.get(batch_id=cid).id}" in text
    assert "| web v?(?) | conversation |" in text
    assert "visit ref=acme" in text
    assert "TOOL -> list_checklists({})" in text
    assert "USER: (typed) What lists do we have?" in text
