"""ADR 0045: households are tenants.

Hand-written, not generated - three of its six operations are raw DDL over
forty-three `public` tables Django does not manage, and the two that ARE
generated (`CreateModel Household`, the two `AddField`s on
`HouseholdMember`) are kept in the same file so the order reads top to
bottom: make the household, put the existing people in it, then put every
existing row in it.

**This migration is NOT reversible, on purpose.** Going forwards it reads
each table's unique keys off the Postgres catalogs, drops them and rebuilds
them with `household_id` prepended. Backwards, it would have to know the
name each dropped key used to have - and it does not, because the name is
exactly what it read from a catalog that no longer holds it. A
`reverse_code` that dropped the columns and left the old unique keys
missing would report a successful reversal for a database that had silently
lost its constraints, which is worse than refusing. Restore from the
`pg_dump` the runbook takes first.

**Order matters on a FRESH stack, and this migration cannot enforce it.**
The forty legacy `public` tables are `managed = False` with no migrations of
their own - their DDL lives in `supabase/migrations/*.sql` and is applied by
hand. So on a brand-new database, `manage.py migrate` run BEFORE that SQL
finds forty of the forty-three tables missing, records a note per table
(printed by `add_household_column_everywhere` below), and marks itself
applied - after which it will never run again, and those forty tables carry
no `household_id`. `tests/test_tenancy.py`'s `information_schema` check is
what catches that state, and `manage.py tenancy_sql` is what shows what is
still owed. **Apply `supabase/migrations/` first, then `migrate`.** Written
here rather than left to be discovered; the fresh-clone runbook is owed and
is named in this ticket's report.

**Nothing in here is applied to the live database by this ticket.** Ticket
02 builds and proves it against the pytest test database; applying it is a
separate, audited step with a dump taken first. `manage.py tenancy_sql`
prints the exact statements it would run against whatever database it is
pointed at, without running them, so the plan can be read before it is
applied.
"""

import django.db.models.deletion
import uuid
from django.conf import settings
from django.db import migrations, models

from household.tenancy import TENANT_TABLES, bootstrap_household_id, bootstrap_household_name
from household.tenancy_sql import apply_all


def create_bootstrap_household(apps, schema_editor):
    """The one household every pre-tenancy row and every existing member is
    put in.

    Its id comes from `LEGION_BOOTSTRAP_HOUSEHOLD_ID` and is never minted
    here - `household.tenancy.bootstrap_household_id` refuses in words when
    the variable is unset or is not a uuid, rather than choosing a random one
    nobody wrote down. See that function for the argument.
    """
    Household = apps.get_model("household", "Household")
    Household.objects.get_or_create(
        id=bootstrap_household_id(),
        defaults={"name": bootstrap_household_name()},
    )


def adopt_existing_members(apps, schema_editor):
    """Every `HouseholdMember` that predates tenancy becomes an OWNER of the
    bootstrap household.

    Owner rather than member because ADR 0045's `owner` role exists only to
    invite and remove people, and a household whose every member is a plain
    member could never add a third - there would be nobody able to mint an
    invite. The two adults who were here before there were households are the
    people who own it.
    """
    HouseholdMember = apps.get_model("household", "HouseholdMember")
    HouseholdMember.objects.filter(household__isnull=True).update(
        household_id=bootstrap_household_id(), role="owner"
    )


def add_household_column_everywhere(apps, schema_editor):
    """`household/tenancy_sql.apply_all` over all forty-three tenant tables.

    The work is in that module rather than inline here because
    `tests/conftest.py` runs the SAME function against the pytest test
    database (where the forty legacy tables do not exist yet at `migrate`
    time, being `managed = False` with no migrations of their own), and
    `manage.py tenancy_sql` prints what it would do without doing it. One
    implementation, three callers, so what the suite proves is what the live
    run will execute.
    """
    with schema_editor.connection.cursor() as cursor:
        plans = apply_all(cursor, TENANT_TABLES, bootstrap_household_id())
    # Printed, not swallowed: on the live database every table should produce
    # statements, and a table that produced only a "no such table" note is
    # something the operator must see at the moment it happens rather than
    # discover later from a column that is not there.
    for plan in plans:
        for note in plan.notes:
            print(f"  [tenancy] {note}")


class Migration(migrations.Migration):

    dependencies = [
        ("household", "0001_initial"),
        # The three `checklists` tables live in `public` and are tenanted like
        # any other, so they have to EXIST before the SQL loop reaches them.
        ("checklists", "0001_initial"),
        migrations.swappable_dependency(settings.AUTH_USER_MODEL),
    ]

    operations = [
        migrations.CreateModel(
            name="Household",
            fields=[
                (
                    "id",
                    models.UUIDField(
                        default=uuid.uuid4,
                        editable=False,
                        primary_key=True,
                        serialize=False,
                    ),
                ),
                ("name", models.CharField(max_length=120)),
                ("created_at", models.DateTimeField(auto_now_add=True)),
                (
                    "created_by",
                    models.ForeignKey(
                        null=True,
                        on_delete=django.db.models.deletion.SET_NULL,
                        related_name="+",
                        to=settings.AUTH_USER_MODEL,
                    ),
                ),
            ],
        ),
        migrations.RunPython(create_bootstrap_household, migrations.RunPython.noop),
        # Nullable first, backfilled, then tightened - the standard three-step
        # for a NOT NULL column on a table that already has rows. The two
        # existing members have no household to point at until the step above
        # has run.
        migrations.AddField(
            model_name="householdmember",
            name="household",
            field=models.ForeignKey(
                null=True,
                on_delete=django.db.models.deletion.CASCADE,
                related_name="members",
                to="household.household",
            ),
        ),
        migrations.AddField(
            model_name="householdmember",
            name="role",
            field=models.CharField(
                choices=[("owner", "owner"), ("member", "member")],
                default="member",
                max_length=8,
            ),
        ),
        migrations.RunPython(adopt_existing_members, migrations.RunPython.noop),
        migrations.AlterField(
            model_name="householdmember",
            name="household",
            field=models.ForeignKey(
                on_delete=django.db.models.deletion.CASCADE,
                related_name="members",
                to="household.household",
            ),
        ),
        migrations.RunPython(add_household_column_everywhere),
    ]
