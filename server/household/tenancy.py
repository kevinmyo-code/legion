"""The one list of tenanted tables, and the helpers that scope a query to a
household.

ADR 0045 ("Households are tenants"): every data row belongs to exactly one
household, every user belongs to exactly one household, and a member sees
everything in their household and nothing outside it. That is one column,
`household_id uuid NOT NULL`, on every data table - and one list naming
those tables, because three copies of a forty-name list is three chances to
miss one. The migration that adds the column, `api/registry.py`'s coverage
check, and `tests/test_tenancy.py` all read `TENANT_TABLES` from here.

`tests/test_tenancy.py` closes the loop the other way as well: it reads
`information_schema.columns` on the test database and asserts the set of
`public` tables carrying a `household_id` column is EXACTLY this list. A
table added to the schema and forgotten here fails there, and a name typed
here that no table carries fails there too.
"""
from __future__ import annotations

import os
import uuid

# Every `public` table that carries `household_id`, in aspect order - the same
# grouping `api/registry.py` uses, so the two read down in the same order.
#
# Fifty names. The fifty-first `public` table, `household_members`, is
# DELIBERATELY ABSENT and its absence is the one thing in this file that is a
# judgement rather than an inventory:
#
#   `public.household_members` is the Supabase-Auth-era membership roster
#   (`legacy/models/household.py`), keyed on `auth.users(id)` in a schema
#   Django does not own. It is superseded by `household_householdmember`,
#   which ticket 02 gives its own `household_id`, and it is read by no view,
#   no serializer and no permission class in this server - only by the
#   `LEGION_PG_URL`-gated round-trip suite. Putting a tenancy column on the
#   roster that tenancy replaces would be adding a scope to a table with no
#   reader; leaving it out means it never appears in a scoped query, because
#   there is no scoped query over it to appear in.
#
#   **What that leaves for ticket 02b (RLS):** every other `public` table gets
#   a policy keyed on `household_id`. This one cannot, so 02b must decide
#   between a deny-all policy and leaving it unprotected, and must decide it
#   in words rather than by skipping the table because the loop over
#   TENANT_TABLES did not reach it. Named here so it cannot be missed.
TENANT_TABLES: tuple[str, ...] = (
    # dates
    "events",
    "event_skips",
    # checklists (Django-managed, `public` schema - see checklists/models.py)
    "checklists",
    "checklist_items",
    "checklist_ticks",
    # places
    "places",
    # notes
    "voice_notes",
    "item_lists",
    "list_items",
    # body
    "bodyweight_logs",
    "sleep_logs",
    "sleep_targets",
    "workout_plans",
    "workout_plan_items",
    "workout_set_logs",
    "goals",
    # memory
    "memories",
    "memory_audit",
    "companion_memories",
    "conversation_audit",
    # ledger
    "categories",
    "category_rules",
    "budget_targets",
    "statements",
    "ledger_transactions",
    # backend-etl ticket 14 option 2: a category laid over a gated row. Born
    # tenanted; its household must equal its transaction's, by composite FK
    # (`ingest/category_overrides.py`).
    "ledger_transaction_categories",
    # pantry
    "grocery_staples",
    "meal_logs",
    "meal_targets",
    "receipts",
    "receipt_line_items",
    # ingest
    "ingested_files",
    # Django-managed, `public` schema, like checklists (see ingest/models.py).
    # backend-etl ticket 01: one row per run of one scheduled pipeline.
    "ingest_runs",
    # backend-etl ticket 02: the session vault. Ciphertext only; the key is
    # LEGION_VAULT_KEY in the environment, never in the database.
    "source_credentials",
    # engine-mcp ticket 10 (ticket 08's audit ruling): one row per `/mcp`
    # call, Django-managed, born tenanted like `ingest_runs`.
    "mcp_calls",
    # web-revamp ticket 15: Web Push, Django-managed and born tenanted like
    # `ingest_runs` (`push/models.py`).
    "push_subscriptions",
    "push_preferences",
    "push_sent",
    # fleet
    "vehicles",
    "vehicle_specs",
    "drives",
    "drive_reassignments",
    "code_events",
    "code_clear_events",
    "obd_samples",
    "oil_analyses",
    "service_history",
    "maintenance_schedules",
    "chassis_quirks",
    "build_entries",
)

