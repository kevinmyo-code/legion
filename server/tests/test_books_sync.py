"""Books ticket 08: reading position and bookmarks synced through the engine.

One test per line of the ticket's Tests section where it can be, so the
ticket's verification maps onto names: upsert and newest-wins, per-user
isolation inside one household, tombstone round-trip, `since` paging. The
tenancy leak test for both tables lives in `tests/test_tenancy.py` beside
every other table's.
"""

from __future__ import annotations

import datetime
import uuid

import pytest

from books.models import BookBookmark, BookPosition
from tests.conftest import _client_for, _member

pytestmark = pytest.mark.django_db

BOOK = "ab" * 32
OTHER_BOOK = "cd" * 32
EPOCH = "1970-01-01T00:00:00Z"

POSITION_KEYS = {
    "book_sha256",
    "variant",
    "locator",
    "total_progression",
    "chapter_label",
    "recorded_at",
    "device",
    "updated_at",
}
BOOKMARK_KEYS = {
    "id",
    "book_sha256",
    "variant",
    "locator",
    "total_progression",
    "label",
    "note",
    "created_at",
    "deleted_at",
    "updated_at",
}


def _locator(progression: float, href: str = "chapter-3.xhtml") -> dict:
    return {
        "href": href,
        "type": "application/xhtml+xml",
        "title": "Chapter 3",
        "locations": {"progression": 0.5, "totalProgression": progression, "position": 40},
    }


def _position(progression: float, at: str, device: str = "A25", **extra) -> dict:
    return {
        "locator": _locator(progression),
        "total_progression": progression,
        "chapter_label": "Chapter 3",
        "recorded_at": at,
        "device": device,
        **extra,
    }


def _put_position(client, body, book=BOOK, variant="original"):
    return client.put(f"/api/books/positions/{book}/{variant}", body, format="json")


def _bookmark(book=BOOK, variant="resizable", progression=0.25, **extra) -> dict:
    return {
        "book_sha256": book,
        "variant": variant,
        "locator": _locator(progression),
        "total_progression": progression,
        "label": "The good bit",
        "note": None,
        **extra,
    }


def _put_bookmark(client, bookmark_id, body):
    return client.put(f"/api/books/bookmarks/{bookmark_id}", body, format="json")


@pytest.fixture
def mia(household_a):
    return _member(household_a, "mia@example.com")


@pytest.fixture
def mia_client(mia):
    return _client_for(mia)


# =============================================================================
# Upsert and newest wins
# =============================================================================


def test_a_position_is_created_and_listed_with_exactly_the_contract_keys(auth_client):
    made = _put_position(auth_client, _position(0.43, "2026-10-23T10:00:00Z"))
    assert made.status_code == 200, made.data
    assert set(made.data) == POSITION_KEYS, "no `kept` when the write was stored"
    assert made.data["book_sha256"] == BOOK
    assert made.data["variant"] == "original"
    assert made.data["total_progression"] == 0.43
    assert made.data["chapter_label"] == "Chapter 3"
    assert made.data["device"] == "A25"
    assert made.data["recorded_at"] == "2026-10-23T10:00:00Z"
    assert made.data["locator"] == _locator(0.43)

    listed = auth_client.get("/api/books/positions")
    assert listed.status_code == 200
    assert isinstance(listed.data, list), "a bare array, no envelope"
    assert [set(row) for row in listed.data] == [POSITION_KEYS]
    assert listed.data[0]["updated_at"] == made.data["updated_at"]


def test_a_newer_write_replaces_the_stored_position(auth_client):
    first = _put_position(auth_client, _position(0.10, "2026-10-23T10:00:00Z"))
    second = _put_position(auth_client, _position(0.20, "2026-10-23T11:00:00Z", device="web"))
    assert second.status_code == 200
    assert "kept" not in second.data
    assert second.data["total_progression"] == 0.20
    assert second.data["device"] == "web"
    assert second.data["updated_at"] > first.data["updated_at"]
    assert BookPosition.objects.count() == 1


