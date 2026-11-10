"""`POST /api/internal/tick/<task>`: Cloud Scheduler runs a frequent job on the warm service.

Architecture review 2026-11-10, item 12. `push_dispatch` (every 5 minutes, 288 a day) and
`evaluate_watches` (every 15) each started a whole Cloud Run Job execution: a container
boot and a Django import to do a few queries. Called over HTTP they run on an instance
that is usually already up.

**Same code, same locks.** The endpoint runs the management command itself
(`call_command`), so the command stays the one implementation: `push_dispatch` still goes
through `ingest.jobs.run_job` per household, with its session advisory lock and its
`ingest_runs` row, and the job-based schedule can run alongside during the switch without
double work (a held lock records `skipped_locked`; `push_sent` dedupes a notification).

**Who may call it.** Only a Google-signed OIDC ID token whose `email` is
`LEGION_SCHEDULER_SA` (verified) and whose audience is `LEGION_TICK_AUDIENCE`. The service
is `--allow-unauthenticated` (phones authenticate with their own tokens), so the check is
here, not in IAM. Either setting unset: every call is a 404, as if the route did not exist.

**Not the brief or the backups.** Only these two: they are short, frequent, and safe to
retry. Long jobs (`backup_nightly`, `drive_statements`, `plaid_sync`) stay on the job,
where a 300 s request ceiling cannot cut them off.
"""

from __future__ import annotations

import io
import logging
import os

from django.core.management import call_command
from django.http import Http404, JsonResponse
from django.views.decorators.csrf import csrf_exempt
from django.views.decorators.http import require_POST

logger = logging.getLogger(__name__)

TASKS = frozenset({"push_dispatch", "evaluate_watches"})
GOOGLE_ISSUERS = frozenset({"accounts.google.com", "https://accounts.google.com"})


def _settings() -> tuple[str, str] | None:
    account = os.environ.get("LEGION_SCHEDULER_SA", "").strip()
    audience = os.environ.get("LEGION_TICK_AUDIENCE", "").strip()
    return (account, audience) if account and audience else None


def verify_google_id_token(token: str, audience: str) -> dict:
    """Google's signature, expiry and audience check. Raises ValueError on any failure."""
    from google.auth.transport import requests as google_requests
    from google.oauth2 import id_token

    return id_token.verify_oauth2_token(token, google_requests.Request(), audience=audience)


def _caller_is_scheduler(request, account: str, audience: str) -> bool:
    header = request.headers.get("Authorization", "")
    if not header.startswith("Bearer "):
        return False
    try:
        claims = verify_google_id_token(header[len("Bearer ") :].strip(), audience)
    except Exception as exc:  # noqa: BLE001 - any verification failure is a refusal
        logger.warning("tick: OIDC token refused (%s)", type(exc).__name__)
        return False
    return (
        claims.get("iss") in GOOGLE_ISSUERS
        and claims.get("email") == account
        and claims.get("email_verified") is True
    )


@csrf_exempt
@require_POST
def tick(request, task: str):
    configured = _settings()
    if configured is None or task not in TASKS:
        raise Http404
    account, audience = configured
    if not _caller_is_scheduler(request, account, audience):
        return JsonResponse({"detail": "Not the scheduler. Nothing ran."}, status=403)
    out, err = io.StringIO(), io.StringIO()
    try:
        call_command(task, stdout=out, stderr=err)
    except Exception:
        # Logged with its stack (Error Reporting); a 500 makes Scheduler record the failure
        # and retry on its own policy.
        logger.exception("tick: %s failed", task)
        return JsonResponse({"task": task, "ok": False}, status=500)
    said = (out.getvalue() + err.getvalue()).strip()
    logger.info("tick: %s ran", task, extra={"tick_task": task, "tick_output": said[:2000]})
    return JsonResponse({"task": task, "ok": True})
