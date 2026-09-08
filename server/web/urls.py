"""`web` has exactly one route and it is mounted as `legion/urls.py`'s
catch-all, not under a prefix of its own. The pattern lives there rather than
here because its correctness depends on being LAST in the root table, and a
pattern whose meaning comes from its position should be readable at that
position. This module exports the view for that file and for tests.
"""
from __future__ import annotations

from web.views import spa_index

__all__ = ["spa_index"]
