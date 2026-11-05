"""`manage.py demo_code rotate|off` (recruiter-demo ticket 02).

`rotate` revokes the live invite code and prints a new one, once, on stdout.
Only its hash is stored, so the printed code cannot be fetched again: copy it
then. Visitors already inside keep their visit until it expires (two hours);
prune or revoke their tokens to end visits early.

`off` revokes the live code and mints none, which closes `/api/demo/enter`
("The demo is closed right now.") until the next `rotate`.

`--label linkedin --cap 2` does either to a named code with its own daily cap
in USD, on top of the global budget (`DemoCode.label`, `daily_cap_cents`): a
LinkedIn code drained by strangers never eats the resume code's day, and its
visitors are told the cap and to ask Kevin for a proper code.

`--tester` does either to Kevin's own tester code instead (`DemoCode.unmetered`:
its visitors are not counted against the daily budget). The shared code and the
tester code are separate: rotating or closing one leaves the other as it was.
"""

from __future__ import annotations

import math
from decimal import Decimal, InvalidOperation

from django.core.management.base import BaseCommand, CommandError

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
        parser.add_argument("--label", default="", help="Act on a named code, e.g. linkedin.")
        parser.add_argument(
            "--cap", default=None, help="With rotate --label: the code's own daily cap in USD."
        )

    def handle(self, *args, **options):
        tester = options["tester"]
        label = options["label"].strip().lower()
        if label and tester:
            raise CommandError("A named code is never a tester code: pass --label or --tester.")
        if options["cap"] is not None and not label:
            raise CommandError("--cap is for a named code: pass --label too.")
        if label:
            return self._labelled(options["action"], label, options["cap"])
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

    def _labelled(self, action, label, cap):
        if action == "off":
            closed = DemoCode.close_all(label=label)
            self.stdout.write(f"The {label} code is off: {closed} live code(s) revoked.")
            return
        cap_cents = None
        if cap is not None:
            try:
                value = Decimal(str(cap))
            except InvalidOperation:
                raise CommandError(
                    f"--cap must be an amount in USD, like 2; got {cap!r}."
                ) from None
            if not value.is_finite() or value <= 0:
                raise CommandError(f"--cap must be more than zero; got {cap!r}.")
            cap_cents = math.ceil(value * 100)
        _row, code = DemoCode.rotate(label=label, daily_cap_cents=cap_cents)
        capped = f"daily cap ${cap_cents / 100:.2f}" if cap_cents else "no cap of its own"
        self.stdout.write(
            f"New {label} code, {capped} (shown once; the old {label} code no longer works; "
            f"the other codes are unchanged):"
        )
        self.stdout.write(code)
