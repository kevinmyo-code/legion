"""`GET /api/device-logs/conversations/`: the conversation page's one read (#160).

    GET /api/device-logs/conversations/?device=web&limit=10

The caller's own `reason=conversation` batches, each cut to his own lines (`devicelogs.own_lines`)
and rendered with `devicelogs.render.render` (the timeline `manage.py device_logs --render`
prints), grouped by device, newest first. One call instead of a list plus a render per row, so a
page paints from a single request.

**Data shape.** `devices[]`, ordered by each device's newest batch; each has `device_label`,
`total` (how many conversation batches the engine holds for it, so the page can say "showing 10 of
40") and `batches[]` newest first by `received_at` (the engine's clock; the device's is untrusted).
A batch carries `id`, `received_at`, `header`, `lines`, `run_id` and, when its stored body cannot
be read, `readable: false` with `problem` in words and no lines: an unreadable batch is named, never
dropped, so a gap on the page is never mistaken for a quiet day.

**Scoping is the list route's** (`records._own`): the caller's own rows in their household
(`household.tenancy.scoped`, ADR 0045), never another member's, never a head unit's upload, never a
recruiter visit's record (no household).

**Only the person's own lines are shown** (CLAUDE.md section 7, `devicelogs.own_lines`): never an
assistant line, a tool's arguments or a tool's reply, in any session. The engine does not rely on
the device having redacted anything, and keeps no taint state, so a batch that opens mid-session
behaves like any other.

**A session split across two batches is not stitched.** Each stored batch is one block with its own
header. `run_id` rides along so a later change can join batches of one process run without a
second shape; per-turn saves (#450) add rows, which appear here as more, smaller batches.

Nothing here logs a record's text.
"""

from __future__ import annotations

import re

from drf_spectacular.utils import OpenApiParameter, OpenApiResponse, extend_schema
from rest_framework import serializers
from rest_framework.response import Response
from rest_framework.views import APIView

from api.schema import DetailSerializer
from devicelogs.models import REASON_CONVERSATION
from devicelogs.own_lines import own_lines
from devicelogs.records import TAGS, _bad, _own
from devicelogs.render import batch_header, batch_text, render

_BREAKS = re.compile(r"[^\S ]")  # every whitespace character but a plain space
NOTHING_OF_YOURS = "(nothing of yours in this batch)"
DEFAULT_PER_DEVICE = 10
MAX_PER_DEVICE = 30
# Rendering inflates and parses each body, so the whole response is bounded too.
MAX_BATCHES = 60


class ConversationBatchSerializer(serializers.Serializer):
    id = serializers.UUIDField()
    received_at = serializers.DateTimeField(help_text="The engine's clock.")
    header = serializers.CharField(help_text="The header line `manage.py device_logs` prints.")
    lines = serializers.ListField(
        child=serializers.CharField(), help_text="The rendered timeline. Empty when unreadable."
    )
    run_id = serializers.CharField(help_text="The phone's process run; blank elsewhere.")
    readable = serializers.BooleanField()
    problem = serializers.CharField(allow_null=True, help_text="Why it is unreadable, in words.")


class ConversationDeviceSerializer(serializers.Serializer):
    device_label = serializers.CharField()
    total = serializers.IntegerField(help_text="Conversation batches held for this device.")
    batches = ConversationBatchSerializer(many=True)


class ConversationsSerializer(serializers.Serializer):
    devices = ConversationDeviceSerializer(many=True)


def _unreadable(row, reason: str) -> tuple[str, list, str]:
    header = f"=== batch {row.id} | {row.device_label} | {row.reason}"
    return header, [], f"could not be read: {reason}"


def _batch(row) -> dict:
    """One batch, cut to the person's own lines. Any failure names this batch only."""
    try:
        # One element is one visual line: a decoded newline or separator inside a field must
        # not start another (a forged `USER:` line), so every one becomes a space.
        lines = [_BREAKS.sub(" ", line) for line in render(own_lines(batch_text(row)))]
        lines = lines or [NOTHING_OF_YOURS]
        # The response is UTF-8 JSON: a lone surrogate from a stored escape would fail the whole
        # page there, so it fails this batch here.
        "".join(lines).encode("utf-8")
        header = batch_header(row, len(lines))
        problem = None
    except ValueError as exc:  # already in words (bad gzip, too large)
        header, lines, problem = _unreadable(row, str(exc))
    except Exception as exc:  # noqa: BLE001 - one odd batch must not take the page down
        # Only the type: an exception's message can quote the stored text.
        header, lines, problem = _unreadable(row, type(exc).__name__)
    return {
        "id": row.id,
        "received_at": row.received_at,
        "header": header,
        "lines": lines,
        "run_id": row.run_id,
        "readable": problem is None,
        "problem": problem,
    }


class ConversationsView(APIView):
    @extend_schema(
        operation_id="api_device_logs_conversations",
        tags=TAGS,
        parameters=[
            OpenApiParameter("device", str, description="Keep only this device label."),
            OpenApiParameter(
                "limit",
                int,
                description=f"Batches per device (default {DEFAULT_PER_DEVICE}, "
                f"max {MAX_PER_DEVICE}).",
            ),
        ],
        responses={
            200: OpenApiResponse(
                response=ConversationsSerializer, description="Newest first, per device."
            ),
            400: OpenApiResponse(response=DetailSerializer, description="A filter was malformed."),
        },
    )
    def get(self, request):
        try:
            limit = int(request.query_params.get("limit", DEFAULT_PER_DEVICE))
        except ValueError:
            return _bad("limit must be a whole number.")
        limit = max(1, min(limit, MAX_PER_DEVICE))
        rows = _own(request).filter(reason=REASON_CONVERSATION)
        device = request.query_params.get("device")
        if device:
            rows = rows.filter(device_label__iexact=device)
        # Newest first overall; each device keeps its first `limit`, and the devices come out in
        # the order their newest batch appeared. Bodies are fetched only for rows that are kept.
        light = rows.order_by("-received_at", "-id").only("id", "device_label")
        kept: dict[str, list] = {}
        totals: dict[str, int] = {}
        for row in light:
            totals[row.device_label] = totals.get(row.device_label, 0) + 1
            bucket = kept.setdefault(row.device_label, [])
            if len(bucket) < limit and sum(map(len, kept.values())) < MAX_BATCHES:
                bucket.append(row.id)
        wanted = [i for ids in kept.values() for i in ids]
        by_id = {row.id: row for row in rows.filter(id__in=wanted)}
        return Response(
            {
                "devices": [
                    {
                        "device_label": label,
                        "total": totals[label],
                        "batches": [_batch(by_id[i]) for i in ids if i in by_id],
                    }
                    for label, ids in kept.items()
                ]
            }
        )
