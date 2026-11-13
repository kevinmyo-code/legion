from django.urls import path

from greeting.views import GreetingFactsView, GreetingTopicView

urlpatterns = [
    path("facts", GreetingFactsView.as_view(), name="greeting-facts"),
    path("topics", GreetingTopicView.as_view(), name="greeting-topics"),
]
