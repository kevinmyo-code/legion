from django.urls import path

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
    path("<uuid:memory_id>", MemoryDetailView.as_view(), name="member-memory-detail"),
]
