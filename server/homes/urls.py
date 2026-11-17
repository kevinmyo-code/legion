"""Mounted at `api/` beside `api.urls` (`legion/urls.py`); see `homes/views.py` for the routes."""

from homes.views import home_paths

urlpatterns = home_paths()
