from django.urls import path

from push.views import (
    PreferenceOffView,
    PreferenceView,
    SubscriptionDetailView,
    SubscriptionListCreateView,
    VapidKeyView,
)

urlpatterns = [
    path("vapid-public-key", VapidKeyView.as_view(), name="push-vapid-public-key"),
    path("subscriptions", SubscriptionListCreateView.as_view(), name="push-subscriptions"),
    path(
        "subscriptions/<uuid:subscription_id>",
        SubscriptionDetailView.as_view(),
        name="push-subscription-detail",
    ),
    path("preferences", PreferenceView.as_view(), name="push-preferences"),
    path("preferences/off", PreferenceOffView.as_view(), name="push-preferences-off"),
]
