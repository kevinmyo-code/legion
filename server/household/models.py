"""Households, users, membership, and device tokens.

**ADR 0045 replaced "one household per server" with "households are
tenants" on 2026-09-08.** One engine holds more than one household; every
data row belongs to exactly one household (`household/tenancy.py` holds the
list of tables carrying the column); every user belongs to exactly one
household; a member sees everything in their household and nothing outside
it. There are no roles inside a household except `owner`, which exists only
to invite and remove members, and there are no approval workflows: an
invite is a code, not a request.

**This docstring used to say** "One household per server (ADR 0044,
CLAUDE.md section 1: two adults, no roles, no tenancy, ever). There is no
signup and no invite flow - accounts are made with `manage.py
createsuperuser` or the admin". Then, after ADR 0045, it said the second
half was "still true TODAY", because signup and invites were ticket 03's
deferred half.

**Both halves are now history.** `Invite` is at the bottom of this file,
`POST /api/auth/signup` redeems a code, and `manage.py create_household`
founds one from the command line - web-and-households ticket 03, built
2026-09-10. `createsuperuser`, the admin and `manage.py
add_household_member` all still work and are still the compose-bootstrap
path; they are no longer the only path.
"""
from __future__ import annotations

import datetime
import hashlib
import secrets
import uuid

from django.contrib.auth.base_user import AbstractBaseUser, BaseUserManager
from django.contrib.auth.models import PermissionsMixin
from django.db import models
from django.utils.functional import cached_property


class Household(models.Model):
    """One family on one engine (ADR 0045).

    The id is a uuid rather than a serial because it is written into
    `household_id` on every row of forty-three `public` tables and travels with
    a `pg_dump` into other environments; a sequence number would collide the
    first time two dumps met. The bootstrap household's id is chosen by the
    operator once and read from `LEGION_BOOTSTRAP_HOUSEHOLD_ID` - never minted
    randomly by a migration, for the reason `household.tenancy` sets out.
    """

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    name = models.CharField(max_length=120)
    created_at = models.DateTimeField(auto_now_add=True)
    # SET_NULL rather than CASCADE: deleting the person who made the household
    # must never delete the household, and with it every row every OTHER member
    # of it owns.
    created_by = models.ForeignKey(
        "household.User", null=True, on_delete=models.SET_NULL, related_name="+"
    )

    def __str__(self) -> str:
        return self.name


class UserManager(BaseUserManager):
    """Mirrors `django.contrib.auth.models.UserManager` but keyed on email -
    `USERNAME_FIELD` is `email` below, so the stock manager's `username=`
    keyword argument is the wrong shape."""

    use_in_migrations = True

    def _create_user(self, email: str, password: str | None, **extra_fields):
        if not email:
            raise ValueError("User must have an email address")
        email = self.normalize_email(email)
        user = self.model(email=email, **extra_fields)
        user.set_password(password)
        user.save(using=self._db)
        return user

    def create_user(self, email: str, password: str | None = None, **extra_fields):
        extra_fields.setdefault("is_staff", False)
        extra_fields.setdefault("is_superuser", False)
        return self._create_user(email, password, **extra_fields)

    def create_superuser(self, email: str, password: str | None = None, **extra_fields):
        extra_fields.setdefault("is_staff", True)
        extra_fields.setdefault("is_superuser", True)
        if extra_fields.get("is_staff") is not True:
            raise ValueError("Superuser must have is_staff=True.")
        if extra_fields.get("is_superuser") is not True:
            raise ValueError("Superuser must have is_superuser=True.")
        return self._create_user(email, password, **extra_fields)


