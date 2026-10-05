from django.apps import AppConfig


class ChecklistsConfig(AppConfig):
    default_auto_field = "django.db.models.BigAutoField"
    name = "checklists"

    def ready(self) -> None:
        from checklists import signals  # noqa: F401  (registers the Household post_save receiver)
