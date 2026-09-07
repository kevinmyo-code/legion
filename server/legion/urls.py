"""Root URL table. Kept flat and legible - each app owns its own `urls.py`
and this file only says where each one is mounted."""
from django.contrib import admin
from django.urls import include, path
from drf_spectacular.views import SpectacularAPIView, SpectacularSwaggerView

from api.views import healthz

urlpatterns = [
    path("admin/", admin.site.urls),
    # The SAME view on two paths, and the second one is the one that works in
    # production. **Cloud Run's frontend reserves `/healthz`**: it answers with
    # Google's own 404 page before the request ever reaches the container,
    # measured on the live service 2026-09-06 (`/healthzz`, `/health`,
    # `/readyz` and every other path reached Django on the same deploy, so it
    # is that exact string and not a routing mistake here). `/healthz` still
    # works under `docker compose` locally, where nothing is in front of
    # gunicorn, and it stays for that reason and because ticket 01's own
    # verification names it. Anything checking the deployed engine must use
    # `/health`.
    path("healthz", healthz, name="healthz"),
    path("health", healthz, name="health"),
    path("api/auth/", include("household.urls")),
    # `server/openapi.yaml` (map's handoff artefact to ticket 09) is
    # regenerated from this endpoint by `manage.py spectacular`.
    path("api/schema/", SpectacularAPIView.as_view(), name="schema"),
    path(
        "api/schema/swagger/",
        SpectacularSwaggerView.as_view(url_name="schema"),
        name="swagger-ui",
    ),
    # Ticket 04 (django-engine map), Phase 2 slice: events, checklists, and
    # the combined changes feed. Two separate includes because
    # `checklists.urls` nests item/tick routes under its own `<checklist_id>`
    # prefix and reads more plainly mounted at its own `api/checklists/`
    # root than folded into `api.urls` alongside `events`/`changes`.
    path("api/", include("api.urls")),
    path("api/checklists/", include("checklists.urls")),
    # Ticket 03 (django-engine map): the section 4 gate. Its own mount rather
    # than folded into `api.urls` for the same reason `checklists` has one -
    # these are commit endpoints that return a verdict, not CRUD over a
    # collection, and grouping them under one prefix keeps that visible.
    path("api/ingest/", include("ingest.urls")),
]
