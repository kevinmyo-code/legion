from django.apps import AppConfig


class ReleasesConfig(AppConfig):
    """alfred-ota ticket 04: the phone's (and later the head unit's) published APKs, and the
    signed links it downloads them through. Engine-wide, not household-scoped (`models.py`)."""

    default_auto_field = "django.db.models.BigAutoField"
    name = "releases"
