"""`LivePlaidGateway` reads the two Plaid responses it was taught this session.

`FakePlaid` cannot prove any of this: it returns the dicts the gateway is
supposed to produce, so a mistake in the real parsing or in a request class
name would pass the whole suite and then stop every sync in production. An
`/accounts/get` failure now stops a sync, so that path is load-bearing.

No database, and no network: the gateway is built without its `__init__` (which
is the only thing that needs keys) and handed a stub client that returns
plaid-python-shaped objects, nested models and real `datetime`s included.
"""
from __future__ import annotations

import datetime
import types

from ingest.plaid_client import LivePlaidGateway

LAST_UPDATE = datetime.datetime(2026, 11, 20, 15, 30, tzinfo=datetime.UTC)


class Body:
    """What `to_dict()` gives back on a plaid-python response."""

    def __init__(self, payload):
        self._payload = payload

    def to_dict(self):
        return self._payload


class Model:
    """A nested plaid-python model, as `_value` has to unwrap it."""

    def __init__(self, payload):
        self._payload = payload

    def to_dict(self):
        return self._payload


def gateway(**calls) -> LivePlaidGateway:
    gw = object.__new__(LivePlaidGateway)
    # A namespace, not a class: an attribute here stays a plain function and
    # is not handed a `self`.
    gw._client = types.SimpleNamespace(**calls)
    return gw


def item_body(status):
    payload = {
        "item": {
            "item_id": "item-1",
            "institution_id": "ins_127989",
            "institution_name": "Bank of America",
            "consent_expiration_time": None,
            "error": None,
        },
        "request_id": "req-1",
    }
    if status is not Ellipsis:
        payload["status"] = status
    return Body(payload)


def test_get_item_reads_the_top_level_status():
    gw = gateway(item_get=lambda request: item_body({
        "transactions": {"last_successful_update": LAST_UPDATE, "last_failed_update": None},
        "last_webhook": None,
    }))

    info = gw.get_item("access-never-served")

    assert info["transactions_last_successful_update"] == LAST_UPDATE
    assert info["institution_name"] == "Bank of America"
    assert info["error_code"] is None


def test_a_null_status_is_not_a_time():
    """Plaid types `status` nullable, and an Item it has never updated has
    none. The sync then leaves every stored as-of where it was."""
    for status in (None, Ellipsis, {"transactions": None}, {}):
        gw = gateway(item_get=lambda request, status=status: item_body(status))

        assert gw.get_item("access-never-served")["transactions_last_successful_update"] is None


def test_accounts_get_returns_every_account_as_plain_dicts():
    captured = []

    def accounts_get(request):
        captured.append(request)
        return Body(
            {
                "accounts": [
                    Model({"account_id": "acc-chk", "mask": "1000", "balances": {"current": 1.5}}),
                    {"account_id": "acc-card", "mask": "1199", "balances": {"current": 2.5}},
                ],
                "item": {"item_id": "item-1"},
                "request_id": "req-2",
            }
        )

    accounts = gateway(accounts_get=accounts_get).accounts_get("access-never-served")["accounts"]

    assert [a["account_id"] for a in accounts] == ["acc-chk", "acc-card"]
    assert accounts[0]["balances"]["current"] == 1.5
    # The request class exists and takes the token under that name.
    assert captured[0].access_token == "access-never-served"


def test_accounts_get_with_no_accounts_is_an_empty_list():
    gw = gateway(accounts_get=lambda request: Body({"accounts": None, "request_id": "req-3"}))

    assert gw.accounts_get("access-never-served") == {"accounts": []}
