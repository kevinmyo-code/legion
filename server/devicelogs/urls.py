from django.urls import path

from devicelogs.records import DeviceLogRenderView
from devicelogs.views import DeviceLogUploadView

urlpatterns = [
    # POST uploads a batch; GET lists the household's recent ones (alfred-builds ticket 02).
    path("", DeviceLogUploadView.as_view(), name="device-logs-upload"),
    path("<uuid:log_id>/render", DeviceLogRenderView.as_view(), name="device-logs-render"),
]
