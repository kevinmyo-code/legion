# ruff: noqa: E501 - the LIVE_AUDIT fixture is copied as the phone writes it, one line each
"""The daily memory review, engine half (memory-review tickets 05, 06, 07, 08, 11 and 15).

Kevin's rulings, 2026-11-09: the review runs on the Claude worker (the Alienware); R1 shadow
first, then only gated merges and past-date expiries apply on their own, corrections never; R2 the
Memory screen plus a recall flag; R3 exact duplicates merge by code; R4 a paraphrased reason plus
record ids, never a quote.

**Every example here is invented** (the repo is public). One made-up member, "Sam", with a made-up
pharmacy, street and book club. No model is ever called: the change lists below are recorded
answers, as if the helper had written them (ticket 11), so the gates run in the normal suite.
"""

from __future__ import annotations

import datetime
import logging

import pytest
from django.db import connection
from django.utils import timezone
from rest_framework.test import APIClient

from claude_session.models import (
    KIND_MEMORY_REVIEW,
    SCOPE_FULL,
    SCOPE_LISTED,
    ClaudeMachine,
    ClaudeTask,
)
from household.models import DeviceToken
from member_memory import review, service
from member_memory.models import (
    DECAY_DURABLE,
    DECAY_EPISODIC,
    SOURCE_CONSOLIDATION,
    SOURCE_MANUAL,
    SOURCE_PHONE,
    SOURCE_REVIEW,
    MemberMemory,
    MemoryReview,
    MemoryReviewChange,
)
from tests.conftest import _client_for, _member
from tests.test_member_memory_consolidate import _log

pytestmark = pytest.mark.django_db

BASE = "/api/member-memories/"
CONTEXT = f"{BASE}review/context"
CHANGES = f"{BASE}review/changes"
SENTINEL = "Zebrafinch"


@pytest.fixture
def shadow(settings):
    settings.LEGION_MEMORY_REVIEW = "shadow"


@pytest.fixture
def on(settings):
    settings.LEGION_MEMORY_REVIEW = "on"


def _row(user, household, text, *, days_ago=30, kind="fact", durable=True, source=SOURCE_PHONE,
         shared=False, importance=5):
    row = MemberMemory.objects.create(
        household=household, member=user, owner_user=None if shared else user, text=text,
        kind=kind, decay_class=DECAY_DURABLE if durable else DECAY_EPISODIC, source=source,
        importance=importance,
    )
    stamp = timezone.now() - datetime.timedelta(days=days_ago)
    MemberMemory.objects.filter(pk=row.pk).update(created_at=stamp, updated_at=stamp)
    row.refresh_from_db()
    return row


def _context(client):
    response = client.get(CONTEXT)
    assert response.status_code == 200, response.data
    return response.data


def _handle(context, text) -> str:
    for memory in context["memories"]:
        if memory["text"] == text:
            return memory["handle"]
    raise AssertionError("no handle for that text")


def _submit(client, context, changes, **extra):
    return client.post(
        CHANGES, {"context_id": context["context_id"], "changes": changes, **extra}, format="json"
    )


def _live(user) -> list[str]:
    return sorted(
        MemberMemory.objects.filter(member=user, superseded_at__isnull=True)
        .values_list("text", flat=True)
    )


def _ok_runs(user, household, n=review.SHADOW_RUNS_BEFORE_AUTO):
    for _ in range(n):
        MemoryReview.objects.create(
            household=household, member=user, owner_user=user, outcome="ok",
            submitted_at=timezone.now() - datetime.timedelta(days=3),
        )


