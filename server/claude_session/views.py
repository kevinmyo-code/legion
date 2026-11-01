"""`/api/claude/`: Alfred's tasks for Claude Code on Kevin's laptops (ADR 0067).

Implements the map's API table (`.scratch/claude-session/map.md`). Every
route is token-authenticated like every other device call
(`DeviceTokenAuthentication` plus `IsHouseholdMember`, the project
defaults) and every read and write goes through the caller's household
(`household_of` / `scoped`, ADR 0045). The phone, the web, the laptop worker
and the session hooks all hold ordinary household device tokens; nothing
here tells them apart, and the "Who" column of the table is the intended
caller, not an enforced role.

**Nothing here logs.** No logger is imported. A request, a summary, a
question and a file name are household data; they are stored and returned
to the household, and never written anywhere else. Refusals name the field
and the limit, never the value.

Rulings this file enforces, by number in the map:
- 4: a named machine wins; unnamed goes to the only machine online, or
  comes back `needs_machine` when more than one is online.
- 5: a `listed` machine (the work laptop) never sends a file back: send-file,
  upload and a `deliver` task are refused for it, `found` rows are not kept,
  and its sessions' `last_prompt_summary` is dropped on arrival.
- 9 and the state list: the 30 minute cap and the 10 minute silence are
  computed at read time (`claude_session.state`).
- 17: `deliver: email_self` is accepted on create and returned on every task
  row. The engine only carries it; the worker mails the file from the laptop.
"""

from __future__ import annotations

import datetime

from django.db import transaction
from django.http import HttpResponse
from django.utils import timezone
from drf_spectacular.types import OpenApiTypes
from drf_spectacular.utils import OpenApiParameter, OpenApiResponse, extend_schema
from rest_framework import status as http
from rest_framework.parsers import FormParser, JSONParser, MultiPartParser
from rest_framework.response import Response
from rest_framework.views import APIView

from api.schema import DetailSerializer
from claude_session import serializers as s
from claude_session.models import (
    CANCEL_REQUESTED,
    CANCELLED,
    FILE_MAX_BYTES,
    IN_FLIGHT,
    NEEDS_MACHINE,
    OFFLINE_AFTER_SECONDS,
    QUEUED,
    RUNNING,
    SCOPE_LISTED,
    SESSION_WAITING,
    TERMINAL,
    ClaudeCodeSession,
    ClaudeMachine,
    ClaudeTask,
    ClaudeTaskFile,
)
from claude_session.state import effective_status, failure_reason, machine_state
from household.tenancy import household_of, scoped

TAGS = ["claude"]
LIST_DEFAULT = 20
LIST_MAX = 100
# How long a session row stays on the status screen without a new hook post.
SESSION_SHOWN_HOURS = 24
SESSION_ENDED = "ended"
LISTED_REFUSAL = (
    "Nothing was sent. The {name} returns summaries only, never files (ruling 5)."
)


def _detail(text: str, code: int) -> Response:
    return Response({"detail": text}, status=code)


def _refused(serializer) -> Response:
    return _detail(f"Nothing was changed. {serializer.errors}", http.HTTP_400_BAD_REQUEST)


def _machine_named(request, name: str) -> ClaudeMachine | None:
    return scoped(ClaudeMachine, request).filter(name__iexact=name.strip()).first()


def _online_machines(request, now) -> list[ClaudeMachine]:
    cutoff = now - datetime.timedelta(seconds=OFFLINE_AFTER_SECONDS)
    return list(scoped(ClaudeMachine, request).filter(last_seen_at__gte=cutoff).order_by("name"))


def _task(request, task_id: int) -> ClaudeTask | None:
    return scoped(ClaudeTask, request).select_related("machine").filter(pk=task_id).first()


def _no_task(task_id: int) -> Response:
    return _detail(f"There is no task {task_id} in this household.", http.HTTP_404_NOT_FOUND)


def _file_row(row: ClaudeTaskFile) -> dict:
    return {
        "id": row.id,
        "name": row.name,
        "size": row.size,
        "uploaded": row.uploaded_at is not None,
        "requested": row.requested_at is not None,
    }


