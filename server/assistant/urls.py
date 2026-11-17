from django.urls import path

from assistant.pack_views import AssistantPackView
from assistant.views import (
    AssistantCompanionView,
    AssistantConversationView,
    AssistantRoboticView,
    AssistantSessionView,
    AssistantToolView,
    AssistantTranscribeView,
    AssistantTurnView,
)

urlpatterns = [
    path("session", AssistantSessionView.as_view(), name="assistant-session"),
    path("tool", AssistantToolView.as_view(), name="assistant-tool"),
    # ADR 0102: one cascade turn, answered as Server-Sent Events.
    path("turn", AssistantTurnView.as_view(), name="assistant-turn"),
    path("transcribe", AssistantTranscribeView.as_view(), name="assistant-transcribe"),
    path("robotic", AssistantRoboticView.as_view(), name="assistant-robotic"),
    path("conversation", AssistantConversationView.as_view(), name="assistant-conversation"),
    path("companion", AssistantCompanionView.as_view(), name="assistant-companion"),
    # alfred-ota ticket 01: the Alfred pack the phone reads at session start.
    path("pack", AssistantPackView.as_view(), name="assistant-pack"),
]
