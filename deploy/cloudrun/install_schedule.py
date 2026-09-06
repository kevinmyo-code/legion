"""Wire deploy/crontab's lines to Cloud Scheduler, each firing an
execution of the Cloud Run Job deploy_job.py deploys.

deploy/crontab is the ONE place a cron line and its management command are
written down - ticket 06 fills it in (job 1, shipped first: `backup_nightly`;
later, `canvas_poll`, `webassign_read`). This script is the only other
reader of that file: the compose `worker` container's supercronic reads it
too (mounted in, ticket 06), but Cloud Scheduler cannot read a crontab
file directly, so this script parses it once and creates one Scheduler job
per line.

Each Scheduler entry calls the Cloud Run Jobs v2 "run" API with a JSON body
that OVERRIDES the Job's args to the specific `manage.py <task>` for that
line - one Cloud Run Job (from deploy_job.py) serves every scheduled task,
rather than needing a separate Job per task the way midconerpdash's single
pipeline does not need to.

deploy/crontab does not exist yet as of this ticket (ticket 06, blocked on
02, has not landed) - this refuses loudly with that explanation rather than
scheduling nothing and saying "done".

Usage:
    python install_schedule.py --job legion-worker
    python install_schedule.py --dry-run --job legion-worker
    python install_schedule.py --status
    python install_schedule.py --remove
"""

from __future__ import annotations

import argparse
import re
from pathlib import Path

from _common import (
    HERE,
    capture,
    die,
    find_gcloud,
    read_cloud_env,
    require_project,
    run,
)
from deploy_job import DEFAULT_JOB

from deploy import DEFAULT_REGION

CRONTAB = HERE.parent / "crontab"

# Five cron fields, then the command. `python manage.py backup_nightly` and
# bare `manage.py backup_nightly` are both accepted since ticket 06's own
# worked example writes the former.
CRON_LINE = re.compile(
    r"^(?P<schedule>(?:\S+\s+){4}\S+)\s+"
    r"(?:python\s+)?manage\.py\s+(?P<task>\S+)\s*$"
)


def parse_crontab(path: Path) -> list[tuple[str, str]]:
    """[(cron schedule, management command name), ...], comments and blank
    lines skipped. A line that is not a comment and does not match the
    expected shape is a hard failure, not a silent skip - CLAUDE.md section
    4 rule 6's posture ("a line the parser does not recognize is a hard
    failure, never a skip") applies here too: a mistyped crontab line
    should fail loudly, not quietly schedule nothing for it."""
    entries: list[tuple[str, str]] = []
    for lineno, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        match = CRON_LINE.match(line)
        if not match:
            die(
                f"{path}:{lineno}: does not look like '<5-field cron> [python] "
                f"manage.py <task>':\n    {raw}"
            )
        entries.append((match.group("schedule"), match.group("task")))
    return entries


