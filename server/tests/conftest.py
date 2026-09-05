"""pytest-django wiring, plus the one guardrail ticket 01 asks for by name:
this suite must refuse to run against anything but Postgres.

Section 4's gate depends on exact-equality arithmetic and the
`forbid_mutation_of_facts` trigger that ships as a Django migration; SQLite
has neither the trigger nor Postgres's numeric semantics, so a green run
against SQLite would be testing a database this app never ships against.
"""
from __future__ import annotations

import pytest
from django.conf import settings


@pytest.fixture(autouse=True, scope="session")
def _require_postgres():
    engine = settings.DATABASES["default"]["ENGINE"]
    if "postgresql" not in engine:
        pytest.exit(
            f"This suite requires Postgres; DATABASES['default']['ENGINE'] is "
            f"{engine!r}. There is no SQLite fallback (CLAUDE.md section 4/5, "
            f"ticket 01's own instruction not to substitute one)."
        )
    yield