def task_row(task: ClaudeTask, now) -> dict:
    """The task shape every GET returns. A failure computed at read time puts
    its reason, in words, where a worker's summary would be."""
    reason = failure_reason(task, now)
    files = sorted(task.files.all(), key=lambda f: f.id)
    return {
        "id": task.id,
        "request": task.request,
        "machine": task.machine.name if task.machine else None,
        "status": effective_status(task, now),
        "summary": task.summary if task.summary else reason,
        "question": task.question,
        "created_at": task.created_at,
        "finished_at": task.finished_at,
        "files": [_file_row(f) for f in files],
        "parent": task.parent_id,
        "origin": task.origin,
        "deliver": task.deliver,
        "progress": task.progress,
        "progress_at": task.progress_at,
    }


def _machine_ref(machine: ClaudeMachine | None, now) -> dict | None:
    if machine is None:
        return None
    return {
        "name": machine.name,
        "state": machine_state(machine, now),
        "last_seen_at": machine.last_seen_at,
    }


class TaskListCreateView(APIView):
    parser_classes = [JSONParser]

    @extend_schema(
        operation_id="api_claude_tasks_list",
        tags=TAGS,
        parameters=[
            OpenApiParameter(
                "limit", int, description=f"Default {LIST_DEFAULT}, at most {LIST_MAX}."
            ),
            OpenApiParameter("status", str, description="One status, as computed at read time."),
            OpenApiParameter("machine", str, description="A machine's spoken name."),
        ],
        responses={200: s.ClaudeTaskRowSerializer(many=True)},
    )
    def get(self, request):
        now = timezone.now()
        try:
            limit = int(request.query_params.get("limit", LIST_DEFAULT))
        except ValueError:
            return _detail("limit must be a whole number.", http.HTTP_400_BAD_REQUEST)
        limit = max(1, min(limit, LIST_MAX))
        wanted_status = request.query_params.get("status")
        rows = (
            scoped(ClaudeTask, request)
            .select_related("machine")
            .prefetch_related("files")
            .order_by("-created_at", "-id")
        )
        machine_name = request.query_params.get("machine")
        if machine_name:
            rows = rows.filter(machine__name__iexact=machine_name.strip())
        out = []
        # The status filter is applied after the read-time computation, so a
        # task the clock has failed answers `?status=failed`, not `running`.
        for task in rows.iterator(chunk_size=200):
            row = task_row(task, now)
            if wanted_status and row["status"] != wanted_status:
                continue
            out.append(row)
            if len(out) >= limit:
                break
        return Response(out)

    @extend_schema(
        operation_id="api_claude_tasks_create",
        tags=TAGS,
        request=s.ClaudeTaskCreateSerializer,
        responses={
            201: OpenApiResponse(
                response=s.ClaudeTaskCreatedSerializer,
                description="queued, or needs_machine when unnamed and more than one is online.",
            ),
            400: OpenApiResponse(response=DetailSerializer, description="Refused, nothing stored."),
        },
    )
    def post(self, request):
        household = household_of(request)
        serializer = s.ClaudeTaskCreateSerializer(data=request.data)
        if not serializer.is_valid():
            return _refused(serializer)
        data = serializer.validated_data
        now = timezone.now()

        parent = None
        if data["parent"] is not None:
            parent = _task(request, data["parent"])
            if parent is None:
                return _detail(
                    f"Nothing was sent. There is no task {data['parent']} to follow up.",
                    http.HTTP_400_BAD_REQUEST,
                )

        machine = None
        status = QUEUED
        if data["machine"]:
            machine = _machine_named(request, data["machine"])
            if machine is None:
                known = sorted(scoped(ClaudeMachine, request).values_list("name", flat=True))
                listing = ", ".join(known) if known else "none yet"
                return _detail(
                    f"Nothing was sent. No machine has that name here. Known machines: {listing}.",
                    http.HTTP_400_BAD_REQUEST,
                )
        elif parent is not None and parent.machine is not None:
            # Ruling 10: a follow-up reaches the main session that did the task.
            machine = parent.machine
        else:
            online = _online_machines(request, now)
            if len(online) == 1:
                machine = online[0]
            elif len(online) > 1:
                status = NEEDS_MACHINE

        if data["deliver"] and machine is not None and machine.scope == SCOPE_LISTED:
            return _detail(
                LISTED_REFUSAL.format(name=machine.name), http.HTTP_400_BAD_REQUEST
            )

        task = ClaudeTask.objects.create(
            household=household,
            user=request.user,
            machine=machine,
            parent=parent,
            origin=data["origin"],
            deliver=data["deliver"],
            request=data["request"],
            status=status,
        )
        return Response(
            {"id": task.id, "status": task.status, "machine": _machine_ref(machine, now)},
            status=http.HTTP_201_CREATED,
        )