def scheduler_job_name(prefix: str, task: str) -> str:
    return f"{prefix}-{task}".replace("_", "-")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", default=None)
    parser.add_argument("--job", default=DEFAULT_JOB, help="the Cloud Run Job deploy_job.py made")
    parser.add_argument("--region", default=None)
    parser.add_argument(
        "--schedule-prefix", default=None,
        help="Scheduler job name prefix; defaults to --job",
    )
    parser.add_argument(
        "--dry-run", action="store_true",
        help="print every gcloud command this would run and run none of them",
    )
    parser.add_argument("--remove", action="store_true", help="delete every scheduled entry")
    parser.add_argument("--status", action="store_true", help="list what is currently scheduled")
    args = parser.parse_args()

    project = require_project(args.project)
    cloud_env = read_cloud_env()
    region = args.region or cloud_env.get("CLOUD_RUN_REGION") or DEFAULT_REGION
    prefix = args.schedule_prefix or args.job

    if not CRONTAB.exists():
        die(
            f"{CRONTAB} does not exist yet. Ticket 06 (the worker: backup_nightly, "
            f"canvas_poll, webassign_read) fills it in - there is nothing to "
            f"schedule until it does. Nothing was created."
        )

    entries = parse_crontab(CRONTAB)
    if not entries:
        die(f"{CRONTAB} exists but has no cron lines in it. Nothing to schedule.")

    gcloud = find_gcloud() if not args.dry_run else "gcloud"

    print(f"project  {project}")
    print(f"region   {region}")
    print(f"job      {args.job}")
    print(f"crontab  {CRONTAB} ({len(entries)} line(s))\n")
    if args.dry_run:
        print("--dry-run: printing every command, running none of them.\n")

    if args.status:
        for _, task in entries:
            name = scheduler_job_name(prefix, task)
            run([gcloud, "scheduler", "jobs", "describe", name, "--location", region], args.dry_run)
        return 0

    if args.remove:
        for _, task in entries:
            name = scheduler_job_name(prefix, task)
            run([gcloud, "scheduler", "jobs", "delete", name, "--location", region, "--quiet"], args.dry_run)
        return 0

    print("Enabling the APIs this needs (no-op if already on) ...")
    run([gcloud, "services", "enable", "cloudscheduler.googleapis.com", "run.googleapis.com"], args.dry_run)

    # The Job's own service account needs permission to be started by
    # Scheduler - granted once, idempotent to re-run. The project number
    # (distinct from the project ID) is what the default compute service
    # account's email is built from, so it has to be resolved for real even
    # under --dry-run - a placeholder here would make the printed command
    # uncopyable, defeating the point of showing it.
    if args.dry_run:
        account = "PROJECT_NUMBER-compute@developer.gserviceaccount.com"
        print(
            f"[plan] would resolve the real project number via "
            f"`gcloud projects describe {project} --format value(projectNumber)` "
            f"to build this account email; showing a placeholder since "
            f"--dry-run makes no real gcloud calls:"
        )
    else:
        described = capture(
            [gcloud, "projects", "describe", project, "--format", "value(projectNumber)"]
        )
        if described.returncode != 0 or not described.stdout.strip():
            die(f"Could not resolve the project number for {project!r}: {described.stderr.strip()}")
        account = f"{described.stdout.strip()}-compute@developer.gserviceaccount.com"

    print(f"\nGranting {account} permission to run {args.job} ...")
    run(
        [
            gcloud, "run", "jobs", "add-iam-policy-binding", args.job,
            "--region", region,
            f"--member=serviceAccount:{account}",
            "--role=roles/run.invoker", "--quiet",
        ],
        args.dry_run,
    )

    for schedule, task in entries:
        name = scheduler_job_name(prefix, task)
        uri = (
            f"https://{region}-run.googleapis.com/apis/run.googleapis.com/v1/"
            f"namespaces/{project}/jobs/{args.job}:run"
        )
        # Cloud Run Jobs v2's "run" API takes an optional body overriding
        # the container's args for just this execution - this is what lets
        # one Job (one image, one --command python) serve every crontab
        # line without deploy_job.py needing to know the task list.
        body = f'{{"overrides":{{"containerOverrides":[{{"args":["manage.py","{task}"]}}]}}}}'
        print(f"\nScheduling {name}: '{schedule}' -> manage.py {task}")
        code = run(
            [
                gcloud, "scheduler", "jobs", "create", "http", name,
                "--location", region,
                "--schedule", schedule,
                "--uri", uri,
                "--http-method", "POST",
                "--message-body", body,
                f"--oauth-service-account-email={account}",
                "--attempt-deadline", "60s",
                "--quiet",
            ],
            args.dry_run,
        )
        if code != 0 and not args.dry_run:
            # Create fails if the job already exists; retry as an update.
            # No "does it exist" pre-check needed here the way deploy.py
            # does one for Artifact Registry - `scheduler jobs create`
            # failing with ALREADY_EXISTS is itself the signal to update.
            code = run(
                [
                    gcloud, "scheduler", "jobs", "update", "http", name,
                    "--location", region,
                    "--schedule", schedule,
                    "--uri", uri,
                    "--http-method", "POST",
                    "--message-body", body,
                    "--attempt-deadline", "60s",
                    "--quiet",
                ],
                False,
            )
        if code != 0:
            die(f"Could not schedule {name}. Nothing after this line was scheduled either.")

    print(f"\nDone{' (dry-run: nothing above actually ran)' if args.dry_run else ''}.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
