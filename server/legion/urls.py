"""Root URL table. Kept flat and legible - each app owns its own `urls.py`
and this file only says where each one is mounted."""
from django.contrib import admin
from django.urls import include, path, re_path
from drf_spectacular.views import SpectacularAPIView, SpectacularSwaggerView

from api.views import healthz
from web.views import spa_index

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
    # web-and-households ticket 03. Its own mount rather than folded into
    # `household.urls`, because these routes are the household RESOURCE an
    # owner administers and those are the doors a credential goes through.
    path("api/households/", include("household.urls_households")),
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
    # LAST, and it has to be: this pattern matches almost everything, so any
    # route added below it would be unreachable. Django tries patterns in
    # order, so putting the catch-all at the end is what lets every real route
    # above win first.
    #
    # The negative lookahead is belt to that braces. Order alone would be
    # enough today, but the failure mode if someone ever adds a route below
    # here - or reorders this list - is the worst kind: `/api/...` would answer
    # 200 with an HTML page instead of JSON, and a client would parse the shell
    # as a body rather than see an error. The lookahead makes that impossible
    # regardless of position, and names exactly what Django owns:
    # `api/` (the contract), `admin/`, `static/` and `media/` (files), and the
    # two health paths. Everything else is the SPA's, 404s included - an
    # unknown URL renders the client's own "no such page" rather than Django's,
    # because the SPA is the thing that knows which of its routes exist.
    #
    # `health` alone would cover `healthz` by prefix; both are written out
    # because both are real entries in this table (see the note above on Cloud
    # Run reserving `/healthz`) and a reader should not have to spot that one
    # subsumes the other. The cost is that an SPA route beginning with one of
    # these words is unreachable - `/health-log` would be swallowed - which is
    # a real constraint on ticket 05's route names, not a bug here.
    re_path(r"^(?!api/|admin/|static/|media/|health|healthz).*$", spa_index, name="spa"),
]
