from django.urls import path

from brief.views import BriefLatestView, BriefRefreshView

urlpatterns = [
    path("latest", BriefLatestView.as_view(), name="brief-latest"),
    path("refresh", BriefRefreshView.as_view(), name="brief-refresh"),
]
