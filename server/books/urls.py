from django.urls import path

from books.reading import (
    BookBookmarkDetailView,
    BookBookmarkListView,
    BookPositionDetailView,
    BookPositionListView,
)
from books.views import BookConvertView

# No trailing slashes: ticket 08's contract writes the paths without one, and
# the phone and the web reader are built against it. `str` converters rather
# than `uuid` or a regex, so a malformed hash, variant or id reaches the view
# and is refused in words (400) rather than falling through to a bare 404.
urlpatterns = [
    path("convert", BookConvertView.as_view(), name="books-convert"),
    path("positions", BookPositionListView.as_view(), name="books-positions"),
    path(
        "positions/<str:sha256>/<str:variant>",
        BookPositionDetailView.as_view(),
        name="books-position",
    ),
    path("bookmarks", BookBookmarkListView.as_view(), name="books-bookmarks"),
    path("bookmarks/<str:id>", BookBookmarkDetailView.as_view(), name="books-bookmark"),
]
