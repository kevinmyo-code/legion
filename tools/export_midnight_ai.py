#!/usr/bin/env python3
"""Convert a Midnight AI v12 database into a LEGION import bundle.

Why this exists
---------------
Kevin's fleet history lived in Midnight AI: three vehicles, ~11.5k OBD samples,
drive logs, maintenance, saved places. LEGION's own DB knows one car, which is
why the Fleet tab looks nearly empty.

Drive is NOT a route in. `appDataFolder` is scoped per application, so nothing
outside the app can write into LEGION's folder - that needs OAuth AS LEGION,
which is package + SHA-1-cert bound (the clone-and-run blocker). So the data
has to enter through the app itself, and the app's existing SyncEngine then
pushes it up to Drive, where every other device pulls it.

Output format is therefore sync's OWN format - one gzipped-NDJSON file per
table, exactly what `sync/SyncCodec.kt` reads - rather than a bespoke one, so
the in-app importer decodes it with code that already exists and is tested.

PRIVACY. The bundle contains real personal data: `places` carries the latitude
and longitude of saved locations, `daily_drive_logs` carry written narratives.
It is written under app/src/main/assets/, which means it ends up INSIDE THE
APK. That directory must stay gitignored (this repo is public), and an APK
built with it must not be shared.

Usage:
    python tools/export_midnight_ai.py <midnight_ai_database> [--keep-stray]
"""

from __future__ import annotations

import argparse
import gzip
import io
import json
import sqlite3
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
SCHEMA = REPO / "app/schemas/com.kevin.legion.data.local.CarDatabase/5.json"
OUT_DIR = REPO / "app/src/main/assets/midnight_import"

# Retired in the 2026-07-31 pivot - the music-taste ledger and mixtapes went
# with it, so these rows have nowhere to land and are deliberately not carried.
RETIRED = {"music_plays", "mixtape", "mixtape_track", "library_track"}


def legion_columns() -> dict[str, list[str]]:
    """Target column sets, read from the committed Room schema rather than guessed."""
    schema = json.loads(SCHEMA.read_text(encoding="utf-8"))
    return {
        e["tableName"]: [f["columnName"] for f in e["fields"]]
        for e in schema["database"]["entities"]
    }


def is_stray(row: dict) -> bool:
    """A vehicle with no make, no model and no year is an adapter that got read once."""
    return not (row.get("make") or "").strip() and not (row.get("model") or "").strip() and not row.get("year")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("db")
    ap.add_argument(
        "--keep-stray",
        action="store_true",
        help="keep vehicles with blank make/model/year (and their samples) instead of skipping them",
    )
    args = ap.parse_args()

    targets = legion_columns()
    con = sqlite3.connect(args.db)
    con.row_factory = sqlite3.Row

    old_tables = {
        r[0]
        for r in con.execute(
            "SELECT name FROM sqlite_master WHERE type='table' "
            "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%' AND name != 'android_metadata'"
        )
    }

    # Which vehicles are we dropping, if any? Needed before the row loop so
    # their telemetry goes with them rather than dangling on a missing parent.
    skipped_vehicles: set[str] = set()
    if not args.keep_stray and "vehicles" in old_tables:
        for r in con.execute("SELECT * FROM vehicles"):
            row = dict(r)
            if is_stray(row):
                skipped_vehicles.add(row["obdMac"])

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    for existing in OUT_DIR.glob("*.json.gz"):
        existing.unlink()

    manifest: dict[str, int] = {}
    skipped_report: dict[str, int] = {}

    for table in sorted(old_tables):
        if table in RETIRED:
            skipped_report[table] = con.execute(f'SELECT COUNT(*) FROM "{table}"').fetchone()[0]
            continue
        if table not in targets:
            skipped_report[table] = con.execute(f'SELECT COUNT(*) FROM "{table}"').fetchone()[0]
            continue

        keep = targets[table]  # LEGION's columns; anything the old row has extra is dropped here
        rows = []
        dropped = 0
        for r in con.execute(f'SELECT * FROM "{table}"'):
            row = dict(r)
            owner = row.get("obdMac") if table == "vehicles" else row.get("vehicleId")
            if owner is not None and owner in skipped_vehicles:
                dropped += 1
                continue
            rows.append({k: row[k] for k in keep if k in row})

        if dropped:
            skipped_report[f"{table} (stray vehicle)"] = dropped
        if not rows:
            continue

        buf = io.BytesIO()
        # mtime=0 so a re-run with identical data produces an identical file -
        # otherwise every export churns the asset and the APK diff is noise.
        with gzip.GzipFile(fileobj=buf, mode="wb", mtime=0) as gz:
            for row in rows:
                gz.write((json.dumps(row, separators=(",", ":"), ensure_ascii=False) + "\n").encode("utf-8"))
        (OUT_DIR / f"{table}.json.gz").write_bytes(buf.getvalue())
        manifest[table] = len(rows)

    (OUT_DIR / "manifest.json").write_text(
        json.dumps({"tables": manifest, "source": "midnight_ai v12"}, indent=2), encoding="utf-8"
    )

    total = sum(manifest.values())
    print(f"wrote {len(manifest)} tables, {total} rows -> {OUT_DIR}")
    for t, n in sorted(manifest.items(), key=lambda kv: -kv[1]):
        print(f"  {n:>6}  {t}")
    if skipped_report:
        print("\nnot carried:")
        for t, n in sorted(skipped_report.items(), key=lambda kv: -kv[1]):
            print(f"  {n:>6}  {t}")
    size = sum(f.stat().st_size for f in OUT_DIR.glob("*"))
    print(f"\nbundle size: {size / 1024:.0f} KB (goes inside the APK - keep it gitignored)")


if __name__ == "__main__":
    sys.exit(main())
