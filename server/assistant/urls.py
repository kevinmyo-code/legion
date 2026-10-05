from django.urls import path

from assistant.views import AssistantCompanionView, AssistantSessionView, AssistantToolView

urlpatterns = [
    path("session", AssistantSessionView.as_view(), name="assistant-session"),
    path("tool", AssistantToolView.as_view(), name="assistant-tool"),
    path("companion", AssistantCompanionView.as_view(), name="assistant-companion"),
]
