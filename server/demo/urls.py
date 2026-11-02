from django.urls import path

from demo.views import DemoEnterView

urlpatterns = [
    path("enter", DemoEnterView.as_view(), name="demo-enter"),
]
