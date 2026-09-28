from django.apps import AppConfig


class IngestConfig(AppConfig):
    default_auto_field = "django.db.models.BigAutoField"
    name = "ingest"

    def ready(self):
        # backend-etl ticket 10: the BofA PDF parsers join the registry, so a
        # BofA statement is read deterministically and never reaches Gemini.
        from ingest.parsers import bofa

        bofa.register()
