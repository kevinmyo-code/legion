"""The recruiter demo's door (recruiter-demo ticket 02, ADR 0068).

Kevin shares ONE invite code with every recruiter (map ruling 3). The code is
never a `DeviceToken`: entering it makes a throwaway visitor `User`, a
`recruiter` member of the demo household, and THAT visitor gets its own
read-scoped token with a two-hour life. One visitor per entry keeps the
per-member throttles meaningful (one shared user would collapse every
recruiter into one budget) and gives the audit one row per visit, tagged
with the `ref` from the resume link.

**The code is stored hashed**, unlike `household.Invite`. An invite has to be
listed and previewed by its owner; this code does not. The operator sees it
once, when `manage.py demo_code rotate` prints it, and the engine only ever
needs to recognise it.
"""

from __future__ import annotations

import hashlib
import secrets

from django.db import models
from django.utils import timezone

DEMO_CODE_BYTES = 9  # twelve URL-safe characters, the same length as an invite


def hash_demo_code(code: str) -> str:
    return hashlib.sha256(code.strip().encode("utf-8")).hexdigest()


class DemoCode(models.Model):
    """One generation of the shared invite code. At most one is live: rotating
    revokes the old before minting the new (`DemoCode.rotate`), and
    `demo_code off` revokes it without a successor, which closes the door."""

    code_hash = models.CharField(max_length=64, unique=True)
    created_at = models.DateTimeField(auto_now_add=True)
    revoked_at = models.DateTimeField(null=True, blank=True)
    # Kevin's own tester code (Kevin, 2026-11-04: "a code that has no budget
    # limit so i can test myself"). Its visitors are the same throwaway
    # recruiters on the same demo household, with the same five-minute
    # conversations, but nothing they do counts against the daily budget
    # (`demo.budget.counts`). It is a separate generation from the shared code:
    # rotating or closing one never touches the other. Never put it on a resume.
    unmetered = models.BooleanField(default=False)
    # A named code for one channel (Kevin, 2026-11-04: a code for LinkedIn,
    # "if its drained, explicitly tell users that the code is on a shared daily
    # spend cap of $2 and to contact me for a proper demo code"). "" is the
    # shared resume code. Each label is its own generation: rotating or closing
    # one never touches another (`rotate`, `close_all`).
    label = models.CharField(max_length=32, blank=True, default="")
    # The code's own daily cap in cents, on top of the global budget, or None
    # for none. Counted per UTC day in `DemoCodeDay` (`demo/budget.py`).
    daily_cap_cents = models.IntegerField(null=True, blank=True)

    def __str__(self) -> str:
        # Never the code, and never the hash either: neither belongs in a log line.
        state = "revoked" if self.revoked_at else "live"
        kind = "tester " if self.unmetered else f"{self.label} " if self.label else ""
        return f"Demo {kind}code from {self.created_at:%Y-%m-%d %H:%M} ({state})"

    @classmethod
    def live(cls):
        return cls.objects.filter(revoked_at__isnull=True)

    @classmethod
    def close_all(cls, unmetered: bool = False, label: str = "") -> int:
        """Revoke the live codes of one kind: the shared code by default, the
        tester code with `unmetered`, a named code with `label`."""
        return cls.live().filter(unmetered=unmetered, label=label).update(revoked_at=timezone.now())

    @classmethod
    def rotate(
        cls, unmetered: bool = False, label: str = "", daily_cap_cents: int | None = None
    ) -> tuple[DemoCode, str]:
        """Revoke the live code of this kind and mint a new one. Returns the row
        and the raw code, which is never stored and cannot be fetched again."""
        cls.close_all(unmetered=unmetered, label=label)
        code = secrets.token_urlsafe(DEMO_CODE_BYTES)
        row = cls.objects.create(
            code_hash=hash_demo_code(code),
            unmetered=unmetered,
            label=label,
            daily_cap_cents=daily_cap_cents,
        )
        return row, code

    @classmethod
    def match(cls, code: str) -> DemoCode | None:
        """The row this code hashes to, live or revoked, or None. The lookup is
        on the hash, so a timing difference says nothing about the code."""
        if not code or not code.strip():
            return None
        return cls.objects.filter(code_hash=hash_demo_code(code)).first()