# The tables Django itself owns the DDL for (`managed = True`). They are in
# `public` alongside the legacy forty.
#
# The three `checklists` tables predate tenancy, so the migration's SQL loop
# added their column exactly like the rest - one code path, one set of
# behaviours to reason about - and they need EXTRA a state-only migration
# (`checklists/migrations/0002_household.py`) telling Django's model state
# about a column the SQL already created; without it `makemigrations` would
# propose adding it a second time.
#
# `ingest_runs` (backend-etl ticket 01, `ingest/models.py`) is born tenanted:
# its `household` is an ordinary ForeignKey in its own first migration, so the
# SQL loop finds the column already there and has nothing to do. So is
# `source_credentials` (ticket 02, same file, same pattern).
DJANGO_MANAGED_TENANT_TABLES: frozenset[str] = frozenset(
    {
        "checklists",
        "checklist_items",
        "checklist_ticks",
        "ingest_runs",
        "source_credentials",
        "mcp_calls",
        "push_subscriptions",
        "push_preferences",
        "push_sent",
    }
)

BOOTSTRAP_ID_ENV = "LEGION_BOOTSTRAP_HOUSEHOLD_ID"
BOOTSTRAP_NAME_ENV = "LEGION_BOOTSTRAP_HOUSEHOLD_NAME"
DEFAULT_BOOTSTRAP_NAME = "Home"


def bootstrap_household_id() -> uuid.UUID:
    """The uuid of the household every pre-tenancy row is backfilled into.

    Read from the environment and never minted here. A random uuid would be
    correct exactly once - on the machine that ran the migration - and then
    every other environment (a second engine, a restored dump, a fresh
    clone's compose stack) would hold a DIFFERENT id for the same household,
    with nothing written down anywhere to reconcile them. So this refuses in
    words rather than choosing, the same posture `legion/settings.required_env`
    takes for a secret.
    """
    raw = os.environ.get(BOOTSTRAP_ID_ENV, "").strip()
    if not raw:
        raise RuntimeError(
            f"{BOOTSTRAP_ID_ENV} is not set, so there is no household to put the "
            f"existing rows in. Nothing was migrated. Choose a uuid once, write it "
            f"into deploy/.env (see deploy/.env.example), and keep it: it is the id "
            f"every backfilled row will carry, in every environment this database is "
            f"ever restored into. Generate one with "
            f"`python -c \"import uuid; print(uuid.uuid4())\"`."
        )
    try:
        return uuid.UUID(raw)
    except ValueError as exc:
        raise RuntimeError(
            f"{BOOTSTRAP_ID_ENV} is {raw!r}, which is not a uuid. Nothing was "
            f"migrated. It must be a uuid because it becomes "
            f"`household_household.id`, a uuid column - and a random one is not "
            f"substituted for it, deliberately: see bootstrap_household_id()."
        ) from exc


def bootstrap_household_name() -> str:
    return os.environ.get(BOOTSTRAP_NAME_ENV, "").strip() or DEFAULT_BOOTSTRAP_NAME


def household_of(request):
    """The household every read and every write in this request is scoped to.

    ADR 0045's choke point in one function. `IsHouseholdMember` has already
    refused a request from a user who belongs to no household by the time any
    view body runs, so the refusal below is unreachable through the routed API
    - it exists because "unreachable" is a claim about today's URL map, and the
    alternative to raising here is returning an UNSCOPED queryset, which is the
    one outcome this whole ticket exists to make impossible. Fail closed, in
    words.
    """
    from rest_framework.exceptions import PermissionDenied

    user = getattr(request, "user", None)
    household = getattr(user, "household", None)
    if household is None:
        raise PermissionDenied(
            "Nothing was read or written. This account belongs to no household, so "
            "there is no set of rows it can see. An owner has to add it to one."
        )
    return household


def scoped(model, request):
    """`model.objects` narrowed to the request's household. Every read path in
    `api/`, `checklists/` and `ingest/` goes through this or through an
    explicit `household=` filter; a bare `Model.objects.filter(...)` in those
    packages is a defect, and `tests/test_tenancy.py` is what catches it from
    the outside."""
    return model.objects.filter(household=household_of(request))


