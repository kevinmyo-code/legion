from django.apps import AppConfig


class CoreConfig(AppConfig):
    """No models. Holds infrastructure migrations that have to run before
    any other app's first migration - `migrations/0001_create_django_schema.py`
    is the only thing here. Keeping this separate from `household` matters:
    `AUTH_USER_MODEL = "household.User"` makes `household`'s own first
    migration load-bearing for Django's `swappable_dependency` machinery
    (several contrib apps depend on `("household", "__first__")` to mean
    "the migration that creates the User model"). Putting the
    schema-creation step inside `household`'s own chain instead of here
    made THAT migration household's first one instead, and every
    swappable-dependency edge pointing at `household.__first__` then landed
    on a migration that creates no models at all - `create_test_db` failed
    with `Related model 'household.user' cannot be resolved` the one time
    this was tried. `core` has no models and therefore no swappable
    dependencies pointing at it, so this problem cannot recur here.
    """

    default_auto_field = "django.db.models.BigAutoField"
    name = "core"
