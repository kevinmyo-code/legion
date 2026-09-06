"""The Phase 2 slice's own routes - events and the combined changes feed
(django-engine ticket 04). Mounted at `api/` in `legion/urls.py`, alongside
`checklists.urls` (mounted at `api/checklists/`) and the pre-existing
`api/auth/`/`api/schema/` routes ticket 01 already owns.
"""
from django.urls import path

from api.changes import ChangesView
from api.events import EventDetailView, EventListCreateView

urlpatterns = [
    path("events", EventListCreateView.as_view(), name="event-list-create"),
    path("events/<uuid:pk>", EventDetailView.as_view(), name="event-detail"),
    path("changes", ChangesView.as_view(), name="changes"),
]
