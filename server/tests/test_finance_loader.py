"""Household-finance ticket 12: the private-file loader.

The file read here is the repo's TEMPLATE, `finance/example_household.md`,
whose household is invented. The real one never enters the repo.
"""

from __future__ import annotations

import base64
import re
from pathlib import Path

import pytest
from django.core.management import CommandError, call_command

from finance import loader
from finance.models import FinanceAccount, FinanceMember, FinancePolicy, IncomePlan
from legacy.models.ledger import BudgetTarget

pytestmark = pytest.mark.django_db

TEMPLATE = Path(__file__).resolve().parents[1] / "finance" / "example_household.md"


def template() -> str:
    # A Windows checkout may hand it back with CRLF; the edits below are LF.
    return TEMPLATE.read_text(encoding="utf-8").replace("\r\n", "\n")


def load(text: str, capsys, *extra) -> str:
    encoded = base64.b64encode(text.encode()).decode()
    call_command("load_household_finance", "--content-b64", encoded, *extra)
    return capsys.readouterr().out


def test_the_template_loads_every_part(household_a, capsys):
    out = load(template(), capsys)
    assert "Members: 2." in out and "Accounts: 10." in out
    assert FinanceMember.objects.get(household=household_a, key="sam").payer_names == [
        "SAM RIVER",
        "S RIVER",
    ]
    by = {a.key: a for a in FinanceAccount.objects.filter(household=household_a)}
    assert (by["us-card-7823"].kind, by["us-card-7823"].last4) == ("credit", "7823")
    assert (by["flat-loan"].kind, by["flat-loan"].owner) == ("loan", None)
    assert by["sg-bonds"].kind == "bond" and by["brokerage"].source == "ibkr_flex"
    assert by["brokerage"].country == "SG"  # the yaml override
    assert by["car"].last4 is None and by["car"].kind == "vehicle"
    assert by["flat"].source == "computed"
    policy = FinancePolicy.objects.get(household=household_a)
    assert policy.liquid_floor_cents == 1_000_000 and policy.us_bills_30d_cents == 200_000
    assert policy.topup_checking.key == "us-checking-1000" and policy.card_due_day == 20
    assert (policy.mortgage_monthly_cents, policy.mortgage_currency) == (100_000, "SGD")
    assert policy.fixed_bills == [
        {"label": "Rent", "amount_cents": 100_000, "day": 1},
        {"label": "Power", "amount_cents": 10_000, "day": 15},
    ]
    assert IncomePlan.objects.filter(household=household_a).count() == 4
    caps = {
        t.category: t.amount_cents
        for t in BudgetTarget.objects.filter(household=household_a, deleted_at__isnull=True)
    }
    assert caps == {"Groceries": 50_000, "Dining": 10_000, "Subscriptions": 5_000}


def test_loading_twice_changes_nothing_the_second_time(household_a, capsys):
    load(template(), capsys)
    out = load(template(), capsys)
    assert "Nothing changed: already loaded." in out
    assert FinanceAccount.objects.filter(household=household_a).count() == 10
    assert BudgetTarget.objects.filter(household=household_a).count() == 3


def test_a_changed_cap_and_a_dropped_account_are_applied(household_a, capsys):
    load(template(), capsys)
    edited = template().replace("Dining: 100", "Dining: 200").replace(
        "| car | Car | Alex | USD | other | manual |\n", ""
    )
    out = load(edited, capsys)
    assert "Marked inactive (no longer in the file): car." in out
    target = BudgetTarget.objects.get(household=household_a, category="Dining")
    assert target.amount_cents == 20_000
    assert FinanceAccount.objects.get(household=household_a, key="car").active is False


def test_the_output_carries_no_figure_name_or_account_number(household_a, capsys):
    out = load(template(), capsys)
    numbers = [int(n) for n in re.findall(r"\d+", out)]
    assert all(n <= 20 for n in numbers), numbers
    assert "RIVER" not in out and "7823" not in out and "1000" not in out


def test_a_dry_run_writes_nothing(household_a, capsys):
    out = load(template(), capsys, "--dry-run")
    assert "Dry run, nothing written" in out and "10 accounts" in out
    assert not FinanceAccount.objects.filter(household=household_a).exists()


def test_a_path_inside_the_repo_is_refused(household_a):
    with pytest.raises(CommandError, match="inside the repo"):
        call_command("load_household_finance", str(TEMPLATE))


@pytest.mark.parametrize(
    ("old", "new", "said"),
    [
        ("| liquid (debt) | plaid |", "| savings | plaid |", "layer"),
        ("| ibkr-flex |", "| carrier pigeon |", "source"),
        ("amount: 2000\n", "amount: 2000.005\n", "two decimal places"),
        ("budgets:\n", "budget_ideas:\n", "keys this does not read"),
        ("    account: sg-savings\n", "    account: nowhere\n", "no account called"),
    ],
)
def test_anything_unreadable_is_refused_in_words_and_nothing_is_written(
    household_a, capsys, old, new, said
):
    assert old in template()
    with pytest.raises(CommandError, match=said):
        load(template().replace(old, new, 1), capsys)
    assert not FinanceAccount.objects.filter(household=household_a).exists()


def test_a_file_with_no_yaml_block_says_what_to_add():
    text = template().split("## Machine-read block")[0]
    with pytest.raises(loader.LoaderRefused, match="example_household.md"):
        loader.parse(text)
