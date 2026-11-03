from django.apps import AppConfig
from django.db.models.signals import post_migrate


class ProceduresConfig(AppConfig):
    """procedures ticket 11 (ADR 0070): versioned procedure definitions and the built-in loader.

    The built-ins in `procedures/builtin/*.yaml` are loaded after every `migrate`, so a stranger's
    clone has them after `compose up` with no manual step. The loader writes a new version only
    when a file's hash changed, so a second `migrate` writes nothing.
    """

    default_auto_field = "django.db.models.BigAutoField"
    name = "procedures"

    def ready(self) -> None:
        # sender=self: post_migrate fires once per app config; this runs once per migrate.
        post_migrate.connect(_load_builtins_after_migrate, sender=self)


def _load_builtins_after_migrate(sender, using="default", **kwargs):
    from django.db import connections

    from procedures.loader import load_builtins

    if using != "default":
        return
    # Migrated backwards past 0001, or a partial plan: no table, nothing to load into.
    tables = set(connections[using].introspection.table_names())
    if not {"procedures", "procedure_versions"} <= tables:
        return
    load_builtins()