def resolve_default_household():
    """The household a NEW member joins when nobody said which, or None.

    web-and-households ticket 03 owns signup, invites and joining. Until it
    lands there are still two doors that create members - `createsuperuser`
    (through `household/signals.py`) and `manage.py add_household_member` -
    and ADR 0045 means neither can assume there is only one household to put
    them in any more. This answers the question those two ask, in the only
    order that is defensible:

    1. the household `LEGION_BOOTSTRAP_HOUSEHOLD_ID` names, if it exists. It
       is the one the operator chose and wrote into `deploy/.env`;
    2. otherwise the ONLY household, if there is exactly one. A fresh clone's
       compose stack is this case, and it is the whole of clone-and-run;
    3. otherwise None, and the caller refuses in words. On an engine holding
       several families there is no honest guess: picking the oldest, or the
       first alphabetically, would put a person in someone else's house and
       report success.

    Reads the environment through `bootstrap_household_id`, so an unset or
    malformed variable falls through to case 2 rather than crashing a command
    that would otherwise have worked - unlike the migration, where the id IS
    the answer and a missing one has to stop everything.
    """
    from household.models import Household

    try:
        wanted = bootstrap_household_id()
    except RuntimeError:
        wanted = None
    if wanted is not None:
        household = Household.objects.filter(id=wanted).first()
        if household is not None:
            return household
    if Household.objects.count() == 1:
        return Household.objects.first()
    return None


# =============================================================================
# ADR 0052: a row may be private to one member
# =============================================================================
#
# Tenancy is still by household (`scoped` above). Inside one household,
# `events` and `checklists` carry `owner_user_id`: null is shared, set is
# private to that member, and `event_skips`, `checklist_items` and
# `checklist_ticks` inherit their parent's. `visible()` is the filter every
# read and write path for those five tables goes through, MCP included,
# because `engine_mcp/dispatch.py` runs the routed views. Another member's
# private row is then a 404, the same shape as another household's row.
#
# The database refuses an owner who is not a member of the row's household
# (`household/visibility_sql.py`); `tests/test_visibility.py` proves member
# isolation inside one household the way `tests/test_tenancy.py` proves
# household isolation. Neither substitutes for the other.

VISIBILITY_SHARED = "shared"
VISIBILITY_PRIVATE = "private"
VISIBILITY_CHOICES: tuple[str, ...] = (VISIBILITY_SHARED, VISIBILITY_PRIVATE)

# Spec D3, verbatim: the 403 when a member who did not add a shared row tries
# to make it private.
MAKE_PRIVATE_REFUSAL = "Only the person who added this can make it private."
# Unreachable through the routed API (another member's private row is a 404
# before any change is considered), and written down anyway so the rule is
# stated in words rather than implied by a lookup.
MAKE_SHARED_REFUSAL = "Only the person this is private to can share it."

# The lookup from each privacy-bearing table to the column that decides it.
# Keyed by table, so a model added to this list is one line, and a model
# missing from it fails loudly in `_owner_path` rather than reading unfiltered.
OWNER_PATHS: dict[str, str] = {
    "events": "owner_user",
    "event_skips": "event__owner_user",
    "checklists": "owner_user",
    "checklist_items": "checklist__owner_user",
    "checklist_ticks": "item__checklist__owner_user",
}

# For an inheriting table, the parent's `updated_at`. Turning a parent private
# moves the parent's `updated_at` and not the child's, so a feed that only
# compared the child's own timestamp would never tell a replica holding the
# child that it is gone. `feed_rows` compares the later of the two.
PARENT_UPDATED_AT: dict[str, str] = {
    "event_skips": "event__updated_at",
    "checklist_items": "checklist__updated_at",
    "checklist_ticks": "item__checklist__updated_at",
}


def _owner_path(model) -> str:
    table = model._meta.db_table
    try:
        return OWNER_PATHS[table]
    except KeyError as exc:
        raise RuntimeError(
            f"Nothing was read. {table} carries no member privacy, so visible() has no rule "
            f"for it; use scoped() for a table every member sees in full."
        ) from exc


def _user_pk(request):
    user = getattr(request, "user", None)
    pk = getattr(user, "pk", None)
    if pk is None:
        from rest_framework.exceptions import PermissionDenied

        raise PermissionDenied(
            "Nothing was read or written. There is no signed-in member, so there is no way to "
            "tell which private rows are theirs."
        )
    return pk


