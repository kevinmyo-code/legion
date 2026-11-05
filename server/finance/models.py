"""Household finance (household-finance map, ADR 0073): the household's
accounts across two countries, a dated balance per account, the day's FX
rate, the income plan and the limits Kevin set.

**No figure of the real household is in this repo.** Every account, income
and limit is loaded from a private file outside it by
`manage.py load_household_finance <file>` (ticket 12); tests use invented
households (`tests/finance_synthetic.py`).

**Money is int cents, FX is int `rate_micros`** (CLAUDE.md section 4 rule 3).
A debt balance is NEGATIVE: a card owing 100.00 is `-10000`.

Born tenanted in `public`, like `device_logs`: every table but `fx_rates`
carries `household` as an ordinary foreign key, is listed in
`household.tenancy.TENANT_TABLES`, and is read through `scoped()` /
`household_of()`. **Every finance row is visible to every member** (map
ruling, Kevin 2026-11-05: one household, no private finance rows), so none of
these carries an owner that hides it; `FinanceAccount.owner` says whose
account it is, never who may see it.

`fx_rates` is the one table with no household: a USD/SGD rate on a day is a
fact about the world, the same for every family on the engine. It lives in
the `django` schema like every other non-tenant table Django owns.
"""

from __future__ import annotations

import uuid

from django.db import models
from django.db.models.functions import Now

CURRENCIES = ("USD", "SGD")
COUNTRIES = ("US", "SG")


class Kind(models.TextChoices):
    DEPOSITORY = "depository"
    CREDIT = "credit"
    BROKERAGE = "brokerage"
    BOND = "bond"
    CPF = "cpf"
    PROPERTY = "property"
    LOAN = "loan"
    VEHICLE = "vehicle"


# A balance on one of these is money owed: stored negative.
DEBT_KINDS = frozenset({Kind.CREDIT, Kind.LOAN})


class Layer(models.TextChoices):
    """Map ruling 2. Runway counts `liquid` and `investments` only."""

    LIQUID = "liquid"
    INVESTMENTS = "investments"
    CPF = "cpf"
    PROPERTY = "property"
    OTHER = "other"


RUNWAY_LAYERS = frozenset({Layer.LIQUID, Layer.INVESTMENTS})


class SourceKind(models.TextChoices):
    """Where an account's balance comes from (map ruling 9)."""

    PLAID = "plaid"
    STATEMENT = "statement"
    IBKR_FLEX = "ibkr_flex"
    COMPUTED = "computed"
    MANUAL = "manual"


# A balance from one of these is worked out, not stated by anyone: an estimate
# (CLAUDE.md section 4 rule 5).
ESTIMATE_SOURCES = frozenset({SourceKind.COMPUTED})


class Confidence(models.TextChoices):
    CONFIRMED = "confirmed"
    EXPECTED = "expected"
    FALLBACK = "fallback"


class Cadence(models.TextChoices):
    WEEKLY = "weekly"
    BIWEEKLY = "biweekly"
    MONTHLY = "monthly"
    YEARLY = "yearly"
    ONCE = "once"


def _choices_check(field: str, choices, name: str) -> models.CheckConstraint:
    return models.CheckConstraint(
        condition=models.Q(**{f"{field}__in": [c for c in choices]}), name=name
    )


class FinanceMember(models.Model):
    """A person in the household's money, for ticket 02: the names the bank
    prints when money moves to or from them (`payer_names`, e.g. the name a
    Zelle line carries). Loaded from the private file, never hard-coded.

    `user` links to a LEGION sign-in when the person has one; a spouse who
    never signs in is still a member of the household's money."""

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    household = models.ForeignKey(
        "household.Household", on_delete=models.PROTECT, related_name="+"
    )
    key = models.TextField()
    label = models.TextField()
    user = models.ForeignKey(
        "household.User", null=True, blank=True, on_delete=models.SET_NULL, related_name="+"
    )
    payer_names = models.JSONField(default=list, blank=True)
    created_at = models.DateTimeField(db_default=Now())
    updated_at = models.DateTimeField(db_default=Now())

    class Meta:
        db_table = "finance_members"
        constraints = [
            models.UniqueConstraint(
                fields=("household", "key"), name="finance_members_household_key_uniq"
            ),
        ]


