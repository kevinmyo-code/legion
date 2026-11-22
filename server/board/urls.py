"""Mounted at `api/` (`legion/urls.py`), no trailing slashes like `/api/auth/`."""

from django.urls import path

from board.action_views import (
    ActionAllowView,
    ActionDeclineView,
    ActionListView,
    ActionRequestView,
    ApprovedActionsView,
    PulledActionView,
)
from board.answer_views import (
    AnswerListView,
    InboxDeliveredView,
    InboxView,
    QueueView,
    VoteListView,
)
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
    # Control room C2: his answers and votes, and the laptop's pull of them.
    path("board/answers", AnswerListView.as_view(), name="board-answers"),
    path("board/votes", VoteListView.as_view(), name="board-votes"),
    path("board/queue", QueueView.as_view(), name="board-queue"),
    path("board/inbox", InboxView.as_view(), name="board-inbox"),
    path("board/inbox/delivered", InboxDeliveredView.as_view(), name="board-inbox-delivered"),
    # Control room C3 (dark behind LEGION_BOARD_ACTIONS too): action records and his tap.
    path("board/actions", ActionListView.as_view(), name="board-actions"),
    path("board/actions/request", ActionRequestView.as_view(), name="board-actions-request"),
    path("board/actions/approved", ApprovedActionsView.as_view(), name="board-actions-approved"),
    path(
        "board/actions/<uuid:action_id>/allow", ActionAllowView.as_view(), name="board-action-allow"
    ),
    path(
        "board/actions/<uuid:action_id>/decline",
        ActionDeclineView.as_view(),
        name="board-action-decline",
    ),
    path(
        "board/actions/<uuid:action_id>/pulled",
        PulledActionView.as_view(),
        name="board-action-pulled",
    ),
]
