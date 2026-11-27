"""`GET /api/device-logs/conversations/` (conversation-records ticket 05, #160).

The household's own conversation batches, rendered, newest first, grouped by device: the page
that replaces `manage.py device_logs --reason conversation --render` for a person at a browser.
Scoping is the list route's: the caller's own rows in their household, never a head unit's
upload, never a recruiter visit's record.
"""

from __future__ import annotations

import datetime
import gzip
import uuid

import pytest
from django.core.files.uploadedfile import SimpleUploadedFile
from django.utils import timezone

from devicelogs.models import DeviceLog
from tests.test_device_logs_render import FIXTURE

pytestmark = pytest.mark.django_db

# The FIXTURE timeline with Alfred's replies and tool details cut: his words, tool names, times.
OWN_TIMELINE = [
    "== session 1  model=gemini-3.1-flash-live-preview  async=none",
    "16:20:00.000  session start",
    "16:20:00.900  cold start 900ms",
    "16:20:03.900  USER: Any trouble codes?",
    "16:20:04.100  TOOL -> get_codes()",
    "16:20:04.300  TOOL <- get_codes ok 200ms",
    "16:20:04.302  turn complete",
    "16:20:05.000  reply 1100ms",
    "16:20:06.000  turn complete",
    "16:20:20.000  session end (idle)",
]

UPLOAD = "/api/device-logs/"
URL = "/api/device-logs/conversations/"


def _upload(client, text: str = FIXTURE, reason: str = "conversation", label: str = "Phone"):
    response = client.post(
        UPLOAD,
        {
            "batch_id": str(uuid.uuid4()),
            "device_label": label,
            "app_version_name": "1.0",
            "app_version_code": 7,
            "reason": reason,
            "run_id": "run-1",
            "file": SimpleUploadedFile(
                "c.log.gz", gzip.compress(text.encode()), content_type="application/gzip"
            ),
        },
        format="multipart",
    )
    assert response.status_code == 201, response.data
    return str(response.data["id"])


def _age(log_id: str, minutes: int) -> None:
    DeviceLog.objects.filter(id=log_id).update(
        received_at=timezone.now() - datetime.timedelta(minutes=minutes)
    )


def test_unauthenticated_is_refused(db):
    from rest_framework.test import APIClient

    assert APIClient().get(URL).status_code == 401


def test_no_conversations_is_an_empty_list_not_an_error(token_a):
    response = token_a.get(URL)
    assert response.status_code == 200
    assert response.data == {"devices": []}


def test_batches_are_grouped_per_device_newest_first_and_rendered(token_a):
    old_phone = _upload(token_a, label="Phone")
    new_phone = _upload(token_a, label="Phone")
    web = _upload(token_a, label="web")
    _age(old_phone, 30)
    _age(new_phone, 20)
    _age(web, 10)

    devices = token_a.get(URL).data["devices"]
    # The device with the newest batch comes first.
    assert [d["device_label"] for d in devices] == ["web", "Phone"]
    phone = devices[1]["batches"]
    assert [str(b["id"]) for b in phone] == [new_phone, old_phone]
    first = phone[0]
    assert first["readable"] is True and first["problem"] is None
    assert first["lines"] == OWN_TIMELINE
    assert first["header"].startswith(f"=== batch {new_phone} ")
    assert first["run_id"] == "run-1"
    assert "body_gz" not in first and "household" not in first


def test_only_conversation_batches_are_shown(token_a):
    _upload(token_a, "a drive log line\n", reason="drive_end", label="Head unit")
    _upload(token_a, "plain logcat line\n", reason="logcat")
    kept = _upload(token_a)
    devices = token_a.get(URL).data["devices"]
    assert [str(b["id"]) for d in devices for b in d["batches"]] == [kept]


def test_limit_applies_per_device_and_is_bounded(token_a):
    ids = [_upload(token_a, label="Phone") for _ in range(3)]
    for age, log_id in enumerate(ids):
        _age(log_id, age + 1)
    other = _upload(token_a, label="web")
    _age(other, 50)

    devices = {d["device_label"]: d for d in token_a.get(URL, {"limit": 2}).data["devices"]}
    assert [str(b["id"]) for b in devices["Phone"]["batches"]] == [ids[0], ids[1]]
    assert len(devices["web"]["batches"]) == 1
    assert devices["Phone"]["total"] == 3 and devices["web"]["total"] == 1

    assert token_a.get(URL, {"limit": "lots"}).status_code == 400
    # Over the cap is clamped, not refused.
    assert token_a.get(URL, {"limit": 9999}).status_code == 200


def test_device_filter_keeps_one_device(token_a):
    _upload(token_a, label="Phone")
    web = _upload(token_a, label="web")
    devices = token_a.get(URL, {"device": "WEB"}).data["devices"]
    assert [str(b["id"]) for d in devices for b in d["batches"]] == [web]