SAM_SAYS_OAK_LANE = """\
11-08 10:00:00.000  1 2 I LIVE_AUDIT: session_start model=gemini-live async_set=none
11-08 10:00:01.000  1 2 I LIVE_AUDIT: user final=true text="I moved to Oak Lane last week."
11-08 10:00:01.500  1 2 I LIVE_AUDIT: assistant text="Noted."
11-08 10:00:02.000  1 2 I LIVE_AUDIT: turn_complete
11-08 10:00:03.000  1 2 I LIVE_AUDIT: user final=true text="Read my last email."
11-08 10:00:03.100  1 2 I LIVE_AUDIT: tool_call id=m1 name=read_mail async=false args={"index":0}
11-08 10:00:03.400  1 2 I LIVE_AUDIT: tool_result id=m1 name=read_mail success=true ms=300 scheduling=none sent=true response="[redacted: third-party content]"
11-08 10:00:03.600  1 2 I LIVE_AUDIT: assistant text="The Zebrafinch newsletter says hi."
11-08 10:00:04.000  1 2 I LIVE_AUDIT: turn_complete
11-08 10:00:09.000  1 2 I LIVE_AUDIT: session_end reason="stopped"
"""


# =============================================================================
# Switch and doors
# =============================================================================


def test_off_is_the_default_and_refuses_the_context(auth_client, household_user, household_a):
    _row(household_user, household_a, "Sam likes green tea.")
    refused = auth_client.get(CONTEXT)
    assert refused.status_code == 403 and "off" in refused.data["detail"]
    assert not MemoryReview.objects.exists()


def test_a_recruiter_and_the_work_laptop_are_refused(shadow, household_user):
    from tests.test_member_memory import _worker

    _token, _raw, laptop = _worker(household_user, "work laptop", SCOPE_LISTED)
    assert laptop.get(CONTEXT).status_code == 403
    assert laptop.get(CHANGES).status_code == 403


# =============================================================================
# Context
# =============================================================================


def test_the_context_has_handles_shared_context_and_filtered_words_only(
    shadow, auth_client, household_user, household_a
):
    _row(household_user, household_a, "Sam lives on Elm Road.")
    _row(household_user, household_a, "The household's pharmacy is Maple Pharmacy.", shared=True)
    _log(household_a, household_user, SAM_SAYS_OAK_LANE)
    context = _context(auth_client)
    assert [m["handle"] for m in context["memories"]] == ["m1"]
    assert "id" not in context["memories"][0]
    assert context["shared_context"] == [
        {"text": "The household's pharmacy is Maple Pharmacy.", "kind": "fact",
         "context_only": True}
    ]
    words = "\n".join(c["text"] for c in context["conversations"])
    assert "Oak Lane" in words
    assert SENTINEL not in words  # the mail turn never reaches the laptop
    assert context["conversations"][0]["label"] == "L1"
    stored = MemoryReview.objects.get()
    assert set(stored.handles) == {"m1"} and "text" not in str(stored.handles)


def test_an_old_conversation_and_another_members_rows_stay_out(
    shadow, auth_client, household_user, household_a
):
    mia = _member(household_a, "mia@example.com")
    _row(mia, household_a, "Mia's private note.")
    _log(household_a, household_user, SAM_SAYS_OAK_LANE, age=datetime.timedelta(days=9))
    context = _context(auth_client)
    assert context["memories"] == [] and context["conversations"] == []


# =============================================================================
# The gates (recorded answers, ticket 11's synthetic cases)
# =============================================================================


def test_exact_duplicates_merge_by_code_even_in_shadow(
    shadow, auth_client, household_user, household_a
):
    old = _row(household_user, household_a, "Sam likes green tea.", days_ago=40,
               source=SOURCE_CONSOLIDATION)
    _row(household_user, household_a, "sam likes green tea", days_ago=20, source=SOURCE_PHONE)
    context = _context(auth_client)
    assert context["exact_duplicates"] == [{"handle": "m1", "copies": 2, "handled_by": "code"}]
    answer = _submit(auth_client, context, []).data
    assert answer["code_merged"] == 1 and answer["mode"] == "shadow"
    kept = MemberMemory.objects.get(member=household_user, superseded_at__isnull=True)
    assert kept.source == SOURCE_PHONE
    old.refresh_from_db()
    assert old.retired_by_id == kept.pk and old.retired_reason == "merged"


