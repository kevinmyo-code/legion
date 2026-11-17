"""`manage.py agent_eval`: run an eval suite against a LOCAL database (plan section 16.5).

    manage.py agent_eval --suite voice21 --replay              # the cassette, no network, seconds
    manage.py agent_eval --suite voice21 --runs 3 --key-file F # live: real Gemini, F holds the key
    manage.py agent_eval --suite voice21 --runs 3 --key-file F --record   # and rewrite the cassette

Refuses any database that is not on this machine: it creates a throwaway household and member,
seeds the invented fixture, runs writes, and rolls ALL of it back at the end, so nothing it does
outlives the command, and it never runs against the engine's own database (the image carries this
command). The key is read from the file and never printed. The usual way to run the live suite is
the laptop ship script, through pytest on a throwaway Postgres
(`tests/test_agents_eval_live.py`), which builds the legacy tables this command expects to exist.

Exit status 1 when the suite misses its bar or a critical case failed.
"""

from __future__ import annotations

import sys
from pathlib import Path

from django.core.management.base import BaseCommand, CommandError
from django.db import transaction

from agents.evals import fixture, runner
from agents.runtime.model import GeminiClient


class Command(BaseCommand):
    help = "Run an agent eval suite on a local database (live or from its cassette)."

    def add_arguments(self, parser):
        parser.add_argument("--suite", default="voice21")
        parser.add_argument("--runs", type=int, default=None)
        parser.add_argument("--only", default="", help="comma-separated case ids")
        parser.add_argument("--replay", action="store_true")
        parser.add_argument("--key-file", default="")
        parser.add_argument("--record", action="store_true")
        parser.add_argument("--max-cost-usd", type=float, default=2.50)

    def handle(self, *args, **opts):
        try:
            fixture.assert_local_database()
        except fixture.NotLocal as exc:
            raise CommandError(str(exc)) from None
        suite = runner.load_suite(opts["suite"])
        only = {s for s in opts["only"].split(",") if s} or None
        live = None
        cassette = None
        if opts["replay"]:
            cassette = runner.read_cassette(opts["suite"])
        else:
            path = Path(opts["key_file"]) if opts["key_file"] else None
            if path is None or not path.is_file():
                raise CommandError("--key-file must name a file holding the key (never printed)")
            key = path.read_text(encoding="utf-8").strip()
            live = GeminiClient(key=lambda: key)
        from household.models import Household, HouseholdMember, User

        with transaction.atomic():
            household = Household.objects.create(name="Eval household (rolled back)")
            user = User.objects.create_user(email="eval-member@example.invalid", password=None)
            user.first_name = "Sam"
            user.save(update_fields=["first_name"])
            HouseholdMember.objects.create(
                user=user, household=household, role=HouseholdMember.OWNER
            )
            result, recorded = runner.run_suite(
                suite,
                user,
                household,
                live_model=live,
                cassette=cassette,
                runs=opts["runs"],
                only=only,
                max_cost_micro_usd=int(opts["max_cost_usd"] * 1_000_000),
            )
            transaction.set_rollback(True)
        for line in result.lines():
            self.stdout.write(line)
        if recorded is not None and opts["record"]:
            recorded["graded"] = {c.id: [not r["reasons"] for r in c.runs] for c in result.cases}
            self.stdout.write(f"cassette: {runner.write_cassette(recorded)}")
        if not result.ok:
            sys.exit(1)
