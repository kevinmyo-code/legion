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
from tests.test_device_logs_render import FIXTURE, TIMELINE

pytestmark = pytest.mark.django_db

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
    assert first["lines"] == TIMELINE
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


def test_mail_stays_redacted(token_a):
    from tests.test_device_logs_records import MAIL_SECRET, REDACTED_BATCH

    _upload(token_a, REDACTED_BATCH)
    batch = token_a.get(URL).data["devices"][0]["batches"][0]
    joined = "\n".join(batch["lines"])
    assert "ASSISTANT: [redacted]" in joined
    assert MAIL_SECRET not in joined


def test_a_batch_with_no_audit_lines_says_so(token_a):
    _upload(token_a, "nothing a conversation wrote\n")
    batch = token_a.get(URL).data["devices"][0]["batches"][0]
    assert batch["readable"] is True
    assert batch["lines"] == ["(no LIVE_AUDIT or LIVE_LATENCY lines in this batch)"]


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


# --- read-through: what the engine withholds at render time (CLAUDE.md section 7) --------------

MAIL_SECRET = "Dana wrote that the invented pickup is at noon"
ASSISTANT_SECRET = "Dana says the invented pickup is at noon"
AUDIT = "10-28 16:{} 0 {} I LIVE_AUDIT: {}"
MAIL_BATCH = "\n".join(
    [
        AUDIT.format("20:00.000", 2, "session_start model=invented"),
        AUDIT.format("20:01.000", 1, 'user final=true text="Anything from Dana?"'),
        AUDIT.format("20:02.000", 2, "tool_call id=m1 name=read_mail async=false args={}"),
        AUDIT.format(
            "20:02.500",
            2,
            "tool_result id=m1 name=read_mail success=true ms=500 scheduling=none sent=true "
            f'response={{"body":"{MAIL_SECRET}"}}',
        ),
        AUDIT.format("20:03.000", 1, "turn_complete"),
        AUDIT.format("20:04.000", 1, f'assistant text="{ASSISTANT_SECRET}"'),
        AUDIT.format("20:04.500", 1, 'assistant text=" and more of it"'),
        AUDIT.format("20:05.000", 1, "turn_complete"),
        AUDIT.format("20:06.000", 1, 'user final=true text="Thanks"'),
        AUDIT.format("20:07.000", 1, 'assistant text="Any time."'),
        AUDIT.format("20:08.000", 2, 'session_end reason="idle"'),
        AUDIT.format("21:00.000", 2, "session_start model=invented"),
        AUDIT.format("21:01.000", 1, 'user final=true text="Next day"'),
        AUDIT.format("21:02.000", 1, 'assistant text="A clean session."'),
        AUDIT.format("21:03.000", 2, 'session_end reason="idle"'),
    ]
)


def _joined(token_a) -> str:
    return "\n".join(token_a.get(URL).data["devices"][0]["batches"][0]["lines"])


def test_mail_text_in_a_stored_batch_is_withheld_at_render_time(token_a):
    """The web record stores a tool's reply and the assistant's words un-redacted; the engine,
    not the uploading device, withholds them, for the rest of the session."""
    _upload(token_a, MAIL_BATCH)
    joined = _joined(token_a)
    assert MAIL_SECRET not in joined and ASSISTANT_SECRET not in joined
    assert "and more of it" not in joined and "Any time." not in joined
    assert joined.count("ASSISTANT: withheld: read from mail") == 2  # one run, then the next turn
    assert "= withheld: read from mail" in joined
    # the person's own words and the next, clean session are untouched
    assert "USER: Anything from Dana?" in joined and "USER: Thanks" in joined
    assert "ASSISTANT: A clean session." in joined


def test_a_third_party_web_tool_is_withheld_too(token_a):
    secret = "an invented headline"
    _upload(
        token_a,
        "\n".join(
            [
                AUDIT.format("20:00.000", 2, "session_start model=invented"),
                AUDIT.format("20:02.000", 2, "tool_call id=w1 name=web_search async=false args={}"),
                AUDIT.format(
                    "20:02.500",
                    2,
                    "tool_result id=w1 name=web_search success=true ms=5 scheduling=none "
                    f'sent=true response={{"answer":"{secret}"}}',
                ),
            ]
        ),
    )
    joined = _joined(token_a)
    assert secret not in joined and "= withheld: read from mail" in joined


