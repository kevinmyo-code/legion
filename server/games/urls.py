from django.urls import path

from games.views import (
    AcceptView,
    CancelView,
    DeclineView,
    MatchCreateView,
    MatchDetailView,
    OpponentsView,
    PendingView,
    ProgressView,
)

# Trailing slashes throughout: ticket 05 writes the contract this way and the
# phone (ticket 06) calls these exact paths.
urlpatterns = [
    path("opponents/", OpponentsView.as_view(), name="games-opponents"),
    path("matches/", MatchCreateView.as_view(), name="games-matches"),
    path("matches/pending/", PendingView.as_view(), name="games-matches-pending"),
    path("matches/<uuid:match_id>/", MatchDetailView.as_view(), name="games-match"),
    path("matches/<uuid:match_id>/accept/", AcceptView.as_view(), name="games-match-accept"),
    path("matches/<uuid:match_id>/decline/", DeclineView.as_view(), name="games-match-decline"),
    path("matches/<uuid:match_id>/cancel/", CancelView.as_view(), name="games-match-cancel"),
    path("matches/<uuid:match_id>/progress/", ProgressView.as_view(), name="games-match-progress"),
]