class TaskDetailView(APIView):
    @extend_schema(
        operation_id="api_claude_tasks_retrieve",
        tags=TAGS,
        responses={200: s.ClaudeTaskRowSerializer, 404: DetailSerializer},
    )
    def get(self, request, task_id: int):
        task = _task(request, task_id)
        if task is None:
            return _no_task(task_id)
        return Response(task_row(task, timezone.now()))


class TaskCancelView(APIView):
    @extend_schema(
        operation_id="api_claude_tasks_cancel",
        tags=TAGS,
        request=None,
        responses={
            200: s.ClaudeCancelledSerializer,
            404: DetailSerializer,
            409: OpenApiResponse(response=DetailSerializer, description="Already finished."),
        },
    )
    def post(self, request, task_id: int):
        now = timezone.now()
        with transaction.atomic():
            task = (
                scoped(ClaudeTask, request).select_for_update().filter(pk=task_id).first()
            )
            if task is None:
                return _no_task(task_id)
            current = effective_status(task, now)
            if current in TERMINAL:
                return _detail(
                    f"Nothing was cancelled. Task {task.id} is already {current}.",
                    http.HTTP_409_CONFLICT,
                )
            if current in (QUEUED, NEEDS_MACHINE):
                task.status = CANCELLED
                task.finished_at = now
                detail = "Cancelled before any machine started it."
            elif current == RUNNING:
                task.status = CANCEL_REQUESTED
                detail = "Cancel requested; the machine stops it on its next heartbeat."
            else:
                detail = "Cancel was already requested."
            task.save(update_fields=["status", "finished_at"])
        return Response({"id": task.id, "status": task.status, "detail": detail})


class TaskSendFileView(APIView):
    parser_classes = [JSONParser]

    @extend_schema(
        operation_id="api_claude_tasks_send_file",
        tags=TAGS,
        request=s.ClaudeSendFileSerializer,
        responses={
            200: s.ClaudeSendFileResultSerializer,
            400: DetailSerializer,
            403: OpenApiResponse(response=DetailSerializer, description="A listed machine."),
            404: DetailSerializer,
        },
    )
    def post(self, request, task_id: int):
        serializer = s.ClaudeSendFileSerializer(data=request.data)
        if not serializer.is_valid():
            return _refused(serializer)
        task = _task(request, task_id)
        if task is None:
            return _no_task(task_id)
        if task.machine is not None and task.machine.scope == SCOPE_LISTED:
            return _detail(LISTED_REFUSAL.format(name=task.machine.name), http.HTTP_403_FORBIDDEN)
        file_id = serializer.validated_data["file_id"]
        row = scoped(ClaudeTaskFile, request).filter(task=task, pk=file_id).first()
        if row is None:
            return _detail(
                f"Nothing was requested. Task {task.id} has no file {file_id}.",
                http.HTTP_404_NOT_FOUND,
            )
        if row.uploaded_at is not None:
            detail = "It is already on the engine."
        else:
            if row.requested_at is None:
                row.requested_at = timezone.now()
                row.save(update_fields=["requested_at"])
            detail = "Requested; the machine uploads it on its next heartbeat."
        return Response(
            {
                "file_id": row.id,
                "requested": True,
                "uploaded": row.uploaded_at is not None,
                "detail": detail,
            }
        )