class User(AbstractBaseUser, PermissionsMixin):
    """The household's users. UUID primary key on purpose: ticket 10 sets the
    two migrated users' ids to their Supabase `auth.uid` so every row that
    already references a user by that id keeps referencing it across the
    cutover, with no backfill pass over foreign keys.

    `AbstractUser` was not used directly because it hardcodes a `username`
    field alongside `email`; `AbstractBaseUser` + `PermissionsMixin` is the
    documented way to drop `username` entirely rather than carry a second,
    unused login field forever.
    """

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    email = models.EmailField(unique=True)
    first_name = models.CharField(max_length=150, blank=True)
    last_name = models.CharField(max_length=150, blank=True)
    is_staff = models.BooleanField(default=False)
    is_active = models.BooleanField(default=True)
    date_joined = models.DateTimeField(auto_now_add=True)

    objects = UserManager()

    USERNAME_FIELD = "email"
    REQUIRED_FIELDS: list[str] = []

    def __str__(self) -> str:
        return self.email

    @cached_property
    def household(self) -> Household | None:
        """The household this user belongs to, or None for a user who belongs
        to none (which `IsHouseholdMember` refuses outright before any view
        runs, so inside a view this is never None).

        Cached for the life of the request's `User` instance: every scoped
        query in `api/` reads it, and without the cache a list route would
        re-query `household_householdmember` once per serializer field.
        """
        member = HouseholdMember.objects.filter(user=self).select_related("household").first()
        return member.household if member is not None else None


def _generate_device_key() -> str:
    """32 bytes of randomness, hex-encoded. Shown to the caller exactly once
    at creation; only its hash is ever persisted (see `DeviceToken.key_hash`
    and `hash_device_key` below)."""
    return secrets.token_hex(32)


def hash_device_key(raw_key: str) -> str:
    """SHA-256 of a raw device key. A stolen row in `household_devicetoken`
    must not be usable as a credential, so the raw key itself never touches
    the database - only this hash does, matching the reasoning
    `.scratch/backend-erp` used for provenance columns: an anchor you cannot
    reproduce from storage is worthless, and a raw secret you should not be
    able to reproduce FROM storage is the same rule pointed at a credential.
    """
    return hashlib.sha256(raw_key.encode("utf-8")).hexdigest()


class DeviceToken(models.Model):
    """One row per phone, per browser, per robot (ADR 0044: 'a future device
    is another limb with another device token'). Revoking one never touches
    another - there is no shared secret across devices to invalidate.
    """

    user = models.ForeignKey(User, on_delete=models.CASCADE, related_name="device_tokens")
    name = models.CharField(max_length=255)
    key_hash = models.CharField(max_length=64, unique=True)
    created_at = models.DateTimeField(auto_now_add=True)
    last_seen_at = models.DateTimeField(null=True, blank=True)
    revoked_at = models.DateTimeField(null=True, blank=True)

    class Meta:
        indexes = [models.Index(fields=["key_hash"])]

    def __str__(self) -> str:
        return f"{self.name} ({self.user.email})"

    @property
    def is_revoked(self) -> bool:
        return self.revoked_at is not None

    @classmethod
    def issue(cls, user: User, name: str) -> tuple[DeviceToken, str]:
        """Create a token and return it alongside the raw key. The raw key
        is the return value, never a model field - callers must hand it to
        the device immediately and cannot fetch it again later."""
        raw_key = _generate_device_key()
        token = cls.objects.create(user=user, name=name, key_hash=hash_device_key(raw_key))
        return token, raw_key


