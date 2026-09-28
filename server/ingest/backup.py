"""`backup_nightly` and `restore_backup`: the database, dumped to the operator's
Google Drive, and a restore that is drilled rather than described (backend-etl
ticket 03; carries django-engine 06 job 1, adjusted for Cloud Run).

**One dump per database, one run row per household.** A backup is of the whole
engine, and `ingest_runs` rows belong to households, so:

- the ONE real run is taken under the **operator household**
  (`household.tenancy.resolve_default_household`: the one
  `LEGION_BOOTSTRAP_HOUSEHOLD_ID` names, else the only one), with that run's
  advisory lock, so two schedulers never dump twice;
- every OTHER household gets a mirrored row with the same outcome, so its
  `/api/freshness` answers the question a member actually has ("is the
  database my rows live in backed up?") on the same 36h threshold. A mirrored
  row never carries the operator's Drive file ids or error text; a failure
  or a dead Drive login reads as a plain "did not complete" there, because
  only the operator can fix it and a "run the login script" line would send
  someone else's parent to a laptop for nothing.

**Only the operator household's Drive may hold it, and only when switched on.**
The dump contains EVERY household's rows (ADR 0045). Letting any household's
Drive login receive it would copy one family's data into another's Drive, so
the credential is the operator household's `drive` session with
`config.backup == true` (`tools/connect_session.py drive --backup`), and
nothing else. Without that, every household records `skipped` ("not set up").

**Only what this job made is ever trashed.** Every folder and file it creates
carries `appProperties` (private to this OAuth client), the Drive queries
filter on them, and the code checks them again before trashing, so a folder a
person made inside `LEGION backups/` survives retention. With the `drive.file`
scope Drive would refuse to touch anything else anyway; this does not rely on
that.

**Media is not backed up** (ADR 0041 gap: there is no durable media store
yet). Every `ok` run's `watermark` says so in words.
"""
from __future__ import annotations

import datetime
import os
import re
import shutil
import subprocess
import tempfile
from collections.abc import Callable, Iterable
from pathlib import Path
from urllib.parse import parse_qs, urlparse

from django.conf import settings
from django.db import connection
from django.utils import timezone

from ingest import vault
from ingest.drive import FOLDER_MIME, DriveClient, DriveRefused, app_property_clause
from ingest.jobs import record_not_configured, run_job
from ingest.models import IngestRun, Outcome, SessionSource, Source

ROOT_FOLDER_NAME = "LEGION backups"
KEEP_DAILY = 90
DUMP_MIME = "application/octet-stream"

# appProperties this job stamps on everything it creates. `kind` is one of
# root / day / dump; `date` is the day folder's ISO date.
PROP_KIND = "legion_backup"
PROP_DATE = "legion_backup_date"
_ISO_DATE = re.compile(r"^\d{4}-\d{2}-\d{2}$")

# What is dumped. LEGION's data is in `django` (Django's own tables) and
# `public` (the tenant and legacy tables), with `private` holding the SQL
# helper functions the legacy tables' policies call. Supabase's platform
# schemas (auth, storage, realtime, vault, ...) are not LEGION's, cannot be
# restored anywhere but Supabase, and retire with it (ADR 0044). Every run
# records, in words, which live schemas were left out, so a new LEGION
# schema cannot go missing silently.
DEFAULT_SCHEMAS = ("django", "public", "private")
SCHEMAS_ENV = "LEGION_BACKUP_SCHEMAS"
PG_BIN_ENV = "LEGION_PG_BIN_DIR"

MEDIA_NOT_BACKED_UP = (
    "Media (photos, recordings, uploads under MEDIA_ROOT) is NOT in this backup: "
    "LEGION has no durable media store yet (ADR 0041 gap)."
)
MIRRORED_OK = "The engine's database was backed up to the Drive of the household that runs it."
MIRRORED_FAILED = (
    "The engine's nightly backup did not complete. The household that runs this "
    "engine has the details."
)
LOGIN_COMMAND = "tools/connect_session.py drive --backup"


class BackupError(Exception):
    """The backup or restore cannot proceed. The message says why in words and
    carries no secret."""


# =============================================================================
# Who owns the backup
# =============================================================================


def backup_household():
    """The operator household, if its Drive login has backups switched on."""
    from household.tenancy import resolve_default_household

    household = resolve_default_household()
    if household is None:
        return None
    credential = vault.credential_for(household, SessionSource.DRIVE)
    if credential is None or credential.config.get("backup") is not True:
        return None
    return household


