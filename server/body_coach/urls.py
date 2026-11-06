"""Mounted at `api/body/coach/` in `legion/urls.py`. See `body_coach/views.py`."""

from django.urls import path

from body_coach.views import (
    EnergyView,
    ProfileView,
    ProposalAcceptView,
    ProposalDismissView,
    SavedMealDetailView,
    SavedMealListCreateView,
    SavedMealLogView,
    TrendView,
    WaistDetailView,
    WaistListCreateView,
    WeeklyLineView,
)

urlpatterns = [
    path("profile/", ProfileView.as_view(), name="body-coach-profile"),
    path("energy/", EnergyView.as_view(), name="body-coach-energy"),
    path("trend/", TrendView.as_view(), name="body-coach-trend"),
    path("waist/", WaistListCreateView.as_view(), name="body-coach-waist"),
    path("waist/<uuid:waist_id>/", WaistDetailView.as_view(), name="body-coach-waist-detail"),
    path("saved-meals/", SavedMealListCreateView.as_view(), name="body-coach-saved-meals"),
    path(
        "saved-meals/<uuid:meal_id>/",
        SavedMealDetailView.as_view(),
        name="body-coach-saved-meal-detail",
    ),
    path(
        "saved-meals/<uuid:meal_id>/log/",
        SavedMealLogView.as_view(),
        name="body-coach-saved-meal-log",
    ),
    path(
        "proposals/<uuid:proposal_id>/accept/",
        ProposalAcceptView.as_view(),
        name="body-coach-proposal-accept",
    ),
    path(
        "proposals/<uuid:proposal_id>/dismiss/",
        ProposalDismissView.as_view(),
        name="body-coach-proposal-dismiss",
    ),
    path("weekly-line/", WeeklyLineView.as_view(), name="body-coach-weekly-line"),
]
