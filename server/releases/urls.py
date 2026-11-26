from django.urls import path

from releases.views import (
    ReleaseDownloadView,
    ReleaseHistoryView,
    ReleaseLatestView,
    ReleasePublishView,
)

urlpatterns = [
    path("", ReleasePublishView.as_view(), name="releases-publish"),
    path("latest", ReleaseLatestView.as_view(), name="releases-latest"),
    path("history", ReleaseHistoryView.as_view(), name="releases-history"),
    path("<uuid:release_id>/download", ReleaseDownloadView.as_view(), name="releases-download"),
]
