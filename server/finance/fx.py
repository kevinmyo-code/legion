"""Daily FX rates (household-finance ticket 03, ADR 0073).

- **Stored as an int**: `rate_micros` is the rate x 1e6, rounded half-even
  from the source's decimal text through `Decimal(str(x))`, never float maths.
- **Converted in int cents, half-even** (`convert`). Every conversion returns
  the rate it used and that rate's date and source, so a converted figure can
  always say "at 1.3000 on 4 Nov (frankfurter)".
- **A missing day falls back to the nearest EARLIER day** with a rate (a
  weekend, a holiday, a failed fetch). Never a later one: a figure for a past
  day is never converted at a rate nobody knew that day.
- **Read-only surfaces never fetch.** Only `manage.py fx_rates` reaches the
  network; a tool or route with no rate on file says so in words.

Sources, keyless and free, tried in order: Frankfurter (ECB reference rates)
then open.er-api.com. Each is one `fetch_*` function so tests replace them and
never reach the network.
"""

from __future__ import annotations

import datetime as dt
import email.utils
import json
import urllib.error
import urllib.request
from collections.abc import Callable
from dataclasses import dataclass
from decimal import ROUND_HALF_EVEN, Decimal, InvalidOperation

MICROS = 1_000_000
TIMEOUT_SECONDS = 8
USER_AGENT = "LEGION engine (household assistant)"
# The pair the household needs. Stored one way; the other is its inverse.
BASE = "USD"
QUOTE = "SGD"

FRANKFURTER_URL = "https://api.frankfurter.dev/v1/latest?base=USD&symbols=SGD"
OPEN_ER_URL = "https://open.er-api.com/v6/latest/USD"


class FxUnavailable(Exception):
    """A source was not reached, or answered in a shape this cannot read."""


class NoRate(Exception):
    """No rate is on file on or before the day asked for. Said in words."""


def div_half_even(numerator: int, denominator: int) -> int:
    """`numerator / denominator` rounded half to even, in integers only."""
    if denominator <= 0:
        raise ValueError("denominator must be positive")
    quotient, remainder = divmod(numerator, denominator)
    twice = 2 * remainder
    if twice > denominator or (twice == denominator and quotient % 2):
        quotient += 1
    return quotient


def to_micros(value) -> int:
    """A rate from a source (a JSON number or text) as int micros, half-even."""
    try:
        exact = Decimal(str(value))
    except (InvalidOperation, ValueError) as exc:
        raise FxUnavailable(f"the rate {value!r} is not a number") from exc
    if not exact.is_finite() or exact <= 0:
        raise FxUnavailable(f"the rate {value!r} is not a positive number")
    return int((exact * MICROS).quantize(Decimal(1), rounding=ROUND_HALF_EVEN))


def rate_words(rate_micros: int) -> str:
    """1300000 -> "1.3000"."""
    return f"{Decimal(rate_micros) / MICROS:.4f}"


@dataclass(frozen=True)
class Conversion:
    cents: int
    from_currency: str
    to_currency: str
    # Null when no conversion was needed (same currency).
    rate_micros: int | None
    rate_date: dt.date | None
    source: str | None

    def as_dict(self) -> dict:
        return {
            "cents": self.cents,
            "from_currency": self.from_currency,
            "to_currency": self.to_currency,
            "rate_micros": self.rate_micros,
            "rate_date": self.rate_date,
            "source": self.source,
        }


def apply_rate(cents: int, from_currency: str, to_currency: str, rate_micros: int) -> int:
    """`cents` converted at `rate_micros` (1 USD = rate SGD), half-even."""
    if (from_currency, to_currency) == (BASE, QUOTE):
        return div_half_even(cents * rate_micros, MICROS)
    if (from_currency, to_currency) == (QUOTE, BASE):
        return div_half_even(cents * MICROS, rate_micros)
    raise NoRate(
        f"this engine converts only {BASE} and {QUOTE}, not {from_currency}->{to_currency}"
    )


