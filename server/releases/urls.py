from django.urls import path

from releases.views import ReleaseDownloadView, ReleaseLatestView, ReleasePublishView

urlpatterns = [
    path("", ReleasePublishView.as_view(), name="releases-publish"),
    path("latest", ReleaseLatestView.as_view(), name="releases-latest"),
    path("<uuid:release_id>/download", ReleaseDownloadView.as_view(), name="releases-download"),
]
