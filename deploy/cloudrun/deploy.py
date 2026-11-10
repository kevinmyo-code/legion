"""Deploy LEGION's Django server to Google Cloud Run.

Ticket 07's amendment: compute is a Cloud Run service (this script) plus a
Cloud Run Job on Cloud Scheduler (`deploy_job.py`, `install_schedule.py`),
modelled on the shape already running in Kevin's `midconerpdash` project -
same `deploy.py`/`deploy_job.py` split, same reasoning ("the figures have to
keep refreshing when no workstation is switched on"; here, the phone has to
keep syncing when no laptop is switched on).

Prerequisites, once per machine:
    1. Install the gcloud SDK      https://cloud.google.com/sdk/docs/install
    2. gcloud auth login
    3. gcloud config set project YOUR-PROJECT-ID (or export GOOGLE_CLOUD_PROJECT)
    4. Create the two secrets this reads by name (README.md has the exact
       commands): SECRET_KEY, DATABASE_URL.
    5. Copy .env.example to .env in this folder and fill in the non-secret
       values.

Usage:
    python deploy.py                  build, push, deploy
    python deploy.py --dry-run        print every gcloud command, run none of them
    python deploy.py --build-with local-docker    build and push with the
                                       local Docker daemon instead of Cloud Build
"""

from __future__ import annotations

import argparse
import shutil

from _common import (
    PLAIN_ENV_VARS,
    plain_env_from,
    secret_names_for,
    SERVER_DIR,
    capture,
    die,
    find_gcloud,
    read_cloud_env,
    require_project,
    run,
    secrets_flag_value,
    write_env_vars_file,
)

DEFAULT_SERVICE = "legion"
DEFAULT_REGION = "us-south1"
ARTIFACT_REPO = "legion"

# Engine runtime upgrade (architecture review 2026-11-10, item 3).
# The request ceiling, matched by gunicorn's `--timeout` in `server/entrypoint.sh`. The
# slowest synchronous model call inside a request (`brief/news.py`, 30 s) sits well inside it.
REQUEST_TIMEOUT_SECONDS = 300

# Database connections this service may hold at once, across every instance. Every gunicorn
# thread can hold one (Django CONN_MAX_AGE), so workers x threads x max-instances must fit.
# ASSUMPTION (Q5, not read from the database secret): `DATABASE_URL` is the Supabase SESSION
# pooler (`README.md`, and `ingest/jobs.py`'s session advisory locks need session mode), whose
# pool size on the smallest compute is 15. 12 leaves 3 for the `legion-worker` job executions
# Cloud Scheduler starts, which run outside this service. Raise it in `.env`
# (LEGION_DB_CONN_BUDGET) once the plan's real pool size is known.
DEFAULT_DB_CONN_BUDGET = 12


def connection_plan(cloud_env: dict[str, str], max_instances: int) -> tuple[int, int, str | None]:
    """(concurrency, max_instances, note): Cloud Run's per-instance concurrency is workers x
    threads, and max-instances is lowered, with a note saying so, when workers x threads x
    max-instances would exceed the database connection budget."""
    from _common import DEFAULTED_PLAIN_ENV_VARS

    def number(name: str) -> int:
        raw = cloud_env.get(name) or DEFAULTED_PLAIN_ENV_VARS.get(name, "")
        try:
            value = int(raw)
        except ValueError:
            value = 0
        if value < 1:
            die(f"{name} must be a whole number of at least 1; got {raw!r}.")
        return value

    per_instance = number("LEGION_GUNICORN_WORKERS") * number("LEGION_GUNICORN_THREADS")
    budget_raw = cloud_env.get("LEGION_DB_CONN_BUDGET") or str(DEFAULT_DB_CONN_BUDGET)
    try:
        budget = int(budget_raw)
    except ValueError:
        die(f"LEGION_DB_CONN_BUDGET must be a whole number; got {budget_raw!r}.")
    if per_instance > budget:
        die(
            f"One instance alone ({per_instance} threads) needs more database connections than "
            f"LEGION_DB_CONN_BUDGET={budget}. Lower LEGION_GUNICORN_WORKERS/THREADS in .env."
        )
    allowed = budget // per_instance
    if max_instances > allowed:
        return per_instance, allowed, (
            f"max-instances {max_instances} -> {allowed}: {per_instance} threads x "
            f"{max_instances} instances = {per_instance * max_instances} database connections, "
            f"over LEGION_DB_CONN_BUDGET={budget}."
        )
    return per_instance, max_instances, None


def image_uri(project: str, region: str, tag: str) -> str:
    return f"{region}-docker.pkg.dev/{project}/{ARTIFACT_REPO}/server:{tag}"


