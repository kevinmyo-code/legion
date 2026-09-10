from django.urls import path

from household.views import (
    CsrfView,
    DeviceDetailView,
    DeviceListView,
    InvitePreviewView,
    LoginView,
    LogoutView,
    MeView,
    SessionLoginView,
    SessionLogoutView,
    SignupView,
)

urlpatterns = [
    path("login", LoginView.as_view(), name="auth-login"),
    path("logout", LogoutView.as_view(), name="auth-logout"),
    path("me", MeView.as_view(), name="auth-me"),
    path("signup", SignupView.as_view(), name="auth-signup"),
    # `<str:code>` rather than a tighter converter: an invite code is
    # base64url, so it can contain `-` and `_`, and a mistyped code must reach
    # the view and be answered in words rather than 404ing out of the URL
    # resolver with Django's own page.
    path("invite/<str:code>", InvitePreviewView.as_view(), name="auth-invite"),
    path("devices", DeviceListView.as_view(), name="auth-devices"),
    path("devices/<int:token_id>", DeviceDetailView.as_view(), name="auth-device-detail"),
    path("session/login", SessionLoginView.as_view(), name="auth-session-login"),
    path("session/logout", SessionLogoutView.as_view(), name="auth-session-logout"),
    path("csrf", CsrfView.as_view(), name="auth-csrf"),
]
