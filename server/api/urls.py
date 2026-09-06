"""The Phase 2 slice's own routes - events and the combined changes feed
(django-engine ticket 04). Mounted at `api/` in `legion/urls.py`, alongside
`checklists.urls` (mounted at `api/checklists/`) and the pre-existing
`api/auth/`/`api/schema/` routes ticket 01 already owns.

Phase 5 adds the synced-model routes underneath the same `api/` mount, from
`api/registry.py`. They are generated rather than typed out because the
generation is the point: thirteen tables, two routes each, and a hand-typed
list of twenty-six paths would be twenty-six chances to spell one
differently. `synced_paths` builds them from the viewset's own `aspect` and
`table`, so a route cannot disagree with the class it dispatches to.

What that loop produces today:

    /api/places/                          /api/places/<label>/
    /api/voice_notes/                     /api/voice_notes/<uuid>/
    /api/body/bodyweight_logs/            /api/body/bodyweight_logs/<origin_guid>/
    /api/body/meal_logs/                  /api/body/meal_logs/<origin_guid>/
    /api/body/meal_targets/               /api/body/meal_targets/<origin_guid>/
    /api/body/sleep_logs/                 /api/body/sleep_logs/<origin_guid>/
    /api/body/sleep_targets/              /api/body/sleep_targets/<origin_guid>/
    /api/body/workout_plans/              /api/body/workout_plans/<origin_guid>/
    /api/body/workout_plan_items/         /api/body/workout_plan_items/<origin_guid>/
    /api/body/workout_set_logs/           /api/body/workout_set_logs/<origin_guid>/
    /api/memory/memories/                 /api/memory/memories/<origin_guid>/
    /api/memory/companion_memories/       /api/memory/companion_memories/<origin_guid>/
    /api/memory/memory_audit/             /api/memory/memory_audit/<origin_guid>/

`memory_audit`'s detail route carries PUT and no DELETE (append-only), and
`voice_notes`' list route carries POST because its identity is the server's
own id. Both are stated on the viewset, not here.
"""
from django.urls import path

from api.changes import ChangesView
from api.events import EventDetailView, EventListCreateView
from api.registry import SYNCED_VIEWSETS
from api.synced import synced_paths

urlpatterns = [
    path("events", EventListCreateView.as_view(), name="event-list-create"),
    path("events/<uuid:pk>", EventDetailView.as_view(), name="event-detail"),
    path("changes", ChangesView.as_view(), name="changes"),
]

for _viewset in SYNCED_VIEWSETS:
    urlpatterns += synced_paths(_viewset)
