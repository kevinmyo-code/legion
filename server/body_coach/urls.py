"""Mounted at `api/body/coach/` in `legion/urls.py`. See `body_coach/views.py`."""

from django.urls import path

from body_coach.meal_option_views import MealOptionLogView, MealOptionsView
from body_coach.plan_views import PlanDiscardView, PlanDraftView, PlanLockView, PlanView
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
from body_coach.workout_views import (
    ProgrammeProposalAcceptView,
    ProgrammeProposalDismissView,
    ProgrammeProposalsView,
    WorkoutAlternativesView,
    WorkoutCancelView,
    WorkoutFinishView,
    WorkoutLogView,
    WorkoutProgrammeView,
    WorkoutSetsView,
    WorkoutStartView,
    WorkoutSwapView,
    WorkoutTodayView,
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
    # body coach 2 (2026-11-06): the plan, drafted, locked in, adjusted.
    path("plan/", PlanView.as_view(), name="body-coach-plan"),
    path("plan/draft/", PlanDraftView.as_view(), name="body-coach-plan-draft"),
    path("plan/<uuid:plan_id>/lock/", PlanLockView.as_view(), name="body-coach-plan-lock"),
    path("plan/<uuid:plan_id>/discard/", PlanDiscardView.as_view(), name="body-coach-plan-discard"),
    # body coach 2 (2026-11-06): what to eat from what's left.
    path("meal-options/", MealOptionsView.as_view(), name="body-coach-meal-options"),
    path("meal-options/log/", MealOptionLogView.as_view(), name="body-coach-meal-options-log"),
    # workouts (2026-11-06): the deterministic programme (`body_coach/programme.py`).
    path("workout/", WorkoutTodayView.as_view(), name="body-coach-workout"),
    path("workout/programme/", WorkoutProgrammeView.as_view(), name="body-coach-workout-programme"),
    path("workout/start/", WorkoutStartView.as_view(), name="body-coach-workout-start"),
    path("workout/sets/", WorkoutSetsView.as_view(), name="body-coach-workout-sets"),
    path("workout/log/", WorkoutLogView.as_view(), name="body-coach-workout-log"),
    path("workout/swap/", WorkoutSwapView.as_view(), name="body-coach-workout-swap"),
    path("workout/finish/", WorkoutFinishView.as_view(), name="body-coach-workout-finish"),
    path("workout/cancel/", WorkoutCancelView.as_view(), name="body-coach-workout-cancel"),
    # body coach 2 (2026-11-06): programme change proposals and the alternatives read.
    path(
        "workout/proposals/",
        ProgrammeProposalsView.as_view(),
        name="body-coach-workout-proposals",
    ),
    path(
        "workout/proposals/<uuid:proposal_id>/accept/",
        ProgrammeProposalAcceptView.as_view(),
        name="body-coach-workout-proposal-accept",
    ),
    path(
        "workout/proposals/<uuid:proposal_id>/dismiss/",
        ProgrammeProposalDismissView.as_view(),
        name="body-coach-workout-proposal-dismiss",
    ),
    path(
        "workout/alternatives/<str:exercise>/",
        WorkoutAlternativesView.as_view(),
        name="body-coach-workout-alternatives",
    ),
]