def rate_on(on_date: dt.date):
    """The `FxRate` for `on_date`, else the nearest earlier one, else raise."""
    from finance.models import FxRate

    row = (
        FxRate.objects.filter(base=BASE, quote=QUOTE, on_date__lte=on_date)
        .order_by("-on_date")
        .first()
    )
    if row is None:
        raise NoRate(
            f"No {BASE}/{QUOTE} rate is on file on or before {on_date.isoformat()}, so nothing "
            f"was converted. The daily rate job (`manage.py fx_rates`) has not stored one yet."
        )
    return row


def convert(cents: int, from_currency: str, to_currency: str, on_date: dt.date) -> Conversion:
    """`cents` in `to_currency` at `on_date`'s rate (or the nearest earlier)."""
    if from_currency == to_currency:
        return Conversion(cents, from_currency, to_currency, None, None, None)
    row = rate_on(on_date)
    return Conversion(
        apply_rate(cents, from_currency, to_currency, row.rate_micros),
        from_currency,
        to_currency,
        row.rate_micros,
        row.on_date,
        row.source,
    )


# =============================================================================
# Sources
# =============================================================================


@dataclass(frozen=True)
class Quote:
    on_date: dt.date
    rate_micros: int
    source: str


def _get(url: str) -> dict:
    request = urllib.request.Request(
        url, headers={"User-Agent": USER_AGENT, "Accept": "application/json"}
    )
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:  # noqa: S310
            raw = response.read()
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        host = url.split("/")[2]
        raise FxUnavailable(f"{host} could not be reached ({type(exc).__name__})") from exc
    try:
        body = json.loads(raw)
    except (TypeError, ValueError) as exc:
        raise FxUnavailable("the answer was not JSON") from exc
    if not isinstance(body, dict):
        raise FxUnavailable("the answer was not an object")
    return body


def parse_frankfurter(body: dict) -> Quote:
    rates = body.get("rates")
    if body.get("base") != BASE or not isinstance(rates, dict) or QUOTE not in rates:
        raise FxUnavailable("frankfurter's answer had no USD->SGD rate")
    try:
        day = dt.date.fromisoformat(str(body.get("date")))
    except ValueError as exc:
        raise FxUnavailable("frankfurter's answer had no date") from exc
    return Quote(day, to_micros(rates[QUOTE]), "frankfurter")


def parse_open_er(body: dict) -> Quote:
    rates = body.get("rates")
    if body.get("result") != "success" or not isinstance(rates, dict) or QUOTE not in rates:
        raise FxUnavailable("open.er-api's answer had no USD->SGD rate")
    if body.get("base_code") != BASE:
        raise FxUnavailable("open.er-api's answer was not based on USD")
    try:
        day = email.utils.parsedate_to_datetime(str(body.get("time_last_update_utc"))).date()
    except (TypeError, ValueError) as exc:
        raise FxUnavailable("open.er-api's answer had no date") from exc
    return Quote(day, to_micros(rates[QUOTE]), "open.er-api")


def fetch_frankfurter() -> Quote:
    return parse_frankfurter(_get(FRANKFURTER_URL))


def fetch_open_er() -> Quote:
    return parse_open_er(_get(OPEN_ER_URL))


# Tried in order; the first that answers is stored. Tests replace this list.
SOURCES: list[Callable[[], Quote]] = [fetch_frankfurter, fetch_open_er]


@dataclass
class FetchReport:
    quote: Quote | None
    created: bool
    failures: list[str]


def record_latest(sources: list[Callable[[], Quote]] | None = None) -> FetchReport:
    """Ask each source in turn and store the first answer as that day's rate.
    A day already on file is left as it was (the first rate recorded for a day
    is the one past figures were converted at)."""
    from finance.models import FxRate

    failures: list[str] = []
    for fetch in sources if sources is not None else SOURCES:
        try:
            quote = fetch()
        except FxUnavailable as exc:
            failures.append(str(exc))
            continue
        _, created = FxRate.objects.get_or_create(
            on_date=quote.on_date,
            base=BASE,
            quote=QUOTE,
            defaults={"rate_micros": quote.rate_micros, "source": quote.source},
        )
        return FetchReport(quote, created, failures)
    return FetchReport(None, False, failures)
