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
from api.memory import MEMORY_VIEWSETS
from api.places import PlaceViewSet
from api.synced import SyncedModelViewSet
from api.voice_notes import VoiceNoteViewSet

SYNCED_VIEWSETS: list[type[SyncedModelViewSet]] = [
    PlaceViewSet,
    VoiceNoteViewSet,
    *BODY_VIEWSETS,
    *MEMORY_VIEWSETS,
]

# Aspect name -> the viewsets that make it up, in registry order. `body` has
# eight tables and `memory` three; `places` and `voice_notes` are one each.
# Built from the list rather than typed out a second time.
SYNCED_ASPECTS: dict[str, list[type[SyncedModelViewSet]]] = {}
for _viewset in SYNCED_VIEWSETS:
    SYNCED_ASPECTS.setdefault(_viewset.aspect, []).append(_viewset)