class FinanceAccount(models.Model):
    """One place the household holds money or owes it (ticket 01)."""

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    household = models.ForeignKey(
        "household.Household", on_delete=models.PROTECT, related_name="+"
    )
    key = models.TextField()
    label = models.TextField()
    # Null is joint.
    owner = models.ForeignKey(
        FinanceMember, null=True, blank=True, on_delete=models.PROTECT, related_name="accounts"
    )
    institution = models.TextField(blank=True, default="")
    country = models.TextField()
    currency = models.TextField()
    kind = models.TextField(choices=Kind.choices)
    layer = models.TextField(choices=Layer.choices)
    source = models.TextField(choices=SourceKind.choices)
    # The ledger's `account_last4` for this account, when it has one.
    last4 = models.TextField(null=True, blank=True)
    plaid_account_id = models.TextField(null=True, blank=True)
    # How a dropped statement is recognised as this account (ticket 04).
    statement_match = models.TextField(blank=True, default="")
    active = models.BooleanField(default=True)
    created_at = models.DateTimeField(db_default=Now())
    updated_at = models.DateTimeField(db_default=Now())

    class Meta:
        db_table = "finance_accounts"
        constraints = [
            models.UniqueConstraint(
                fields=("household", "key"), name="finance_accounts_household_key_uniq"
            ),
            _choices_check("currency", CURRENCIES, "finance_accounts_currency_valid"),
            _choices_check("country", COUNTRIES, "finance_accounts_country_valid"),
            _choices_check("kind", Kind.values, "finance_accounts_kind_valid"),
            _choices_check("layer", Layer.values, "finance_accounts_layer_valid"),
            _choices_check("source", SourceKind.values, "finance_accounts_source_valid"),
            models.CheckConstraint(
                condition=models.Q(last4__isnull=True) | models.Q(last4__regex=r"^[0-9]{4}$"),
                name="finance_accounts_last4_four_digits",
            ),
        ]

    @property
    def is_debt(self) -> bool:
        return self.kind in DEBT_KINDS


class BalanceSnapshot(models.Model):
    """One account's balance on one day, from one source (ticket 01).

    `amount_cents` is signed: debt negative. `evidence` names what the figure
    stands on (a statement id, the Plaid item, an IBKR report id, or the
    computation's name) so a figure can be traced. `is_estimate` is true when
    the figure was worked out rather than stated (section 4 rule 5), and every
    surface says "estimate" beside it.

    `pending_cents` / `pending_count`: the bank's own not-yet-posted rows on
    this account at the time, kept beside the balance rather than in it, so a
    surface can say them as pending (map rule)."""

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    household = models.ForeignKey(
        "household.Household", on_delete=models.PROTECT, related_name="+"
    )
    account = models.ForeignKey(
        FinanceAccount, on_delete=models.PROTECT, related_name="snapshots"
    )
    as_of = models.DateField()
    amount_cents = models.BigIntegerField()
    currency = models.TextField()
    source = models.TextField(choices=SourceKind.choices)
    evidence = models.TextField(blank=True, default="")
    is_estimate = models.BooleanField(default=False)
    pending_cents = models.BigIntegerField(null=True, blank=True)
    pending_count = models.IntegerField(default=0)
    recorded_at = models.DateTimeField(db_default=Now())

    class Meta:
        db_table = "finance_balance_snapshots"
        constraints = [
            models.UniqueConstraint(
                fields=("account", "as_of", "source"),
                name="finance_balance_snapshots_account_day_source_uniq",
            ),
            _choices_check("currency", CURRENCIES, "finance_balance_snapshots_currency_valid"),
            _choices_check("source", SourceKind.values, "finance_balance_snapshots_source_valid"),
        ]
        indexes = [
            models.Index(fields=["account", "-as_of"], name="finance_snapshots_account_day"),
        ]


class FxRate(models.Model):
    """One day's rate: 1 `base` = `rate_micros` / 1e6 `quote` (ticket 03).
    An integer, never a float in storage. `source` names where it came from."""

    id = models.BigAutoField(primary_key=True)
    on_date = models.DateField()
    base = models.TextField()
    quote = models.TextField()
    rate_micros = models.BigIntegerField()
    source = models.TextField()
    fetched_at = models.DateTimeField(db_default=Now())

    class Meta:
        db_table = "fx_rates"
        constraints = [
            models.UniqueConstraint(
                fields=("on_date", "base", "quote"), name="fx_rates_day_pair_uniq"
            ),
            models.CheckConstraint(
                condition=models.Q(rate_micros__gt=0), name="fx_rates_rate_positive"
            ),
        ]