def test_a_merge_that_adds_no_word_applies_when_on(on, auth_client, household_user, household_a):
    _ok_runs(household_user, household_a)
    a = _row(household_user, household_a, "Sam's dentist is Dr Reed.", source=SOURCE_MANUAL)
    _row(household_user, household_a, "Dr Reed is Sam's dentist on Elm Road.",
         source=SOURCE_CONSOLIDATION)
    context = _context(auth_client)
    answer = _submit(auth_client, context, [{
        "action": "merge", "handles": ["m1", "m2"],
        "new_text": "Sam's dentist is Dr Reed on Elm Road.", "kind": "fact", "importance": 6,
        "durable": True, "reason": "Two rows name the same dentist.", "confidence": "high",
    }]).data
    assert answer["counts"]["applied"] == 1, answer
    assert _live(household_user) == ["Sam's dentist is Dr Reed on Elm Road."]
    merged = MemberMemory.objects.get(member=household_user, superseded_at__isnull=True)
    assert merged.source == SOURCE_MANUAL  # the strongest source survives the merge
    a.refresh_from_db()
    assert a.retired_by_id == merged.pk


def test_a_merge_that_adds_a_word_is_a_proposal(on, auth_client, household_user, household_a):
    _ok_runs(household_user, household_a)
    _row(household_user, household_a, "Sam enjoys hiking.")
    _row(household_user, household_a, "Sam thought one trail was too crowded.")
    context = _context(auth_client)
    answer = _submit(auth_client, context, [{
        "action": "merge", "handles": ["m1", "m2"],
        "new_text": "Sam enjoys hiking but dislikes crowded trails.",
        "reason": "Both about hiking.", "confidence": "high",
    }]).data
    assert answer["counts"] == {"applied": 0, "proposed": 1, "refused": 0, "stale": 0}
    assert "adds" in answer["verdicts"][0]["gate"]
    assert len(_live(household_user)) == 2


def test_shadow_turns_every_gated_change_into_a_proposal(
    shadow, auth_client, household_user, household_a
):
    _row(household_user, household_a, "Sam's dentist is Dr Reed.")
    _row(household_user, household_a, "Dr Reed is Sam's dentist.")
    context = _context(auth_client)
    answer = _submit(auth_client, context, [{
        "action": "merge", "handles": ["m1", "m2"], "new_text": "Sam's dentist is Dr Reed.",
        "reason": "Same dentist.", "confidence": "high",
    }]).data
    assert answer["counts"]["proposed"] == 1 and answer["verdicts"][0]["gate"] == "passed"
    assert len(_live(household_user)) == 2


def test_on_still_behaves_as_shadow_before_fourteen_runs(
    on, auth_client, household_user, household_a
):
    _ok_runs(household_user, household_a, n=3)
    _row(household_user, household_a, "Sam's dentist is Dr Reed.")
    _row(household_user, household_a, "Dr Reed is Sam's dentist.")
    context = _context(auth_client)
    assert context["mode"] == "shadow" and "3 so far" in context["mode_note"]


