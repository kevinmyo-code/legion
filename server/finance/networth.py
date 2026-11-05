"""Net worth by layer (household-finance ticket 07, map rulings 2 and 3).

Every account's latest balance (`finance.balances`), converted to the view
currency at TODAY's stored rate (the nearest earlier day's when today has none;
ruling 3: "the SGD view uses today's rate"; the USD headline uses the same
rate for the SGD accounts). Summed per layer and in total. Debt is negative,
so a card or a loan reduces its layer.

Each line carries its day, source and estimate flag; a layer or total holding
any estimate is an estimate. An account with no balance on file is listed as
missing and is in no total. When an account's currency needs a rate and none is
stored, it is listed as unconverted and left out of the totals, said in words:
never converted at a guessed rate.
"""

from __future__ import annotations

import datetime as dt

from finance import fx
from finance.balances import latest_balances
from finance.models import RUNWAY_LAYERS, Layer

LAYER_ORDER = [Layer.LIQUID, Layer.INVESTMENTS, Layer.CPF, Layer.PROPERTY, Layer.OTHER]


def today_rate(today: dt.date):
    """The stored rate for today or the nearest earlier day, or None."""
    try:
        return fx.rate_on(today)
    except fx.NoRate:
        return None


def rate_body(row) -> dict | None:
    if row is None:
        return None
    return {"rate_micros": row.rate_micros, "rate_date": row.on_date, "source": row.source}


def net_worth(household, today: dt.date, zone, currency: str = "USD") -> dict:
    balances, missing = latest_balances(household, today, zone)
    rate = today_rate(today)
    layers = {layer: {"layer": str(layer), "total_cents": 0, "is_estimate": False,
                      "accounts": []} for layer in LAYER_ORDER}
    unconverted = []
    for balance in balances:
        account = balance.account
        line = {
            "account": account.key,
            "label": account.label,
            "currency": balance.currency,
            "amount_cents": balance.cents,
            "as_of": balance.as_of,
            "source": balance.source,
            "is_estimate": balance.is_estimate,
            "stale": balance.stale(today),
            "pending_cents": balance.pending_cents,
            "converted_cents": None,
        }
        if balance.currency == currency:
            line["converted_cents"] = balance.cents
        elif rate is not None:
            line["converted_cents"] = fx.apply_rate(
                balance.cents, balance.currency, currency, rate.rate_micros
            )
        layer = layers[account.layer]
        layer["accounts"].append(line)
        if line["converted_cents"] is None:
            unconverted.append(account.key)
            continue
        layer["total_cents"] += line["converted_cents"]
        layer["is_estimate"] = layer["is_estimate"] or balance.is_estimate
    ordered = [layers[layer] for layer in LAYER_ORDER]
    return {
        "currency": currency,
        "as_of": today,
        "rate": rate_body(rate),
        "layers": ordered,
        "total_cents": sum(layer["total_cents"] for layer in ordered),
        "total_is_estimate": any(layer["is_estimate"] for layer in ordered),
        "runway_cents": sum(
            layer["total_cents"] for layer in ordered if layer["layer"] in RUNWAY_LAYERS
        ),
        "missing": [a.key for a in missing],
        "unconverted": unconverted,
    }
