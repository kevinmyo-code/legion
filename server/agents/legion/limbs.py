"""The limb contract (ADR 0098; engine-brain ticket 04, slice E1): what a device declares when it
attaches, and which brain answers its member's turns.

**Contract version N only.** `CONTRACT_VERSION` is the one version this engine serves. The ADR
asks for N and N-1; no N-1 client exists yet (the head unit brings one, ticket 14), so a manifest
naming any other version is refused in words and nothing is stored (plan 1119, "Deviation from the
ticket").

**The manifest** (one upload per conversation open, upserted by device):
- `kind`: phone or headunit; `app_version`: the client's own version string.
- `tools`: what the limb runs for the agents. Each has a `name`, a `description`, a JSON-schema
  `parameters` object, and the four flags the guards key on: `writes`, `read_through` (its result
  is outside text, ADR 0090), `actuates` (acts in the physical world) and `confirm` (`engine`: the
  engine's own confirm-before-write applies; `limb`: the limb runs its own confirm gate, as the
  phone's clear-codes yes rule does). `needs` names what the call needs on the device.
- `streams`: the data streams the limb can send (unused until ticket 11).
Nothing here relays a call yet (that is E2); the manifest is stored for it.

**Which brain** (plan 1119 section 4, flag precedence), first that is set wins:
1. `LEGION_AGENTS_BRAIN_FORCE` (phone or engine): every member, whatever the phone asks.
2. `Limb.brain_choice`: the server's override, set by an operator, never by an upload. Stored on
   this device's row, not yet per member (ticket 04, "E1 debts for E2" (g)).
3. `brain_asked` in the upload: the member's own Settings choice. Not stored: the client sends it
   at every upload (and, from E2, with every turn), so the phone's setting is the one source.
4. `LEGION_AGENTS_BRAIN_DEFAULT`, else `phone`.
An unset or unknown value at any step falls through to the next.

Device identity is the authenticated device token, never a body field: a household can only ever
upsert the row keyed by its own token.
"""

from __future__ import annotations

import json
import os

from django.utils import timezone
from rest_framework import serializers

from agents.models import BRAINS, LIMB_KINDS, Limb
from household.tenancy import household_of

CONTRACT_VERSION = 1
BRAIN_DEFAULT_ENV = "LEGION_AGENTS_BRAIN_DEFAULT"
BRAIN_FORCE_ENV = "LEGION_AGENTS_BRAIN_FORCE"
FALLBACK_BRAIN = "phone"

MAX_TOOLS = 128
MAX_STREAMS = 32
# The whole manifest as stored, so one device cannot park megabytes in a member's row. Sized from
# the phone's whole Live tool surface, ~74 KB of declarations (`LiveSetupPayloadSizeTest`, 11-19),
# with room to grow: a relayed subset is smaller.
MAX_MANIFEST_BYTES = 256 * 1024
# The phone's longest declaration is ~2,300 chars (`manage_checklist`, same test).
MAX_DESCRIPTION_CHARS = 4000
NAME_PATTERN = r"^[a-z][a-z0-9_]{0,63}$"
NEEDS = ("screen", "unlock", "parked")
CONFIRMS = ("engine", "limb")
BRAIN_REASONS = ("forced", "override", "asked", "default")
# The `code` beside every refusal's `detail`, so a client can branch without parsing words.
CODE_INVALID = "manifest_invalid"
CODE_UNSUPPORTED = "contract_unsupported"
CODE_NOT_A_DEVICE = "not_a_device"


def _has_nul(value) -> bool:
    """U+0000 anywhere in a key or a string. Postgres jsonb cannot store it, so it is refused here,
    in words, rather than surfacing as a database error after validation."""
    if isinstance(value, str):
        return "\x00" in value
    if isinstance(value, dict):
        return any(_has_nul(k) or _has_nul(v) for k, v in value.items())
    if isinstance(value, list):
        return any(_has_nul(v) for v in value)
    return False