def test_expiry_needs_a_printed_past_date(on, auth_client, household_user, household_a):
    _ok_runs(household_user, household_a)
    _row(household_user, household_a, "Sam has a dentist appointment on August 3.",
         kind="event", days_ago=120)
    _row(household_user, household_a, "Sam waters the plants on Friday.", kind="event",
         days_ago=20)
    _row(household_user, household_a, "The book club meets on December 27.", kind="event",
         days_ago=5)
    context = _context(auth_client)
    dentist = _handle(context, "Sam has a dentist appointment on August 3.")
    plants = _handle(context, "Sam waters the plants on Friday.")
    club = _handle(context, "The book club meets on December 27.")
    year = (timezone.now() - datetime.timedelta(days=120)).date()
    august = datetime.date(year.year if year <= datetime.date(year.year, 8, 3) else year.year + 1,
                           8, 3)
    friday = timezone.localdate() - datetime.timedelta(days=10)
    future = timezone.localdate() + datetime.timedelta(days=20)
    answer = _submit(auth_client, context, [
        {"action": "expire", "handle": dentist, "event_date": august.isoformat(),
         "reason": "The appointment has passed.", "confidence": "high"},
        {"action": "expire", "handle": plants, "event_date": friday.isoformat(),
         "reason": "Last Friday has passed.", "confidence": "high"},
        {"action": "expire", "handle": club, "event_date": future.isoformat(),
         "reason": "x", "confidence": "high"},
    ]).data
    statuses = [v["status"] for v in answer["verdicts"]]
    if august <= timezone.localdate() - datetime.timedelta(days=2):
        assert statuses[0] == "applied"
    assert statuses[1] == "pending" and "relative" in answer["verdicts"][1]["gate"]
    assert statuses[2] == "refused"
    assert "The book club meets on December 27." in _live(household_user)


def test_printed_dates_never_guess_an_earlier_year():
    written = datetime.date(2026, 12, 20)
    assert review.printed_dates("dinner on Jan 5", written) == [datetime.date(2027, 1, 5)]
    assert review.printed_dates("due 2026-08-21", written) == [datetime.date(2026, 8, 21)]
    assert review.printed_dates("on 21st of August, 2025", written) == [datetime.date(2025, 8, 21)]
    assert review.printed_dates("on Friday", written) == []


def test_a_correction_is_never_auto_even_when_the_transcript_supports_it(
    on, auth_client, household_user, household_a
):
    _ok_runs(household_user, household_a)
    _row(household_user, household_a, "Sam lives on Elm Road.", days_ago=30)
    _log(household_a, household_user, SAM_SAYS_OAK_LANE)
    context = _context(auth_client)
    answer = _submit(auth_client, context, [
        {"action": "correct", "handle": "m1", "new_text": "Sam lives on Oak Lane.",
         "evidence": ["L1"], "reason": "Sam said they moved.", "confidence": "high"},
    ]).data
    assert answer["verdicts"][0]["status"] == "pending"
    assert answer["verdicts"][0]["gate"] == "passed"
    change = MemoryReviewChange.objects.get(action="correct")
    assert change.evidence_logs and change.reason == "Sam said they moved."


def test_a_correction_the_transcript_does_not_support_says_so(
    shadow, auth_client, household_user, household_a
):
    _row(household_user, household_a, "Sam lives on Elm Road.", days_ago=30)
    _log(household_a, household_user, SAM_SAYS_OAK_LANE)
    context = _context(auth_client)
    answer = _submit(auth_client, context, [
        {"action": "correct", "handle": "m1", "new_text": "Sam lives on Birch Court.",
         "evidence": ["L1"], "reason": "Moved.", "confidence": "high"},
    ]).data
    assert "do not appear" in answer["verdicts"][0]["gate"]


def test_retire_and_durable_to_episodic_are_always_proposals(
    on, auth_client, household_user, household_a
):
    _ok_runs(household_user, household_a)
    _row(household_user, household_a, "The assistant cannot see the step count.")
    _row(household_user, household_a, "Sam's favourite tea is green tea.")
    context = _context(auth_client)
    answer = _submit(auth_client, context, [
        {"action": "retire", "handle": "m1", "reason": "About the app.", "confidence": "high"},
        {"action": "relabel", "handle": "m2", "kind": "preference", "durable": False,
         "reason": "x", "confidence": "high"},
    ]).data
    assert [v["status"] for v in answer["verdicts"]] == ["pending", "pending"]


def test_an_unknown_handle_refuses_the_whole_change(shadow, auth_client, household_user,
                                                   household_a):
    _row(household_user, household_a, "Sam likes green tea.")
    context = _context(auth_client)
    answer = _submit(auth_client, context, [
        {"action": "merge", "handles": ["m1", "m9"], "new_text": "Sam likes green tea.",
         "reason": "x"},
        {"action": "explode", "handle": "m1"},
    ]).data
    assert answer["counts"]["refused"] == 2
    assert MemoryReviewChange.objects.count() == 0


