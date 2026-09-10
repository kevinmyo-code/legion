from django.urls import path

from household.views import (
    CsrfView,
    LoginView,
    LogoutView,
    MeView,
    SessionLoginView,
    SessionLogoutView,
)

urlpatterns = [
    path("login", LoginView.as_view(), name="auth-login"),
    path("logout", LogoutView.as_view(), name="auth-logout"),
    path("me", MeView.as_view(), name="auth-me"),
    path("session/login", SessionLoginView.as_view(), name="auth-session-login"),
    path("session/logout", SessionLogoutView.as_view(), name="auth-session-logout"),
    path("csrf", CsrfView.as_view(), name="auth-csrf"),
]
