from django.urls import path

from open_loops.views import (
    OpenLoopDismissView,
    OpenLoopListView,
    OpenLoopOfferView,
    OpenLoopSettingsView,
    OpenLoopTrackView,
)

urlpatterns = [
    path("", OpenLoopListView.as_view(), name="open-loops-list"),
    path("offer", OpenLoopOfferView.as_view(), name="open-loops-offer"),
    path("settings", OpenLoopSettingsView.as_view(), name="open-loops-settings"),
    path("<uuid:loop_id>/track", OpenLoopTrackView.as_view(), name="open-loops-track"),
    path("<uuid:loop_id>/dismiss", OpenLoopDismissView.as_view(), name="open-loops-dismiss"),
]