def test_a_row_edited_after_the_context_is_stale(shadow, auth_client, household_user,
                                                 household_a):
    row = _row(household_user, household_a, "Sam likes green tea.")
    _row(household_user, household_a, "Sam likes green tea a lot.")
    context = _context(auth_client)
    auth_client.patch(f"{BASE}{row.id}", {"importance": 8}, format="json")
    answer = _submit(auth_client, context, [
        {"action": "merge", "handles": ["m1", "m2"], "new_text": "Sam likes green tea a lot.",
         "reason": "x"},
    ]).data
    assert answer["counts"]["stale"] == 1


def test_the_cap_holds_at_ten(on, auth_client, household_user, household_a):
    _ok_runs(household_user, household_a)
    for i in range(11):
        _row(household_user, household_a, f"Sam has a call on 2026-01-{i + 1:02d}.", kind="event")
    context = _context(auth_client)
    changes = [
        {"action": "expire", "handle": m["handle"], "event_date": m["text"][-11:-1],
         "reason": "Passed.", "confidence": "high"}
        for m in context["memories"]
    ]
    answer = _submit(auth_client, context, changes).data
    assert answer["counts"]["applied"] == 10 and answer["counts"]["proposed"] == 1


def test_dry_run_writes_nothing_and_a_repeat_changes_nothing(
    shadow, auth_client, household_user, household_a
):
    _row(household_user, household_a, "Sam likes green tea.")
    _row(household_user, household_a, "Sam likes green tea.")
    context = _context(auth_client)
    dry = _submit(auth_client, context, [], dry_run=True).data
    assert dry["dry_run"] is True and dry["code_merged"] == 1
    assert len(_live(household_user)) == 2 and not MemoryReviewChange.objects.exists()
    first = _submit(auth_client, context, []).data
    again = _submit(auth_client, context, []).data
    assert first == again and MemoryReviewChange.objects.count() == 1


def test_a_context_older_than_an_hour_is_refused(shadow, auth_client, household_user,
                                                 household_a):
    _row(household_user, household_a, "Sam likes green tea.")
    context = _context(auth_client)
    MemoryReview.objects.update(context_at=timezone.now() - datetime.timedelta(hours=2))
    assert _submit(auth_client, context, []).status_code == 410
    assert MemoryReview.objects.get().outcome == "failed"


def test_another_member_cannot_submit_or_see_the_changes(shadow, auth_client, household_user,
                                                         household_a, household_b):
    _row(household_user, household_a, "Sam likes green tea.")
    _row(household_user, household_a, "Sam enjoys green tea.")
    context = _context(auth_client)
    _submit(auth_client, context, [{"action": "merge", "handles": ["m1", "m2"],
                                    "new_text": "Sam likes green tea.", "reason": "x"}])
    mia = _client_for(_member(household_a, "mia@example.com"))
    assert _submit(mia, context, []).status_code == 404
    assert mia.get(CHANGES).data["results"] == []
    change = MemoryReviewChange.objects.get()
    assert mia.post(f"{CHANGES}/{change.id}/accept").status_code == 404
    other = _client_for(_member(household_b, "lee@example.com"))
    assert other.post(f"{CHANGES}/{change.id}/dismiss").status_code == 404


def test_the_hourly_job_holding_the_lock_refuses_a_submit(shadow, auth_client, household_user,
                                                          household_a):
    from ingest.jobs import lock_key
    from ingest.models import Source

    _row(household_user, household_a, "Sam likes green tea.")
    context = _context(auth_client)
    other = connection.copy()
    try:
        with other.cursor() as cursor:
            cursor.execute("select pg_advisory_lock(%s)", [lock_key(Source.MEMORY, household_a.pk)])
        refused = _submit(auth_client, context, [])
        assert refused.status_code == 409 and "hourly" in refused.data["detail"]
    finally:
        other.close()
    assert _submit(auth_client, context, []).status_code == 200


