from django.apps import AppConfig


class GarageConfig(AppConfig):
    """The garage assistant (option B, slice 1): `show_part` and the checked fastener specs it
    speaks. The model and its ids ship in the image (`garage/xj_front.py`); the specs load from a
    private sheet outside the repo (`manage.py load_garage_specs`)."""

    default_auto_field = "django.db.models.BigAutoField"
    name = "garage"