def build_commands(gcloud: str, docker, image: str, build_with: str) -> list[list[str]]:
    """The command(s) that produce `image` in Artifact Registry. Returned
    as a list rather than run inline so --dry-run can print every one of
    them without this function needing its own dry-run branch."""
    if build_with == "local-docker":
        if docker is None:
            die(
                "docker was not found on PATH and --build-with local-docker "
                "was requested. Install Docker, or drop --build-with to use "
                "Cloud Build instead (it needs no local Docker daemon)."
            )
        return [
            [docker, "build", "-t", image, str(SERVER_DIR)],
            [docker, "push", image],
        ]
    # Cloud Build: no local Docker daemon required, matches the reference's
    # "--source ." shortcut but as an explicit build+push step so
    # deploy_job.py can point at the exact same tag without rebuilding.
    return [[gcloud, "builds", "submit", "--tag", image, str(SERVER_DIR)]]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", default=None, help="overrides GOOGLE_CLOUD_PROJECT")
    parser.add_argument("--service", default=DEFAULT_SERVICE)
    parser.add_argument("--region", default=None, help="overrides CLOUD_RUN_REGION / .env")
    parser.add_argument("--tag", default="latest", help="image tag in Artifact Registry")
    parser.add_argument(
        "--max-instances", type=int, default=None,
        help="overrides CLOUD_RUN_MAX_INSTANCES / .env; keeps a runaway bill impossible",
    )
    parser.add_argument(
        "--build-with", choices=("cloud-build", "local-docker"), default="cloud-build",
    )
    parser.add_argument(
        "--dry-run", action="store_true",
        help="print every gcloud command this would run and run none of them",
    )
    args = parser.parse_args()

    # Refuses first, before anything else, dry-run included: a plan against
    # no project is not a plan, and every script in this folder makes the
    # same refusal so none of them can be run half-configured by accident.
    project = require_project(args.project)

    cloud_env = read_cloud_env()
    region = args.region or cloud_env.get("CLOUD_RUN_REGION") or DEFAULT_REGION
    max_instances = args.max_instances or int(cloud_env.get("CLOUD_RUN_MAX_INSTANCES", "4"))

    missing_plain = [name for name in PLAIN_ENV_VARS if not cloud_env.get(name) and name != "CSRF_TRUSTED_ORIGINS"]
    if missing_plain and not args.dry_run:
        die(
            f"{', '.join(missing_plain)} missing from deploy/cloudrun/.env. "
            f"Copy .env.example and fill them in first."
        )
    plain_env = plain_env_from(cloud_env)
    concurrency, max_instances, clamp_note = connection_plan(cloud_env, max_instances)

    # --dry-run must produce readable output even on a machine with no
    # gcloud installed at all (this one, today) - find_gcloud() would
    # otherwise die() before a single line is printed.
    gcloud = find_gcloud() if not args.dry_run else (shutil.which("gcloud") or "gcloud")
    docker = shutil.which("docker")
    image = image_uri(project, region, args.tag)

    print(f"project        {project}")
    print(f"service        {args.service}")
    print(f"region         {region}")
    print(f"image          {image}")
    print(f"max-instances  {max_instances}")
    print(f"concurrency    {concurrency} (gunicorn workers x threads)")
    if clamp_note:
        print(f"NOTE           {clamp_note}")
    print(f"secrets        {secrets_flag_value()}")
    print()

    if args.dry_run:
        print("--dry-run: printing every command, running none of them.\n")

    print("Enabling the APIs this needs (no-op if already on) ...")
    run(
        [
            gcloud, "services", "enable",
            "run.googleapis.com",
            "artifactregistry.googleapis.com",
            "cloudbuild.googleapis.com",
            "secretmanager.googleapis.com",
        ],
        args.dry_run,
    )

    print("\nEnsuring the Artifact Registry repository exists ...")
    if args.dry_run:
        print(
            f"[plan] would `gcloud artifacts repositories describe {ARTIFACT_REPO}` "
            f"and only run `create` below if that describe fails - dry-run never "
            f"calls Google, so this always shows the create command:"
        )
        run(
            [
                gcloud, "artifacts", "repositories", "create", ARTIFACT_REPO,
                "--repository-format=docker", f"--location={region}", "--quiet",
            ],
            True,
        )
    else:
        described = capture(
            [gcloud, "artifacts", "repositories", "describe", ARTIFACT_REPO, f"--location={region}"]
        )
        create_failed = described.returncode != 0 and run(
            [
                gcloud, "artifacts", "repositories", "create", ARTIFACT_REPO,
                "--repository-format=docker", f"--location={region}", "--quiet",
            ],
            False,
        ) != 0
        if create_failed:
            die("Could not create the Artifact Registry repository.")

    print(f"\nBuilding and pushing {image} with {args.build_with} ...")
    for command in build_commands(gcloud, docker, image, args.build_with):
        if run(command, args.dry_run) != 0:
            die("Build failed. Nothing deployed.")

    env_file = None
    if args.dry_run:
        print("\n[plan] would write non-secret env vars to a temp YAML file, e.g.:")
        print(f"  {plain_env}")
    else:
        env_file = write_env_vars_file(plain_env, args.service)

    print(f"\nDeploying {args.service} to {region} ...")
    try:
        deploy_command = [
            gcloud, "run", "deploy", args.service,
            "--image", image,
            "--region", region,
            "--platform", "managed",
            # Auth is Django's own device-token header (household/authentication.py),
            # not IAM - the whole point is a stranger's phone can talk to their
            # own deploy without being added as a GCP principal.
            "--allow-unauthenticated",
            "--memory", "512Mi",
            "--cpu", "1",
            "--min-instances", "0",
            "--max-instances", str(max_instances),
            "--timeout", str(REQUEST_TIMEOUT_SECONDS),
            "--concurrency", str(concurrency),
            "--quiet",
            "--set-secrets", secrets_flag_value(secret_names_for(cloud_env)),
        ]
        if not args.dry_run:
            deploy_command += ["--env-vars-file", str(env_file)]
        else:
            deploy_command += ["--env-vars-file", "<temp .yaml, values printed above>"]
        code = run(deploy_command, args.dry_run)
    finally:
        if env_file is not None:
            env_file.unlink(missing_ok=True)
    if code != 0:
        die("Deploy failed.")

    print("\nDeployed. URL:")
    run(
        [gcloud, "run", "services", "describe", args.service, "--region", region, "--format", "value(status.url)"],
        args.dry_run,
    )
    if args.dry_run:
        print(
            "\n(dry-run: the URL above is the command that WOULD print it, "
            "not an answer - nothing was actually deployed.)"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