def test_the_views_log_no_memory_or_transcript_text(shadow, auth_client, household_user,
                                                     household_a, caplog):
    _row(household_user, household_a, f"Sam's cat is called {SENTINEL}.")
    _row(household_user, household_a, f"Sam's cat is called {SENTINEL}.")
    caplog.set_level(logging.DEBUG)
    context = _context(auth_client)
    _submit(auth_client, context, [{"action": "retire", "handle": "m1", "reason": SENTINEL}])
    auth_client.get(CHANGES)
    assert SENTINEL not in caplog.text


# =============================================================================
# Kevin's answers (ticket 08) and forget follows merges (ticket 05)
# =============================================================================


def _proposed_merge(client, user, household):
    a = _row(user, household, "Sam's pharmacy is Maple Pharmacy.")
    b = _row(user, household, "Maple Pharmacy is Sam's pharmacy.")
    context = _context(client)
    _submit(client, context, [{"action": "merge", "handles": ["m1", "m2"],
                               "new_text": "Sam's pharmacy is Maple Pharmacy.",
                               "reason": "Same pharmacy.", "confidence": "high"}])
    return a, b, MemoryReviewChange.objects.get(status="pending")


def test_accept_applies_what_the_proposal_said_and_revert_restores(
    shadow, auth_client, household_user, household_a
):
    a, b, change = _proposed_merge(auth_client, household_user, household_a)
    listed = auth_client.get(CHANGES).data["results"]
    assert listed[0]["id"] == change.id and len(listed[0]["before"]) == 2
    accepted = auth_client.post(f"{CHANGES}/{change.id}/accept")
    assert accepted.status_code == 200, accepted.data
    assert _live(household_user) == ["Sam's pharmacy is Maple Pharmacy."]
    merged = MemberMemory.objects.get(member=household_user, superseded_at__isnull=True)
    rows = auth_client.get(BASE, {"include_superseded": "true"}).data["results"]
    retired = [r for r in rows if r["retired_reason"] == "merged"]
    assert len(retired) == 2 and retired[0]["retired_change"] == change.id
    undone = auth_client.post(f"{CHANGES}/{change.id}/revert")
    assert undone.status_code == 200 and undone.data["restored"] == 2
    assert not MemberMemory.objects.filter(pk=merged.pk).exists()
    assert len(_live(household_user)) == 2


def test_accept_after_a_forget_is_409_and_stale(shadow, auth_client, household_user,
                                                household_a):
    a, _b, change = _proposed_merge(auth_client, household_user, household_a)
    assert auth_client.delete(f"{BASE}{a.id}").status_code == 204
    refused = auth_client.post(f"{CHANGES}/{change.id}/accept")
    assert refused.status_code == 409 and "changed since the review" in refused.data["detail"]
    change.refresh_from_db()
    assert change.status == "stale"


def test_dismiss_changes_nothing(shadow, auth_client, household_user, household_a):
    _a, _b, change = _proposed_merge(auth_client, household_user, household_a)
    assert auth_client.post(f"{CHANGES}/{change.id}/dismiss").status_code == 200
    assert len(_live(household_user)) == 2
    assert auth_client.post(f"{CHANGES}/{change.id}/accept").status_code == 409


def test_accept_all_takes_a_cluster(shadow, auth_client, household_user, household_a):
    _row(household_user, household_a, "Sam's pharmacy is Maple Pharmacy.")
    _row(household_user, household_a, "Maple Pharmacy is Sam's pharmacy.")
    _row(household_user, household_a, "The assistant cannot see the step count.")
    context = _context(auth_client)
    pharmacy = [m["handle"] for m in context["memories"] if "Pharmacy" in m["text"]]
    _submit(auth_client, context, [
        {"action": "merge", "handles": pharmacy, "new_text": "Sam's pharmacy is Maple Pharmacy.",
         "reason": "x"},
        {"action": "retire", "handle": _handle(context, "The assistant cannot see the step count."),
         "reason": "x"},
    ])
    merge = MemoryReviewChange.objects.get(action="merge")
    cluster = f"{merge.review_id}.{merge.cluster}"
    done = auth_client.post(f"{BASE}review/clusters/{cluster}/accept")
    assert done.status_code == 200 and done.data["accepted"] == 1
    assert MemoryReviewChange.objects.get(action="retire").status == "pending"


