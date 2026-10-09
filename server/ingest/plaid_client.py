"""The one place this engine talks to Plaid (ADR 0057).

Everything else in the bank-feed code (`ingest/plaid_sync.py`,
`ingest/plaid_views.py`) talks to a `PlaidGateway` and gets plain dicts back,
so the suite swaps in a fake (`set_gateway_factory`) and never reaches the
network. `plaid-python` is pinned exactly in `requirements.txt`: Plaid ships a
new major roughly monthly.

**Secrets never leave this module.** `PLAID_CLIENT_ID` and `PLAID_SECRET` are
read from settings here and nowhere else; an access token is passed in by the
caller (opened from the vault) and never put into an exception message. A
`PlaidError` carries only Plaid's own `error_code` and `error_message`, which
name what went wrong and never a credential.
"""
from __future__ import annotations

import json
from collections.abc import Callable
from typing import Any, Protocol

from django.conf import settings

ENVIRONMENTS = ("production", "sandbox")

# Item states the person fixes by signing in to the bank again, in Plaid's
# update mode (the same access token; no new Trial slot is spent).
SIGN_IN_ERROR_CODES = frozenset(
    {"ITEM_LOGIN_REQUIRED", "PENDING_DISCONNECT", "PENDING_EXPIRATION", "ACCESS_NOT_GRANTED"}
)
# `/transactions/sync` says the data moved under it mid-pagination: restart the
# whole loop from the cursor the loop started at (Plaid's own instruction).
MUTATION_DURING_PAGINATION = "TRANSACTIONS_SYNC_MUTATION_DURING_PAGINATION"

# Plaid's maximum history on the first link (BofA caps it lower: 18 months of
# checking, 11 closed cycles of a card). Only settable on the first Link.
DAYS_REQUESTED = 730
SYNC_PAGE_SIZE = 500
CLIENT_NAME = "LEGION"


class PlaidNotConfigured(Exception):
    """The server has no Plaid keys, or an environment it does not know. The
    message says what to set, and never a value."""


class PlaidError(Exception):
    """Plaid refused a call. `code` is Plaid's `error_code` (or the HTTP status
    when the body had none)."""

    def __init__(self, code: str, message: str = ""):
        super().__init__(f"Plaid {code}: {message}".rstrip(": "))
        self.code = code
        self.message = message

    @property
    def needs_sign_in(self) -> bool:
        return self.code in SIGN_IN_ERROR_CODES


class PlaidGateway(Protocol):
    def create_link_token(
        self, *, client_user_id: str, access_token: str | None = None
    ) -> dict[str, Any]: ...

    def exchange_public_token(self, public_token: str) -> dict[str, Any]: ...

    def get_item(self, access_token: str) -> dict[str, Any]: ...

    def sync_transactions(self, access_token: str, cursor: str | None) -> dict[str, Any]: ...


def configuration_problem() -> str | None:
    """None when this server can call Plaid, else a sentence saying why not."""
    missing = [
        name
        for name in ("PLAID_CLIENT_ID", "PLAID_SECRET")
        if not getattr(settings, name, "")
    ]
    if missing:
        return (
            f"{' and '.join(missing)} {'is' if len(missing) == 1 else 'are'} not set on the "
            f"server, so the bank connection is not set up."
        )
    env = getattr(settings, "PLAID_ENV", "")
    if env not in ENVIRONMENTS:
        return f"PLAID_ENV must be production or sandbox; it is {env!r}."
    return None


def environment() -> str:
    return getattr(settings, "PLAID_ENV", "sandbox")


ENVIRONMENT_SENTENCES = {
    "sandbox": "Test mode (Plaid sandbox): no real bank data.",
    "production": "Live (Plaid production): your real bank data.",
}


def environment_sentence() -> str:
    return ENVIRONMENT_SENTENCES.get(environment(), f"Plaid environment {environment()!r}.")


def _value(obj: Any) -> Any:
    """A plaid-python model, enum or plain value as plain Python."""
    if hasattr(obj, "to_dict"):
        return obj.to_dict()
    if hasattr(obj, "value") and not isinstance(obj, (str, bytes)):
        return obj.value
    return obj


