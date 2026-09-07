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

from ingest.views import ReceiptIngestView, StatementIngestView

urlpatterns = [
    path("statement", StatementIngestView.as_view(), name="ingest-statement"),
    path("receipt", ReceiptIngestView.as_view(), name="ingest-receipt"),
]