def test_an_older_write_is_kept_out_and_answered_with_the_stored_row(auth_client):
    newer = _put_position(auth_client, _position(0.60, "2026-10-23T12:00:00Z", device="web"))
    older = _put_position(auth_client, _position(0.30, "2026-10-23T09:00:00Z", device="A25"))
    assert older.status_code == 200
    assert older.data["kept"] == "newer"
    assert set(older.data) == POSITION_KEYS | {"kept"}
    assert older.data["total_progression"] == 0.60
    assert older.data["device"] == "web"
    # Nothing was written, so the cursor did not move either.
    assert older.data["updated_at"] == newer.data["updated_at"]
    stored = BookPosition.objects.get()
    assert stored.total_progression == 0.60
    assert stored.device == "web"


def test_the_same_recorded_at_is_applied_so_a_retry_lands(auth_client):
    _put_position(auth_client, _position(0.50, "2026-10-23T12:00:00Z"))
    again = _put_position(auth_client, _position(0.51, "2026-10-23T12:00:00Z"))
    assert "kept" not in again.data
    assert again.data["total_progression"] == 0.51


def test_variants_of_one_book_are_separate_positions(auth_client):
    _put_position(auth_client, _position(0.1, "2026-10-23T10:00:00Z"), variant="original")
    _put_position(auth_client, _position(0.9, "2026-10-23T10:00:00Z"), variant="resizable")
    rows = {row["variant"]: row for row in auth_client.get("/api/books/positions").data}
    assert rows["original"]["total_progression"] == 0.1
    assert rows["resizable"]["total_progression"] == 0.9


def test_an_upper_case_hash_meets_the_lower_case_row(auth_client):
    _put_position(auth_client, _position(0.1, "2026-10-23T10:00:00Z"), book=BOOK.upper())
    again = _put_position(auth_client, _position(0.2, "2026-10-23T11:00:00Z"), book=BOOK)
    assert again.data["book_sha256"] == BOOK
    assert BookPosition.objects.count() == 1


def test_a_position_needs_only_progression_and_recorded_at(auth_client):
    made = _put_position(
        auth_client, {"total_progression": 0.5, "recorded_at": "2026-10-23T10:00:00Z"}
    )
    assert made.status_code == 200, made.data
    assert made.data["locator"] is None
    assert made.data["chapter_label"] is None
    assert made.data["device"] == ""


@pytest.mark.parametrize(
    ("book", "variant", "body", "words"),
    [
        ("abc", "original", None, "not a book hash"),
        ("zz" * 32, "original", None, "not a book hash"),
        (BOOK, "pdf", None, "`original` or `resizable`"),
        (BOOK, "original", {"total_progression": 1.5}, "from 0 to 1"),
        (BOOK, "original", {"total_progression": -0.01}, "from 0 to 1"),
        (BOOK, "original", {"total_progression": "most"}, "from 0 to 1"),
        (BOOK, "original", {"total_progression": True}, "from 0 to 1"),
        (BOOK, "original", {"total_progression": None}, "from 0 to 1"),
        (BOOK, "original", {"recorded_at": None}, "recorded_at"),
        (BOOK, "original", {"recorded_at": "yesterday"}, "recorded_at"),
    ],
)
def test_a_bad_position_is_refused_in_words_and_nothing_is_written(
    auth_client, book, variant, body, words
):
    payload = _position(0.4, "2026-10-23T10:00:00Z")
    payload.update(body or {})
    refused = _put_position(auth_client, payload, book=book, variant=variant)
    assert refused.status_code == 400, refused.data
    assert set(refused.data) == {"detail"}
    assert refused.data["detail"].startswith("Nothing was saved.")
    assert words in refused.data["detail"]
    assert BookPosition.objects.count() == 0


def test_a_progression_missing_from_the_body_is_refused(auth_client):
    refused = _put_position(auth_client, {"recorded_at": "2026-10-23T10:00:00Z"})
    assert refused.status_code == 400
    assert "from 0 to 1" in refused.data["detail"]


def test_book_text_in_a_locator_is_never_stored(auth_client):
    text = {"before": "It was", "highlight": "the best", "after": "of times"}
    locator = {**_locator(0.4), "text": text}
    made = _put_position(
        auth_client, {**_position(0.4, "2026-10-23T10:00:00Z"), "locator": locator}
    )
    assert made.status_code == 200
    assert "text" not in made.data["locator"]
    assert "text" not in BookPosition.objects.get().locator
    bookmark = _put_bookmark(auth_client, uuid.uuid4(), _bookmark(locator=locator))
    assert bookmark.status_code == 200
    assert "text" not in bookmark.data["locator"]
    assert "text" not in BookBookmark.objects.get().locator


