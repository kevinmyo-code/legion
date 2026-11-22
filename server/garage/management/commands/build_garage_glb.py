"""`manage.py build_garage_glb [--check]`: write the schematic front end as a GLB for the web.

Written to `server/frontend/public/garage/<model>.glb`, served as a static file at
`garage.xj_front.MODEL_URL`. It is the owner's own schematic (rods and boxes), no figures, so it
may be public. `--check` exits non-zero when the committed file differs from a fresh build.
"""

from __future__ import annotations

from pathlib import Path

from django.core.management.base import BaseCommand, CommandError

from garage import glb
from garage import xj_front as m

OUT = Path(__file__).resolve().parents[3] / "frontend" / "public" / "garage" / f"{m.MODEL}.glb"


class Command(BaseCommand):
    help = "Build the garage's schematic GLB from garage/xj_front.py."

    def add_arguments(self, parser):
        parser.add_argument("--check", action="store_true")

    def handle(self, *args, check: bool, **options):
        data = glb.build()
        if check:
            if not OUT.is_file() or OUT.read_bytes() != data:
                raise CommandError(f"{OUT} is stale. Run: manage.py build_garage_glb")
            self.stdout.write(f"{OUT.name} is current ({len(data)} bytes).")
            return
        OUT.parent.mkdir(parents=True, exist_ok=True)
        OUT.write_bytes(data)
        self.stdout.write(f"Wrote {OUT} ({len(data)} bytes).")
