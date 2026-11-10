"""Request and response shapes for `/api/claude/` (the map's API table).

Request serializers validate; response serializers exist so `openapi.yaml`
describes what the views return (`tests/test_openapi_schema.py`). A refusal
names the field and the limit, never the value: a request, a summary and a
file name are never echoed into an error.
"""

from __future__ import annotations

from rest_framework import serializers

from claude_session.models import (
    DELIVERIES,
    FINISH_STATUSES,
    ORIGIN_NAMED,
    ORIGIN_OFFERED,
    ORIGINS,
    PARAMS_MAX_CHARS,
    PROGRESS_MAX_CHARS,
    REPORTED_MACHINE_STATES,
    REQUEST_MAX_BYTES,
    SCOPES,
    SHOWN_MACHINE_STATES,
    SUMMARY_MAX_BYTES,
    TASK_KINDS,
    TASK_STATUSES,
)

_NAME_MAX = 100
_PATH_MAX = 2000


def _max_bytes(limit: int, what: str):
    def check(value: str) -> None:
        if value is not None and len(value.encode("utf-8")) > limit:
            raise serializers.ValidationError(f"{what} is over {limit // 1024} KB.")

    return check


class _Text(serializers.CharField):
    """A CharField whose length errors never quote the value."""

    def __init__(self, *, max_bytes: int, what: str, **kwargs):
        kwargs.setdefault("trim_whitespace", False)
        super().__init__(validators=[_max_bytes(max_bytes, what)], **kwargs)


# --- requests -----------------------------------------------------------------


class ClaudeTaskCreateSerializer(serializers.Serializer):
    request = _Text(max_bytes=REQUEST_MAX_BYTES, what="The request")
    machine = serializers.CharField(
        required=False, allow_null=True, default=None, max_length=_NAME_MAX,
        help_text="The spoken machine name, or null to let the engine choose.",
    )
    parent = serializers.IntegerField(required=False, allow_null=True, default=None)
    # `schedule` is the engine's own (the memory review); a client never sends it.
    origin = serializers.ChoiceField(choices=[ORIGIN_NAMED, ORIGIN_OFFERED])
    deliver = serializers.ChoiceField(
        choices=list(DELIVERIES), required=False, allow_null=True, default=None,
        help_text="email_self only when Kevin named his own email in the request (ruling 17); "
        "phone for a researched page (ticket 09); files when he asked for files on his phone "
        "(ADR 0080). A confirms follow-up to a files task inherits files.",
    )
    confirms = serializers.BooleanField(
        required=False, default=False,
        help_text="Kevin's yes to the parent's question. Needs a parent in needs_confirmation.",
    )
    # Worker workflows (ticket 03, ruling R2): Alfred's `action: "workflow"` sends Kevin's phrase;
    # the screen and the web send the exact name. The engine resolves it against the machine's
    # catalog; a miss is a 400 that names the workflows there are.
    workflow = serializers.CharField(
        required=False, allow_null=True, default=None, max_length=PARAMS_MAX_CHARS,
        help_text="Run a named workflow: Kevin's phrase or the exact name. Omitted for a plain task.",
    )


class ClaudeAssignSerializer(serializers.Serializer):
    machine = serializers.CharField(max_length=_NAME_MAX, help_text="The spoken machine name.")


class ClaudeSendFileSerializer(serializers.Serializer):
    file_id = serializers.IntegerField()


class ClaudeHeartbeatSerializer(serializers.Serializer):
    name = serializers.CharField(max_length=_NAME_MAX)
    hostname = serializers.CharField(required=False, allow_blank=True, default="", max_length=255)
    scope = serializers.ChoiceField(choices=list(SCOPES))
    state = serializers.ChoiceField(choices=list(REPORTED_MACHINE_STATES))
    running = serializers.ListField(
        child=serializers.IntegerField(), required=False, default=list,
        help_text="Task ids the worker is running. Accepted; the engine's own rows are the truth.",
    )
    claude_version = serializers.CharField(
        required=False, allow_blank=True, default="", max_length=64
    )
    progress = serializers.DictField(
        child=serializers.CharField(max_length=PROGRESS_MAX_CHARS, allow_blank=True),
        required=False, default=dict,
        help_text="Task id to the helper's current step, for tasks running on this machine.",
    )
    # memory-review ticket 15: whether the laptop is idle enough for the daily review.
    input_idle_s = serializers.IntegerField(
        required=False, allow_null=True, default=None, min_value=0,
        help_text="Seconds since Windows last saw keyboard or mouse input.",
    )
    on_mains = serializers.BooleanField(required=False, allow_null=True, default=None)
    # Worker workflows (ticket 03). A worker that knows workflows always sends its hash, and the
    # full list only when the hash the engine echoed differs. Never a reason to refuse a heartbeat:
    # a bad entry is dropped and counted (`workflows_dropped`).
    workflows_hash = serializers.CharField(
        required=False, allow_null=True, allow_blank=True, default=None, max_length=64,
        help_text="The worker's hash of its catalog. Absent: an older worker, catalog untouched.",
    )
    workflows = serializers.JSONField(
        required=False, default=None,
        help_text="The catalog (at most 50 entries, each at most 2 KB), only when the hash moved.",
    )