def not_set_up_reason() -> str:
    from household.tenancy import resolve_default_household

    household = resolve_default_household()
    if household is None:
        return (
            "backup: not set up. This engine holds more than one household and "
            "LEGION_BOOTSTRAP_HOUSEHOLD_ID names none of them, so no household owns the "
            "whole-database backup. Nothing was dumped."
        )
    return (
        f"backup: not set up. Household {household.pk} runs this engine but has no Drive "
        f"login with backups switched on. Run {LOGIN_COMMAND}. Nothing was dumped."
    )


# =============================================================================
# Postgres client tools
# =============================================================================


def schemas() -> tuple[str, ...]:
    raw = os.environ.get(SCHEMAS_ENV, "").strip()
    if not raw:
        return DEFAULT_SCHEMAS
    names = tuple(part.strip() for part in raw.split(",") if part.strip())
    for name in names:
        if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", name):
            raise BackupError(f"{SCHEMAS_ENV} names {name!r}, which is not a schema name.")
    return names


def pg_binary(name: str) -> str:
    directory = os.environ.get(PG_BIN_ENV, "").strip() or None
    found = shutil.which(name, path=directory) if directory else shutil.which(name)
    if not found:
        where = f"in {PG_BIN_ENV}={directory}" if directory else "on PATH"
        raise BackupError(
            f"{name} is not installed {where}. The image installs postgresql-client "
            f"(server/Dockerfile); on a laptop, point {PG_BIN_ENV} at PostgreSQL's bin "
            f"directory. Nothing was written."
        )
    return found


_VERSION = re.compile(r"(\d+)(?:\.(\d+))?")


def major_of(version: str) -> int:
    match = _VERSION.search(version)
    if not match:
        raise BackupError(f"Cannot read a Postgres version out of {version!r}.")
    return int(match.group(1))


def server_version() -> str:
    with connection.cursor() as cursor:
        cursor.execute("SHOW server_version")
        return str(cursor.fetchone()[0]).split()[0]


def client_version(binary: str, *, run=subprocess.run) -> str:
    completed = run([binary, "--version"], capture_output=True, text=True, check=False)
    # "pg_dump (PostgreSQL) 17.6" or "pg_dump (PostgreSQL) 17.6 (Debian 17.6-1)":
    # the version is the first number after "(PostgreSQL)".
    output = (completed.stdout or "").strip()
    match = re.search(r"(\d+(?:\.\d+)*)", output.split(")", 1)[-1])
    if completed.returncode != 0 or not match:
        raise BackupError(f"{Path(binary).name} --version did not report a version.")
    return match.group(1)


def check_versions(binary: str, *, run=subprocess.run, server: str | None = None) -> str:
    """Refuses unless the client tool's major version equals the server's.
    Returns the server version."""
    server = server or server_version()
    client = client_version(binary, run=run)
    if major_of(client) != major_of(server):
        raise BackupError(
            f"{Path(binary).name} is version {client} but the database server is "
            f"{server}. The major versions must match, so nothing was dumped or "
            f"uploaded. Install postgresql-client-{major_of(server)} (server/Dockerfile's "
            f"PG_MAJOR)."
        )
    return server


def libpq_env(dbname: str | None = None) -> dict[str, str]:
    """The environment a Postgres client tool connects with. The password goes
    in PGPASSWORD, never on the command line, where `ps` and an error message
    would both show it."""
    db = settings.DATABASES["default"]
    env = dict(os.environ)
    env.update(
        {
            "PGHOST": db["HOST"],
            "PGPORT": str(db["PORT"]),
            "PGUSER": db["USER"],
            "PGPASSWORD": db["PASSWORD"],
            "PGDATABASE": dbname or db["NAME"],
        }
    )
    sslmode = parse_qs(urlparse(getattr(settings, "DATABASE_URL", "")).query).get("sslmode")
    if sslmode:
        env["PGSSLMODE"] = sslmode[0]
    return env


def _tail(text: str, lines: int = 20) -> str:
    return "\n".join((text or "").strip().splitlines()[-lines:])


def pg_dump(path: Path, *, run=subprocess.run, binary: str | None = None) -> None:
    """`pg_dump -Fc` of the LEGION schemas into `path`."""
    binary = binary or pg_binary("pg_dump")
    args = [binary, "--format=custom", f"--file={path}"]
    args += [f"--schema={name}" for name in schemas()]
    completed = run(args, env=libpq_env(), capture_output=True, text=True, check=False)
    if completed.returncode != 0:
        raise BackupError(
            f"pg_dump exited {completed.returncode}; nothing was uploaded. "
            f"{_tail(completed.stderr)}"
        )
    if not path.exists() or path.stat().st_size == 0:
        raise BackupError("pg_dump reported success but wrote an empty file; nothing was uploaded.")


