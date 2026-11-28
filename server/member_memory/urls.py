from django.urls import path

from member_memory import review_views as rv
from member_memory.turn_views import (
    ConversationSummariesView,
    ConversationTurnsView,
    SessionEndView,
)
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
    # issue #450: each conversation turn, saved as it ends.
    path("turns", ConversationTurnsView.as_view(), name="member-memory-turns"),
    # issue #451: a conversation ended, and the summaries written when one does.
    path("turns/end", SessionEndView.as_view(), name="member-memory-turns-end"),
    path("summaries", ConversationSummariesView.as_view(), name="member-memory-summaries"),
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