class ClaudeWorkflowRunSerializer(serializers.Serializer):
    machine = serializers.CharField(
        required=False, allow_null=True, default=None, max_length=_NAME_MAX,
        help_text="The machine's spoken name; null for the only machine that has the workflow.",
    )
    name = serializers.CharField(max_length=100, help_text="The workflow's exact name.")
    params = serializers.CharField(
        required=False, allow_null=True, allow_blank=True, default=None,
        max_length=PARAMS_MAX_CHARS, help_text="Optional words for the workflow, as typed.",
    )


class ClaudeClaimSerializer(serializers.Serializer):
    name = serializers.CharField(max_length=_NAME_MAX)


class ClaudeFoundFileSerializer(serializers.Serializer):
    path = serializers.CharField(max_length=_PATH_MAX, trim_whitespace=False)
    name = serializers.CharField(max_length=500, trim_whitespace=False)
    size = serializers.IntegerField(required=False, allow_null=True, default=None, min_value=0)


class ClaudeFinishSerializer(serializers.Serializer):
    status = serializers.ChoiceField(
        choices=list(FINISH_STATUSES),
        help_text="cancelled only for a task in cancel_requested.",
    )
    summary = _Text(
        max_bytes=SUMMARY_MAX_BYTES, what="The summary",
        required=False, allow_null=True, allow_blank=True, default=None,
    )
    question = _Text(
        max_bytes=SUMMARY_MAX_BYTES, what="The question",
        required=False, allow_null=True, allow_blank=True, default=None,
    )
    turns = serializers.IntegerField(required=False, allow_null=True, default=None, min_value=0)
    duration_ms = serializers.IntegerField(
        required=False, allow_null=True, default=None, min_value=0
    )
    found = ClaudeFoundFileSerializer(many=True, required=False, default=list)


class ClaudeFileUploadSerializer(serializers.Serializer):
    file_id = serializers.IntegerField()
    file = serializers.FileField(use_url=False)


class ClaudeSessionReportSerializer(serializers.Serializer):
    machine = serializers.CharField(max_length=_NAME_MAX)
    session_id = serializers.CharField(max_length=200)
    project = serializers.CharField(required=False, allow_blank=True, default="", max_length=500)
    state = serializers.CharField(max_length=32)
    last_prompt_summary = _Text(
        max_bytes=SUMMARY_MAX_BYTES, what="The prompt summary",
        required=False, allow_null=True, allow_blank=True, default=None,
    )
    at = serializers.DateTimeField(required=False, allow_null=True, default=None)


# --- responses ----------------------------------------------------------------


class ClaudeMachineRefSerializer(serializers.Serializer):
    name = serializers.CharField()
    state = serializers.ChoiceField(choices=list(SHOWN_MACHINE_STATES))
    last_seen_at = serializers.DateTimeField(allow_null=True)


class ClaudeTaskCreatedSerializer(serializers.Serializer):
    id = serializers.IntegerField()
    status = serializers.ChoiceField(choices=list(TASK_STATUSES))
    machine = ClaudeMachineRefSerializer(allow_null=True)
    workflow = serializers.CharField(
        allow_null=True, help_text="The workflow it runs (its name), or null for a plain task."
    )
    asks = serializers.ListField(
        child=serializers.CharField(),
        help_text="Where that workflow stops for Kevin's yes, in words; empty for a plain task.",
    )