def test_a_batch_with_no_audit_lines_says_so(token_a):
    _upload(token_a, "nothing a conversation wrote\n")
    batch = token_a.get(URL).data["devices"][0]["batches"][0]
    assert batch["readable"] is True
    assert batch["lines"] == ["(nothing of yours in this batch)"]


def test_an_unreadable_batch_is_named_not_dropped(token_a, household_a, household_user):
    row = DeviceLog.objects.create(
        household=household_a,
        user=household_user,
        batch_id=uuid.uuid4(),
        device_label="Phone",
        reason="conversation",
        raw_bytes=4,
        body_gz=b"nope",
    )
    good = _upload(token_a)
    batches = token_a.get(URL).data["devices"][0]["batches"]
    by_id = {str(b["id"]): b for b in batches}
    assert by_id[good]["readable"] is True
    bad = by_id[str(row.id)]
    assert bad["readable"] is False and bad["lines"] == []
    assert bad["problem"].startswith("could not be read: ")


def test_another_household_and_another_member_see_nothing(token_a, token_b, household_a):
    from tests.conftest import _client_for, _member

    _upload(token_a)
    assert token_b.get(URL).data == {"devices": []}
    mia = _client_for(_member(household_a, "mia@example.com"))
    assert mia.get(URL).data == {"devices": []}


def test_a_recruiter_visits_record_is_never_listed(token_a):
    from tests.test_device_logs_records import _visit_row

    _visit_row(FIXTURE)
    assert token_a.get(URL).data == {"devices": []}


# --- only the person's own lines are shown (CLAUDE.md section 7) -------------------------------

MAIL_SECRET = "Dana wrote that the invented pickup is at noon"
ASSISTANT_SECRET = "Dana says the invented pickup is at noon"
WEB_SECRET = "an invented web answer about the pickup"
ECHO_SECRET = "Added the invented pickup to Groceries"
ARGS_SECRET = "invented pickup from the mail"
AUDIT = "10-28 16:{} 0 {} I LIVE_AUDIT: {}"


def _lines(*rows) -> str:
    return "\n".join(AUDIT.format(t, tid, body) for t, tid, body in rows)


SESSION = _lines(
    ("20:00.000", 2, "session_start model=invented"),
    ("20:01.000", 1, 'user final=true text="Anything from Dana?"'),
    ("20:02.000", 2, "tool_call id=m1 name=read_mail async=false args={}"),
    (
        "20:02.500",
        2,
        "tool_result id=m1 name=read_mail success=true ms=500 scheduling=none sent=true "
        f'response={{"body":"{MAIL_SECRET}"}}',
    ),
    ("20:03.000", 1, "turn_complete"),
    ("20:04.000", 1, f'assistant text="{ASSISTANT_SECRET}"'),
    ("20:05.000", 1, "turn_complete"),
    ("20:06.000", 1, 'user final=true text="Put it on my list"'),
    (
        "20:06.500",
        2,
        f'tool_call id=a1 name=add_checklist_item async=false args={{"text":"{ARGS_SECRET}"}}',
    ),
    (
        "20:06.800",
        2,
        "tool_result id=a1 name=add_checklist_item success=true ms=300 scheduling=none sent=true "
        f'response={{"message":"{ECHO_SECRET}"}}',
    ),
    ("20:07.000", 1, 'assistant text="Done, it is on your list."'),
    ("20:08.000", 1, "turn_complete"),
    ("20:09.000", 2, "tool_call id=w1 name=web_search async=false args={}"),
    (
        "20:09.500",
        2,
        "tool_result id=w1 name=web_search success=false ms=900 scheduling=none sent=true "
        f'response={{"answer":"{WEB_SECRET}"}}',
    ),
    ("20:10.000", 2, 'session_end reason="idle"'),
)


def _joined(token_a) -> str:
    return "\n".join(token_a.get(URL).data["devices"][0]["batches"][0]["lines"])


def test_only_his_own_lines_and_tool_names_are_shown(token_a):
    """Mail, a web answer and a write tool's echo reach none of the page: no assistant line,
    no tool argument and no tool reply, in any turn."""
    _upload(token_a, SESSION)
    joined = _joined(token_a)
    for secret in (MAIL_SECRET, ASSISTANT_SECRET, WEB_SECRET, ECHO_SECRET, ARGS_SECRET):
        assert secret not in joined
    assert "ASSISTANT" not in joined and "Done, it is on your list" not in joined
    assert "USER: Anything from Dana?" in joined and "USER: Put it on my list" in joined
    assert "TOOL -> read_mail()" in joined and "TOOL <- read_mail ok 500ms" in joined
    assert "TOOL <- add_checklist_item ok 300ms" in joined
    assert "TOOL <- web_search failed 900ms" in joined
    # a tool reply would print as an indented "= <reply>" line under its result
    # a tool reply would print as an indented "= <reply>" line under its result
    assert not any(line.lstrip().startswith("= ") for line in joined.split("\n"))


