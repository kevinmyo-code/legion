"""`/api/households/` - the household resource, mounted separately from
`/api/auth/`.

Two prefixes rather than one because they answer different questions: the
auth routes are about a CREDENTIAL (who is this, give me a token, drop this
device), and these are about the household an owner administers. See
`household/households.py` for why the household's own uuid is allowed on
the wire here and nowhere else.
"""
from django.urls import path

from household.households import (
    HouseholdMeView,
    InviteDetailView,
    InviteListCreateView,
    MemberDetailView,
)

urlpatterns = [
    path("me", HouseholdMeView.as_view(), name="household-me"),
    path("me/invites", InviteListCreateView.as_view(), name="household-invites"),
    path("me/invites/<str:code>", InviteDetailView.as_view(), name="household-invite-detail"),
    path("me/members/<uuid:user_id>", MemberDetailView.as_view(), name="household-member-detail"),
]
