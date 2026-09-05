"""WSGI entry point. `deploy/docker-compose.yml`'s `web` service runs this
under gunicorn; nothing else in the repo imports it."""
import os

from django.core.wsgi import get_wsgi_application

os.environ.setdefault("DJANGO_SETTINGS_MODULE", "legion.settings")

application = get_wsgi_application()