class StatusView(APIView):
    @extend_schema(
        operation_id="api_claude_status", tags=TAGS, responses={200: s.ClaudeStatusSerializer}
    )
    def get(self, request):
        now = timezone.now()
        machines = list(scoped(ClaudeMachine, request).order_by("name"))
        in_flight = (
            scoped(ClaudeTask, request)
            .filter(status__in=IN_FLIGHT, machine__isnull=False)
            .select_related("machine")
            .order_by("id")
        )
        running: dict = {m.id: [] for m in machines}
        for task in in_flight:
            if failure_reason(task, now) is None:
                running.setdefault(task.machine_id, []).append(task)
        shown_since = now - datetime.timedelta(hours=SESSION_SHOWN_HOURS)
        sessions = (
            scoped(ClaudeCodeSession, request)
            .filter(last_seen_at__gte=shown_since)
            .exclude(state=SESSION_ENDED)
            .select_related("machine")
            .order_by("machine__name", "-last_seen_at")
        )
        # Waiting on Kevin first; the order inside each group is kept.
        sessions = sorted(sessions, key=lambda row: row.state != SESSION_WAITING)
        return Response(
            {
                "machines": [
                    {
                        "name": m.name,
                        "scope": m.scope,
                        "state": machine_state(m, now),
                        "last_seen_at": m.last_seen_at,
                        "running": [t.id for t in running.get(m.id, [])],
                        "progress": [
                            {"task": t.id, "progress": t.progress, "progress_at": t.progress_at}
                            for t in running.get(m.id, [])
                        ],
                    }
                    for m in machines
                ],
                "sessions": [
                    {
                        "machine": row.machine.name,
                        "project": row.project,
                        "state": row.state,
                        "last_prompt_summary": row.last_prompt_summary,
                        "last_seen_at": row.last_seen_at,
                    }
                    for row in sessions
                ],
            }
        )


def _touch_machine(request, data: dict, now) -> ClaudeMachine:
    household = household_of(request)
    machine = _machine_named(request, data["name"])
    if machine is None:
        machine = ClaudeMachine(household=household, name=data["name"].strip())
    machine.hostname = data.get("hostname", "")
    machine.scope = data["scope"]
    machine.reported_state = data["state"]
    machine.claude_version = data.get("claude_version", "")
    machine.last_seen_at = now
    machine.save()
    return machine


def _store_progress(tasks, progress: dict, now) -> None:
    """Each running task's current step, as the worker reported it. Only tasks
    in flight on THIS machine take it; an unknown or finished id is ignored."""
    for key, text in progress.items():
        try:
            task_id = int(key)
        except (TypeError, ValueError):
            continue
        tasks.filter(pk=task_id, status__in=IN_FLIGHT).update(
            progress=text or None, progress_at=now
        )


class HeartbeatView(APIView):
    parser_classes = [JSONParser]

    @extend_schema(
        operation_id="api_claude_machines_heartbeat",
        tags=TAGS,
        request=s.ClaudeHeartbeatSerializer,
        responses={200: s.ClaudeHeartbeatResultSerializer, 400: DetailSerializer},
    )
    def post(self, request):
        serializer = s.ClaudeHeartbeatSerializer(data=request.data)
        if not serializer.is_valid():
            return _refused(serializer)
        now = timezone.now()
        machine = _touch_machine(request, serializer.validated_data, now)
        tasks = scoped(ClaudeTask, request).filter(machine=machine)
        _store_progress(tasks, serializer.validated_data["progress"], now)
        cancel = list(
            tasks.filter(status=CANCEL_REQUESTED).order_by("id").values_list("id", flat=True)
        )
        files = []
        if machine.scope != SCOPE_LISTED:
            files = [
                {"task": row.task_id, "file_id": row.id, "path": row.path}
                for row in scoped(ClaudeTaskFile, request)
                .filter(task__machine=machine, requested_at__isnull=False, uploaded_at__isnull=True)
                .order_by("id")
            ]
        return Response({"cancel_requested": cancel, "files_requested": files})


