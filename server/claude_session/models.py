"""The Claude session tables (claude-session ticket 03, ADR 0067).

Alfred hands a task to Claude Code on one of Kevin's laptops; a worker there
claims it, runs it in a long-lived main session, and reports back. Four
tables, all in `public`, all born tenanted (`household` is an ordinary
foreign key, each table is in `household.tenancy.TENANT_TABLES`):

- `claude_machines`: one row per laptop worker, keyed by its spoken name
  ("Alienware", "work laptop"), unique per household ignoring case.
- `claude_tasks`: one row per request Kevin sent. Integer ids on purpose: the
  main session sees "[task 41] ...", and a uuid there would be noise.
- `claude_task_files`: files a task found (listed) and, on Kevin's yes, the
  bytes the worker uploaded. Bytes live in Postgres (`content`), like
  `device_logs.body_gz`, because Cloud Run's filesystem is ephemeral and the
  upload and the download land on different instances.
- `claude_code_sessions`: the interactive Claude Code sessions open on each
  machine, posted by hooks (ticket 06).

**Nothing here may put a request, summary or file name in a log.** That
includes Django admin's `LogEntry.object_repr`, which stores `str(obj)` on
every admin save, so every `__str__` below names ids and states only.

**The stored status is not the whole truth.** A running task past 30 minutes,
or on a machine silent for 10, is failed; a machine silent for 90 s is
offline. Both are computed at read time from the clock (`claude_session.state`),
never written, so nothing has to run on a schedule for them to be true.
"""

from __future__ import annotations

import uuid

from django.db import models
from django.db.models.functions import Lower, Now

# Task statuses. `needs_machine` and `cancel_requested` are the two the map's
# state list leaves implicit: the first is a create-time answer (unnamed, more
# than one machine online), the second is a running task Kevin cancelled that
# the worker has not stopped yet.
QUEUED = "queued"
NEEDS_MACHINE = "needs_machine"
RUNNING = "running"
CANCEL_REQUESTED = "cancel_requested"
DONE = "done"
FAILED = "failed"
NEEDS_CONFIRMATION = "needs_confirmation"
CANCELLED = "cancelled"
TASK_STATUSES: tuple[str, ...] = (
    QUEUED,
    NEEDS_MACHINE,
    RUNNING,
    CANCEL_REQUESTED,
    DONE,
    FAILED,
    NEEDS_CONFIRMATION,
    CANCELLED,
)
TERMINAL: frozenset[str] = frozenset({DONE, FAILED, NEEDS_CONFIRMATION, CANCELLED})
IN_FLIGHT: frozenset[str] = frozenset({RUNNING, CANCEL_REQUESTED})

ORIGIN_NAMED = "named"
ORIGIN_OFFERED = "offered"
ORIGINS: tuple[str, ...] = (ORIGIN_NAMED, ORIGIN_OFFERED)

# Ruling 17 (Kevin 2026-11-01): naming his own email in the request is the yes
# for that one task. The phone sets this; the worker's `send_mail.py` mails the
# file from Alfred's Gmail on the laptop. The engine only carries the flag.
DELIVER_EMAIL_SELF = "email_self"
DELIVERIES: tuple[str, ...] = (DELIVER_EMAIL_SELF,)

# Ruling 5. `full`: every fixed drive, files may come back. `listed`: the repos
# folder only, summaries only, never a file.
SCOPE_FULL = "full"
SCOPE_LISTED = "listed"
SCOPES: tuple[str, ...] = (SCOPE_FULL, SCOPE_LISTED)

MACHINE_IDLE = "idle"
MACHINE_BUSY = "busy"
MACHINE_OFFLINE = "offline"
# A session state the status screen puts first: Claude is waiting on Kevin
# (the `Notification` hook, ticket 06).
SESSION_WAITING = "waiting"
REPORTED_MACHINE_STATES: tuple[str, ...] = (MACHINE_IDLE, MACHINE_BUSY)
SHOWN_MACHINE_STATES: tuple[str, ...] = (MACHINE_IDLE, MACHINE_BUSY, MACHINE_OFFLINE)
# What a worker may finish a task as. `cancelled` only from cancel_requested.
FINISH_STATUSES: tuple[str, ...] = (DONE, FAILED, NEEDS_CONFIRMATION, CANCELLED)

# The ticket's rules.
REQUEST_MAX_BYTES = 4 * 1024
SUMMARY_MAX_BYTES = 16 * 1024
PROGRESS_MAX_CHARS = 120
FILE_MAX_BYTES = 25 * 1024 * 1024
RETENTION_DAYS = 30
OFFLINE_AFTER_SECONDS = 90
TASK_CAP_MINUTES = 30
SILENT_FAIL_MINUTES = 10


