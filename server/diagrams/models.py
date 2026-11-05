"""The private projects' diagrams (arch-diagrams ticket 01, Kevin 2026-11-05).

Midcon and Quanex are other companies' systems, so their diagrams never go in the public repo.
`manage.py load_recruiter_diagrams <folder>` reads the SVGs `tools/render_diagrams.py --private`
rendered in Kevin's private folder and replaces the rows here. Keyed like
`demo.models.RecruiterContent`: `key` is a demo code's label for that code's own version, or
`current` for everyone else; `diagrams.catalogue.version_for` picks the visitor's key.

Not tenanted: a diagram describes a system, not a household's rows, and nothing here is a
household's (the same posture as `RecruiterContent`).
"""

from __future__ import annotations

from django.db import models

CURRENT_KEY = "current"


class RecruiterDiagram(models.Model):
    key = models.CharField(max_length=32, default=CURRENT_KEY)
    project = models.CharField(max_length=32)
    view = models.CharField(max_length=48)
    title = models.CharField(max_length=200)
    caption = models.TextField()
    # [{id, label}], read back from the stamped SVG by `diagrams.render.nodes_of`.
    nodes = models.JSONField(default=list)
    svg = models.TextField()
    source_sha256 = models.CharField(max_length=64)
    loaded_at = models.DateTimeField(auto_now=True)

    class Meta:
        constraints = [
            models.UniqueConstraint(
                fields=["key", "project", "view"], name="recruiter_diagram_unique"
            )
        ]
        ordering = ["key", "project", "id"]

    def __str__(self) -> str:
        return f"Diagram {self.project}/{self.view} ({self.key}, loaded {self.loaded_at:%Y-%m-%d})"

    def as_view(self) -> dict:
        return {
            "project": self.project,
            "view": self.view,
            "title": self.title,
            "caption": self.caption,
            "nodes": list(self.nodes or []),
            "svg": self.svg,
        }
