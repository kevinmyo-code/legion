"""Mounted at `api/auth/` beside `household.urls` (`legion/urls.py`), no trailing slashes like
the rest of `/api/auth/`."""

from django.urls import path

from headunit_signin.views import (
    HeadUnitDetailView,
    HeadUnitListView,
    HeadUnitRevokeAllView,
    HeadUnitRotateView,
)

urlpatterns = [
    path("head-units", HeadUnitListView.as_view(), name="headunit-list"),
    path("head-units/revoke-all", HeadUnitRevokeAllView.as_view(), name="headunit-revoke-all"),
    path(
        "head-units/<uuid:credential_id>", HeadUnitDetailView.as_view(), name="headunit-detail"
    ),
    path(
        "head-units/<uuid:credential_id>/rotate",
        HeadUnitRotateView.as_view(),
        name="headunit-rotate",
    ),
]
