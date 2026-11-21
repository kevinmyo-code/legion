"""Mounted at `api/` (`legion/urls.py`), no trailing slashes like `/api/auth/`."""

from django.urls import path

from board.views import BoardView, BoardWriterView

urlpatterns = [
    path("board", BoardView.as_view(), name="board"),
    path("board/writer", BoardWriterView.as_view(), name="board-writer"),
]