def live_schemas_left_out() -> list[str]:
    dumped = set(schemas())
    with connection.cursor() as cursor:
        cursor.execute(
            "select nspname from pg_namespace "
            "where nspname not like 'pg\\_%%' and nspname <> 'information_schema' "
            "order by nspname"
        )
        return [row[0] for row in cursor.fetchall() if row[0] not in dumped]


# =============================================================================
# Drive layout
# =============================================================================


def _folders(client: DriveClient, *clauses: str) -> list[dict]:
    query = " and ".join([f"mimeType='{FOLDER_MIME}'", "trashed=false", *clauses])
    return client.list_files(query, order_by="createdTime")


def find_root(client: DriveClient) -> dict | None:
    found = [
        f
        for f in _folders(client, app_property_clause(PROP_KIND, "root"))
        if (f.get("appProperties") or {}).get(PROP_KIND) == "root"
    ]
    return found[0] if found else None


def ensure_root(client: DriveClient) -> dict:
    return find_root(client) or client.create_folder(
        ROOT_FOLDER_NAME, parent=None, app_properties={PROP_KIND: "root"}
    )


def find_day(client: DriveClient, root_id: str, day: str) -> dict | None:
    found = [
        f
        for f in _folders(
            client,
            f"'{root_id}' in parents",
            app_property_clause(PROP_KIND, "day"),
            app_property_clause(PROP_DATE, day),
        )
        if _is_day_folder(f) and f["appProperties"][PROP_DATE] == day
    ]
    return found[0] if found else None


def ensure_day(client: DriveClient, root_id: str, day: str) -> dict:
    return find_day(client, root_id, day) or client.create_folder(
        day, parent=root_id, app_properties={PROP_KIND: "day", PROP_DATE: day}
    )


def _is_day_folder(entry: dict) -> bool:
    props = entry.get("appProperties") or {}
    return (
        entry.get("mimeType") == FOLDER_MIME
        and props.get(PROP_KIND) == "day"
        and bool(_ISO_DATE.match(props.get(PROP_DATE, "")))
    )


def dumps_in(client: DriveClient, folder_id: str) -> list[dict]:
    """This job's dump files in one day folder, oldest first."""
    query = " and ".join(
        [f"'{folder_id}' in parents", "trashed=false", app_property_clause(PROP_KIND, "dump")]
    )
    return [
        f
        for f in client.list_files(query, order_by="createdTime")
        if (f.get("appProperties") or {}).get(PROP_KIND) == "dump"
    ]


def apply_retention(client: DriveClient, root_id: str, keep: int = KEEP_DAILY) -> list[str]:
    """Trash every day folder this job made beyond the newest `keep`, by date.
    Returns the dates trashed. Anything without this job's appProperties is
    never touched, whatever Drive's query returned."""
    days = [f for f in _folders(client, f"'{root_id}' in parents") if _is_day_folder(f)]
    days.sort(key=lambda f: f["appProperties"][PROP_DATE], reverse=True)
    trashed = []
    for folder in days[keep:]:
        client.trash(folder["id"])
        trashed.append(folder["appProperties"][PROP_DATE])
    return sorted(trashed)


# =============================================================================
# The job
# =============================================================================


def dump_name(day: str) -> str:
    return f"legion-{day}.dump"