class DemoVisit(models.Model):
    """One recruiter's visit: who they became, which token they were handed,
    and the `ref` their resume link carried (`/try?ref=acme`).

    `user` is SET_NULL so pruning an expired visitor (`manage.py
    prune_demo_visitors`) keeps the visit's own record - when, which ref, which
    code generation - after the user, its membership and its token are gone.
    `AssistantCall.user` is SET_NULL for the same reason."""

    user = models.OneToOneField(
        "household.User", null=True, blank=True, on_delete=models.SET_NULL, related_name="+"
    )
    token = models.ForeignKey(
        "household.DeviceToken",
        null=True,
        blank=True,
        on_delete=models.SET_NULL,
        related_name="+",
    )
    # Ticket 13 (ADR 0069): the visit's own copy of the demo household, made
    # by `/enter` and deleted whole by `prune_demo_visitors`. SET_NULL so this
    # row outlives it, like `user`. Null on a visit made before ticket 13,
    # which joined the shared demo household instead.
    household = models.ForeignKey(
        "household.Household",
        null=True,
        blank=True,
        on_delete=models.SET_NULL,
        related_name="+",
    )
    code = models.ForeignKey(DemoCode, null=True, on_delete=models.SET_NULL, related_name="visits")
    ref = models.CharField(max_length=64, blank=True, default="")
    created_at = models.DateTimeField(auto_now_add=True)
    expires_at = models.DateTimeField()
    # Which scenario the visit's assistant plays (Kevin, 2026-11-22, `demo/shop/`): "" is the
    # LEGION guide, "shop" the store rep of a made-up shop. Set only by `demo.shop.scenario`.
    # `db_default` too: the revision before this one inserts visits without the column.
    scenario = models.CharField(max_length=16, blank=True, default="", db_default="")

    class Meta:
        indexes = [models.Index(fields=["expires_at"])]

    def __str__(self) -> str:
        return f"Demo visit {self.created_at:%Y-%m-%d %H:%M} (ref {self.ref or 'none'})"


class SpareVisitHousehold(models.Model):
    """A visit household seeded ahead of time, waiting for a visitor (demo-enter-slow,
    2026-11-22: the inline seed took about 16 s on Cloud Run). `/enter` claims one and deletes
    this row in the visit's own transaction, so a claimed household carries no trace of having
    waited. Handed out only on the day and seed version it was made for (`demo.spares`);
    anything else is deleted whole by the refill and by `prune_demo_visitors`."""

    household = models.OneToOneField(
        "household.Household", on_delete=models.CASCADE, related_name="+"
    )
    # The `today` the seed was given, a date in the demo's own zone: every seeded row is
    # relative to it.
    seeded_for = models.DateField()
    # `demo.spares.seed_version()` when it was seeded: a deploy that changes the seed retires it.
    seed_version = models.CharField(max_length=64)
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        indexes = [models.Index(fields=["seeded_for", "seed_version"])]

    def __str__(self) -> str:
        return f"Spare demo visit household for {self.seeded_for}"


class ShopOrder(models.Model):
    """One order in the store-rep demo (`demo/shop/`), inside one visit and nowhere else.

    Made-up goods at made-up prices: nothing is charged or shipped. `quoted` is the read-back the
    visitor has heard; `placed` follows only their yes in a LATER turn (`demo.shop.tools`). Lines
    and totals are code's, in integer cents, from the catalogue (`demo.shop.catalogue`), never the
    model's. Deleted with its visit, and after `VISIT_RETENTION_DAYS` by `prune_demo_visitors`."""

    QUOTED = "quoted"
    PLACED = "placed"

    visit = models.ForeignKey(DemoVisit, on_delete=models.CASCADE, related_name="shop_orders")
    ref = models.CharField(max_length=16)
    status = models.CharField(max_length=8, default=QUOTED)
    # [{"sku", "qty", "unit_cents", "line_cents"}]
    lines = models.JSONField()
    bundle_saving_cents = models.IntegerField(default=0)
    shipping_cents = models.IntegerField(default=0)
    total_cents = models.IntegerField()
    # The turn that heard the read-back: the yes must come in a later one.
    conversation_id = models.UUIDField(null=True, blank=True)
    quoted_turn = models.IntegerField(null=True, blank=True)
    created_at = models.DateTimeField(auto_now_add=True)
    placed_at = models.DateTimeField(null=True, blank=True)

    class Meta:
        constraints = [
            models.CheckConstraint(
                condition=models.Q(total_cents__gte=0), name="demo_shop_order_total_nonneg"
            ),
            models.CheckConstraint(
                condition=models.Q(status__in=("quoted", "placed")),
                name="demo_shop_order_status_valid",
            ),
        ]

    def __str__(self) -> str:
        return f"Demo shop order {self.ref} ({self.status})"


