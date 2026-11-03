from django.apps import AppConfig
from django.core import checks
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
        checks.register(postgres_is_15_or_newer, checks.Tags.database)


# Migration 0001's `UNIQUE NULLS NOT DISTINCT` (one row per built-in name) needs Postgres 15.
MIN_POSTGRES = 150000


def postgres_is_15_or_newer(app_configs=None, databases=None, **kwargs):
    """A database check: `migrate` runs database checks before any migration, so an older
    server is refused in words rather than failing inside 0001 with a syntax error."""
    from django.db import connections

    errors = []
    for alias in databases or ():
        connection = connections[alias]
        if connection.vendor != "postgresql":
            continue
        version = connection.pg_version
        if version is not None and version < MIN_POSTGRES:
            errors.append(
                checks.Error(
                    f"Postgres {version // 10000} is too old for LEGION: procedures migration "
                    "0001 uses UNIQUE NULLS NOT DISTINCT, which needs Postgres 15 or newer. "
                    "Nothing was migrated. Upgrade the database server (compose pins "
                    "postgres:16).",
                    id="procedures.E001",
                )
            )
    return errors


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
