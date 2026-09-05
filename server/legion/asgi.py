"""ASGI entry point. Not served by anything in ticket 01 - gunicorn runs the
WSGI app - but Django scaffolds expect one, and a later ticket may want it
for websockets or SSE without a settings change."""
import os

from django.core.asgi import get_asgi_application

os.environ.setdefault("DJANGO_SETTINGS_MODULE", "legion.settings")

application = get_asgi_application()