def test_the_recall_flag_appears_only_on_rows_in_a_pending_change(
    shadow, auth_client, household_user, household_a
):
    _proposed_merge(auth_client, household_user, household_a)
    other = _row(household_user, household_a, "Sam's car is a blue hatchback.")
    recalled = auth_client.post(f"{BASE}recall", {"query": "pharmacy"}, format="json").data
    flagged = [r for r in recalled["results"] if r["pending_review"]]
    assert flagged and flagged[0]["pending_review"]["action"] == "merge"
    car = auth_client.post(f"{BASE}recall", {"query": "hatchback"}, format="json").data
    assert [r for r in car["results"] if r["id"] == str(other.id)][0]["pending_review"] is None


def test_the_engine_recall_tool_says_the_flag(shadow, household_user, household_a):
    from engine_mcp.tools import _say_memory

    row = {"id": "x", "created_at": "2026-11-01", "kind": "fact", "shared": False,
           "about_you": True, "source": "phone", "text": "Sam's pharmacy is Maple Pharmacy.",
           "pending_review": {"change_id": 1, "action": "merge", "after": "one row"}}
    assert "a review suggests: one row; confirm on the Memory screen" in _say_memory(row)
    row["pending_review"] = None
    assert "review" not in _say_memory(row)


def test_forgetting_a_merged_row_takes_its_duplicates_and_an_old_one_only_itself(
    on, auth_client, household_user, household_a
):
    _ok_runs(household_user, household_a)
    a = _row(household_user, household_a, "Sam's dentist is Dr Reed.")
    b = _row(household_user, household_a, "Dr Reed is Sam's dentist.")
    context = _context(auth_client)
    _submit(auth_client, context, [{"action": "merge", "handles": ["m1", "m2"],
                                    "new_text": "Sam's dentist is Dr Reed.", "reason": "x",
                                    "confidence": "high"}])
    merged = MemberMemory.objects.get(member=household_user, superseded_at__isnull=True)
    assert {str(pk) for pk in service._chain(a)} == {str(a.pk)}
    assert auth_client.delete(f"{BASE}{merged.id}").status_code == 204
    assert not MemberMemory.objects.filter(pk__in=[a.pk, b.pk, merged.pk]).exists()


def test_a_correction_accepted_writes_a_review_row(shadow, auth_client, household_user,
                                                  household_a):
    old = _row(household_user, household_a, "Sam lives on Elm Road.", days_ago=30)
    _log(household_a, household_user, SAM_SAYS_OAK_LANE)
    context = _context(auth_client)
    _submit(auth_client, context, [{"action": "correct", "handle": "m1",
                                    "new_text": "Sam lives on Oak Lane.", "evidence": ["L1"],
                                    "reason": "Moved."}])
    change = MemoryReviewChange.objects.get()
    assert auth_client.post(f"{CHANGES}/{change.id}/accept").status_code == 200
    new = MemberMemory.objects.get(member=household_user, superseded_at__isnull=True)
    assert new.source == SOURCE_REVIEW and new.supersedes_id == old.pk
    old.refresh_from_db()
    assert old.retired_reason == "contradicted"


# =============================================================================
# Catch-up (ticket 15)
# =============================================================================


def _alienware(user, idle=900, scope=SCOPE_FULL, name="Alienware"):
    _token, raw = DeviceToken.issue(user, f"claude-session-{name}")
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Token {raw}")
    body = {"name": name, "scope": scope, "state": "idle", "progress": {}}
    if idle is not None:
        body["input_idle_s"] = idle
        body["on_mains"] = True
    assert client.post("/api/claude/machines/heartbeat/", body, format="json").status_code == 200
    return client


