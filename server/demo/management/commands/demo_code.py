"""`manage.py demo_code rotate|off` (recruiter-demo ticket 02).

`rotate` revokes the live invite code and prints a new one, once, on stdout.
Only its hash is stored, so the printed code cannot be fetched again: copy it
then. Visitors already inside keep their visit until it expires (two hours);
prune or revoke their tokens to end visits early.

`off` revokes the live code and mints none, which closes `/api/demo/enter`
("The demo is closed right now.") until the next `rotate`.

`--tester` does either to Kevin's own tester code instead (`DemoCode.unmetered`:
its visitors are not counted against the daily budget). The shared code and the
tester code are separate: rotating or closing one leaves the other as it was.
"""

from __future__ import annotations

from django.core.management.base import BaseCommand

from demo.models import DemoCode


class Command(BaseCommand):
    help = "Rotate the recruiter demo's shared invite code, or turn the demo door off."

    def add_arguments(self, parser):
        parser.add_argument("action", choices=["rotate", "off"])
        parser.add_argument(
            "--tester",
            action="store_true",
            help="Act on the tester code (not counted against the daily budget), "
            "not the shared one.",
        )

    def handle(self, *args, **options):
        tester = options["tester"]
        if options["action"] == "off":
            closed = DemoCode.close_all(unmetered=tester)
            if tester:
                self.stdout.write(f"The tester code is off: {closed} live tester code(s) revoked.")
                return
            self.stdout.write(
                f"The demo door is closed: {closed} live code(s) revoked, none issued. "
                f"Run 'manage.py demo_code rotate' to open it again."
            )
            return
        _row, code = DemoCode.rotate(unmetered=tester)
        if tester:
            self.stdout.write(
                "New TESTER code, not counted against the daily budget (shown once; the "
                "old tester code no longer works; the shared code is unchanged). Never put "
                "it on a resume:"
            )
        else:
            self.stdout.write(
                "New demo invite code (shown once, only its hash is stored; the old code no "
                "longer works):"
            )
        self.stdout.write(code)
