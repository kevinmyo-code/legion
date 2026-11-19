from django.urls import path

from demo.content_views import (
    DemoAssetView,
    DemoChipsView,
    DemoContentView,
    DemoMediaView,
    DemoSessionView,
)
from demo.views import DemoEnterView, DemoFunnelView

urlpatterns = [
    path("enter", DemoEnterView.as_view(), name="demo-enter"),
    path("funnel", DemoFunnelView.as_view(), name="demo-funnel"),
    path("session", DemoSessionView.as_view(), name="demo-session"),
    path("content", DemoContentView.as_view(), name="demo-content"),
    path("asset/<str:kind>", DemoAssetView.as_view(), name="demo-asset"),
    path("media/<str:name>", DemoMediaView.as_view(), name="demo-media"),
    path("chips", DemoChipsView.as_view(), name="demo-chips"),
]
