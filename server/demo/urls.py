from django.urls import path

from demo.content_views import DemoAssetView, DemoContentView, DemoSessionView
from demo.views import DemoEnterView

urlpatterns = [
    path("enter", DemoEnterView.as_view(), name="demo-enter"),
    path("session", DemoSessionView.as_view(), name="demo-session"),
    path("content", DemoContentView.as_view(), name="demo-content"),
    path("asset/<str:kind>", DemoAssetView.as_view(), name="demo-asset"),
]
