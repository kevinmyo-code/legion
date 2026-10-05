"""The household has a timezone (Kevin, 2026-10-05).

- `PATCH /api/households/me` sets it, owner only, refused in words; any member
  reads it.
- The Groceries same-day untick rule (ADR 0055) uses it first: household
  zone, then the client's `?today=`, then UTC.
- The web assistant may turn it into a CURRENT UTC offset when the browser
  sends none, and the zone's name never reaches the prompt (CLAUDE.md
  section 1).
"""

from __future__ import annotations

import re
from datetime import UTC, datetime

import pytest
from django.core.exceptions import ValidationError
from django.core.management import CommandError, call_command

from assistant.session import token_request_for
from checklists.models import ChecklistTick
from household.households import TIMEZONE_OWNER_ONLY
from household.models import DeviceToken, Household, HouseholdMember, User
from purchases import groceries
from purchases.models import Purchase
from tests.conftest import _client_for

pytestmark = pytest.mark.django_db

URL = "/api/households/me"
DAY = 20365  # 2025-10-04
IANA_ID = re.compile(r"\b[A-Z][a-z]+/[A-Z][A-Za-z_]+\b")


@pytest.fixture
def member(household_a):
    """A plain MEMBER (conftest's `_member` makes owners)."""
    user = User.objects.create_user(email="member@example.com", password="correct horse")
    HouseholdMember.objects.create(user=user, household=household_a, role=HouseholdMember.MEMBER)
    return user


@pytest.fixture
def member_client(member):
    return _client_for(member)


def _set_zone(household, zone):
    household.timezone = zone
    household.save(update_fields=["timezone"])


# =============================================================================
# The setting: read by any member, written by the owner
# =============================================================================


def test_unset_by_default_and_any_member_reads_it(member_client, household_a):
    assert member_client.get(URL).data["timezone"] is None
    _set_zone(household_a, "America/Chicago")
    assert member_client.get(URL).data["timezone"] == "America/Chicago"


def test_the_owner_sets_and_clears_it(auth_client, household_a):
    response = auth_client.patch(URL, {"timezone": "America/Chicago"}, format="json")
    assert response.status_code == 200, response.data
    assert response.data["timezone"] == "America/Chicago"
    household_a.refresh_from_db()
    assert household_a.timezone == "America/Chicago"
    assert household_a.name == response.data["name"], "the name is left as it was"

    response = auth_client.patch(URL, {"timezone": None}, format="json")
    assert response.status_code == 200, response.data
    household_a.refresh_from_db()
    assert household_a.timezone is None


def test_a_rename_leaves_the_timezone_alone(auth_client, household_a):
    _set_zone(household_a, "America/Chicago")
    response = auth_client.patch(URL, {"name": "The Wins"}, format="json")
    assert response.status_code == 200, response.data
    household_a.refresh_from_db()
    assert (household_a.name, household_a.timezone) == ("The Wins", "America/Chicago")


def test_a_member_is_refused_in_words_and_nothing_changes(member_client, household_a):
    response = member_client.patch(URL, {"timezone": "Asia/Tokyo"}, format="json")
    assert response.status_code == 403
    assert response.data["detail"] == TIMEZONE_OWNER_ONLY
    assert response.data["detail"] == (
        "Only the household owner can change the timezone. Nothing was changed."
    )
    household_a.refresh_from_db()
    assert household_a.timezone is None


def test_a_member_renaming_still_gets_the_rename_sentence(member_client):
    response = member_client.patch(URL, {"name": "Mine"}, format="json")
    assert response.status_code == 403
    assert "rename" in response.data["detail"]


def test_a_read_scoped_owner_token_cannot_set_it(household_user, household_a):
    from rest_framework.test import APIClient

    _token, raw = DeviceToken.issue(household_user, "dev", scope=DeviceToken.SCOPE_READ)
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Token {raw}")
    response = client.patch(URL, {"timezone": "America/Chicago"}, format="json")
    assert response.status_code == 403
    assert response.data["detail"].startswith("Nothing was written.")
    household_a.refresh_from_db()
    assert household_a.timezone is None


@pytest.mark.parametrize("zone", ["America/Houston", "Chicago", "utc+5", "", "CST6CDT/x"])
def test_an_unknown_zone_is_refused_in_words(auth_client, household_a, zone):
    response = auth_client.patch(URL, {"timezone": zone}, format="json")
    assert response.status_code == 400
    assert response.data["detail"].startswith("Nothing was changed.")
    household_a.refresh_from_db()
    assert household_a.timezone is None


