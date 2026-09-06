from django.apps import AppConfig


class HouseholdConfig(AppConfig):
    default_auto_field = "django.db.models.BigAutoField"
    name = "household"

    def ready(self) -> None:
        from household import signals  # noqa: F401  (registers the post_save receiver)