class LivePlaidGateway:
    """The real gateway, over `plaid-python`. Only constructed when
    `configuration_problem()` is None."""

    def __init__(self):
        import plaid
        from plaid.api import plaid_api

        problem = configuration_problem()
        if problem:
            raise PlaidNotConfigured(problem)
        host = {
            "production": plaid.Environment.Production,
            "sandbox": plaid.Environment.Sandbox,
        }[environment()]
        configuration = plaid.Configuration(
            host=host,
            api_key={"clientId": settings.PLAID_CLIENT_ID, "secret": settings.PLAID_SECRET},
        )
        self._client = plaid_api.PlaidApi(plaid.ApiClient(configuration))

    def _call(self, fn: Callable, request) -> dict[str, Any]:
        import plaid

        try:
            return fn(request).to_dict()
        except plaid.ApiException as exc:
            code, message = str(exc.status or "error"), ""
            try:
                body = json.loads(exc.body or "{}")
                code = str(body.get("error_code") or code)
                message = str(body.get("error_message") or "")
            except (TypeError, ValueError):
                pass
            raise PlaidError(code, message) from None

    def create_link_token(self, *, client_user_id: str, access_token: str | None = None):
        from plaid.model.country_code import CountryCode
        from plaid.model.link_token_create_request import LinkTokenCreateRequest
        from plaid.model.link_token_create_request_user import LinkTokenCreateRequestUser
        from plaid.model.link_token_transactions import LinkTokenTransactions
        from plaid.model.products import Products

        fields: dict[str, Any] = {
            "client_name": CLIENT_NAME,
            "language": "en",
            "country_codes": [CountryCode("US")],
            "user": LinkTokenCreateRequestUser(client_user_id=client_user_id),
        }
        if access_token:
            # Update mode: the same Item, the same access token, no new slot.
            fields["access_token"] = access_token
        else:
            fields["products"] = [Products("transactions")]
            fields["transactions"] = LinkTokenTransactions(days_requested=DAYS_REQUESTED)
        body = self._call(
            self._client.link_token_create, LinkTokenCreateRequest(**fields)
        )
        return {"link_token": body["link_token"], "expiration": body.get("expiration")}

    def exchange_public_token(self, public_token: str):
        from plaid.model.item_public_token_exchange_request import (
            ItemPublicTokenExchangeRequest,
        )

        body = self._call(
            self._client.item_public_token_exchange,
            ItemPublicTokenExchangeRequest(public_token=public_token),
        )
        return {"access_token": body["access_token"], "item_id": body["item_id"]}

    def get_item(self, access_token: str):
        from plaid.model.item_get_request import ItemGetRequest

        body = self._call(self._client.item_get, ItemGetRequest(access_token=access_token))
        item = body.get("item") or {}
        error = item.get("error") or {}
        return {
            "item_id": item.get("item_id"),
            "institution_id": item.get("institution_id"),
            "institution_name": item.get("institution_name"),
            "consent_expiration_time": item.get("consent_expiration_time"),
            "error_code": error.get("error_code") if isinstance(error, dict) else None,
        }

    def sync_transactions(self, access_token: str, cursor: str | None):
        from plaid.model.transactions_sync_request import TransactionsSyncRequest
        from plaid.model.transactions_sync_request_options import (
            TransactionsSyncRequestOptions,
        )

        fields: dict[str, Any] = {
            "access_token": access_token,
            "count": SYNC_PAGE_SIZE,
            # The bank's raw text, so the household's category rules (written
            # against the BofA exports' descriptions) keep matching.
            "options": TransactionsSyncRequestOptions(include_original_description=True),
        }
        if cursor:
            fields["cursor"] = cursor
        body = self._call(
            self._client.transactions_sync, TransactionsSyncRequest(**fields)
        )
        return {
            "added": [_value(t) for t in body.get("added") or []],
            "modified": [_value(t) for t in body.get("modified") or []],
            "removed": [_value(t) for t in body.get("removed") or []],
            "accounts": [_value(a) for a in body.get("accounts") or []],
            "next_cursor": body.get("next_cursor"),
            "has_more": bool(body.get("has_more")),
        }


def _live_gateway() -> PlaidGateway:
    return LivePlaidGateway()


_FACTORY: list[Callable[[], PlaidGateway]] = [_live_gateway]


def gateway() -> PlaidGateway:
    """The gateway to use. Raises `PlaidNotConfigured` without keys."""
    return _FACTORY[0]()


def set_gateway_factory(factory: Callable[[], PlaidGateway] | None) -> None:
    """Tests install a fake here (and pass None to restore the real one)."""
    _FACTORY[0] = factory or _live_gateway
