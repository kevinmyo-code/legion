"""`public.book_positions` and `public.book_bookmarks`: where a member is in a
book, synced between the phone and the laptop (books ticket 08, rulings in
ticket 07).

**A book is a hash and nothing else.** A row names the book by the SHA-256 of
its file bytes (ticket 07 ruling 3) and says which copy was being read
(`original`, or the `resizable` EPUB made from a PDF). No title, no filename,
no book text: the engine learns that a book with hash X is 43% read, nothing
more. `books/reading.py` drops a Readium locator's `text` member on the way
in, because that member quotes the page.

**Per member, not per household** (ticket 07, orchestrator call): Mia's place
in a book is hers. Every read and write goes through
`household.tenancy.scoped`, then narrows to the signed-in member, the same
two steps `brief/views.py` takes.

**Physically in `public`, like `briefs`**: both carry `household_id`, so both
are tenant tables and `tests/test_tenancy.py` holds them to
`household.tenancy.TENANT_TABLES`. Neither `household` nor `user` is ever
served.
"""

from __future__ import annotations

from django.conf import settings
from django.db import models
from django.db.models.functions import Now

VARIANT_ORIGINAL = "original"
VARIANT_RESIZABLE = "resizable"
VARIANTS: tuple[str, ...] = (VARIANT_ORIGINAL, VARIANT_RESIZABLE)

# Lower-case hex, exactly 64 characters. The API accepts either case and
# stores lower, so the phone's and the laptop's hashes of one file always
# meet on one row.
SHA256_PATTERN = r"^[0-9a-f]{64}$"


class BookPosition(models.Model):
    """One member's place in one copy of one book. Newest `recorded_at` wins
    (ticket 07 ruling 4): an older write never replaces a newer one."""

    household = models.ForeignKey("household.Household", on_delete=models.PROTECT, related_name="+")
    # CASCADE: a reading position is the member's own and means nothing
    # without them.
    user = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE, related_name="+")
    book_sha256 = models.CharField(max_length=64)
    variant = models.TextField()
    # Readium Locator JSON for an EPUB, a page index for PDF/CBZ. Null when the
    # reader only knows the overall progression.
    locator = models.JSONField(null=True, blank=True)
    total_progression = models.FloatField()
    chapter_label = models.TextField(null=True, blank=True)
    # The READER's clock, and the one newest-wins compares.
    recorded_at = models.DateTimeField()
    # Which reader wrote it ("A25", "web"). Shown, never compared.
    device = models.TextField(default="", blank=True)
    # The server's clock, and the `?since=` cursor.
    updated_at = models.DateTimeField(db_default=Now())

    class Meta:
        db_table = "book_positions"
        indexes = [
            models.Index(
                fields=["household", "user", "updated_at"], name="book_positions_hh_user_upd"
            ),
        ]
        constraints = [
            models.UniqueConstraint(
                fields=["household", "user", "book_sha256", "variant"],
                name="book_positions_one_per_book",
            ),
            models.CheckConstraint(
                condition=models.Q(variant__in=VARIANTS), name="book_positions_variant_valid"
            ),
            models.CheckConstraint(
                condition=models.Q(total_progression__gte=0) & models.Q(total_progression__lte=1),
                name="book_positions_progression_0_1",
            ),
            models.CheckConstraint(
                condition=models.Q(book_sha256__regex=SHA256_PATTERN),
                name="book_positions_sha256_hex",
            ),
        ]


class BookBookmark(models.Model):
    """A place a member marked, with an optional label and note they typed
    (first-party, so storable). `id` is chosen by the client, so an offline
    phone can make one; deletion is a tombstone (`deleted_at`) so the other
    device learns of it through `?since=`."""

    id = models.UUIDField(primary_key=True)
    household = models.ForeignKey("household.Household", on_delete=models.PROTECT, related_name="+")
    user = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE, related_name="+")
    book_sha256 = models.CharField(max_length=64)
    variant = models.TextField()
    locator = models.JSONField()
    total_progression = models.FloatField()
    label = models.TextField(null=True, blank=True)
    note = models.TextField(null=True, blank=True)
    # When the member made it, by the reader's clock when the reader said;
    # never changed by a later edit.
    created_at = models.DateTimeField(db_default=Now())
    deleted_at = models.DateTimeField(null=True, blank=True)
    updated_at = models.DateTimeField(db_default=Now())

    class Meta:
        db_table = "book_bookmarks"
        indexes = [
            models.Index(
                fields=["household", "user", "updated_at"], name="book_bookmarks_hh_user_upd"
            ),
        ]
        constraints = [
            models.CheckConstraint(
                condition=models.Q(variant__in=VARIANTS), name="book_bookmarks_variant_valid"
            ),
            models.CheckConstraint(
                condition=models.Q(total_progression__gte=0) & models.Q(total_progression__lte=1),
                name="book_bookmarks_progression_0_1",
            ),
            models.CheckConstraint(
                condition=models.Q(book_sha256__regex=SHA256_PATTERN),
                name="book_bookmarks_sha256_hex",
            ),
        ]
