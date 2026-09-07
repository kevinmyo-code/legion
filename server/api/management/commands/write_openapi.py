"""`manage.py write_openapi` - regenerate `server/openapi.yaml`.

**One command, and it is the one `tests/test_openapi_schema.py` names when
it fails.** Wrapping `spectacular` rather than asking people to remember its
flags buys three things, all of which had already gone wrong once by hand:

1. **The path is not a guess.** `spectacular --file server/openapi.yaml`
   writes relative to the CURRENT DIRECTORY, so the same command produces
   `server/openapi.yaml` from the repo root and `server/server/openapi.yaml`
   from inside `server/`. This resolves `settings.BASE_DIR / "openapi.yaml"`
   and writes there wherever it is run from.
2. **`--fail-on-warn` is not optional.** django-engine ticket 04 makes this
   file the contract ticket 09 and a second Android app generate clients
   from, and every drf-spectacular warning is that generator saying it
   could not work out what an endpoint does. A schema that lies is worse
   than no schema, because a generated client believes it. So a warning
   fails the command rather than scrolling past.
3. **`--validate` is not optional either**, for the same reason: the file is
   input to a code generator, and an invalid document fails there instead,
   in someone else's build, with a worse message.

There is deliberately no flag to turn either off. If a new view cannot be
described, the answer is to describe it (`@extend_schema`, or the
`SyncedAutoSchema` in `api/schema.py`), never to lower the bar - the same
posture `tools/docs_check.py` and `tools/voice_guide.py --check` already
take elsewhere in this repo.
"""
from __future__ import annotations

from pathlib import Path

from django.conf import settings
from django.core.management import call_command
from django.core.management.base import BaseCommand

SCHEMA_FILENAME = "openapi.yaml"


def schema_path() -> Path:
    """`server/openapi.yaml`, absolute, independent of the caller's cwd.
    Read by the staleness test too, so the test and the command can never
    disagree about which file is the contract."""
    return Path(settings.BASE_DIR) / SCHEMA_FILENAME


class Command(BaseCommand):
    help = (
        "Regenerates server/openapi.yaml from the live URL conf. Fails on any "
        "drf-spectacular warning and on an invalid document."
    )

    def add_arguments(self, parser):
        parser.add_argument(
            "--file",
            dest="file",
            default=None,
            help=(
                "Write somewhere else instead. Used by the staleness test to render into a "
                "temporary file and compare; there is no reason to pass it by hand."
            ),
        )

    def handle(self, *args, **options):
        target = Path(options["file"]) if options["file"] else schema_path()
        call_command(
            "spectacular",
            file=str(target),
            fail_on_warn=True,
            validate=True,
        )
        self.stdout.write(self.style.SUCCESS(f"Wrote {target}"))