class HouseholdMember(models.Model):
    """Which household a user belongs to, and whether they may invite others.

    **This docstring used to read** "Django-side mirror of the
    `public.household_members` shape ... One household per server, so this is
    really a flag, not a join table to a household entity that does not exist
    here." The household entity exists now (`Household` above, ADR 0045), so
    this IS the join table, and `IsHouseholdMember` still answers "is this
    user a member of any household" with no raw SQL.

    **Still one-to-one with `User`, deliberately.** ADR 0045: "every user
    belongs to exactly one household". A person in two households would need
    every request to say which one it meant, and there is no place on the wire
    to say it - the device token identifies a user and nothing more. A second
    household is a second account.
    """

    OWNER = "owner"
    MEMBER = "member"
    ROLE_CHOICES = [(OWNER, "owner"), (MEMBER, "member")]

    user = models.OneToOneField(User, on_delete=models.CASCADE, related_name="household_member")
    household = models.ForeignKey(Household, on_delete=models.CASCADE, related_name="members")
    # The ONE role, and it governs membership only - never data. ADR 0045:
    # "There are no roles inside a household except `owner`, which exists only
    # to invite and remove members." An owner and a member see exactly the same
    # rows; nothing in `api/` reads this column, and if something ever does,
    # that is a new ruling and not a refactor.
    role = models.CharField(max_length=8, choices=ROLE_CHOICES, default=MEMBER)
    joined_at = models.DateTimeField(auto_now_add=True)

    def __str__(self) -> str:
        return f"{self.user.email} ({self.household.name}, {self.role})"


INVITE_CODE_LENGTH = 12
DEFAULT_INVITE_DAYS = 14
DEFAULT_INVITE_USES = 2


def generate_invite_code() -> str:
    """Twelve URL-safe characters from nine bytes of `secrets` randomness.

    Nine bytes is exactly twelve base64url characters, so the `[:12]` slice
    below truncates nothing - it is there to make the column's `max_length`
    and the generator's output impossible to disagree about, and to keep the
    length fixed if the byte count is ever changed by someone reading only
    one of the two numbers.

    `secrets`, never `random`: this is a credential. Seventy-two bits is far
    more than a code that expires in a fortnight, is capped at a couple of
    uses, and is only ever redeemed through a throttled endpoint needs.
    """
    return secrets.token_urlsafe(9)[:INVITE_CODE_LENGTH]


