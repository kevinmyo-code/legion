from django.urls import path

from watches.views import FiringAckView, FiringListView, WatchDetailView, WatchListCreateView

urlpatterns = [
    path("", WatchListCreateView.as_view(), name="watches-list-create"),
    path("firings", FiringListView.as_view(), name="watches-firings"),
    path("firings/ack", FiringAckView.as_view(), name="watches-firings-ack"),
    path("<uuid:watch_id>", WatchDetailView.as_view(), name="watches-detail"),
]