def test_an_empty_patch_is_refused_in_words(auth_client):
    response = auth_client.patch(URL, {}, format="json")
    assert response.status_code == 400
    assert response.data["detail"] == "Nothing was changed. Send `name`, `timezone`, or both."


def test_the_model_refuses_an_unknown_zone(household_a):
    household_a.timezone = "Mars/Olympus_Mons"
    with pytest.raises(ValidationError):
        household_a.full_clean()


def test_the_command_sets_it_and_refuses_an_unknown_zone(household_a):
    call_command("set_household_timezone", "America/Chicago", "--household", str(household_a.id))
    household_a.refresh_from_db()
    assert household_a.timezone == "America/Chicago"
    with pytest.raises(CommandError, match="Nothing was changed"):
        call_command("set_household_timezone", "Chicago", "--household", str(household_a.id))
    household_a.refresh_from_db()
    assert household_a.timezone == "America/Chicago"


# =============================================================================
# The untick rule: household zone, then the client's `today`, then UTC
# =============================================================================


def _groceries_with_a_tick(client, text="soap", day=DAY):
    # The household's BUILT-IN Groceries list (system_key), the only list the hook
    # watches since Groceries became built in (2026-10-05). A user list merely named
    # "Groceries" no longer logs purchases.
    response = client.get("/api/checklists/")
    rows = response.data["results"] if isinstance(response.data, dict) else response.data
    checklist_id = next(row["id"] for row in rows if row.get("system_key") == "groceries")
    response = client.post(f"/api/checklists/{checklist_id}/items", {"text": text}, format="json")
    item_id = response.data["id"]
    response = client.post(
        f"/api/checklists/{checklist_id}/items/{item_id}/tick", {"day": day}, format="json"
    )
    assert response.status_code == 201, response.data
    return checklist_id, item_id


def _untick(client, checklist_id, item_id, day=DAY, today=None):
    suffix = f"?today={today}" if today is not None else ""
    response = client.delete(f"/api/checklists/{checklist_id}/items/{item_id}/tick/{day}{suffix}")
    assert response.status_code == 204
    assert ChecklistTick.objects.get(item_id=item_id).deleted_at is not None


def _at(monkeypatch, instant: datetime):
    monkeypatch.setattr(groceries, "now_utc", lambda: instant)


# 2025-10-04 23:30 in Chicago (CDT, UTC-5) is already 2025-10-05 in UTC.
LATE_EVENING_CHICAGO = datetime(2025, 10, 5, 4, 30, tzinfo=UTC)
NOON_CHICAGO_NEXT_DAY = datetime(2025, 10, 5, 17, 0, tzinfo=UTC)


def test_a_late_evening_untick_in_chicago_is_still_the_same_day(
    auth_client, household_a, monkeypatch
):
    _set_zone(household_a, "America/Chicago")
    _at(monkeypatch, LATE_EVENING_CHICAGO)
    checklist_id, item_id = _groceries_with_a_tick(auth_client)
    _untick(auth_client, checklist_id, item_id)
    assert Purchase.objects.get().deleted_at is not None


def test_without_a_zone_the_same_late_untick_falls_back_to_utc_and_keeps_it(
    auth_client, monkeypatch
):
    _at(monkeypatch, LATE_EVENING_CHICAGO)
    checklist_id, item_id = _groceries_with_a_tick(auth_client)
    _untick(auth_client, checklist_id, item_id)
    assert Purchase.objects.get().deleted_at is None, "UTC already reads the next day"


def test_the_household_zone_overrides_a_wrong_client_today(
    auth_client, household_a, monkeypatch
):
    _set_zone(household_a, "America/Chicago")
    _at(monkeypatch, LATE_EVENING_CHICAGO)
    checklist_id, item_id = _groceries_with_a_tick(auth_client)
    # The client claims tomorrow; the household's clock says it is still today.
    _untick(auth_client, checklist_id, item_id, today=DAY + 1)
    assert Purchase.objects.get().deleted_at is not None


def test_the_household_zone_overrides_a_client_today_that_is_too_early(
    auth_client, household_a, monkeypatch
):
    _set_zone(household_a, "America/Chicago")
    _at(monkeypatch, NOON_CHICAGO_NEXT_DAY)
    checklist_id, item_id = _groceries_with_a_tick(auth_client)
    # The client claims it is still the tick's day; in Chicago it is the next.
    _untick(auth_client, checklist_id, item_id, today=DAY)
    assert Purchase.objects.get().deleted_at is None


