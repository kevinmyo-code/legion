from django.apps import AppConfig


class LegacyConfig(AppConfig):
    """The 41 `public` tables Supabase created (`supabase/migrations/`), read
    through Django as `managed = False` mirrors. See `legacy/models/` and
    `legacy/CONSTRAINTS.md`. Ticket 02 of the django-engine map; ADR 0044."""

    default_auto_field = "django.db.models.BigAutoField"
    name = "legacy"