class ClaimView(APIView):
    parser_classes = [JSONParser]

    @extend_schema(
        operation_id="api_claude_tasks_claim",
        tags=TAGS,
        request=s.ClaudeClaimSerializer,
        responses={
            200: s.ClaudeTaskRowSerializer(many=True),
            204: OpenApiResponse(description="Nothing queued for this machine."),
            400: DetailSerializer,
            404: OpenApiResponse(response=DetailSerializer, description="No heartbeat yet."),
        },
    )
    def post(self, request):
        serializer = s.ClaudeClaimSerializer(data=request.data)
        if not serializer.is_valid():
            return _refused(serializer)
        now = timezone.now()
        machine = _machine_named(request, serializer.validated_data["name"])
        if machine is None:
            return _detail(
                "Nothing was claimed. This machine has not sent a heartbeat yet.",
                http.HTTP_404_NOT_FOUND,
            )
        # A claim is proof of life, like a heartbeat.
        machine.last_seen_at = now
        machine.save(update_fields=["last_seen_at"])
        only_one_online = [m.id for m in _online_machines(request, now)] == [machine.id]
        with transaction.atomic():
            queued = scoped(ClaudeTask, request).filter(status=QUEUED)
            mine = queued.filter(machine=machine)
            if only_one_online:
                mine = queued.filter(machine=machine) | queued.filter(machine__isnull=True)
            claimed = list(mine.select_for_update(skip_locked=True).order_by("id"))
            for task in claimed:
                task.machine = machine
                task.status = RUNNING
                task.started_at = now
                task.save(update_fields=["machine", "status", "started_at"])
        if not claimed:
            return Response(status=http.HTTP_204_NO_CONTENT)
        return Response([task_row(task, now) for task in claimed])


class FinishView(APIView):
    parser_classes = [JSONParser]

    @extend_schema(
        operation_id="api_claude_tasks_finish",
        tags=TAGS,
        request=s.ClaudeFinishSerializer,
        responses={
            200: s.ClaudeFinishResultSerializer,
            400: DetailSerializer,
            403: OpenApiResponse(response=DetailSerializer, description="A listed machine."),
            404: DetailSerializer,
            409: OpenApiResponse(response=DetailSerializer, description="Not running."),
        },
    )
    def post(self, request, task_id: int):
        household = household_of(request)
        serializer = s.ClaudeFinishSerializer(data=request.data)
        if not serializer.is_valid():
            return _refused(serializer)
        data = serializer.validated_data
        now = timezone.now()
        with transaction.atomic():
            task = (
                scoped(ClaudeTask, request)
                .select_for_update(of=("self",))
                .select_related("machine")
                .filter(pk=task_id)
                .first()
            )
            if task is None:
                return _no_task(task_id)
            current = effective_status(task, now)
            if current not in IN_FLIGHT:
                return _detail(
                    f"Nothing was recorded. Task {task.id} is {current}, not running.",
                    http.HTTP_409_CONFLICT,
                )
            if data["status"] == CANCELLED and task.status != CANCEL_REQUESTED:
                return _detail(
                    "Nothing was recorded. Only a task whose cancel was requested may finish "
                    "as cancelled.",
                    http.HTTP_400_BAD_REQUEST,
                )
            listed = task.machine is not None and task.machine.scope == SCOPE_LISTED
            task.status = data["status"]
            task.summary = data["summary"] or None
            task.question = data["question"] or None
            task.turns = data["turns"]
            task.duration_ms = data["duration_ms"]
            task.finished_at = now
            task.progress = None
            task.progress_at = None
            task.save()
            notes = []
            if listed and data["found"]:
                notes.append("Found files were not kept: this machine returns summaries only.")
            elif data["found"]:
                ClaudeTaskFile.objects.bulk_create(
                    ClaudeTaskFile(
                        household=household, task=task, path=f["path"], name=f["name"],
                        size=f["size"],
                    )
                    for f in data["found"]
                )
        files = list(task.files.order_by("id"))
        return Response(
            {
                "id": task.id,
                "status": task.status,
                "files": [_file_row(f) for f in files],
                "detail": " ".join(["Recorded.", *notes]),
            }
        )