def visible(model, request):
    """`scoped(model, request)` narrowed to what this member may see: every
    shared row, and their own private ones. The ADR 0052 choke point."""
    from django.db.models import Q

    path = _owner_path(model)
    return scoped(model, request).filter(
        Q(**{f"{path}__isnull": True}) | Q(**{path: _user_pk(request)})
    )


def private_to_someone_else(model, request):
    """The complement of `visible` inside the household: rows private to
    another member. Read only to emit redacted tombstones, never served."""
    path = _owner_path(model)
    return (
        scoped(model, request)
        .filter(**{f"{path}__isnull": False})
        .exclude(**{path: _user_pk(request)})
    )


def visibility_of(row) -> str:
    return VISIBILITY_PRIVATE if row.owner_user_id is not None else VISIBILITY_SHARED


def owner_after_change(row, wanted: str, user) -> tuple[object, str | None]:
    """The `owner_user_id` a change to `wanted` leaves, or a refusal sentence.

    Spec D3: the owner may make a private row shared. A shared row may be made
    private by its creator, or by any member when the creator is unknown
    (legacy rows, `created_by_id` null). Anything else is refused in words.
    Asking for what the row already is changes nothing and is never refused.
    """
    current = row.owner_user_id
    if wanted == VISIBILITY_SHARED:
        if current is None or current == user.pk:
            return None, None
        return current, MAKE_SHARED_REFUSAL
    if current is not None:
        return (current, None) if current == user.pk else (current, MAKE_SHARED_REFUSAL)
    if row.created_by_id is None or row.created_by_id == user.pk:
        return user.pk, None
    return None, MAKE_PRIVATE_REFUSAL


def redacted_tombstone(row_id, at) -> dict:
    """What a replica is told about a row it may no longer see: that the id
    is gone, and when. Nothing else, by construction: the dict is built here
    from two values, never by stripping fields off a serialized row."""
    from rest_framework.fields import DateTimeField

    stamp = DateTimeField().to_representation(at)
    return {"id": str(row_id), "deleted_at": stamp, "updated_at": stamp, "redacted": True}


def render_rows(rows, serializer_cls, request, *, owner_of=None, changed_at_of=None) -> list:
    """Serialize `rows` in order: a row this member may see in full, a row
    private to someone else as a redacted tombstone.

    `owner_of(row)` is the deciding owner id (the row's own by default);
    `changed_at_of(row)` the instant the tombstone carries (`updated_at` by
    default). The caller passes rows from `scoped()`, never from outside the
    household.
    """
    me = _user_pk(request)
    owner_of = owner_of or (lambda row: row.owner_user_id)
    changed_at_of = changed_at_of or (lambda row: row.updated_at)
    rows = list(rows)
    shown = [row for row in rows if owner_of(row) in (None, me)]
    full = iter(serializer_cls(shown, many=True).data)
    out = []
    for row in rows:
        if owner_of(row) in (None, me):
            out.append(next(full))
        else:
            out.append(redacted_tombstone(row.pk, changed_at_of(row)))
    return out


def feed_rows(model, request, since):
    """Rows of a privacy-bearing table for an unpaged `?since=` feed
    (`api/changes.py`): every visible row changed at or after `since`, plus
    every row private to someone else whose own change OR whose parent's
    change is at or after it. Annotated with `_owner` and `_changed_at` for
    `render_feed`. Ordered by `updated_at`, then id."""
    from django.db.models import F, Q
    from django.db.models.functions import Greatest

    path = _owner_path(model)
    parent = PARENT_UPDATED_AT.get(model._meta.db_table)
    changed = Greatest(F("updated_at"), F(parent)) if parent else F("updated_at")
    me = _user_pk(request)
    shown = Q(**{f"{path}__isnull": True}) | Q(**{path: me})
    return (
        scoped(model, request)
        .annotate(_owner=F(path), _changed_at=changed)
        .filter((shown & Q(updated_at__gte=since)) | (~shown & Q(_changed_at__gte=since)))
        .order_by("updated_at", "pk")
    )


def render_feed(rows, serializer_cls, request) -> list:
    """`render_rows` over `feed_rows`' annotations."""
    return render_rows(
        rows,
        serializer_cls,
        request,
        owner_of=lambda row: row._owner,
        changed_at_of=lambda row: row._changed_at,
    )
