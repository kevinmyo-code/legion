from django.urls import path

from member_memory import review_views as rv
from member_memory.views import (
    MemoryDetailView,
    MemoryImportView,
    MemoryListCreateView,
    MemoryRecallView,
)

urlpatterns = [
    path("", MemoryListCreateView.as_view(), name="member-memory-list-create"),
    path("recall", MemoryRecallView.as_view(), name="member-memory-recall"),
    path("import", MemoryImportView.as_view(), name="member-memory-import"),
    # memory-review tickets 06, 08 and 15.
    path("review/context", rv.ReviewContextView.as_view(), name="member-memory-review-context"),
    path("review/changes", rv.ReviewChangesView.as_view(), name="member-memory-review-changes"),
    path(
        "review/changes/<int:change_id>/accept",
        rv.ReviewAcceptView.as_view(),
        name="member-memory-review-accept",
    ),
    path(
        "review/changes/<int:change_id>/dismiss",
        rv.ReviewDismissView.as_view(),
        name="member-memory-review-dismiss",
    ),
    path(
        "review/changes/<int:change_id>/revert",
        rv.ReviewRevertView.as_view(),
        name="member-memory-review-revert",
    ),
    path(
        "review/clusters/<str:cluster>/accept",
        rv.ReviewClusterAcceptView.as_view(),
        name="member-memory-review-cluster-accept",
    ),
    path("review/run", rv.ReviewRunView.as_view(), name="member-memory-review-run"),
    path("<uuid:memory_id>", MemoryDetailView.as_view(), name="member-memory-detail"),
]
