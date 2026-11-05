from django.apps import AppConfig


class DiagramsConfig(AppConfig):
    """arch-diagrams ticket 01 (Kevin, 2026-11-05): the architecture diagrams `show_diagram`
    shows. The public catalogue ships in the image (`catalogue.json`); the private projects'
    diagrams are loaded into `RecruiterDiagram` from a folder outside the repo."""

    default_auto_field = "django.db.models.BigAutoField"
    name = "diagrams"