class IncomePlan(models.Model):
    """One line of the household's income plan (ticket 07): money expected
    in, how often, between which dates, and how sure it is. A `fallback`
    line is what arrives if the `expected` one does not."""

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    household = models.ForeignKey(
        "household.Household", on_delete=models.PROTECT, related_name="+"
    )
    key = models.TextField()
    member = models.ForeignKey(
        FinanceMember, null=True, blank=True, on_delete=models.PROTECT, related_name="+"
    )
    label = models.TextField()
    amount_cents = models.BigIntegerField()
    currency = models.TextField()
    cadence = models.TextField(choices=Cadence.choices)
    start_on = models.DateField(null=True, blank=True)
    end_on = models.DateField(null=True, blank=True)
    confidence = models.TextField(choices=Confidence.choices)
    # The account it lands in, when known. Decides which country's money it is.
    account = models.ForeignKey(
        FinanceAccount, null=True, blank=True, on_delete=models.PROTECT, related_name="+"
    )
    created_at = models.DateTimeField(db_default=Now())
    updated_at = models.DateTimeField(db_default=Now())

    class Meta:
        db_table = "finance_income_plans"
        constraints = [
            models.UniqueConstraint(
                fields=("household", "key"), name="finance_income_plans_household_key_uniq"
            ),
            _choices_check("currency", CURRENCIES, "finance_income_plans_currency_valid"),
            _choices_check("cadence", Cadence.values, "finance_income_plans_cadence_valid"),
            _choices_check(
                "confidence", Confidence.values, "finance_income_plans_confidence_valid"
            ),
            models.CheckConstraint(
                condition=models.Q(amount_cents__gte=0), name="finance_income_plans_amount_nonneg"
            ),
        ]


class FinancePolicy(models.Model):
    """The limits Kevin set (map ruling 4), one row per household.

    - `liquid_floor_cents`: the household liquid floor, in `currency`.
    - `mortgage_*`: the monthly mortgage, for test A (no burn). Paid from
      Singapore in cash today, so not in the ledger; while it is not,
      cash flow adds it beside spend.
    - US top-up: `topup_checking` minus what is owed on `topup_card` must
      cover `us_bills_30d_cents`; warned `topup_warn_days` ahead of the
      card's due day (`card_due_day`, day of month, optional).
    - `transfer_lead_days`: how long before a crossing a cross-border
      transfer must be started (it takes days).
    - `fixed_categories`: categories that are fixed, only checked for being
      paid (map ruling 5).
    """

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    household = models.OneToOneField(
        "household.Household", on_delete=models.PROTECT, related_name="+"
    )
    currency = models.TextField(default="USD")
    liquid_floor_cents = models.BigIntegerField(null=True, blank=True)
    mortgage_monthly_cents = models.BigIntegerField(null=True, blank=True)
    mortgage_currency = models.TextField(default="SGD")
    mortgage_in_ledger = models.BooleanField(default=False)
    topup_checking = models.ForeignKey(
        FinanceAccount, null=True, blank=True, on_delete=models.SET_NULL, related_name="+"
    )
    topup_card = models.ForeignKey(
        FinanceAccount, null=True, blank=True, on_delete=models.SET_NULL, related_name="+"
    )
    us_bills_30d_cents = models.BigIntegerField(null=True, blank=True)
    topup_warn_days = models.IntegerField(default=7)
    card_due_day = models.IntegerField(null=True, blank=True)
    transfer_lead_days = models.IntegerField(default=7)
    fixed_categories = models.JSONField(default=list, blank=True)
    updated_at = models.DateTimeField(db_default=Now())

    class Meta:
        db_table = "finance_policies"
        constraints = [
            _choices_check("currency", CURRENCIES, "finance_policies_currency_valid"),
            _choices_check(
                "mortgage_currency", CURRENCIES, "finance_policies_mortgage_currency_valid"
            ),
            models.CheckConstraint(
                condition=models.Q(card_due_day__isnull=True)
                | models.Q(card_due_day__gte=1, card_due_day__lte=31),
                name="finance_policies_card_due_day_valid",
            ),
        ]