def test_an_unredacted_read_through_mark_withholds_the_turn(token_a):
    _upload(
        token_a,
        "\n".join(
            [
                AUDIT.format("20:00.000", 2, "session_start model=invented"),
                AUDIT.format("20:01.000", 2, "read_through source=grounding"),
                AUDIT.format("20:02.000", 1, 'assistant text="invented grounded fact"'),
            ]
        ),
    )
    joined = _joined(token_a)
    assert "invented grounded fact" not in joined
    assert "ASSISTANT: withheld: read from mail" in joined


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
    assert by_id[good]["readable"] is True and by_id[good]["lines"] == TIMELINE
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


# --- the taint is per conversation session, not per stored batch ------------------------------

PARAPHRASE = "the invented pickup moves to noon, as the mail says"


def _lines(*rows) -> str:
    return "\n".join(AUDIT.format(t, tid, body) for t, tid, body in rows)


def _both(token_a, first: str, second: str):
    """Two batches of one device, `first` older. Returns (first_id, second_id, batches by id)."""
    one = _upload(token_a, first)
    two = _upload(token_a, second)
    _age(one, 10)
    batches = token_a.get(URL).data["devices"][0]["batches"]
    return one, two, {str(b["id"]): "\n".join(b["lines"]) for b in batches}


def test_a_session_split_across_two_batches_stays_withheld(token_a):
    """The phone seals a batch by size, so a session's later turns can open the next batch with
    no `session_start`. The mail in batch one still taints them."""
    first = _lines(
        ("20:00.000", 2, "session_start model=invented"),
        ("20:02.000", 2, "tool_call id=m1 name=read_mail async=false args={}"),
        ("20:03.000", 1, "turn_complete"),
    )
    second = _lines(
        ("21:01.000", 1, 'user final=true text="And then?"'),
        ("21:02.000", 2, 'tool_call id=c1 name=get_codes async=false args={"q":"mail-derived"}'),
        ("21:03.000", 1, f'assistant text="{PARAPHRASE}"'),
    )
    _one, two, shown = _both(token_a, first, second)
    assert PARAPHRASE not in shown[two] and "mail-derived" not in shown[two]
    assert "ASSISTANT: withheld: read from mail" in shown[two]
    assert "USER: And then?" in shown[two]


def test_a_batch_that_opens_a_session_is_not_tainted_by_the_one_before(token_a):
    first = _lines(
        ("20:00.000", 2, "session_start model=invented"),
        ("20:02.000", 2, "tool_call id=m1 name=read_mail async=false args={}"),
    )
    second = _lines(
        ("21:00.000", 2, "session_start model=invented"),
        ("21:03.000", 1, 'assistant text="A fresh session."'),
    )
    _one, two, shown = _both(token_a, first, second)
    assert "ASSISTANT: A fresh session." in shown[two]


def test_a_clean_session_split_across_two_batches_is_shown(token_a):
    first = _lines(("20:00.000", 2, "session_start model=invented"))
    second = _lines(("21:03.000", 1, 'assistant text="Nothing private here."'))
    _one, two, shown = _both(token_a, first, second)
    assert "ASSISTANT: Nothing private here." in shown[two]


def test_tool_arguments_in_a_tainted_session_are_withheld(token_a):
    _upload(
        token_a,
        _lines(
            ("20:00.000", 2, "session_start model=invented"),
            ("20:01.000", 2, "read_through source=grounding"),
            ("20:02.000", 2, 'tool_call id=c1 name=add_event async=false args={"t":"from-mail"}'),
        ),
    )
    joined = _joined(token_a)
    assert "from-mail" not in joined
    assert "TOOL -> add_event(withheld: read from mail)" in joined
