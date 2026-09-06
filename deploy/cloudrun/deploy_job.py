"""Deploy the Cloud Run Job that runs LEGION's worker tasks.

Ticket 07's amendment replaces the `worker` compose container's
`supercronic` with this: a Cloud Run Job built from the exact same image
`deploy.py` just built (never rebuilt here - the point of building it once
in `deploy.py` and passing `--image` through is that the service and the
job can never quietly diverge), entrypoint overridden to
`python manage.py <task>`, fired on a schedule by `install_schedule.py`
reading `deploy/crontab` (ticket 06 fills that file in - see that ticket's
own comment for the one Job 1 that ships before any other: `backup_nightly`).

Modelled on `midconerpdash/deploy_job.py`: one Cloud Run Job, Cloud
Scheduler starts executions of it, nothing runs (or bills) between runs.
LEGION's version differs in one way the reference does not need: several
DIFFERENT management commands share one Job rather than one pipeline having
one job, so `install_schedule.py` (not this script) is what picks which
task each scheduled execution overrides the Job's args to run.

Usage:
    python deploy_job.py --image us-south1-docker.pkg.dev/PROJECT/legion/server:latest
    python deploy_job.py --dry-run --image ...
"""

from __future__ import annotations

import argparse

from _common import (
    PLAIN_ENV_VARS,
    capture,
    die,
    find_gcloud,
    read_cloud_env,
    require_project,
    run,
    secrets_flag_value,
    write_env_vars_file,
)

from deploy import DEFAULT_REGION, image_uri

DEFAULT_JOB = "legion-worker"

# Killed at this point so a stuck task cannot overlap the next scheduled
# execution of the same or a different task. Ten minutes is generous for
# `backup_nightly`'s pg_dump + tar and for the API-poll jobs ticket 06 adds.
TASK_TIMEOUT = "600s"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", default=None)
    parser.add_argument("--job", default=DEFAULT_JOB)
    parser.add_argument("--region", default=None)
    parser.add_argument(
        "--image", default=None,
        help="the exact image deploy.py just built and pushed; required unless --dry-run",
    )
    parser.add_argument(
        "--dry-run", action="store_true",
        help="print every gcloud command this would run and run none of them",
    )
    args = parser.parse_args()

    project = require_project(args.project)
    cloud_env = read_cloud_env()
    region = args.region or cloud_env.get("CLOUD_RUN_REGION") or DEFAULT_REGION

    image = args.image
    if not image:
        if args.dry_run:
            image = image_uri(project, region, "latest")
            print(f"(--image not given; showing the plan against the default tag {image})")
        else:
            die(
                "--image is required. Run deploy.py first and pass the image it "
                "built, e.g. --image "
                f"{image_uri(project, region, 'latest')}"
            )

    gcloud = find_gcloud() if not args.dry_run else "gcloud"

    # settings.py's required_env() crashes `manage.py` on import without
    # these - a Job with no env vars fails on its first line, not on the
    # task it was meant to run. Same source file, same names, as the
    # service's plain env vars - a Job that read Django settings
    # differently from the service it shares an image with would be its
    # own bug.
    plain_env = {name: cloud_env.get(name, "") for name in PLAIN_ENV_VARS}
    missing_plain = [n for n in PLAIN_ENV_VARS if not plain_env[n] and n != "CSRF_TRUSTED_ORIGINS"]
    if missing_plain and not args.dry_run:
        die(
            f"{', '.join(missing_plain)} missing from deploy/cloudrun/.env. "
            f"Copy .env.example and fill them in first."
        )

    print(f"project   {project}")
    print(f"job       {args.job} in {region}")
    print(f"image     {image}")
    print(f"secrets   {secrets_flag_value()}\n")

    if args.dry_run:
        print("--dry-run: printing every command, running none of them.\n")

    print("Enabling the APIs this needs (no-op if already on) ...")
    run(
        [
            gcloud, "services", "enable",
            "run.googleapis.com", "cloudscheduler.googleapis.com", "secretmanager.googleapis.com",
        ],
        args.dry_run,
    )

    if args.dry_run:
        verb = "create-or-update"
    else:
        described = capture([gcloud, "run", "jobs", "describe", args.job, "--region", region])
        verb = "update" if described.returncode == 0 else "create"
    print(f"\nDeploying the job ({verb}) ...")

    env_file = None
    if args.dry_run:
        print("[plan] would write non-secret env vars to a temp YAML file, e.g.:")
        print(f"  {plain_env}")
    else:
        env_file = write_env_vars_file(plain_env, args.job)

    try:
        # Command and args are left at "python manage.py" here with no
        # task named - install_schedule.py's Cloud Scheduler entries
        # override `args` per execution via the Cloud Run Jobs API
        # `overrides` body, one per line of deploy/crontab, so this one
        # Job definition serves every scheduled task rather than needing
        # one Job per task. A manual `gcloud run jobs execute` with no
        # override would run bare `manage.py` (prints Django's usage and
        # exits 0) - harmless, and a clearer failure than silently running
        # the wrong task.
        code = run(
            [
                gcloud, "run", "jobs", "deploy", args.job,
                "--image", image,
                "--region", region,
                "--command", "python",
                "--args", "manage.py",
                "--memory", "512Mi",
                "--cpu", "1",
                "--task-timeout", TASK_TIMEOUT,
                "--max-retries", "1",
                "--quiet",
                "--set-secrets", secrets_flag_value(),
                "--env-vars-file", str(env_file) if env_file else "<temp .yaml, values printed above>",
            ],
            args.dry_run,
        )
    finally:
        if env_file is not None:
            env_file.unlink(missing_ok=True)
    if code != 0:
        die("The job did not deploy.")

    print(
        f"\nDone{' (dry-run: nothing above actually ran)' if args.dry_run else ''}. "
        f"Next: python install_schedule.py --job {args.job} --region {region} "
        f"to wire deploy/crontab's lines to Cloud Scheduler."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
