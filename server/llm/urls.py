from django.urls import path

from llm.views import ModelConfigView

urlpatterns = [
    path("models", ModelConfigView.as_view(), name="config-models"),
]