class CallbackRequest(models.Model):
    """A visitor asked for a person (the hand-off to Kevin, his 2026-11-22 ruling): what they left
    and whether Kevin was told. Their own name and contact, typed by them in a form, kept
    `VISIT_RETENTION_DAYS` like the visit records. Nothing here is ever dialled."""

    visit = models.ForeignKey(
        DemoVisit, null=True, on_delete=models.SET_NULL, related_name="callbacks"
    )
    ref = models.CharField(max_length=64, blank=True, default="")
    # A keyed hash of the caller's address (`demo.shop.callback.address_key`): one per day.
    address_key = models.CharField(max_length=64, blank=True, default="")
    name = models.CharField(max_length=60)
    contact = models.CharField(max_length=120)
    note = models.CharField(max_length=280, blank=True, default="")
    # Code-built from the visit's orders (`demo.shop.callback.summary`): never the model's words.
    summary = models.CharField(max_length=400, blank=True, default="")
    # `push.dispatch.send_now`'s answer per owner, or "push_off".
    delivery = models.CharField(max_length=40, blank=True, default="")
    created_at = models.DateTimeField(auto_now_add=True)

    def __str__(self) -> str:
        # Never the name or contact: neither belongs in a log line.
        return f"Demo callback {self.created_at:%Y-%m-%d %H:%M} ({self.delivery or 'pending'})"


class DemoCallbackDay(models.Model):
    """How many callbacks were taken on one UTC day, across every visitor: the cap is held by one
    conditional UPDATE (`demo.shop.callback.take_slot`), like `DemoBudgetDay`."""

    day = models.DateField(unique=True)
    count = models.IntegerField(default=0)

    class Meta:
        constraints = [
            models.CheckConstraint(
                condition=models.Q(count__gte=0), name="demo_callback_day_nonneg"
            )
        ]

    def __str__(self) -> str:
        return f"Demo callbacks {self.day}: {self.count}"


class RecruiterContent(models.Model):
    """What the recruiter page shows and what `about_kevin` answers from
    (recruiter-demo tickets 07 and 10).

    **Stored in the engine, never in the repo.** The repo is public, and Kevin
    changes a line without a commit: `manage.py load_recruiter_content <dir>`
    reads his private folder and upserts this row. The two files (a report,
    HTML; a resume, PDF) are bytes in the database for the same reason: the
    engine's image carries no private file, and Cloud Run has no disk worth
    keeping.

    One live row, keyed `CURRENT_KEY`. A reload replaces it in place.

    **A named code may have its own version** (Kevin, 2026-11-04: "a stripped of
    work-section version for linkedin"; the resume code keeps the full page). A row
    keyed by a code's `label` is what that code's visitors read, page,
    files and `about_kevin` alike (`for_user`); a label with no row of its
    own falls back to `CURRENT_KEY`.
    """

    CURRENT_KEY = "current"

    key = models.CharField(max_length=32, unique=True, default=CURRENT_KEY)
    # The validated content.json (`demo.content.CONTENT_SCHEMA`), `profile`
    # included. `profile` is served to nobody but `about_kevin`.
    content = models.JSONField()
    report = models.BinaryField(null=True, blank=True)
    report_content_type = models.CharField(max_length=100, blank=True, default="")
    report_filename = models.CharField(max_length=200, blank=True, default="")
    resume = models.BinaryField(null=True, blank=True)
    resume_content_type = models.CharField(max_length=100, blank=True, default="")
    resume_filename = models.CharField(max_length=200, blank=True, default="")
    # The resume's one page as a PNG (demo-modals, Kevin 2026-11-05): Android Chrome draws no PDF
    # inside a page, so the resume modal shows this and keeps the PDF for the download. Rendered
    # in Kevin's private folder beside resume.pdf, never in the repo.
    resume_image = models.BinaryField(null=True, blank=True)
    resume_image_content_type = models.CharField(max_length=100, blank=True, default="")
    resume_image_filename = models.CharField(max_length=200, blank=True, default="")
    loaded_at = models.DateTimeField(auto_now=True)

    def __str__(self) -> str:
        return f"Recruiter content ({self.key}, loaded {self.loaded_at:%Y-%m-%d %H:%M})"

    @classmethod
    def current(cls) -> RecruiterContent | None:
        return cls.objects.filter(key=cls.CURRENT_KEY).first()

    @classmethod
    def for_user(cls, user) -> RecruiterContent | None:
        """The version this caller reads: their visit code's own, if it has
        one, else the current (full) one. An owner previewing reads current."""
        if user is not None and getattr(user, "pk", None):
            label = (
                DemoVisit.objects.filter(user=user)
                .exclude(code__label="")
                .values_list("code__label", flat=True)
                .first()
            )
            if label:
                row = cls.objects.filter(key=label).first()
                if row is not None:
                    return row
        return cls.current()

    def asset(self, kind: str) -> tuple[bytes, str, str] | None:
        """(bytes, content type, filename) for `report`, `resume` or `resume_image`, or None
        when that file was not loaded."""
        data = getattr(self, kind)
        if data is None:
            return None
        return bytes(data), getattr(self, f"{kind}_content_type"), getattr(self, f"{kind}_filename")


