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

    def __str__(self) -> str:
        # Never the code, and never the hash either: neither belongs in a log line.
        state = "revoked" if self.revoked_at else "live"
        return f"Demo code from {self.created_at:%Y-%m-%d %H:%M} ({state})"

    @classmethod
    def live(cls):
        return cls.objects.filter(revoked_at__isnull=True)

    @classmethod
    def close_all(cls) -> int:
        return cls.live().update(revoked_at=timezone.now())

    @classmethod
    def rotate(cls) -> tuple[DemoCode, str]:
        """Revoke whatever is live and mint a new code. Returns the row and the
        raw code, which is never stored and cannot be fetched again."""
        cls.close_all()
        code = secrets.token_urlsafe(DEMO_CODE_BYTES)
        return cls.objects.create(code_hash=hash_demo_code(code)), code

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
    code = models.ForeignKey(DemoCode, null=True, on_delete=models.SET_NULL, related_name="visits")
    ref = models.CharField(max_length=64, blank=True, default="")
    created_at = models.DateTimeField(auto_now_add=True)
    expires_at = models.DateTimeField()

    class Meta:
        indexes = [models.Index(fields=["expires_at"])]

    def __str__(self) -> str:
        return f"Demo visit {self.created_at:%Y-%m-%d %H:%M} (ref {self.ref or 'none'})"


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
    loaded_at = models.DateTimeField(auto_now=True)

    def __str__(self) -> str:
        return f"Recruiter content ({self.key}, loaded {self.loaded_at:%Y-%m-%d %H:%M})"

    @classmethod
    def current(cls) -> RecruiterContent | None:
        return cls.objects.filter(key=cls.CURRENT_KEY).first()

    def asset(self, kind: str) -> tuple[bytes, str, str] | None:
        """(bytes, content type, filename) for `report` or `resume`, or None
        when that file was not loaded."""
        data = getattr(self, kind)
        if data is None:
            return None
        return bytes(data), getattr(self, f"{kind}_content_type"), getattr(self, f"{kind}_filename")


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

    class Meta:
        constraints = [
            models.CheckConstraint(
                condition=models.Q(reserved_cents__gte=0), name="demo_budget_reserved_nonneg"
            )
        ]

    def __str__(self) -> str:
        return f"Demo budget {self.day}: {self.reserved_cents} cents reserved"