def _claim(client, name="Alienware"):
    return client.post("/api/claude/tasks/claim/", {"name": name}, format="json")


def test_an_idle_alienware_is_minted_exactly_one_review(shadow, household_user, household_a):
    _row(household_user, household_a, "Sam likes green tea.")
    worker = _alienware(household_user)
    first = _claim(worker)
    assert first.status_code == 200
    assert [t["kind"] for t in first.data] == [KIND_MEMORY_REVIEW]
    assert first.data[0]["origin"] == "schedule" and "memory" in first.data[0]["request"]
    assert _claim(worker).status_code == 204
    assert ClaudeTask.objects.filter(kind=KIND_MEMORY_REVIEW).count() == 1


def test_nothing_is_minted_while_off(household_user, household_a):
    _row(household_user, household_a, "Sam likes green tea.")
    assert _claim(_alienware(household_user)).status_code == 204


def test_not_minted_for_the_work_laptop_or_with_no_memories(shadow, household_user, household_a):
    assert _claim(_alienware(household_user)).status_code == 204  # no private rows yet
    _row(household_user, household_a, "Sam likes green tea.")
    laptop = _alienware(household_user, scope=SCOPE_LISTED, name="work laptop")
    assert _claim(laptop, "work laptop").status_code == 204


def test_input_in_use_waits_unless_overdue(shadow, household_user, household_a):
    _row(household_user, household_a, "Sam likes green tea.")
    MemoryReview.objects.create(household=household_a, member=household_user,
                                owner_user=household_user, outcome="ok",
                                submitted_at=timezone.now() - datetime.timedelta(hours=30))
    busy = _alienware(household_user, idle=30)
    assert _claim(busy).status_code == 204
    MemoryReview.objects.update(submitted_at=timezone.now() - datetime.timedelta(hours=40))
    assert _claim(busy).status_code == 200


def test_not_due_within_24_hours_or_6_hours_after_a_failure(shadow, household_user, household_a):
    _row(household_user, household_a, "Sam likes green tea.")
    run = MemoryReview.objects.create(household=household_a, member=household_user,
                                      owner_user=household_user, outcome="ok",
                                      submitted_at=timezone.now() - datetime.timedelta(hours=5))
    worker = _alienware(household_user)
    assert _claim(worker).status_code == 204
    MemoryReview.objects.filter(pk=run.pk).update(
        submitted_at=timezone.now() - datetime.timedelta(days=3)
    )
    MemoryReview.objects.create(household=household_a, member=household_user,
                                owner_user=household_user, outcome="failed")
    assert _claim(worker).status_code == 204


def test_a_machine_running_a_task_is_not_minted_one(shadow, household_user, household_a):
    _row(household_user, household_a, "Sam likes green tea.")
    worker = _alienware(household_user)
    machine = ClaudeMachine.objects.get(name="Alienware")
    ClaudeTask.objects.create(household=household_a, user=household_user, machine=machine,
                              origin="named", request="something", status="running",
                              started_at=timezone.now())
    assert _claim(worker).status_code == 204


def test_another_members_machine_is_never_minted_ones_for_this_member(
    shadow, household_user, household_a
):
    mia = _member(household_a, "mia@example.com")
    _row(household_user, household_a, "Sam likes green tea.")
    worker = _alienware(mia)
    assert _claim(worker).status_code == 204


def test_run_now_queues_one_and_refuses_a_second(shadow, auth_client, household_user,
                                                 household_a):
    _row(household_user, household_a, "Sam likes green tea.")
    _alienware(household_user, idle=0)
    queued = auth_client.post(f"{BASE}review/run")
    assert queued.status_code == 200, queued.data
    assert auth_client.post(f"{BASE}review/run").status_code == 409
