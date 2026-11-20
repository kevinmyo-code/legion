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


def live_vehicles(request):
    """The household's live vehicles, the ones a unit may be bound to (shared rows: `scoped`)."""
    from household.tenancy import scoped
    from legacy.models.fleet import Vehicle

    return scoped(Vehicle, request).filter(deleted_at__isnull=True)


def obd_refusal(request, vehicle_ids) -> str | None:
    """Review B3: a unit token appends samples for its own vehicle only. None for anyone else."""
    if not is_headunit(request):
        return None
    bound = request.auth.vehicle_id
    if bound is None:
        return (
            "Nothing was written. This head unit is bound to no vehicle, so it uploads no "
            "telemetry. Pair it again and choose its vehicle."
        )
    if set(vehicle_ids) != {bound}:
        return (
            "Nothing was written. A head unit uploads samples only for the vehicle it is bound "
            "to; the whole batch was refused."
        )
    return None


CONDITIONS_BY_POINT = (
    "Nothing was read. From the car, conditions are checked at a latitude and longitude only, "
    "never at home or a saved place by name."
)
NO_BOUND_VEHICLE = (
    "Nothing was read. This head unit is bound to no vehicle on file, so it reads none. The "
    "member can pair it again and choose its vehicle."
)


def tool_arguments(request, name: str, args: dict) -> tuple[dict, str | None]:
    """Review B4: a head-unit turn's arguments, narrowed, or the refusal in words.

    - `check_conditions`: a point only. By name ("home" is its default) it would tell whoever holds
      the unit roughly where the member lives, and which places he saved.
    - `next_service`, `vehicle_recalls`: the unit's own vehicle only, whatever the model asked for,
      so the turn never lists the household's other cars.
    """
    if name == "check_conditions":
        if args.get("place") or args.get("lat") is None or args.get("lon") is None:
            return args, CONDITIONS_BY_POINT
        return args, None
    if name in ("next_service", "vehicle_recalls"):
        bound = request.auth.vehicle_id
        if bound is None or not live_vehicles(request).filter(pk=bound, archived=False).exists():
            return args, NO_BOUND_VEHICLE
        return {**args, "vehicle": str(bound)}, None
    return args, None


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
