"""What a head-unit token may reach (T3b): named lists, default deny.

**Routes.** A head-unit token is accepted only by `HeadUnitTokenAuthentication`, and that class
is listed only on the views below. Every other view authenticates `Authorization: Token <key>`
with `DeviceTokenAuthentication` alone, which finds no device token for a head-unit key and
answers 401 "Invalid token." So the default is deny by construction, and
`tests/test_headunit_signin.py` walks every URL pattern to prove it. Inside the class the
(route name, method) pair is checked again against `ROUTES`, so listing the class on a view
by mistake opens nothing.

| Route name | Method | Why |
|---|---|---|
| `agents-turn` | POST | the head unit's voice turn (dark behind `LEGION_AGENTS_TURN`) |
| `agents-turn-cancel` | POST | the stop button of that turn (same switch) |
| `device-logs-upload` | POST | its own drive log; GET (the household's list) is refused |
| `fleet-obd-samples-batch` | POST | append-only OBD telemetry; no read, no edit |

**Tools.** Inside a turn, a tool runs as the member, either through
`engine_mcp.dispatch.call_route` (forced authentication: the class above never runs, and
`permissions.HeadUnitScope` inside `IsHouseholdMember` refuses the credential instead) or
straight on the ORM, where no route check can reach. The fence for a turn is therefore the tool
list: `TOOLS` is every tool a head-unit turn is offered and may call (`agents.legion.gateway`),
read only, the car and the road. No money, no memory, no mail, no calendar, no records, no
writes.
"""

from __future__ import annotations

from django.urls import Resolver404, resolve

ROUTES: frozenset[tuple[str, str]] = frozenset(
    {
        ("agents-turn", "POST"),
        ("agents-turn-cancel", "POST"),
        ("device-logs-upload", "POST"),
        ("fleet-obd-samples-batch", "POST"),
    }
)

TOOLS: frozenset[str] = frozenset({"next_service", "vehicle_recalls", "check_conditions"})

# Review B2. The reasons a head unit's own drive log is sent under (`DriveLogUploader.kt`).
# Never `conversation` (what the Claude worker lists and renders), nor the phone's `logcat` and
# `crash`: a unit token cannot plant text in what another reader trusts. A unit's upload is stored
# with `uploader_kind = head_unit` and the worker-facing reader (`devicelogs.records`) never
# returns it; `manage.py device_logs` (the operator) still does.
LOG_REASONS: frozenset[str] = frozenset({"drive_end", "startup", "manual", "periodic"})
# Its own size cap, decompressed: the unit seals a batch at about 1 MB.
LOG_MAX_RAW_BYTES = 2 * 1024 * 1024

# The `client` every head-unit turn is recorded under; a head-unit token reaches no other
# conversation of its member's (`agents.legion.turn`).
TURN_CLIENT = "headunit"


def route_name(request) -> str | None:
    match = getattr(getattr(request, "_request", request), "resolver_match", None)
    if match is None:
        try:
            match = resolve(request.path_info)
        except Resolver404:
            return None
    return match.url_name


def route_allowed(request) -> bool:
    """May a head-unit token make this request?"""
    return (route_name(request), (request.method or "").upper()) in ROUTES


def is_headunit(request) -> bool:
    """True when this request authenticated with a head-unit token."""
    from headunit_signin.models import HeadUnitCredential

    return isinstance(getattr(request, "auth", None), HeadUnitCredential)