class ClaudeWorkflowMissSerializer(serializers.Serializer):
    detail = serializers.CharField()
    workflows = serializers.ListField(
        child=serializers.CharField(), help_text="The workflows there are, as Alfred says them."
    )


class ClaudeTaskFileRowSerializer(serializers.Serializer):
    id = serializers.IntegerField()
    name = serializers.CharField()
    size = serializers.IntegerField(allow_null=True)
    uploaded = serializers.BooleanField()
    requested = serializers.BooleanField()
    content_type = serializers.CharField(
        help_text="The type it was uploaded as; blank until it is uploaded."
    )
    received = serializers.BooleanField(
        help_text="The asking member's phone said it has it (files to phone, ADR 0080)."
    )
    gone = serializers.BooleanField(
        help_text="Its bytes were deleted from the engine: received, or 24 h after upload."
    )


class ClaudeArtifactSerializer(serializers.Serializer):
    """A delivered page (ticket 09) that has arrived on the engine."""

    id = serializers.IntegerField(help_text="The file's id.")
    filename = serializers.CharField()
    title = serializers.CharField(help_text="The page's own <title>, or the file name.")
    size = serializers.IntegerField(allow_null=True)
    url = serializers.CharField(
        help_text="Relative signed link to the page; no token header needed. Minted per read."
    )
    expires_in = serializers.IntegerField(help_text="Seconds the link stays valid.")


class ClaudeTaskStepSerializer(serializers.Serializer):
    at = serializers.DateTimeField(help_text="When the engine first heard this step.")
    text = serializers.CharField()


class ClaudeTaskRowSerializer(serializers.Serializer):
    id = serializers.IntegerField()
    request = serializers.CharField()
    machine = serializers.CharField(allow_null=True, help_text="The machine's name, or null.")
    status = serializers.ChoiceField(choices=list(TASK_STATUSES))
    summary = serializers.CharField(allow_null=True)
    question = serializers.CharField(allow_null=True)
    created_at = serializers.DateTimeField()
    finished_at = serializers.DateTimeField(allow_null=True)
    files = ClaudeTaskFileRowSerializer(many=True)
    artifacts = ClaudeArtifactSerializer(many=True)
    parent = serializers.IntegerField(allow_null=True)
    origin = serializers.ChoiceField(choices=list(ORIGINS))
    kind = serializers.ChoiceField(
        choices=list(TASK_KINDS),
        help_text="`ask` for Kevin's own tasks; `memory_review` for the daily memory review; "
        "`workflow` for a named workflow (its follow-ups too).",
    )
    workflow = serializers.CharField(
        allow_null=True, help_text="The workflow's name when kind is workflow, else null."
    )
    params = serializers.CharField(
        allow_null=True, help_text="Kevin's words after the workflow's alias, or null."
    )
    deliver = serializers.ChoiceField(choices=list(DELIVERIES), allow_null=True)
    confirms = serializers.BooleanField()
    progress = serializers.CharField(allow_null=True, help_text="The current step while running.")
    progress_at = serializers.DateTimeField(allow_null=True)
    steps = ClaudeTaskStepSerializer(
        many=True,
        help_text="The last 20 distinct steps, newest first; kept after the task finishes. "
        "Always empty for a summaries-only (listed) machine.",
    )


class ClaudeCancelledSerializer(serializers.Serializer):
    id = serializers.IntegerField()
    status = serializers.ChoiceField(choices=list(TASK_STATUSES))
    detail = serializers.CharField()


class ClaudeSendFileResultSerializer(serializers.Serializer):
    file_id = serializers.IntegerField()
    requested = serializers.BooleanField()
    uploaded = serializers.BooleanField()
    detail = serializers.CharField()


class ClaudeFileReceivedSerializer(serializers.Serializer):
    file_id = serializers.IntegerField()
    deleted = serializers.BooleanField(help_text="The engine's copy is gone.")
    detail = serializers.CharField()


class ClaudeRunningProgressSerializer(serializers.Serializer):
    task = serializers.IntegerField()
    progress = serializers.CharField(allow_null=True)
    progress_at = serializers.DateTimeField(allow_null=True)


class ClaudeWorkflowNameSerializer(serializers.Serializer):
    name = serializers.CharField()
    says = serializers.CharField()


