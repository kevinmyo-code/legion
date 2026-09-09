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
# Forty-three names. The forty-fourth `public` table, `household_members`, is
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
    # pantry
    "grocery_staples",
    "meal_logs",
    "meal_targets",
    "receipts",
    "receipt_line_items",
    # ingest
    "ingested_files",
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

# The three tables Django itself owns the DDL for (`checklists/models.py`,
# `managed = True`). They are in `public` alongside the legacy forty and the
# migration's SQL loop treats them exactly like the rest - one code path, so
# one set of behaviours to reason about. What they need EXTRA is a
# state-only migration (`checklists/migrations/0002_household.py`) telling
# Django's model state about a column the SQL already created; without it
# `makemigrations` would propose adding it a second time.
DJANGO_MANAGED_TENANT_TABLES: frozenset[str] = frozenset(
    {"checklists", "checklist_items", "checklist_ticks"}
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
