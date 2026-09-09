"""Every table routed through `api/synced.py`, in one list.

Two things read this list and they must never disagree: `api/urls.py`
(which routes them) and `api/changes.py` (which folds them into the one
feed the phone's cache lives on). Registering an aspect in one place and
forgetting the other is precisely the drift ticket 04's "one shape" exists
to prevent, so there is one list and both import it.

Ordering inside `SYNCED_VIEWSETS` is the order the routes are declared and
the order the changes feed's keys are built - deliberately grouped by
aspect, so `/api/changes?aspects=body` reads down the list the way the
migration reads down the file.
"""
from __future__ import annotations

from api.body import BODY_VIEWSETS
from api.fleet import FLEET_VIEWSETS
from api.ingest_files import INGEST_VIEWSETS
from api.ledger import LEDGER_VIEWSETS
from api.memory import MEMORY_VIEWSETS
from api.pantry import PANTRY_VIEWSETS
from api.places import PlaceViewSet
from api.synced import SyncedModelViewSet
from api.voice_notes import VoiceNoteViewSet
from household.tenancy import TENANT_TABLES

SYNCED_VIEWSETS: list[type[SyncedModelViewSet]] = [
    PlaceViewSet,
    VoiceNoteViewSet,
    *BODY_VIEWSETS,
    *MEMORY_VIEWSETS,
    *LEDGER_VIEWSETS,
    *PANTRY_VIEWSETS,
    *INGEST_VIEWSETS,
    *FLEET_VIEWSETS,
]

# Aspect name -> the viewsets that make it up, in registry order. `fleet` has
# eleven tables here, `body` eight, `ledger` five, `memory` three, `pantry`
# three; `places`, `voice_notes` and `ingest` are one each. Built from the list
# rather than typed out a second time.
#
# **`fleet` has TWELVE tables and only eleven of them are here.** `obd_samples`
# is routed (`api/fleet.py`'s `OBD_SAMPLE_PATHS`, added to `api/urls.py`
# alongside this loop) and is deliberately absent from this list, which makes
# it the one exception to the guarantee stated above: a routed table is
# normally in the changes feed too, because both readers come from here.
#
# It is an exception because the feed is NOT paged - `api/changes.py` says so
# in its own doc - and `obd_samples` held 20,796 rows on 2026-09-07, thirteen
# times every other table in this database combined. Folding it in would make
# `GET /api/changes` with no `aspects` a whole-telemetry-archive download, for
# every client, forever. It is the same reason the feed excludes anything
# unbounded, and it is named here rather than left as a gap someone later
# "fixes" by adding the viewset to this list.
#
# **Six of these are read-only** - `ledger.statements`,
# `ledger.ledger_transactions`, `pantry.receipts`, `pantry.receipt_line_items`
# and `ingest.ingested_files` carry `writable = False` (five tables; the sixth
# departure is `memory.memory_audit`, which is append-only rather than
# read-only). So an aspect here is not a promise that everything under it can
# be written; it is a promise that everything under it can be READ from the
# same feed, which is what a client's cache needs.
SYNCED_ASPECTS: dict[str, list[type[SyncedModelViewSet]]] = {}
for _viewset in SYNCED_VIEWSETS:
    SYNCED_ASPECTS.setdefault(_viewset.aspect, []).append(_viewset)


# ADR 0045's coverage check, at import time rather than in a test.
#
# A table routed here that is NOT in `household.tenancy.TENANT_TABLES` would be
# a table the migration never gave a `household_id` to, whose
# `SyncedModelViewSet.queryset()` would then raise `FieldError` on the first
# request - a 500 on a route that used to work, discovered by whoever hit it.
# This turns that into a refusal to start, naming the table, which is the same
# posture `legion/settings.required_env` takes for a missing secret: the
# failure a household can actually act on is the loud one at boot.
#
# `tests/test_tenancy.py` closes the other direction (a name in TENANT_TABLES
# that no `public` table carries) against `information_schema`; between them
# the list cannot drift from either side.
_untenanted = sorted(
    viewset.table for viewset in SYNCED_VIEWSETS if viewset.table not in TENANT_TABLES
)
if _untenanted:
    raise RuntimeError(
        f"These tables are routed by this registry but are not in "
        f"household.tenancy.TENANT_TABLES, so they carry no household_id and cannot be "
        f"scoped to a household: {', '.join(_untenanted)}. Add them to that list and to "
        f"the migration, or the API will serve one family another family's rows."
    )
