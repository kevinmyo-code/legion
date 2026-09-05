#!/usr/bin/env python
"""Django's command-line entry point. Nothing LEGION-specific lives here;
the failure modes worth knowing about are in `legion/settings.py`, which is
where a missing `SECRET_KEY` or `DATABASE_URL` refuses to start."""
import os
import sys


def main() -> None:
    os.environ.setdefault("DJANGO_SETTINGS_MODULE", "legion.settings")
    try:
        from django.core.management import execute_from_command_line
    except ImportError as exc:
        raise ImportError(
            "Couldn't import Django. Is it installed and available on your "
            "PYTHONPATH environment variable? Did you forget to activate a "
            "virtual environment?"
        ) from exc
    execute_from_command_line(sys.argv)


if __name__ == "__main__":
    main()
