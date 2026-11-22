"""Named suites. A suite is one update's checklist: its versionCode and its checks."""
from __future__ import annotations

from . import next2

SUITES = {s.name: s for s in (next2.SUITE,)}
