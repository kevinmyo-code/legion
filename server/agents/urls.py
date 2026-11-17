from django.urls import path

from agents.views import AgentCancelView, AgentProbeSseView, AgentTurnView

urlpatterns = [
    path("turn", AgentTurnView.as_view(), name="agents-turn"),
    path("turn/<uuid:turn_id>/cancel", AgentCancelView.as_view(), name="agents-turn-cancel"),
    path("probe/sse", AgentProbeSseView.as_view(), name="agents-probe-sse"),
]
