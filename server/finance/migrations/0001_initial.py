"""The household-finance tables (ADR 0073, tickets 01, 03, 07).

Every `finance_*` table is born tenanted in `public`, by the same temporary
search_path swap `devicelogs/migrations/0001_initial.py` uses. `fx_rates` is
the one table with no household and is created with the `django` schema first,
like every other non-tenant table Django owns.

ADDITIVE: new tables only, nothing existing touched.
"""

import django.db.models.deletion
import django.db.models.functions.datetime
import uuid
from django.conf import settings
from django.db import migrations, models


_PUBLIC_FIRST = migrations.RunSQL(
    sql="SET search_path TO public, django;",
    reverse_sql="SET search_path TO django, public;",
)

_DJANGO_FIRST = migrations.RunSQL(
    sql="SET search_path TO django, public;",
    reverse_sql="SET search_path TO public, django;",
)


class Migration(migrations.Migration):

    initial = True

    dependencies = [
        ("household", "0008_demo_visit_delete_exemption"),
        migrations.swappable_dependency(settings.AUTH_USER_MODEL),
    ]

    operations = [
        _PUBLIC_FIRST,
        migrations.CreateModel(
            name="FinanceMember",
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
                ("key", models.TextField()),
                ("label", models.TextField()),
                ("payer_names", models.JSONField(blank=True, default=list)),
                (
                    "created_at",
                    models.DateTimeField(
                        db_default=django.db.models.functions.datetime.Now()
                    ),
                ),
                (
                    "updated_at",
                    models.DateTimeField(
                        db_default=django.db.models.functions.datetime.Now()
                    ),
                ),
                (
                    "household",
                    models.ForeignKey(
                        on_delete=django.db.models.deletion.PROTECT,
                        related_name="+",
                        to="household.household",
                    ),
                ),
                (
                    "user",
                    models.ForeignKey(
                        blank=True,
                        null=True,
                        on_delete=django.db.models.deletion.SET_NULL,
                        related_name="+",
                        to=settings.AUTH_USER_MODEL,
                    ),
                ),
            ],
            options={
                "db_table": "finance_members",
            },
        ),
        migrations.CreateModel(
            name="FinanceAccount",
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
                ("key", models.TextField()),
                ("label", models.TextField()),
                ("institution", models.TextField(blank=True, default="")),
                ("country", models.TextField()),
                ("currency", models.TextField()),
                (
                    "kind",
                    models.TextField(
                        choices=[
                            ("depository", "Depository"),
                            ("credit", "Credit"),
                            ("brokerage", "Brokerage"),
                            ("bond", "Bond"),
                            ("cpf", "Cpf"),
                            ("property", "Property"),
                            ("loan", "Loan"),
                            ("vehicle", "Vehicle"),
                        ]
                    ),
                ),
                (
                    "layer",
                    models.TextField(
                        choices=[
                            ("liquid", "Liquid"),
                            ("investments", "Investments"),
                            ("cpf", "Cpf"),
                            ("property", "Property"),
                            ("other", "Other"),
                        ]
                    ),
                ),
                (
                    "source",
                    models.TextField(
                        choices=[
                            ("plaid", "Plaid"),
                            ("statement", "Statement"),
                            ("ibkr_flex", "Ibkr Flex"),
                            ("computed", "Computed"),
                            ("manual", "Manual"),
                        ]
                    ),
                ),
                ("last4", models.TextField(blank=True, null=True)),
                ("plaid_account_id", models.TextField(blank=True, null=True)),
                ("statement_match", models.TextField(blank=True, default="")),
                ("active", models.BooleanField(default=True)),
                (
                    "created_at",
                    models.DateTimeField(
                        db_default=django.db.models.functions.datetime.Now()
                    ),
                ),
                (
                    "updated_at",
                    models.DateTimeField(
                        db_default=django.db.models.functions.datetime.Now()
                    ),
                ),
                (
                    "household",
                    models.ForeignKey(
                        on_delete=django.db.models.deletion.PROTECT,
                        related_name="+",
                        to="household.household",
                    ),
                ),
                (
                    "owner",
                    models.ForeignKey(
                        blank=True,
                        null=True,
                        on_delete=django.db.models.deletion.PROTECT,
                        related_name="accounts",
                        to="finance.financemember",
                    ),
                ),
            ],
            options={
                "db_table": "finance_accounts",
            },
        ),
        migrations.CreateModel(
            name="FinancePolicy",
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
                ("currency", models.TextField(default="USD")),
                ("liquid_floor_cents", models.BigIntegerField(blank=True, null=True)),
                (
                    "mortgage_monthly_cents",
                    models.BigIntegerField(blank=True, null=True),
                ),
                ("mortgage_currency", models.TextField(default="SGD")),
                ("mortgage_in_ledger", models.BooleanField(default=False)),
                ("us_bills_30d_cents", models.BigIntegerField(blank=True, null=True)),
                ("topup_warn_days", models.IntegerField(default=7)),
                ("card_due_day", models.IntegerField(blank=True, null=True)),
                ("transfer_lead_days", models.IntegerField(default=7)),
                ("fixed_categories", models.JSONField(blank=True, default=list)),
                (
                    "updated_at",
                    models.DateTimeField(
                        db_default=django.db.models.functions.datetime.Now()
                    ),
                ),
                (
                    "household",
                    models.OneToOneField(
                        on_delete=django.db.models.deletion.PROTECT,
                        related_name="+",
                        to="household.household",
                    ),
                ),
                (
                    "topup_card",
                    models.ForeignKey(
                        blank=True,
                        null=True,
                        on_delete=django.db.models.deletion.SET_NULL,
                        related_name="+",
                        to="finance.financeaccount",
                    ),
                ),
                (
                    "topup_checking",
                    models.ForeignKey(
                        blank=True,
                        null=True,
                        on_delete=django.db.models.deletion.SET_NULL,
                        related_name="+",
                        to="finance.financeaccount",
                    ),
                ),
            ],
            options={
                "db_table": "finance_policies",
            },
        ),
        _DJANGO_FIRST,
        migrations.CreateModel(
            name="FxRate",
            fields=[
                ("id", models.BigAutoField(primary_key=True, serialize=False)),
                ("on_date", models.DateField()),
                ("base", models.TextField()),
                ("quote", models.TextField()),
                ("rate_micros", models.BigIntegerField()),
                ("source", models.TextField()),
                (
                    "fetched_at",
                    models.DateTimeField(
                        db_default=django.db.models.functions.datetime.Now()
                    ),
                ),
            ],
            options={
                "db_table": "fx_rates",
                "constraints": [
                    models.UniqueConstraint(
                        fields=("on_date", "base", "quote"),
                        name="fx_rates_day_pair_uniq",
                    ),
                    models.CheckConstraint(
                        condition=models.Q(("rate_micros__gt", 0)),
                        name="fx_rates_rate_positive",
                    ),
                ],
            },
        ),
        _PUBLIC_FIRST,
        migrations.CreateModel(
            name="IncomePlan",
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
                ("key", models.TextField()),
                ("label", models.TextField()),
                ("amount_cents", models.BigIntegerField()),
                ("currency", models.TextField()),
                (
                    "cadence",
                    models.TextField(
                        choices=[
                            ("weekly", "Weekly"),
                            ("biweekly", "Biweekly"),
                            ("monthly", "Monthly"),
                            ("yearly", "Yearly"),
                            ("once", "Once"),
                        ]
                    ),
                ),
                ("start_on", models.DateField(blank=True, null=True)),
                ("end_on", models.DateField(blank=True, null=True)),
                (
                    "confidence",
                    models.TextField(
                        choices=[
                            ("confirmed", "Confirmed"),
                            ("expected", "Expected"),
                            ("fallback", "Fallback"),
                        ]
                    ),
                ),
                (
                    "created_at",
                    models.DateTimeField(
                        db_default=django.db.models.functions.datetime.Now()
                    ),
                ),
                (
                    "updated_at",
                    models.DateTimeField(
                        db_default=django.db.models.functions.datetime.Now()
                    ),
                ),
                (
                    "account",
                    models.ForeignKey(
                        blank=True,
                        null=True,
                        on_delete=django.db.models.deletion.PROTECT,
                        related_name="+",
                        to="finance.financeaccount",
                    ),
                ),
                (
                    "household",
                    models.ForeignKey(
                        on_delete=django.db.models.deletion.PROTECT,
                        related_name="+",
                        to="household.household",
                    ),
                ),
                (
                    "member",
                    models.ForeignKey(
                        blank=True,
                        null=True,
                        on_delete=django.db.models.deletion.PROTECT,
                        related_name="+",
                        to="finance.financemember",
                    ),
                ),
            ],
            options={
                "db_table": "finance_income_plans",
            },
        ),
        migrations.CreateModel(
            name="BalanceSnapshot",
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
                ("as_of", models.DateField()),
                ("amount_cents", models.BigIntegerField()),
                ("currency", models.TextField()),
                (
                    "source",
                    models.TextField(
                        choices=[
                            ("plaid", "Plaid"),
                            ("statement", "Statement"),
                            ("ibkr_flex", "Ibkr Flex"),
                            ("computed", "Computed"),
                            ("manual", "Manual"),
                        ]
                    ),
                ),
                ("evidence", models.TextField(blank=True, default="")),
                ("is_estimate", models.BooleanField(default=False)),
                ("pending_cents", models.BigIntegerField(blank=True, null=True)),
                ("pending_count", models.IntegerField(default=0)),
                (
                    "recorded_at",
                    models.DateTimeField(
                        db_default=django.db.models.functions.datetime.Now()
                    ),
                ),
                (
                    "household",
                    models.ForeignKey(
                        on_delete=django.db.models.deletion.PROTECT,
                        related_name="+",
                        to="household.household",
                    ),
                ),
                (
                    "account",
                    models.ForeignKey(
                        on_delete=django.db.models.deletion.PROTECT,
                        related_name="snapshots",
                        to="finance.financeaccount",
                    ),
                ),
            ],
            options={
                "db_table": "finance_balance_snapshots",
                "indexes": [
                    models.Index(
                        fields=["account", "-as_of"],
                        name="finance_snapshots_account_day",
                    )
                ],
                "constraints": [
                    models.UniqueConstraint(
                        fields=("account", "as_of", "source"),
                        name="finance_balance_snapshots_account_day_source_uniq",
                    ),
                    models.CheckConstraint(
                        condition=models.Q(("currency__in", ["USD", "SGD"])),
                        name="finance_balance_snapshots_currency_valid",
                    ),
                    models.CheckConstraint(
                        condition=models.Q(
                            (
                                "source__in",
                                [
                                    "plaid",
                                    "statement",
                                    "ibkr_flex",
                                    "computed",
                                    "manual",
                                ],
                            )
                        ),
                        name="finance_balance_snapshots_source_valid",
                    ),
                ],
            },
        ),
        migrations.AddConstraint(
            model_name="financemember",
            constraint=models.UniqueConstraint(
                fields=("household", "key"), name="finance_members_household_key_uniq"
            ),
        ),
        migrations.AddConstraint(
            model_name="financeaccount",
            constraint=models.UniqueConstraint(
                fields=("household", "key"), name="finance_accounts_household_key_uniq"
            ),
        ),
        migrations.AddConstraint(
            model_name="financeaccount",
            constraint=models.CheckConstraint(
                condition=models.Q(("currency__in", ["USD", "SGD"])),
                name="finance_accounts_currency_valid",
            ),
        ),
        migrations.AddConstraint(
            model_name="financeaccount",
            constraint=models.CheckConstraint(
                condition=models.Q(("country__in", ["US", "SG"])),
                name="finance_accounts_country_valid",
            ),
        ),
        migrations.AddConstraint(
            model_name="financeaccount",
            constraint=models.CheckConstraint(
                condition=models.Q(
                    (
                        "kind__in",
                        [
                            "depository",
                            "credit",
                            "brokerage",
                            "bond",
                            "cpf",
                            "property",
                            "loan",
                            "vehicle",
                        ],
                    )
                ),
                name="finance_accounts_kind_valid",
            ),
        ),
        migrations.AddConstraint(
            model_name="financeaccount",
            constraint=models.CheckConstraint(
                condition=models.Q(
                    ("layer__in", ["liquid", "investments", "cpf", "property", "other"])
                ),
                name="finance_accounts_layer_valid",
            ),
        ),
        migrations.AddConstraint(
            model_name="financeaccount",
            constraint=models.CheckConstraint(
                condition=models.Q(
                    (
                        "source__in",
                        ["plaid", "statement", "ibkr_flex", "computed", "manual"],
                    )
                ),
                name="finance_accounts_source_valid",
            ),
        ),
        migrations.AddConstraint(
            model_name="financeaccount",
            constraint=models.CheckConstraint(
                condition=models.Q(
                    ("last4__isnull", True),
                    ("last4__regex", "^[0-9]{4}$"),
                    _connector="OR",
                ),
                name="finance_accounts_last4_four_digits",
            ),
        ),
        migrations.AddConstraint(
            model_name="financepolicy",
            constraint=models.CheckConstraint(
                condition=models.Q(("currency__in", ["USD", "SGD"])),
                name="finance_policies_currency_valid",
            ),
        ),
        migrations.AddConstraint(
            model_name="financepolicy",
            constraint=models.CheckConstraint(
                condition=models.Q(("mortgage_currency__in", ["USD", "SGD"])),
                name="finance_policies_mortgage_currency_valid",
            ),
        ),
        migrations.AddConstraint(
            model_name="financepolicy",
            constraint=models.CheckConstraint(
                condition=models.Q(
                    ("card_due_day__isnull", True),
                    models.Q(("card_due_day__gte", 1), ("card_due_day__lte", 31)),
                    _connector="OR",
                ),
                name="finance_policies_card_due_day_valid",
            ),
        ),
        migrations.AddConstraint(
            model_name="incomeplan",
            constraint=models.UniqueConstraint(
                fields=("household", "key"),
                name="finance_income_plans_household_key_uniq",
            ),
        ),
        migrations.AddConstraint(
            model_name="incomeplan",
            constraint=models.CheckConstraint(
                condition=models.Q(("currency__in", ["USD", "SGD"])),
                name="finance_income_plans_currency_valid",
            ),
        ),
        migrations.AddConstraint(
            model_name="incomeplan",
            constraint=models.CheckConstraint(
                condition=models.Q(
                    ("cadence__in", ["weekly", "biweekly", "monthly", "yearly", "once"])
                ),
                name="finance_income_plans_cadence_valid",
            ),
        ),
        migrations.AddConstraint(
            model_name="incomeplan",
            constraint=models.CheckConstraint(
                condition=models.Q(
                    ("confidence__in", ["confirmed", "expected", "fallback"])
                ),
                name="finance_income_plans_confidence_valid",
            ),
        ),
        migrations.AddConstraint(
            model_name="incomeplan",
            constraint=models.CheckConstraint(
                condition=models.Q(("amount_cents__gte", 0)),
                name="finance_income_plans_amount_nonneg",
            ),
        ),
        _DJANGO_FIRST,
    ]
