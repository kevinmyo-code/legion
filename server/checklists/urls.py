from django.urls import path

from checklists.views import (
    ChecklistDetailView,
    ChecklistItemDetailView,
    ChecklistItemListCreateView,
    ChecklistItemTickView,
    ChecklistItemUntickView,
    ChecklistListCreateView,
)

urlpatterns = [
    path("", ChecklistListCreateView.as_view(), name="checklist-list-create"),
    path("<uuid:checklist_id>", ChecklistDetailView.as_view(), name="checklist-detail"),
    path(
        "<uuid:checklist_id>/items",
        ChecklistItemListCreateView.as_view(),
        name="checklist-item-list-create",
    ),
    path(
        "<uuid:checklist_id>/items/<uuid:item_id>",
        ChecklistItemDetailView.as_view(),
        name="checklist-item-detail",
    ),
    path(
        "<uuid:checklist_id>/items/<uuid:item_id>/tick",
        ChecklistItemTickView.as_view(),
        name="checklist-item-tick",
    ),
    path(
        "<uuid:checklist_id>/items/<uuid:item_id>/tick/<int:day>",
        ChecklistItemUntickView.as_view(),
        name="checklist-item-untick",
    ),
]