class ClaudeStatusMachineSerializer(serializers.Serializer):
    name = serializers.CharField()
    scope = serializers.ChoiceField(choices=list(SCOPES))
    state = serializers.ChoiceField(choices=list(SHOWN_MACHINE_STATES))
    last_seen_at = serializers.DateTimeField(allow_null=True)
    running = serializers.ListField(child=serializers.IntegerField())
    progress = ClaudeRunningProgressSerializer(many=True)
    workflows = ClaudeWorkflowNameSerializer(
        many=True, help_text="The workflows this machine published, by name (for check)."
    )


class ClaudeStatusSessionSerializer(serializers.Serializer):
    machine = serializers.CharField()
    project = serializers.CharField()
    state = serializers.CharField()
    last_prompt_summary = serializers.CharField(allow_null=True)
    last_seen_at = serializers.DateTimeField()


class ClaudeStatusSerializer(serializers.Serializer):
    machines = ClaudeStatusMachineSerializer(many=True)
    sessions = ClaudeStatusSessionSerializer(many=True)


class ClaudeFileRequestedSerializer(serializers.Serializer):
    task = serializers.IntegerField()
    file_id = serializers.IntegerField()
    path = serializers.CharField()


class ClaudeHeartbeatResultSerializer(serializers.Serializer):
    cancel_requested = serializers.ListField(child=serializers.IntegerField())
    files_requested = ClaudeFileRequestedSerializer(many=True)
    workflows_hash = serializers.CharField(
        help_text="The catalog hash the engine holds for this machine; blank for none."
    )
    workflows_dropped = serializers.IntegerField(
        help_text="Entries of the catalog just sent that failed the engine's checks; 0 otherwise."
    )


class ClaudeWorkflowParamSerializer(serializers.Serializer):
    name = serializers.CharField()
    says = serializers.CharField()
    required = serializers.BooleanField()


class ClaudeWorkflowEverySerializer(serializers.Serializer):
    hours = serializers.IntegerField()
    idle_minutes = serializers.IntegerField()
    force_after_hours = serializers.IntegerField()
    retry_after_hours = serializers.IntegerField()


class ClaudeWorkflowLastRunSerializer(serializers.Serializer):
    task = serializers.IntegerField()
    status = serializers.ChoiceField(choices=list(TASK_STATUSES))
    origin = serializers.ChoiceField(choices=list(ORIGINS))
    created_at = serializers.DateTimeField()
    finished_at = serializers.DateTimeField(allow_null=True)


class ClaudeWorkflowRowSerializer(serializers.Serializer):
    machine = serializers.CharField()
    machine_state = serializers.ChoiceField(choices=list(SHOWN_MACHINE_STATES))
    name = serializers.CharField()
    spoken = serializers.CharField(help_text="The name as Alfred says it.")
    says = serializers.CharField()
    aliases = serializers.ListField(child=serializers.CharField())
    params = ClaudeWorkflowParamSerializer(many=True)
    asks = serializers.ListField(
        child=serializers.CharField(), help_text="Where it stops for Kevin's yes, in words."
    )
    outputs = serializers.ListField(child=serializers.CharField())
    deliver = serializers.CharField(allow_null=True)
    every = ClaudeWorkflowEverySerializer(allow_null=True)
    last_run = ClaudeWorkflowLastRunSerializer(allow_null=True)
    waiting = serializers.BooleanField(
        help_text="A run is queued, running, or waiting on Kevin's yes."
    )
    due = serializers.BooleanField(help_text="Its schedule says a run is due now.")


class ClaudeWorkflowQueuedSerializer(serializers.Serializer):
    id = serializers.IntegerField()
    status = serializers.ChoiceField(choices=list(TASK_STATUSES))
    machine = ClaudeMachineRefSerializer(allow_null=True)
    workflow = serializers.CharField()
    detail = serializers.CharField(help_text="What was queued, in words.")


class ClaudeFinishResultSerializer(serializers.Serializer):
    id = serializers.IntegerField()
    status = serializers.ChoiceField(choices=list(TASK_STATUSES))
    files = ClaudeTaskFileRowSerializer(many=True)
    detail = serializers.CharField()


class ClaudeUploadResultSerializer(serializers.Serializer):
    file_id = serializers.IntegerField()
    size = serializers.IntegerField()
    detail = serializers.CharField()


class ClaudeSessionStoredSerializer(serializers.Serializer):
    machine = serializers.CharField()
    session_id = serializers.CharField()
    state = serializers.CharField()
    summary_kept = serializers.BooleanField()
