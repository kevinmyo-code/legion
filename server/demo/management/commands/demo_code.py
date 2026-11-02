"""`manage.py demo_code rotate|off` (recruiter-demo ticket 02).

`rotate` revokes the live invite code and prints a new one, once, on stdout.
Only its hash is stored, so the printed code cannot be fetched again: copy it
then. Visitors already inside keep their visit until it expires (two hours);
prune or revoke their tokens to end visits early.

`off` revokes the live code and mints none, which closes `/api/demo/enter`
("The demo is closed right now.") until the next `rotate`.
"""

from __future__ import annotations

from django.core.management.base import BaseCommand

from demo.models import DemoCode


class Command(BaseCommand):
    help = "Rotate the recruiter demo's shared invite code, or turn the demo door off."

    def add_arguments(self, parser):
        parser.add_argument("action", choices=["rotate", "off"])

    def handle(self, *args, **options):
        if options["action"] == "off":
            closed = DemoCode.close_all()
            self.stdout.write(
                f"The demo door is closed: {closed} live code(s) revoked, none issued. "
                f"Run 'manage.py demo_code rotate' to open it again."
            )
            return
        _row, code = DemoCode.rotate()
        self.stdout.write(
            "New demo invite code (shown once, only its hash is stored; the old code no "
            "longer works):"
        )
        self.stdout.write(code)
