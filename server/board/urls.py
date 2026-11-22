"""Mounted at `api/` (`legion/urls.py`), no trailing slashes like `/api/auth/`."""

from django.urls import path

from board.idea_views import (
    DeliveredIdeaView,
    IdeaConfirmView,
    IdeaDetailView,
    IdeaListView,
    PendingIdeasView,
)
from board.views import BoardView, BoardWriterView

urlpatterns = [
    path("board", BoardView.as_view(), name="board"),
    path("board/writer", BoardWriterView.as_view(), name="board-writer"),
    path("board/ideas", IdeaListView.as_view(), name="board-ideas"),
    # Before `<uuid:idea_id>`: "pending" is not a uuid, but the order keeps it obvious.
    path("board/ideas/pending", PendingIdeasView.as_view(), name="board-ideas-pending"),
    path("board/ideas/<uuid:idea_id>", IdeaDetailView.as_view(), name="board-idea"),
    path(
        "board/ideas/<uuid:idea_id>/confirm", IdeaConfirmView.as_view(), name="board-idea-confirm"
    ),
    path(
        "board/ideas/<uuid:idea_id>/delivered",
        DeliveredIdeaView.as_view(),
        name="board-idea-delivered",
    ),
]
