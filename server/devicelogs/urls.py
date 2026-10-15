from django.urls import path

from devicelogs.views import DeviceLogUploadView

urlpatterns = [
    path("", DeviceLogUploadView.as_view(), name="device-logs-upload"),
]
