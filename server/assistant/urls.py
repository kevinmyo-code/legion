from django.urls import path

from assistant.pack_views import AssistantPackView
from assistant.views import (
    AssistantCompanionView,
    AssistantConversationView,
    AssistantSessionView,
    AssistantToolView,
)

urlpatterns = [
    path("session", AssistantSessionView.as_view(), name="assistant-session"),
    path("tool", AssistantToolView.as_view(), name="assistant-tool"),
    path("conversation", AssistantConversationView.as_view(), name="assistant-conversation"),
    path("companion", AssistantCompanionView.as_view(), name="assistant-companion"),
    # alfred-ota ticket 01: the Alfred pack the phone reads at session start.
    path("pack", AssistantPackView.as_view(), name="assistant-pack"),
]