class LimbToolSerializer(serializers.Serializer):
    name = serializers.RegexField(NAME_PATTERN)
    description = serializers.CharField(
        max_length=MAX_DESCRIPTION_CHARS, allow_blank=True, default=""
    )
    parameters = serializers.DictField(default=dict)
    writes = serializers.BooleanField()
    read_through = serializers.BooleanField()
    actuates = serializers.BooleanField()
    needs = serializers.ListField(
        child=serializers.ChoiceField(choices=NEEDS), max_length=len(NEEDS), default=list
    )
    confirm = serializers.ChoiceField(choices=CONFIRMS, default="engine")


class LimbManifestSerializer(serializers.Serializer):
    contract_version = serializers.IntegerField()
    kind = serializers.ChoiceField(choices=LIMB_KINDS)
    app_version = serializers.CharField(max_length=64)
    brain_asked = serializers.ChoiceField(choices=BRAINS, required=False, allow_null=True)
    tools = LimbToolSerializer(many=True, max_length=MAX_TOOLS)
    streams = serializers.ListField(
        child=serializers.RegexField(NAME_PATTERN), max_length=MAX_STREAMS, default=list
    )

    def validate_tools(self, tools):
        names = [t["name"] for t in tools]
        twice = sorted({n for n in names if names.count(n) > 1})
        if twice:
            raise serializers.ValidationError(f"Each tool is declared once; twice: {twice}.")
        return tools

    def validate(self, attrs):
        if _has_nul(_manifest(attrs)):
            raise serializers.ValidationError("A NUL character (U+0000) cannot be stored.")
        size = len(json.dumps(_manifest(attrs)).encode())
        if size > MAX_MANIFEST_BYTES:
            raise serializers.ValidationError(
                f"The manifest is {size} bytes; the limit is {MAX_MANIFEST_BYTES}."
            )
        return attrs


class LimbAnswerSerializer(serializers.Serializer):
    """What the upload answers (documented in `openapi.yaml`)."""

    limb_id = serializers.UUIDField()
    contract_version = serializers.IntegerField()
    brain = serializers.ChoiceField(choices=BRAINS)
    brain_reason = serializers.ChoiceField(choices=BRAIN_REASONS)


class LimbRefusalSerializer(serializers.Serializer):
    """A refusal on the limb route: words for a person, a code for the client."""

    detail = serializers.CharField()
    code = serializers.ChoiceField(choices=[CODE_INVALID, CODE_UNSUPPORTED, CODE_NOT_A_DEVICE])


def _manifest(attrs) -> dict:
    return {"tools": attrs["tools"], "streams": attrs["streams"]}


def _env_brain(name: str) -> str | None:
    value = os.environ.get(name, "").strip().lower()
    return value if value in BRAINS else None


def resolve_brain(limb: Limb, asked: str | None) -> tuple[str, str]:
    """(brain, why): the precedence in this module's docstring."""
    forced = _env_brain(BRAIN_FORCE_ENV)
    if forced:
        return forced, "forced"
    if limb.brain_choice in BRAINS:
        return limb.brain_choice, "override"
    if asked in BRAINS:
        return asked, "asked"
    return _env_brain(BRAIN_DEFAULT_ENV) or FALLBACK_BRAIN, "default"


def attach(request, attrs) -> dict:
    """Upsert the calling device's `Limb` row from a validated manifest; answer the brain.

    Keyed by `request.auth` (the device token the request authenticated with). `brain_choice` is
    never in the update, so an upload cannot clear or set the server's override."""
    limb, _created = Limb.objects.update_or_create(
        household=household_of(request),
        device=request.auth,
        defaults={
            "owner_user": request.user,
            "kind": attrs["kind"],
            "app_version": attrs["app_version"],
            "contract_version": attrs["contract_version"],
            "manifest": _manifest(attrs),
            "last_seen_at": timezone.now(),
        },
    )
    brain, why = resolve_brain(limb, attrs.get("brain_asked"))
    return {
        "limb_id": limb.pk,
        "contract_version": CONTRACT_VERSION,
        "brain": brain,
        "brain_reason": why,
    }