def run_backup(
    run: IngestRun,
    *,
    today: datetime.date | None = None,
    client_factory: Callable[[dict], DriveClient] = DriveClient,
    dumper: Callable[[Path], None] = pg_dump,
    version_check: Callable[[], str] | None = None,
    left_out: Callable[[], list[str]] = live_schemas_left_out,
) -> None:
    """One backup, for `run.household` (the operator household). Raises to
    fail; raises `NeedsLogin` when Google refuses the Drive login."""
    credential, secret = vault.session_for(run.household, SessionSource.DRIVE)
    server = (version_check or (lambda: check_versions(pg_binary("pg_dump"))))()
    day = (today or timezone.now().date()).isoformat()

    with tempfile.TemporaryDirectory(prefix="legion-backup-") as tmp:
        path = Path(tmp) / dump_name(day)
        dumper(path)
        size = path.stat().st_size
        client = client_factory(secret)
        try:
            root = ensure_root(client)
            folder = ensure_day(client, root["id"], day)
            uploaded = client.upload_resumable(
                path,
                name=dump_name(day),
                parent=folder["id"],
                mime_type=DUMP_MIME,
                app_properties={PROP_KIND: "dump", PROP_DATE: day},
            )
            # A second run on one day replaces the first, after the new one
            # is safely up.
            for earlier in dumps_in(client, folder["id"]):
                if earlier["id"] != uploaded["id"]:
                    client.trash(earlier["id"])
            trashed = apply_retention(client, root["id"])
        except DriveRefused as exc:
            raise vault.refuse_session(credential, str(exc)) from exc

    omitted = left_out()
    run.rows_written = 1
    run.watermark = " ".join(
        part
        for part in (
            f"Dumped schemas {', '.join(schemas())} (Postgres {server}, {size} bytes) to "
            f"'{ROOT_FOLDER_NAME}/{day}/{dump_name(day)}' on Drive, file {uploaded['id']}.",
            f"Not dumped: {', '.join(omitted)}." if omitted else "",
            f"Retention trashed {len(trashed)} day folder(s): {', '.join(trashed)}."
            if trashed
            else "",
            MEDIA_NOT_BACKED_UP,
        )
        if part
    )
    return None


def _mirror(primary: IngestRun, household) -> IngestRun:
    outcome = primary.outcome
    error = None
    watermark = None
    if outcome == Outcome.OK:
        watermark = f"{MIRRORED_OK} {MEDIA_NOT_BACKED_UP}"
    elif outcome in (Outcome.FAILED, Outcome.NEEDS_LOGIN):
        outcome, error = Outcome.FAILED, MIRRORED_FAILED
    return IngestRun.objects.create(
        household=household,
        source=Source.BACKUP,
        started_at=primary.started_at,
        finished_at=primary.finished_at,
        outcome=outcome,
        error=error,
        watermark=watermark,
        rows_written=primary.rows_written if outcome == Outcome.OK else 0,
    )


def run_nightly(
    job: Callable[[IngestRun], str | None], *, write: Callable[[str], object] = print
) -> list[IngestRun]:
    """The whole command: one real run under the operator household, one
    mirrored row for every other household, or `skipped` for all of them."""
    from household.models import Household

    households = list(Household.objects.order_by("created_at", "id"))
    if not households:
        write("backup: this engine has no household yet, so there was nothing to back up.")
        return []
    operator = backup_household()
    if operator is None:
        write(not_set_up_reason())
        return [record_not_configured(Source.BACKUP, h) for h in households]

    primary = run_job(Source.BACKUP, operator, job)
    line = f"backup for household {operator.pk}: {primary.outcome}"
    if primary.error:
        line += f" ({primary.error})"
    write(line)
    if primary.watermark:
        write(primary.watermark)
    runs = [primary]
    for household in households:
        if household.pk == operator.pk:
            continue
        mirrored = _mirror(primary, household)
        write(f"backup for household {household.pk}: {mirrored.outcome} (mirrored)")
        runs.append(mirrored)
    return runs


# =============================================================================
# Restore drill
# =============================================================================


def throwaway_name(day: str) -> str:
    return f"legion_restore_{day.replace('-', '')}_{os.urandom(3).hex()}"


def refuse_live_target(target: str) -> None:
    live = settings.DATABASES["default"]["NAME"]
    if target.strip().lower() == str(live).strip().lower():
        raise BackupError(
            f"Refusing to restore into {target!r}: that is the live database. A restore "
            f"drill only ever targets a throwaway database it creates and drops itself. "
            f"Nothing was touched."
        )
    if not re.fullmatch(r"[a-z_][a-z0-9_]{0,62}", target):
        raise BackupError(
            f"{target!r} is not a plain lower-case database name; nothing was touched."
        )


def _admin_connect(dbname: str | None = None):
    import psycopg

    db = settings.DATABASES["default"]
    env = libpq_env(dbname)
    return psycopg.connect(
        host=db["HOST"],
        port=db["PORT"],
        user=db["USER"],
        password=db["PASSWORD"],
        dbname=dbname or db["NAME"],
        sslmode=env.get("PGSSLMODE", "prefer"),
        autocommit=True,
    )


def create_database(name: str) -> None:
    from psycopg import sql

    with _admin_connect() as conn:
        conn.execute(sql.SQL("CREATE DATABASE {}").format(sql.Identifier(name)))


def drop_database(name: str) -> None:
    refuse_live_target(name)
    from psycopg import sql

    with _admin_connect() as conn:
        conn.execute(
            sql.SQL("DROP DATABASE IF EXISTS {} WITH (FORCE)").format(sql.Identifier(name))
        )