class FileUploadView(APIView):
    parser_classes = [MultiPartParser, FormParser]

    @extend_schema(
        operation_id="api_claude_tasks_files_upload",
        tags=TAGS,
        request={"multipart/form-data": s.ClaudeFileUploadSerializer},
        responses={
            201: s.ClaudeUploadResultSerializer,
            200: OpenApiResponse(
                response=s.ClaudeUploadResultSerializer, description="Already stored."
            ),
            400: DetailSerializer,
            403: OpenApiResponse(response=DetailSerializer, description="A listed machine."),
            404: DetailSerializer,
            409: OpenApiResponse(response=DetailSerializer, description="Not requested."),
            413: OpenApiResponse(response=DetailSerializer, description="Over 25 MB."),
        },
    )
    def post(self, request, task_id: int):
        serializer = s.ClaudeFileUploadSerializer(data=request.data)
        if not serializer.is_valid():
            return _refused(serializer)
        data = serializer.validated_data
        task = _task(request, task_id)
        if task is None:
            return _no_task(task_id)
        if task.machine is not None and task.machine.scope == SCOPE_LISTED:
            return _detail(LISTED_REFUSAL.format(name=task.machine.name), http.HTTP_403_FORBIDDEN)
        row = scoped(ClaudeTaskFile, request).filter(task=task, pk=data["file_id"]).first()
        if row is None:
            return _detail(
                f"Nothing was stored. Task {task.id} has no file {data['file_id']}.",
                http.HTTP_404_NOT_FOUND,
            )
        if row.uploaded_at is not None:
            return Response(
                {"file_id": row.id, "size": row.size or 0, "detail": "Already stored."}
            )
        if row.requested_at is None:
            return _detail(
                "Nothing was stored. Kevin has not asked for this file.", http.HTTP_409_CONFLICT
            )
        upload = data["file"]
        if upload.size > FILE_MAX_BYTES:
            return _detail(
                "Nothing was stored. A task file is capped at "
                f"{FILE_MAX_BYTES // (1024 * 1024)} MB.",
                http.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            )
        blob = upload.read()
        row.content = blob
        row.size = len(blob)
        row.content_type = (getattr(upload, "content_type", "") or "")[:200]
        row.uploaded_at = timezone.now()
        row.save(update_fields=["content", "size", "content_type", "uploaded_at"])
        return Response(
            {"file_id": row.id, "size": row.size, "detail": "Stored."}, status=http.HTTP_201_CREATED
        )


class FileDownloadView(APIView):
    @extend_schema(
        operation_id="api_claude_tasks_files_retrieve",
        tags=TAGS,
        responses={
            (200, "application/octet-stream"): OpenApiResponse(
                response=OpenApiTypes.BINARY, description="The file's bytes."
            ),
            404: DetailSerializer,
        },
    )
    def get(self, request, task_id: int, file_id: int):
        row = (
            scoped(ClaudeTaskFile, request)
            .filter(task_id=task_id, pk=file_id, uploaded_at__isnull=False)
            .first()
        )
        if row is None:
            return _detail(
                f"Task {task_id} has no uploaded file {file_id}.", http.HTTP_404_NOT_FOUND
            )
        response = HttpResponse(
            bytes(row.content), content_type=row.content_type or "application/octet-stream"
        )
        safe = "".join(c for c in row.name if c.isprintable() and c not in '"\\') or "file"
        response["Content-Disposition"] = f'attachment; filename="{safe}"'
        return response


class SessionReportView(APIView):
    parser_classes = [JSONParser]

    @extend_schema(
        operation_id="api_claude_sessions_create",
        tags=TAGS,
        request=s.ClaudeSessionReportSerializer,
        responses={200: s.ClaudeSessionStoredSerializer, 400: DetailSerializer},
    )
    def post(self, request):
        household = household_of(request)
        serializer = s.ClaudeSessionReportSerializer(data=request.data)
        if not serializer.is_valid():
            return _refused(serializer)
        data = serializer.validated_data
        now = timezone.now()
        machine = _machine_named(request, data["machine"])
        if machine is None:
            # A hook can fire before the worker's first heartbeat. Until one
            # says otherwise the machine is `listed`, the safe default.
            machine = ClaudeMachine.objects.create(
                household=household, name=data["machine"].strip(), scope=SCOPE_LISTED
            )
        keep_summary = machine.scope != SCOPE_LISTED
        ClaudeCodeSession.objects.update_or_create(
            household=household,
            machine=machine,
            session_id=data["session_id"],
            defaults={
                "project": data["project"],
                "state": data["state"],
                "last_prompt_summary": data["last_prompt_summary"] if keep_summary else None,
                "reported_at": data["at"],
                "last_seen_at": now,
            },
        )
        return Response(
            {
                "machine": machine.name,
                "session_id": data["session_id"],
                "state": data["state"],
                "summary_kept": keep_summary and data["last_prompt_summary"] is not None,
            }
        )
