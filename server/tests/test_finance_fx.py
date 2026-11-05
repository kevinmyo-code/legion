"""Household-finance ticket 03: daily FX rates.

Half-even rounding in int cents and int micros, the nearest EARLIER day when a
day is missing, the source recorded, sources tried in order. The network is
never reached: every source here is a fake.
"""

from __future__ import annotations

import datetime as dt
from pathlib import Path

import pytest
from django.core.management import call_command

from finance import fx
from finance.models import FxRate
from tests.finance_synthetic import RATE_A, RATE_B, rate

pytestmark = pytest.mark.django_db

DAY = dt.date(2026, 11, 2)


@pytest.mark.parametrize(
    ("numerator", "denominator", "expected"),
    [(5, 2, 2), (7, 2, 4), (-5, 2, -2), (-7, 2, -4), (10, 4, 2), (11, 4, 3), (9, 4, 2)],
)
def test_integer_division_rounds_half_to_even(numerator, denominator, expected):
    assert fx.div_half_even(numerator, denominator) == expected


def test_a_source_rate_becomes_int_micros_half_even_never_through_a_float():
    assert fx.to_micros("1.2500005") == 1_250_000  # half, to even
    assert fx.to_micros("1.2500015") == RATE_A + 2
    assert fx.to_micros(1.25) == RATE_A
    for bad in ("abc", "-1", "0", None):
        with pytest.raises(fx.FxUnavailable):
            fx.to_micros(bad)


def test_conversion_is_int_cents_both_ways_and_says_its_rate():
    rate(DAY, RATE_A, "frankfurter")
    to_sgd = fx.convert(10_000, "USD", "SGD", DAY)
    assert (to_sgd.cents, to_sgd.rate_micros, to_sgd.rate_date, to_sgd.source) == (
        12_500, RATE_A, DAY, "frankfurter"
    )
    assert fx.convert(12_500, "SGD", "USD", DAY).cents == 10_000
    # 1 SGD cent at 1.25 is 0.8 USD cents: rounds to 1.
    assert fx.convert(1, "SGD", "USD", DAY).cents == 1
    # 5 USD cents at 1.30 is 6.5 SGD cents: half to even, 6.
    rate(DAY + dt.timedelta(days=1), RATE_B)
    assert fx.convert(5, "USD", "SGD", DAY + dt.timedelta(days=1)).cents == 6
    same = fx.convert(10_000, "USD", "USD", DAY)
    assert (same.cents, same.rate_micros) == (10_000, None)


def test_a_missing_day_uses_the_nearest_earlier_rate_never_a_later_one():
    rate(DAY, RATE_A)
    rate(DAY + dt.timedelta(days=5), RATE_B)
    used = fx.convert(10_000, "USD", "SGD", DAY + dt.timedelta(days=3))
    assert (used.rate_micros, used.rate_date) == (RATE_A, DAY)
    with pytest.raises(fx.NoRate, match="No USD/SGD rate is on file"):
        fx.convert(10_000, "USD", "SGD", DAY - dt.timedelta(days=1))


def _quote(source, micros=RATE_A, day=DAY):
    return lambda: fx.Quote(day, micros, source)


def _down(name):
    def fetch():
        raise fx.FxUnavailable(f"{name} could not be reached (URLError)")

    return fetch


def test_the_first_source_that_answers_is_stored_with_its_name():
    report = fx.record_latest([_down("first"), _quote("second"), _quote("third", RATE_B)])
    assert report.created and report.failures == ["first could not be reached (URLError)"]
    row = FxRate.objects.get(on_date=DAY)
    assert (row.rate_micros, row.source, row.base, row.quote) == (RATE_A, "second", "USD", "SGD")


def test_a_day_already_on_file_keeps_its_first_rate():
    fx.record_latest([_quote("first")])
    report = fx.record_latest([_quote("second", RATE_B)])
    assert report.created is False
    assert FxRate.objects.get(on_date=DAY).rate_micros == RATE_A


def test_the_command_says_when_every_source_failed(capsys, monkeypatch):
    monkeypatch.setattr(fx, "SOURCES", [_down("first"), _down("second")])
    call_command("fx_rates")
    out = capsys.readouterr().out
    assert "No rate was stored: every source failed" in out
    assert not FxRate.objects.exists()
    monkeypatch.setattr(fx, "SOURCES", [_quote("frankfurter")])
    call_command("fx_rates")
    assert "Stored 1 USD = 1.2500 SGD for 2026-11-02 (source: frankfurter)" in (
        capsys.readouterr().out
    )


def test_each_sources_answer_shape_is_read_and_a_wrong_one_refused():
    quote = fx.parse_frankfurter({"base": "USD", "date": "2026-11-02", "rates": {"SGD": 1.25}})
    assert (quote.on_date, quote.rate_micros, quote.source) == (DAY, RATE_A, "frankfurter")
    quote = fx.parse_open_er(
        {
            "result": "success",
            "base_code": "USD",
            "time_last_update_utc": "Mon, 02 Nov 2026 00:02:31 +0000",
            "rates": {"SGD": 1.3},
        }
    )
    assert (quote.on_date, quote.rate_micros, quote.source) == (DAY, RATE_B, "open.er-api")
    with pytest.raises(fx.FxUnavailable):
        fx.parse_frankfurter({"base": "EUR", "rates": {"SGD": 1.4}})
    with pytest.raises(fx.FxUnavailable):
        fx.parse_open_er({"result": "error"})


def test_the_crontab_fetches_the_rate_daily():
    crontab = Path(__file__).resolve().parents[2] / "deploy" / "crontab"
    assert "0 17 * * * python manage.py fx_rates" in crontab.read_text().splitlines()
