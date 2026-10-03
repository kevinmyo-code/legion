from django.apps import AppConfig


class EngineMcpConfig(AppConfig):
    """engine-mcp ticket 10: the engine's `/mcp` endpoint.

    Named `engine_mcp`, never `mcp`: an app package called `mcp` would shadow
    the official SDK it is built on (`import mcp` would find this directory).
    """

    default_auto_field = "django.db.models.BigAutoField"
    name = "engine_mcp"
