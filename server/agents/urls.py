from django.urls import path

from agents.relay_views import (
    AgentToolResultView,
    AgentTurnGoView,
    AgentTurnStatusView,
    AgentTurnTakeView,
)
from agents.views import (
    AgentCancelView,
    AgentLimbView,
    AgentProbeSseView,
    AgentTurnStreamView,
    AgentTurnView,
    AgentWarmView,
)

urlpatterns = [
    path("turn", AgentTurnView.as_view(), name="agents-turn"),
    path("turn/stream", AgentTurnStreamView.as_view(), name="agents-turn-stream"),
    path("turn/<uuid:turn_id>", AgentTurnStatusView.as_view(), name="agents-turn-status"),
    path("turn/<uuid:turn_id>/go", AgentTurnGoView.as_view(), name="agents-turn-go"),
    path("turn/<uuid:turn_id>/take", AgentTurnTakeView.as_view(), name="agents-turn-take"),
    path("turn/<uuid:turn_id>/cancel", AgentCancelView.as_view(), name="agents-turn-cancel"),
    path(
        "turn/<uuid:turn_id>/tool_result",
        AgentToolResultView.as_view(),
        name="agents-turn-tool-result",
    ),
    path("limb", AgentLimbView.as_view(), name="agents-limb"),
    path("warm", AgentWarmView.as_view(), name="agents-warm"),
    path("probe/sse", AgentProbeSseView.as_view(), name="agents-probe-sse"),
]
