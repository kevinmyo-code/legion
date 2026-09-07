from django.apps import AppConfig


class ApiConfig(AppConfig):
    default_auto_field = "django.db.models.BigAutoField"
    name = "api"

    def ready(self) -> None:
        # drf-spectacular discovers extensions by IMPORT, not by setting:
        # `DeviceTokenScheme` registers itself when `api.schema` is first
        # imported. Without this line the schema would still generate, and
        # would silently omit `security:` from every authenticated
        # operation - a generated client with no idea the
        # `Authorization: Token <key>` header exists. Imported here rather
        # than relied on as a side effect of some view import, because
        # "whichever module happens to import it first" is not a
        # guarantee.
        from api import schema  # noqa: F401  (registers the auth extension)
