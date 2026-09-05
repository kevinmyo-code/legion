"""Root URL table. Kept flat and legible - each app owns its own `urls.py`
and this file only says where each one is mounted."""
from django.contrib import admin
from django.urls import include, path
from drf_spectacular.views import SpectacularAPIView, SpectacularSwaggerView

from api.views import healthz

urlpatterns = [
    path("admin/", admin.site.urls),
    path("healthz", healthz, name="healthz"),
    path("api/auth/", include("household.urls")),
    # `server/openapi.yaml` (map's handoff artefact to ticket 09) is
    # regenerated from this endpoint by `manage.py spectacular`.
    path("api/schema/", SpectacularAPIView.as_view(), name="schema"),
    path(
        "api/schema/swagger/",
        SpectacularSwaggerView.as_view(url_name="schema"),
        name="swagger-ui",
    ),
]