def test_an_oversized_locator_is_refused(auth_client):
    locator = {**_locator(0.4), "junk": "x" * (17 * 1024)}
    refused = _put_position(
        auth_client, {**_position(0.4, "2026-10-23T10:00:00Z"), "locator": locator}
    )
    assert refused.status_code == 400
    assert "KiB" in refused.data["detail"]


def test_a_page_index_locator_is_stored_as_sent(auth_client):
    made = _put_position(
        auth_client, {**_position(0.4, "2026-10-23T10:00:00Z"), "locator": {"page": 12}}
    )
    assert made.data["locator"] == {"page": 12}


# =============================================================================
# Per-user isolation inside one household
# =============================================================================


def test_another_member_never_sees_or_moves_my_position(auth_client, mia_client):
    _put_position(auth_client, _position(0.70, "2026-10-23T10:00:00Z"))
    assert mia_client.get(f"/api/books/positions?since={EPOCH}").data == []

    # Mia reading the same file is her own row, even with a newer clock, and
    # she can be behind Kevin without either one moving the other.
    hers = _put_position(mia_client, _position(0.05, "2026-10-23T12:00:00Z", device="web"))
    assert hers.status_code == 200
    assert "kept" not in hers.data
    assert hers.data["total_progression"] == 0.05
    assert BookPosition.objects.count() == 2
    mine = auth_client.get("/api/books/positions").data
    assert [row["total_progression"] for row in mine] == [0.70]
    assert [row["total_progression"] for row in mia_client.get("/api/books/positions").data] == [
        0.05
    ]


def test_another_member_never_sees_or_touches_my_bookmark(auth_client, mia_client):
    bookmark_id = uuid.uuid4()
    made = _put_bookmark(auth_client, bookmark_id, _bookmark(note="mine"))
    assert made.status_code == 200
    assert mia_client.get(f"/api/books/bookmarks?since={EPOCH}").data == []

    taken = _put_bookmark(mia_client, bookmark_id, _bookmark(note="hers"))
    assert taken.status_code == 409
    assert taken.data["detail"].startswith("Nothing was saved.")
    gone = mia_client.delete(f"/api/books/bookmarks/{bookmark_id}")
    assert gone.status_code == 404
    assert gone.data["detail"].startswith("Nothing was deleted.")

    stored = BookBookmark.objects.get()
    assert stored.note == "mine"
    assert stored.deleted_at is None
    assert auth_client.get("/api/books/bookmarks").data[0]["note"] == "mine"


# =============================================================================
# Bookmarks: create, update, tombstone round-trip
# =============================================================================


def test_a_bookmark_is_created_with_the_readers_id_and_exactly_the_contract_keys(auth_client):
    bookmark_id = uuid.uuid4()
    made = _put_bookmark(
        auth_client, bookmark_id, _bookmark(created_at="2026-10-20T08:00:00Z", note="Reread")
    )
    assert made.status_code == 200, made.data
    assert set(made.data) == BOOKMARK_KEYS
    assert made.data["id"] == str(bookmark_id)
    assert made.data["created_at"] == "2026-10-20T08:00:00Z"
    assert made.data["deleted_at"] is None
    assert made.data["note"] == "Reread"
    assert made.data["variant"] == "resizable"


def test_a_bookmark_without_created_at_is_stamped_by_the_server(auth_client):
    made = _put_bookmark(auth_client, uuid.uuid4(), _bookmark())
    assert made.status_code == 200
    assert made.data["created_at"] is not None


def test_an_update_changes_the_bookmark_and_never_its_created_at(auth_client):
    bookmark_id = uuid.uuid4()
    first = _put_bookmark(auth_client, bookmark_id, _bookmark(created_at="2026-10-20T08:00:00Z"))
    edited = _put_bookmark(
        auth_client,
        bookmark_id,
        _bookmark(label="Renamed", note="typed later", created_at="2026-10-23T08:00:00Z"),
    )
    assert edited.status_code == 200
    assert edited.data["label"] == "Renamed"
    assert edited.data["note"] == "typed later"
    assert edited.data["created_at"] == "2026-10-20T08:00:00Z"
    assert edited.data["updated_at"] > first.data["updated_at"]
    assert BookBookmark.objects.count() == 1


