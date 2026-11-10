"""One real-world bank transaction fires a money watch once, pending or posted (ADR 0083).

**The link is the bank's own.** Plaid sends a posted transaction with a NEW `transaction_id` and
names the pending one it replaces in `pending_transaction_id`; `ingest/plaid_sync.apply_changes`
then deletes the pending row and inserts the posted one. A money firing is keyed by the bank id
(`txn:<transaction_id>`), so without help the posted row would be a new fact and fire again
(a tipped charge: pending $40.00, posted $48.00, said twice).

`carry_pending_firings` is called by `apply_changes` in the SAME database transaction that inserts
the posted row: for every member who already has a firing for the pending id, it records the
posted id as that member's fact too, as a baseline (never said). So the posted row is never visible
without its link, the first sighting that crossed a threshold is the only one said, and a pending
row under every threshold whose posted amount crosses one fires once, at posting (no firing to
carry). Deterministic: no amount or merchant matching.

**Not covered, stated:** a posted transaction the bank sends WITHOUT `pending_transaction_id`
(Plaid does not promise it for every institution) has no link, so its pending twin, if it fired,
fires again. Narrowest deterministic fallback, not built: within ONE sync batch, a pending row the
bank REMOVED and a posted row it ADDED on the same account with the identical bank description.
"""

from __future__ import annotations

from django.db import IntegrityError, transaction
from django.utils import timezone

from watches.models import WatchFiring


def carry_pending_firings(household, links) -> int:
    """`links`: (pending bank transaction id, posted `LedgerTransaction`) pairs from one sync.
    Returns how many members' facts were carried."""
    carried = 0
    now = timezone.now()
    for pending_id, posted in links:
        if not pending_id or not posted.bank_transaction_id:
            continue
        for firing in WatchFiring.objects.filter(household=household, fact_key=f"txn:{pending_id}"):
            try:
                with transaction.atomic():
                    WatchFiring.objects.create(
                        household=household,
                        owner_user_id=firing.owner_user_id,
                        watch_id=firing.watch_id,
                        kind=firing.kind,
                        fact_key=f"txn:{posted.bank_transaction_id}",
                        facts=f"Posted; already said while pending: {firing.facts}",
                        detail={
                            "posted_of": str(pending_id),
                            "amount_cents": posted.amount_cents,
                            "account": posted.account_last4,
                        },
                        sources=[{"table": "ledger_transactions", "id": str(posted.id)}],
                        baseline=True,
                        fired_at=now,
                        expires_at=now,
                    )
                carried += 1
            except IntegrityError:
                continue
    return carried
