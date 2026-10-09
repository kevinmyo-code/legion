"""The two ingest routes, mounted at `api/ingest/` in `legion/urls.py`.

Paths come from django-engine ticket 03 verbatim - `POST /api/ingest/statement`
and `POST /api/ingest/receipt` - so the mapping from the RPC each one replaces
stays one to one and readable:

    public.commit_statement(payload jsonb)  ->  POST /api/ingest/statement
    public.commit_receipt(payload jsonb)    ->  POST /api/ingest/receipt

Singular, not `/statements` and `/receipts`, because these are not collection
resources: the endpoint is a commit of one document through the gate, and what
comes back is a verdict, not the row. The read surfaces for statements and
receipts are ticket 04's, under their own plural paths.
"""
from django.urls import path

from ingest.plaid_views import (
    BankExchangeView,
    BankLinkTokenView,
    BankStatusView,
    BankSyncView,
    BankUpdateLinkTokenView,
)
from ingest.sessions import SessionDetailView, SessionListView
from ingest.views import ReceiptIngestView, StatementIngestView

urlpatterns = [
    path("statement", StatementIngestView.as_view(), name="ingest-statement"),
    path("receipt", ReceiptIngestView.as_view(), name="ingest-receipt"),
    # backend-etl ticket 02: the session vault (`ingest/sessions.py`). Plural,
    # unlike the two above: these ARE resources, one per source.
    path("sessions", SessionListView.as_view(), name="ingest-sessions"),
    path("sessions/<str:source>", SessionDetailView.as_view(), name="ingest-session"),
    # ADR 0057: the bank connection (`ingest/plaid_views.py`).
    path("plaid", BankStatusView.as_view(), name="ingest-plaid"),
    path("plaid/link-token", BankLinkTokenView.as_view(), name="ingest-plaid-link-token"),
    path(
        "plaid/update-link-token",
        BankUpdateLinkTokenView.as_view(),
        name="ingest-plaid-update-link-token",
    ),
    path("plaid/exchange", BankExchangeView.as_view(), name="ingest-plaid-exchange"),
    path("plaid/sync", BankSyncView.as_view(), name="ingest-plaid-sync"),
]