class ClaudeMachine(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    household = models.ForeignKey("household.Household", on_delete=models.PROTECT, related_name="+")
    # The spoken name: "Alienware", "work laptop".
    name = models.TextField()
    hostname = models.TextField(blank=True, default="")
    scope = models.TextField(default=SCOPE_LISTED)
    # What the worker last said: idle or busy. Offline is never stored.
    reported_state = models.TextField(default=MACHINE_IDLE)
    claude_version = models.TextField(blank=True, default="")
    # Null for a machine only a session hook has ever named.
    last_seen_at = models.DateTimeField(null=True, blank=True)
    created_at = models.DateTimeField(db_default=Now())

    class Meta:
        db_table = "claude_machines"
        constraints = [
            models.UniqueConstraint(
                "household", Lower("name"), name="claude_machines_household_name_uniq"
            ),
            models.CheckConstraint(
                condition=models.Q(scope__in=SCOPES), name="claude_machines_scope_valid"
            ),
            models.CheckConstraint(
                condition=models.Q(reported_state__in=REPORTED_MACHINE_STATES),
                name="claude_machines_state_valid",
            ),
        ]

    def __str__(self) -> str:
        return f"machine {self.id} ({self.scope})"


class ClaudeTask(models.Model):
    id = models.BigAutoField(primary_key=True)
    household = models.ForeignKey("household.Household", on_delete=models.PROTECT, related_name="+")
    user = models.ForeignKey(
        "household.User", null=True, blank=True, on_delete=models.SET_NULL, related_name="+"
    )
    # Null while unaddressed: unnamed, and no single machine was online.
    machine = models.ForeignKey(
        ClaudeMachine, null=True, blank=True, on_delete=models.SET_NULL, related_name="tasks"
    )
    # A follow-up ("now email it to me") names the task it continues (ruling 10).
    parent = models.ForeignKey(
        "self", null=True, blank=True, on_delete=models.SET_NULL, related_name="+"
    )
    origin = models.TextField()
    deliver = models.TextField(null=True, blank=True)
    request = models.TextField()
    status = models.TextField(default=QUEUED)
    summary = models.TextField(null=True, blank=True)
    question = models.TextField(null=True, blank=True)
    turns = models.IntegerField(null=True, blank=True)
    duration_ms = models.BigIntegerField(null=True, blank=True)
    # The helper's current step while it runs ("reading Downloads"), from the
    # heartbeat (Kevin 2026-11-01, "yes to status extras"). Cleared on finish.
    progress = models.TextField(null=True, blank=True)
    progress_at = models.DateTimeField(null=True, blank=True)
    created_at = models.DateTimeField(db_default=Now())
    started_at = models.DateTimeField(null=True, blank=True)
    finished_at = models.DateTimeField(null=True, blank=True)

    class Meta:
        db_table = "claude_tasks"
        indexes = [
            models.Index(
                fields=["household", "-created_at"], name="claude_tasks_household_created"
            ),
            models.Index(fields=["household", "status"], name="claude_tasks_household_status"),
        ]
        constraints = [
            models.CheckConstraint(
                condition=models.Q(status__in=TASK_STATUSES), name="claude_tasks_status_valid"
            ),
            models.CheckConstraint(
                condition=models.Q(origin__in=ORIGINS), name="claude_tasks_origin_valid"
            ),
            models.CheckConstraint(
                condition=models.Q(deliver__isnull=True) | models.Q(deliver__in=DELIVERIES),
                name="claude_tasks_deliver_valid",
            ),
        ]

    def __str__(self) -> str:
        return f"task {self.id} ({self.status})"


class ClaudeTaskFile(models.Model):
    id = models.BigAutoField(primary_key=True)
    household = models.ForeignKey("household.Household", on_delete=models.PROTECT, related_name="+")
    task = models.ForeignKey(ClaudeTask, on_delete=models.CASCADE, related_name="files")
    # The path on the laptop, as the helper reported it. Only the worker reads it back.
    path = models.TextField()
    name = models.TextField()
    size = models.BigIntegerField(null=True, blank=True)
    # Set when Kevin said yes to "want it on your phone?" (send-file).
    requested_at = models.DateTimeField(null=True, blank=True)
    uploaded_at = models.DateTimeField(null=True, blank=True)
    content = models.BinaryField(null=True, blank=True)
    content_type = models.TextField(blank=True, default="")
    created_at = models.DateTimeField(db_default=Now())

    class Meta:
        db_table = "claude_task_files"
        indexes = [models.Index(fields=["household", "task"], name="claude_task_files_task")]

    def __str__(self) -> str:
        return f"file {self.id} of task {self.task_id}"


class ClaudeCodeSession(models.Model):
    id = models.BigAutoField(primary_key=True)
    household = models.ForeignKey("household.Household", on_delete=models.PROTECT, related_name="+")
    machine = models.ForeignKey(ClaudeMachine, on_delete=models.CASCADE, related_name="sessions")
    session_id = models.TextField()
    project = models.TextField(blank=True, default="")
    state = models.TextField()
    # Never stored for a `listed` machine (ruling 5): the view drops it.
    last_prompt_summary = models.TextField(null=True, blank=True)
    # The laptop's clock, as sent. Ordering and retention use last_seen_at.
    reported_at = models.DateTimeField(null=True, blank=True)
    last_seen_at = models.DateTimeField()

    class Meta:
        db_table = "claude_code_sessions"
        constraints = [
            models.UniqueConstraint(
                fields=("household", "machine", "session_id"),
                name="claude_code_sessions_uniq",
            ),
        ]

    def __str__(self) -> str:
        return f"session row {self.id} ({self.state})"
