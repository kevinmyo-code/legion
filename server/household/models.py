"""Users, membership, and device tokens.

One household per server (ADR 0044, CLAUDE.md section 1: two adults, no
roles, no tenancy, ever). There is no signup and no invite flow - accounts
are made with `manage.py createsuperuser` or the admin, same ruling as
backend-erp ticket 02's dashboard-created-accounts call, moved from the
Supabase dashboard to this one.
"""
from __future__ import annotations

import hashlib
import secrets
import uuid

from django.contrib.auth.base_user import AbstractBaseUser, BaseUserManager
from django.contrib.auth.models import PermissionsMixin
from django.db import models


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
    """Django-side mirror of the `public.household_members` shape (kept in
    ticket 02) so `IsHouseholdMember` needs no raw SQL to answer 'is this
    user in the household'. One household per server, so this is really a
    flag, not a join table to a household entity that does not exist here.
    """

    user = models.OneToOneField(User, on_delete=models.CASCADE, related_name="household_member")
    joined_at = models.DateTimeField(auto_now_add=True)

    def __str__(self) -> str:
        return self.user.email