class RecruiterMediaFile(models.Model):
    """One model or poster file a project section names (`media.models`, Kevin 2026-11-19), stored
    beside its content row for the reasons the resume is: the image carries no private file and
    the page's clip bucket is world-readable. Its own table, not columns on `RecruiterContent`,
    because `for_user` loads that whole row on every page read and every `about_kevin` call.
    Replaced whole by each load (`demo.content.load`); deleted with its row."""

    content = models.ForeignKey(
        RecruiterContent, on_delete=models.CASCADE, related_name="media_files"
    )
    name = models.CharField(max_length=80)
    # Where the bytes are read from, and it is the bucket when `object_name` is set
    # (`demo.media_store`, Supabase egress cut 4): `data` is written beside it for one release so
    # a rollback or an unreachable bucket still serves the page. Blank means Postgres only.
    data = models.BinaryField()
    object_name = models.CharField(max_length=200, default="", blank=True)
    content_type = models.CharField(max_length=100)
    # The ETag: a revisit asks "still this?" and gets a 304 without the bytes leaving Postgres.
    sha256 = models.CharField(max_length=64)

    class Meta:
        constraints = [
            models.UniqueConstraint(fields=["content", "name"], name="demo_media_file_unique")
        ]

    def __str__(self) -> str:
        return f"Recruiter media {self.name} ({self.content_type})"


class DemoBudgetDay(models.Model):
    """One UTC day of recruiter Gemini spend, as RESERVED before acting
    (recruiter-demo ticket 04, `demo/budget.py`). Integer cents (CLAUDE.md
    section 4 rule 3). Only ever grows by a reservation that fits under the
    cap, in one conditional UPDATE, and shrinks only when a mint that reserved
    failed at Google. Members are never counted here."""

    day = models.DateField(unique=True)
    reserved_cents = models.BigIntegerField(default=0)
    voice_mints = models.IntegerField(default=0)
    text_calls = models.IntegerField(default=0)
    # `web_search` calls a recruiter's assistant made (audit 2026-11-03).
    web_searches = models.IntegerField(default=0)

    class Meta:
        constraints = [
            models.CheckConstraint(
                condition=models.Q(reserved_cents__gte=0), name="demo_budget_reserved_nonneg"
            )
        ]

    def __str__(self) -> str:
        return f"Demo budget {self.day}: {self.reserved_cents} cents reserved"


class DemoCodeDay(models.Model):
    """One UTC day of spend reserved by the visitors of one capped code
    (`DemoCode.daily_cap_cents`). Reserved beside the global `DemoBudgetDay`,
    never instead of it: a capped code's visitors must fit both."""

    code = models.ForeignKey(DemoCode, on_delete=models.CASCADE, related_name="days")
    day = models.DateField()
    reserved_cents = models.BigIntegerField(default=0)

    class Meta:
        constraints = [
            models.UniqueConstraint(fields=["code", "day"], name="demo_code_day_unique"),
            models.CheckConstraint(
                condition=models.Q(reserved_cents__gte=0), name="demo_code_day_reserved_nonneg"
            ),
        ]

    def __str__(self) -> str:
        return f"Demo code day {self.day}: {self.reserved_cents} cents reserved"


class DemoFunnelDay(models.Model):
    """How many times a step of the demo happened on one UTC day, per `ref` tag (Kevin,
    2026-11-17: about 30 page loads and one entry since 2026-11-06, so the first ten seconds are
    the question). A counter, nothing else: no visitor, no address, no words, no time of day.
    Kept `VISIT_RETENTION_DAYS` like the visit records (`prune_demo_visitors`)."""

    EVENTS = (
        "page_load",
        "orb_tap",
        "first_word",
        "turns_3",
        "tour_started",
        "tour_finished",
        "resume_opened",
    )

    day = models.DateField()
    ref = models.CharField(max_length=32, blank=True, default="")
    event = models.CharField(max_length=32)
    count = models.IntegerField(default=0)

    class Meta:
        constraints = [
            models.UniqueConstraint(fields=["day", "ref", "event"], name="demo_funnel_unique"),
            models.CheckConstraint(condition=models.Q(count__gte=0), name="demo_funnel_nonneg"),
        ]

    def __str__(self) -> str:
        return f"Demo funnel {self.day} {self.ref or '-'} {self.event}: {self.count}"