def test_unset_the_client_today_still_decides(auth_client, monkeypatch):
    _at(monkeypatch, NOON_CHICAGO_NEXT_DAY)  # UTC says the next day
    checklist_id, item_id = _groceries_with_a_tick(auth_client)
    _untick(auth_client, checklist_id, item_id, today=DAY)
    assert Purchase.objects.get().deleted_at is not None


def test_precedence_in_one_place(household_a, monkeypatch):
    _at(monkeypatch, LATE_EVENING_CHICAGO)
    assert groceries.untick_today(household_a.id, None) == DAY + 1, "UTC"
    assert groceries.untick_today(household_a.id, DAY - 3) == DAY - 3, "the client"
    _set_zone(household_a, "America/Chicago")
    assert groceries.untick_today(household_a.id, DAY - 3) == DAY, "the household"
    assert groceries.untick_today(household_a.id, None) == DAY


def test_a_zone_east_of_utc_is_already_tomorrow(household_a, monkeypatch):
    # 2025-10-04 20:00 UTC is 2025-10-05 05:00 in Tokyo.
    _at(monkeypatch, datetime(2025, 10, 4, 20, 0, tzinfo=UTC))
    _set_zone(household_a, "Asia/Tokyo")
    assert groceries.untick_today(household_a.id, DAY) == DAY + 1


# =============================================================================
# The assistant: an offset from the household zone, never the zone's name
# =============================================================================

OCTOBER = datetime(2026, 10, 5, 1, 31, tzinfo=UTC)
JANUARY = datetime(2026, 1, 15, 18, 0, tzinfo=UTC)


def _fresh(user):
    """The user re-read, so its cached `household` carries the zone just set."""
    return type(user).objects.get(pk=user.pk)


def _prompt(token_request) -> str:
    setup = token_request["bidiGenerateContentSetup"]
    return setup["systemInstruction"]["parts"][0]["text"]


@pytest.mark.parametrize(
    ("zone", "now", "words"),
    [
        ("America/Chicago", OCTOBER, "UTC-05:00"),
        ("America/Chicago", JANUARY, "UTC-06:00"),
        ("Asia/Kolkata", OCTOBER, "UTC+05:30"),
        ("Europe/London", JANUARY, "UTC+00:00"),
        ("America/Argentina/Buenos_Aires", OCTOBER, "UTC-03:00"),
    ],
)
def test_the_assembled_prompt_carries_the_offset_and_never_the_zone(
    household_user, household_a, zone, now, words
):
    _set_zone(household_a, zone)
    user = _fresh(household_user)
    request, _companion = token_request_for(user, now=now, utc_offset_minutes=None)
    prompt = _prompt(request)
    assert words in prompt
    assert "the household's clock" in prompt
    assert zone not in prompt
    # The CITY is what made the assistant talk about Chicago to a man in Houston.
    # (The region alone is not checked: Alfred's persona says "Never American".)
    city = zone.rsplit("/", 1)[-1]
    for spelling in {city, city.replace("_", " ")}:
        assert not re.search(rf"\b{re.escape(spelling)}\b", prompt), spelling
    assert not IANA_ID.search(prompt), "an IANA zone id"
    assert zone not in str(request), "nowhere in the token request either"


def test_the_browsers_offset_wins_over_the_household_zone(household_user, household_a):
    _set_zone(household_a, "Asia/Tokyo")
    request, _companion = token_request_for(
        _fresh(household_user), now=OCTOBER, utc_offset_minutes=-300
    )
    prompt = _prompt(request)
    assert "UTC-05:00" in prompt and "the person's clock" in prompt
    assert "UTC+09:00" not in prompt and "Tokyo" not in prompt


def test_no_zone_and_no_offset_is_still_unknown(household_user):
    request, _companion = token_request_for(household_user, now=OCTOBER, utc_offset_minutes=None)
    prompt = _prompt(request)
    assert "offset is unknown" in prompt
    assert not IANA_ID.search(prompt)


def test_a_zone_the_server_cannot_load_reads_as_unset(household_user, household_a):
    # Written past the validator, as a row from an older tz database might be.
    Household.objects.filter(pk=household_a.pk).update(timezone="Mars/Olympus_Mons")
    request, _companion = token_request_for(
        _fresh(household_user), now=OCTOBER, utc_offset_minutes=None
    )
    prompt = _prompt(request)
    assert "offset is unknown" in prompt
    assert "Mars" not in prompt