def test_a_bookmark_cannot_move_to_another_book(auth_client):
    bookmark_id = uuid.uuid4()
    _put_bookmark(auth_client, bookmark_id, _bookmark())
    moved = _put_bookmark(auth_client, bookmark_id, _bookmark(book=OTHER_BOOK))
    assert moved.status_code == 400
    assert moved.data["detail"].startswith("Nothing was saved.")
    assert BookBookmark.objects.get().book_sha256 == BOOK


@pytest.mark.parametrize(
    ("path_id", "body", "words"),
    [
        ("not-a-uuid", None, "not a bookmark id"),
        (None, {"book_sha256": "abc"}, "not a book hash"),
        (None, {"variant": "pdf"}, "`original` or `resizable`"),
        (None, {"total_progression": 2}, "from 0 to 1"),
        (None, {"locator": None}, "locator"),
    ],
)
def test_a_bad_bookmark_is_refused_in_words(auth_client, path_id, body, words):
    payload = _bookmark()
    payload.update(body or {})
    refused = _put_bookmark(auth_client, path_id or uuid.uuid4(), payload)
    assert refused.status_code == 400, refused.data
    assert refused.data["detail"].startswith("Nothing was saved.")
    assert words in refused.data["detail"]
    assert BookBookmark.objects.count() == 0


def test_a_tombstone_round_trips_and_a_second_delete_changes_nothing(auth_client):
    bookmark_id = uuid.uuid4()
    made = _put_bookmark(auth_client, bookmark_id, _bookmark())

    deleted = auth_client.delete(f"/api/books/bookmarks/{bookmark_id}")
    assert deleted.status_code == 200, deleted.data
    assert set(deleted.data) == BOOKMARK_KEYS
    assert deleted.data["deleted_at"] is not None
    assert deleted.data["updated_at"] > made.data["updated_at"]

    # The other device learns of it through its `since` feed.
    feed = auth_client.get(f"/api/books/bookmarks?since={made.data['updated_at']}").data
    assert [(row["id"], row["deleted_at"]) for row in feed] == [
        (str(bookmark_id), deleted.data["deleted_at"])
    ]

    again = auth_client.delete(f"/api/books/bookmarks/{bookmark_id}")
    assert again.status_code == 200
    assert again.data == deleted.data


def test_deleting_a_bookmark_the_server_never_saw_is_a_404_in_words(auth_client):
    missing = auth_client.delete(f"/api/books/bookmarks/{uuid.uuid4()}")
    assert missing.status_code == 404
    assert missing.data["detail"] == "Nothing was deleted. No bookmark of yours has that id."
    bad = auth_client.delete("/api/books/bookmarks/nope")
    assert bad.status_code == 400
    assert bad.data["detail"].startswith("Nothing was deleted.")


def test_a_put_brings_a_deleted_bookmark_back(auth_client):
    bookmark_id = uuid.uuid4()
    _put_bookmark(auth_client, bookmark_id, _bookmark())
    auth_client.delete(f"/api/books/bookmarks/{bookmark_id}")
    back = _put_bookmark(auth_client, bookmark_id, _bookmark(note="still wanted"))
    assert back.status_code == 200
    assert back.data["deleted_at"] is None
    assert back.data["note"] == "still wanted"


# =============================================================================
# `since` paging
# =============================================================================


def test_since_returns_rows_changed_at_or_after_it_oldest_first(auth_client):
    first = _put_position(auth_client, _position(0.1, "2026-10-23T10:00:00Z"), book=BOOK)
    second = _put_position(auth_client, _position(0.2, "2026-10-23T10:00:00Z"), book=OTHER_BOOK)

    everything = auth_client.get("/api/books/positions").data
    assert [row["book_sha256"] for row in everything] == [BOOK, OTHER_BOOK]

    # The largest `updated_at` seen is the next cursor; it is inclusive, so
    # the row at the cursor comes back and nothing older does.
    cursor = first.data["updated_at"]
    page = auth_client.get("/api/books/positions", {"since": cursor}).data
    assert [row["book_sha256"] for row in page] == [BOOK, OTHER_BOOK]
    cursor = second.data["updated_at"]
    page = auth_client.get("/api/books/positions", {"since": cursor}).data
    assert [row["book_sha256"] for row in page] == [OTHER_BOOK]

    # A later write to the first book moves it past the cursor.
    moved = _put_position(auth_client, _position(0.3, "2026-10-23T11:00:00Z"), book=BOOK)
    page = auth_client.get("/api/books/positions", {"since": cursor}).data
    assert [row["book_sha256"] for row in page] == [OTHER_BOOK, BOOK]
    page = auth_client.get("/api/books/positions", {"since": moved.data["updated_at"]}).data
    assert [row["total_progression"] for row in page] == [0.3]