class Invite(models.Model):
    """A code that lets someone sign up - either into an existing household,
    or (`creates_household`) to found one.

    ADR 0045: "an invite is a code, not a request". There is no approval
    step, nothing to accept on the owner's side, and no pending state: a
    live code works and a dead one does not.

    **The code is stored in the clear, and that is a real difference from
    `DeviceToken`, which stores only a hash.** It has to be: an owner lists
    their live invites to re-share one, and `GET /api/auth/invite/<code>`
    tells the person holding it what it will do before they spend it, and
    neither is possible against a hash. What is kept from `DeviceToken.issue`
    is the shape that matters at the API edge - `mint()` returns the code
    alongside the row, the creating response is the one place it is put on
    the wire in full, and it is never written to a log. The exposure is
    bounded the way a hash is not: a code expires, has a use count, and can
    be revoked, none of which is true of a device key.

    `household` is nullable ONLY for an unspent `creates_household` code -
    the household it points at does not exist yet, because the person who
    signs up names it. The first such signup fills it in, so later uses of
    the same code join the household the first one founded. That is how one
    code reaches both of Kevin's parents.
    """

    code = models.CharField(
        max_length=INVITE_CODE_LENGTH, unique=True, default=generate_invite_code
    )
    household = models.ForeignKey(
        Household, null=True, blank=True, on_delete=models.CASCADE, related_name="invites"
    )
    creates_household = models.BooleanField(default=False)
    created_by = models.ForeignKey(User, on_delete=models.CASCADE, related_name="invites")
    max_uses = models.PositiveSmallIntegerField(default=DEFAULT_INVITE_USES)
    used_count = models.PositiveSmallIntegerField(default=0)
    expires_at = models.DateTimeField()
    revoked_at = models.DateTimeField(null=True, blank=True)
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        indexes = [models.Index(fields=["code"])]
        constraints = [
            # An invite either joins a household that exists or creates one.
            # A row with neither would be a code that signup could not honour
            # in any branch, and CLAUDE.md section 7 is explicit that an
            # integrity rule which must hold even when Django has a bug is SQL
            # shipped by a migration rather than a check in Python. The view
            # still refuses such a row in words, because a 500 from a
            # constraint tells the person signing up nothing.
            models.CheckConstraint(
                condition=models.Q(creates_household=True) | models.Q(household__isnull=False),
                name="invite_joins_or_creates_a_household",
            ),
        ]

    def __str__(self) -> str:
        # Deliberately NOT the code. `__str__` is what lands in an admin log
        # line, a repr in a traceback and a `print` in a shell, and a
        # credential that leaks into any of those is a credential that leaked.
        where = self.household.name if self.household_id else "a new household"
        return f"Invite for {where} ({self.used_count}/{self.max_uses} used)"

    @property
    def is_expired(self) -> bool:
        from django.utils import timezone

        return self.expires_at <= timezone.now()

    @property
    def is_spent(self) -> bool:
        return self.used_count >= self.max_uses

    @property
    def is_live(self) -> bool:
        return self.revoked_at is None and not self.is_expired and not self.is_spent

    def unavailable_reason(self) -> str | None:
        """Why this code cannot be used, in words a person can act on, or
        None when it can.

        One function rather than a check per call site: the signup endpoint's
        400 and the preview endpoint's `reason` must never be able to
        disagree about whether a code is live, and three copies of the same
        three-way check is three chances for them to.
        """
        if self.revoked_at is not None:
            return "That invite code was revoked by the person who made it."
        if self.is_expired:
            return "That invite code has expired. Ask for a new one."
        if self.is_spent:
            return (
                f"That invite code has already been used {self.used_count} "
                f"of {self.max_uses} times. Ask for a new one."
            )
        return None

    @classmethod
    def mint(
        cls,
        created_by: User,
        household: Household | None,
        *,
        creates_household: bool = False,
        max_uses: int = DEFAULT_INVITE_USES,
        expires_in_days: int = DEFAULT_INVITE_DAYS,
    ) -> tuple[Invite, str]:
        """Create an invite and return it alongside its code.

        Same shape as `DeviceToken.issue` for the same reason: the caller is
        handed the secret as a return value, at the one moment it is meant to
        travel, rather than fishing it back off the row later out of habit.

        Retries on the unique constraint rather than checking first, because
        checking first is a race - two mints in the same millisecond would
        both find the code free. Three attempts against a 72-bit space is
        already theatre; it is here so an astronomically unlikely collision
        is a retry and not a 500.
        """
        from django.db import IntegrityError
        from django.utils import timezone

        expires_at = timezone.now() + datetime.timedelta(days=expires_in_days)
        for _attempt in range(3):
            code = generate_invite_code()
            try:
                invite = cls.objects.create(
                    code=code,
                    household=household,
                    creates_household=creates_household,
                    created_by=created_by,
                    max_uses=max_uses,
                    expires_at=expires_at,
                )
            except IntegrityError:
                continue
            return invite, code
        raise RuntimeError(
            "No invite was created: three generated codes all collided with an existing "
            "one, which should be impossible at this code length. Nothing was written."
        )

    @classmethod
    def find(cls, code: str, *, for_update: bool = False) -> Invite | None:
        """The invite with this code, live or not, or None.

        The `filter` is an indexed equality lookup and Postgres's default
        collation is byte-exact, so `compare_digest` after it changes no
        outcome today. It is here because "the database compared it for us"
        is a claim about a collation setting, and a credential comparison
        that depends on one is a credential comparison nobody re-checks when
        the setting changes.

        `for_update=True` takes a row lock, which is what makes `max_uses`
        real: without it two signups redeeming the last use of the same code
        both read `used_count` before either writes, and both get in. It
        drops the `select_related` when it does, and that is not tidiness -
        Postgres refuses `FOR UPDATE` against the nullable side of an outer
        join, and `household` is nullable here by design, so the two cannot
        be combined at all. The caller inside the lock reads
        `invite.household` with one extra query instead.
        """
        if not code:
            return None
        queryset = cls.objects.select_for_update() if for_update else cls.objects.select_related(
            "household"
        )
        candidate = queryset.filter(code=code).first()
        if candidate is None:
            return None
        if not secrets.compare_digest(candidate.code, code):
            return None
        return candidate