def prepare_extensions(name: str, *, write: Callable[[str], object] = print) -> None:
    """Create, in the throwaway database, the extensions live has, in the
    schemas live has them in, so defaults like `extensions.uuid_generate_v4()`
    resolve. Best effort: an extension the server will not create is said,
    and the counts decide the verdict."""
    from psycopg import sql

    with connection.cursor() as cursor:
        cursor.execute(
            "select e.extname, n.nspname from pg_extension e "
            "join pg_namespace n on n.oid = e.extnamespace where e.extname <> 'plpgsql'"
        )
        wanted = cursor.fetchall()
    with _admin_connect(name) as conn:
        for extname, schema in wanted:
            try:
                conn.execute(
                    sql.SQL("CREATE SCHEMA IF NOT EXISTS {}").format(sql.Identifier(schema))
                )
                conn.execute(
                    sql.SQL("CREATE EXTENSION IF NOT EXISTS {} WITH SCHEMA {}").format(
                        sql.Identifier(extname), sql.Identifier(schema)
                    )
                )
            except Exception as exc:  # noqa: BLE001 - reported, then the counts decide
                write(f"note: extension {extname} could not be created ({type(exc).__name__}).")


def pg_restore(path: Path, target: str, *, run=subprocess.run, binary: str | None = None) -> str:
    """`pg_restore` into `target`. Returns its stderr; a non-zero exit is
    reported by the caller, not raised, because pg_restore exits 1 for any
    ignored error (a policy naming a Supabase-only role) and the row counts
    are the verdict."""
    refuse_live_target(target)
    binary = binary or pg_binary("pg_restore")
    completed = run(
        [binary, "--no-owner", "--no-privileges", f"--dbname={target}", str(path)],
        env=libpq_env(target),
        capture_output=True,
        text=True,
        check=False,
    )
    return completed.stderr or ""


_COUNT_TABLES = (
    "select n.nspname, c.relname from pg_class c "
    "join pg_namespace n on n.oid = c.relnamespace "
    "where c.relkind in ('r', 'p') and n.nspname = any(%s) order by 1, 2"
)


def _counts(cursor, schema_names: Iterable[str]) -> dict[str, int]:
    cursor.execute(_COUNT_TABLES, [list(schema_names)])
    tables = cursor.fetchall()
    counts = {}
    for schema, table in tables:
        cursor.execute(f'select count(*) from "{schema}"."{table}"')
        counts[f"{schema}.{table}"] = int(cursor.fetchone()[0])
    return counts


def live_counts(schema_names: Iterable[str]) -> dict[str, int]:
    with connection.cursor() as cursor:
        return _counts(cursor, schema_names)


def restored_counts(target: str, schema_names: Iterable[str]) -> dict[str, int]:
    refuse_live_target(target)
    with _admin_connect(target) as conn, conn.cursor() as cursor:
        return _counts(cursor, schema_names)


def compare(restored: dict[str, int], live: dict[str, int]) -> tuple[list[str], list[str]]:
    """(report lines, mismatched tables). A table that differs is reported
    with which side has more, never hidden: rows written after the dump are a
    legitimate difference, and still a difference."""
    lines, mismatched = [], []
    for table in sorted(set(restored) | set(live)):
        r, lv = restored.get(table), live.get(table)
        if r is None:
            lines.append(f"{table}: MISSING from the restore (live has {lv})")
            mismatched.append(table)
        elif lv is None:
            lines.append(f"{table}: restored {r}, not in live any more")
            mismatched.append(table)
        elif r == lv:
            lines.append(f"{table}: {r} = live")
        else:
            delta = lv - r
            why = (
                f"live has {delta} more, possibly written since the dump"
                if delta > 0
                else f"live has {-delta} fewer, deleted since the dump or lost"
            )
            lines.append(f"{table}: restored {r}, live {lv} ({why})")
            mismatched.append(table)
    return lines, mismatched


def find_dump(client: DriveClient, day: str) -> dict:
    root = find_root(client)
    if root is None:
        raise BackupError(f"No '{ROOT_FOLDER_NAME}' folder made by this job exists on Drive.")
    folder = find_day(client, root["id"], day)
    if folder is None:
        raise BackupError(f"No backup folder for {day} exists in '{ROOT_FOLDER_NAME}'.")
    dumps = dumps_in(client, folder["id"])
    if not dumps:
        raise BackupError(f"The {day} backup folder holds no dump.")
    return dumps[-1]
