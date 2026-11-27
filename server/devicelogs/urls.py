from django.urls import path

from devicelogs.conversations import ConversationsView
from devicelogs.records import DeviceLogRenderView
from devicelogs.views import DeviceLogUploadView

urlpatterns = [
    # POST uploads a batch; GET lists the household's recent ones (alfred-builds ticket 02).
    path("", DeviceLogUploadView.as_view(), name="device-logs-upload"),
    # The household's conversation batches, rendered, per device (#160).
    path("conversations/", ConversationsView.as_view(), name="device-logs-conversations"),
    path("<uuid:log_id>/render", DeviceLogRenderView.as_view(), name="device-logs-render"),
]