def test_a_kept_out_write_does_not_reappear_in_the_feed(auth_client):
    stored = _put_position(auth_client, _position(0.6, "2026-10-23T12:00:00Z"))
    _put_position(auth_client, _position(0.1, "2026-10-23T09:00:00Z"))
    page = auth_client.get("/api/books/positions", {"since": stored.data["updated_at"]}).data
    assert [row["total_progression"] for row in page] == [0.6]


def test_bookmark_since_pages_the_same_way(auth_client):
    a, b = uuid.uuid4(), uuid.uuid4()
    first = _put_bookmark(auth_client, a, _bookmark())
    second = _put_bookmark(auth_client, b, _bookmark(progression=0.5))
    assert [row["id"] for row in auth_client.get("/api/books/bookmarks").data] == [str(a), str(b)]
    page = auth_client.get("/api/books/bookmarks", {"since": second.data["updated_at"]}).data
    assert [row["id"] for row in page] == [str(b)]
    page = auth_client.get("/api/books/bookmarks", {"since": first.data["updated_at"]}).data
    assert [row["id"] for row in page] == [str(a), str(b)]


def test_an_unreadable_since_means_everything(auth_client):
    _put_position(auth_client, _position(0.1, "2026-10-23T10:00:00Z"))
    assert len(auth_client.get("/api/books/positions", {"since": "last tuesday"}).data) == 1


def test_a_since_in_the_future_returns_nothing(auth_client):
    _put_position(auth_client, _position(0.1, "2026-10-23T10:00:00Z"))
    assert auth_client.get("/api/books/positions", {"since": "2999-01-01T00:00:00Z"}).data == []


# =============================================================================
# Who may call it
# =============================================================================


def test_an_anonymous_caller_is_refused(client):
    assert client.get("/api/books/positions").status_code in (401, 403)
    assert client.put(f"/api/books/positions/{BOOK}/original", {}).status_code in (401, 403)


def test_a_read_scoped_token_may_read_and_not_write(household_user):
    from rest_framework.test import APIClient

    from household.models import DeviceToken

    _token, raw_key = DeviceToken.issue(household_user, "reader", scope="read")
    reader = APIClient()
    reader.credentials(HTTP_AUTHORIZATION=f"Token {raw_key}")
    assert reader.get("/api/books/positions").status_code == 200
    refused = _put_position(reader, _position(0.1, "2026-10-23T10:00:00Z"))
    assert refused.status_code == 403
    assert BookPosition.objects.count() == 0


def test_a_since_with_an_offset_sent_as_percent_2b_is_read_as_that_instant(auth_client):
    """The phone sends `since` URL-encoded, `+` as `%2B`, with an offset."""
    _put_position(auth_client, _position(0.1, "2026-10-23T10:00:00Z"))
    stored = BookPosition.objects.get()
    later = stored.updated_at.timestamp() + 1
    tz = datetime.timezone(datetime.timedelta(hours=5))
    before = (stored.updated_at - datetime.timedelta(seconds=1)).astimezone(tz).isoformat()
    after = datetime.datetime.fromtimestamp(later, tz).isoformat()
    assert "+05:00" in before
    hit = auth_client.get("/api/books/positions?since=" + before.replace("+", "%2B")).data
    assert len(hit) == 1
    miss = auth_client.get("/api/books/positions?since=" + after.replace("+", "%2B")).data
    assert miss == []


def test_the_phones_device_name_and_page_locator_are_accepted(auth_client):
    made = _put_position(
        auth_client,
        {
            "locator": {"page": 4},
            "total_progression": 0.2,
            "chapter_label": None,
            "recorded_at": "2026-10-23T10:00:00.123+00:00",
            "device": "phone",
        },
        variant="original",
    )
    assert made.status_code == 200, made.data
    assert made.data["device"] == "phone"
    assert made.data["locator"] == {"page": 4}
    bookmark = _put_bookmark(auth_client, uuid.uuid4(), _bookmark(locator={"page": 4}))
    assert bookmark.status_code == 200, bookmark.data
