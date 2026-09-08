from __future__ import annotations

from django.apps import AppConfig


class WebConfig(AppConfig):
    """The SPA shell. No models, no migrations, one view.

    It is an app rather than a couple of lines in `legion/urls.py` because the
    catch-all route and the "the bundle is not built" refusal are both real
    behaviour with real edge cases, and they belong somewhere a test can import
    them by name.
    """

    default_auto_field = "django.db.models.BigAutoField"
    name = "web"