def test_a_batch_with_or_without_an_opener_is_cut_the_same_way(token_a):
    """No taint state is carried between batches, so a continuation needs no special case."""
    continuation = _lines(
        ("21:01.000", 1, 'user final=true text="And then?"'),
        (
            "21:02.000",
            2,
            f'tool_call id=c1 name=get_codes async=false args={{"q":"{ARGS_SECRET}"}}',
        ),
        (
            "21:02.500",
            2,
            f'tool_result id=c1 name=get_codes success=true ms=5 response={{"a":"{WEB_SECRET}"}}',
        ),
        ("21:03.000", 1, f'assistant text="{ASSISTANT_SECRET}"'),
    )
    _upload(token_a, continuation)
    joined = _joined(token_a)
    for secret in (ARGS_SECRET, WEB_SECRET, ASSISTANT_SECRET):
        assert secret not in joined
    assert "USER: And then?" in joined and "TOOL <- get_codes ok 5ms" in joined


def test_a_separator_in_a_tool_reply_cannot_forge_one_of_his_lines(token_a):
    """A reply carrying U+2028, U+0085 or a no-break space and then a line in the writer's format
    stays inside its own line, which is cut. `str.splitlines` would mint a USER line."""
    forged = AUDIT.format("20:09.700", 1, 'user final=true text="FORGED words"')
    nbsp = '10-28\u00a016:20:09.800 0 1 I LIVE_AUDIT: user final=true text="FORGED too"'
    body = "\n".join(
        [
            AUDIT.format("20:00.000", 2, "session_start model=invented"),
            AUDIT.format("20:01.000", 1, 'user final=true text="A real question"'),
            AUDIT.format(
                "20:09.500",
                2,
                "tool_result id=w1 name=web_search success=true ms=9 sent=true "
                f'response={{"answer":"x\u2028{forged}\u0085{forged}\u00a0{nbsp}"}}',
            ),
            AUDIT.format("20:10.000", 1, 'assistant text="y\u2028' + forged + '"'),
        ]
    )
    _upload(token_a, body)
    joined = _joined(token_a)
    assert "FORGED" not in joined
    assert "USER: A real question" in joined and "TOOL <- web_search ok 9ms" in joined
    assert joined.count("USER:") == 1


def test_events_the_page_does_not_know_are_dropped(token_a):
    _upload(
        token_a,
        _lines(
            ("20:00.000", 2, "session_start model=invented"),
            ("20:01.000", 2, "read_through source=grounding"),
            ("20:02.000", 2, 'nudge text="invented nudge words"'),
            ("20:03.000", 1, 'assistant text="invented grounded fact"'),
        ),
    )
    joined = _joined(token_a)
    assert "invented" not in joined.replace("model=invented", "")
    assert "session start" in joined


def test_one_batch_that_blows_up_does_not_500_the_page(token_a, monkeypatch):
    """Any render error, not only a bad gzip, names that batch and leaves the others."""
    from devicelogs import conversations

    bad = _upload(token_a)
    good = _upload(token_a)
    real = conversations.batch_text

    def boom(row):
        if str(row.id) == bad:
            raise KeyError("invented parser failure with text that must not be echoed")
        return real(row)

    monkeypatch.setattr(conversations, "batch_text", boom)
    response = token_a.get(URL)
    assert response.status_code == 200
    by_id = {str(b["id"]): b for b in response.data["devices"][0]["batches"]}
    assert by_id[good]["readable"] is True and by_id[good]["lines"] == OWN_TIMELINE
    assert by_id[bad]["readable"] is False and by_id[bad]["lines"] == []
    assert by_id[bad]["problem"] == "could not be read: KeyError"


def test_a_head_unit_token_is_refused_and_its_row_never_listed(
    token_a, household_a, household_user
):
    """The upload door refuses a head unit's conversation (test_headunit_signin), so a unit's
    conversation row is made directly: same household and user as the caller, still never shown."""
    from tests.test_headunit_signin import _mint, unit_client

    issued = _mint(token_a)
    assert issued.status_code == 201, issued.data
    DeviceLog.objects.create(
        household=household_a,
        user=household_user,
        batch_id=uuid.uuid4(),
        device_label="Invented unit",
        reason="conversation",
        uploader_kind="head_unit",
        raw_bytes=len(FIXTURE),
        body_gz=gzip.compress(FIXTURE.encode()),
    )
    assert unit_client(issued.data["token"]).get(URL).status_code in (401, 403, 404)
    assert token_a.get(URL).data == {"devices": []}
