from django.urls import path

from agents.views import (
    AgentCancelView,
    AgentLimbView,
    AgentProbeSseView,
    AgentTurnView,
    AgentWarmView,
)

urlpatterns = [
    path("turn", AgentTurnView.as_view(), name="agents-turn"),
    path("turn/<uuid:turn_id>/cancel", AgentCancelView.as_view(), name="agents-turn-cancel"),
    path("limb", AgentLimbView.as_view(), name="agents-limb"),
    path("warm", AgentWarmView.as_view(), name="agents-warm"),
    path("probe/sse", AgentProbeSseView.as_view(), name="agents-probe-sse"),
]
