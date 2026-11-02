from django.apps import AppConfig


class DemoConfig(AppConfig):
    """recruiter-demo ticket 02: the shared invite code, and the throwaway
    visitor each recruiter becomes when they enter it (ADR 0068)."""

    default_auto_field = "django.db.models.BigAutoField"
    name = "demo"
