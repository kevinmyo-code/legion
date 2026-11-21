"""The relay, engine side (ADR 0098; engine-brain ticket 04, slice E2): which limb tools a turn is
offered, and whether one call may go to the limb.

**Which tools.** The tools of the limb that MADE the turn: the `Limb` row of the request's own
device token (`limb_of`). A session (the web), a head unit's token and the late-answer runner's
rebuilt request have no limb, so they are offered none and nothing here queries for them. From its
manifest the engine drops, before the model ever sees them:
- every mail-class tool (`is_mail_class`): mail is never relayed by the engine until ticket 05;
  a folded tool keeps its declaration, but the mail values of its enums are removed;
- every name an engine tool already has (the engine's own tool wins).

**Whether a call may go** (`decide`), in order:
1. mail class (the tool, a declared mail action, or any argument naming a mail tool): refused;
2. a write of a tool whose earlier write in this turn timed out: refused (it may still land; a
   retry would be the double run the wire contract forbids, section 8);
3. a write in a read-only spec: refused;
4. a write or an actuating call in a turn marked tainted (ADR 0090, ADR 0098): refused, unless the
   owner's ruling K1 names the tool in `AFTER_OUTSIDE_TEXT` (open: empty, the strict default);
5. an actuating call, or one the spec lists, with `confirm: engine`: held for his yes in a later
   turn (`guards.hold_for_yes`). `confirm: limb` skips this: the limb runs its own gate;
6. outside text already in the turn (`TurnState.outside`): never paused for, whatever the call (plan
   1119 section 5 and K2): saving the pause would save that text. A tool K1 allows becomes a device
   action the limb runs once the reply ends (`done.device_actions`); anything else is refused.
7. otherwise it goes: a read-through call marks the turn tainted first and is saved (fail closed),
   and pauses alone (`runtime.tools.RELAY_ALONE`).
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass, field

from agents.legion import guards, redact
from agents.legion.guards import Verdict
from agents.models import Limb
from assistant import read_through
from household.models import DeviceToken
from household.tenancy import household_of

# Mail and the phone tools whose results carry mail text (`ask_mail`, the brief's `get_sitrep`
# with its mail sections, parcel and flight status read from mail): never relayed by the engine
# until ticket 05 (plan 1119 section 5). Matched against a call's name AND every string argument,
# so a folded call (`look_up` with `domain: ask_mail`) is caught whatever the manifest declares.
MAIL_CLASS = frozenset(
    {
        "ask_mail",
        "search_mail",
        "read_mail",
        "email",
        "gmail",
        "get_sitrep",
        "track_package",
        "flight_status",
    }
)

# DECISION K1 (open, the owner's; plan 1119 "Decisions for Kevin"): the limb tools that may still
# act in a turn that read outside text, as the phone allows navigation, timers, music, volume and
# screens today (`ReadThroughWriteGuard.ALLOWED_AFTER_READ`). Empty is the strict default (ADR 0098:
# an actuating tool is never callable in a tainted turn). A name here never makes the turn pause
# with outside text in it (K2): once outside text was read, the call runs on the limb as a device
# action after the reply (`done.device_actions`, with the limb's own guard), never mid-turn. His
# ruling is a change to this one line; `tests/test_agents_relay.py` covers both settings.
AFTER_OUTSIDE_TEXT: frozenset[str] = frozenset()

# The engine waits this long past a call's `deadline_ms` before it calls the call timed out.
GRACE_MS = 5_000

MAIL_REFUSED = (
    "Nothing was read: {name} would read mail through the device, and mail is not read that way "
    "here yet. Say so plainly."
)
TIMED_OUT_REFUSED = (
    "Nothing was sent: an earlier {name} in this turn did not finish in time and may still go "
    "through, so it is not tried again now. Say so; they can check and ask again."
)
OUTSIDE_REFUSED = (
    "Nothing was done: {name} runs on the device, and this turn already read outside text, which "
    "is never held while the device works. Say so; they can ask again in their next message."
)
AFTER_TURN_WORDS = (
    "Not done yet: {name} will run on the device after this reply. Do not say it happened; say it "
    "is on its way."
)
TIMED_OUT_WORDS = (
    "That did not finish on the device in time. Nothing is claimed: it may or may not have "
    "happened. Say exactly that."
)


@dataclass(frozen=True)
class LimbTool:
    """One tool of a limb's manifest (`limbs.LimbToolSerializer`), with E1 manifests' defaults."""

    name: str
    description: str = ""
    parameters: dict = field(default_factory=dict)
    writes: bool = False
    read_through: bool = False
    actuates: bool = False
    needs: tuple[str, ...] = ()
    confirm: str = "engine"
    read_through_when: dict = field(default_factory=dict)
    mail: bool = False
    mail_when: dict = field(default_factory=dict)
    deadline_ms: int = 20_000

    @classmethod
    def from_manifest(cls, raw: dict) -> LimbTool:
        return cls(
            name=raw["name"],
            description=raw.get("description") or "",
            parameters=dict(raw.get("parameters") or {}),
            writes=bool(raw.get("writes")),
            read_through=bool(raw.get("read_through")),
            actuates=bool(raw.get("actuates")),
            needs=tuple(raw.get("needs") or ()),
            confirm=raw.get("confirm") or "engine",
            read_through_when=dict(raw.get("read_through_when") or {}),
            mail=bool(raw.get("mail")),
            mail_when=dict(raw.get("mail_when") or {}),
            deadline_ms=int(raw.get("deadline_ms") or 20_000),
        )


def limb_of(request) -> Limb | None:
    """The limb that makes this request: its device token's row. None for anything else (a
    browser session, a head unit's token, a rebuilt request), with no query."""
    if not isinstance(getattr(request, "auth", None), DeviceToken):
        return None
    return Limb.objects.filter(
        household=household_of(request), owner_user=request.user, device=request.auth
    ).first()


def _matches(when: dict, args: dict) -> bool:
    return any(str((args or {}).get(key)) in set(values) for key, values in (when or {}).items())


def is_mail_class(tool: LimbTool, args: dict | None = None) -> bool:
    args = args or {}
    named = tool.name in MAIL_CLASS or redact.is_mail_tool(tool.name)
    passed = any(isinstance(v, str) and v in MAIL_CLASS for v in args.values())
    return tool.mail or named or passed or _matches(tool.mail_when, args)


def reads_outside(tool: LimbTool, args: dict) -> bool:
    """The call's result is outside text (ADR 0090): the tool's class, or a declared action."""
    return tool.read_through or _matches(tool.read_through_when, args)


def tools_of(limb: Limb | None, engine_names) -> dict[str, LimbTool]:
    if limb is None:
        return {}
    out = {}
    for raw in (limb.manifest or {}).get("tools") or []:
        tool = LimbTool.from_manifest(raw)
        if tool.name in engine_names or is_mail_class(tool):
            continue
        out[tool.name] = tool
    return out


def declaration(tool: LimbTool) -> dict:
    """The tool as the model is offered it, with every mail value removed from its enums."""
    return {
        "name": tool.name,
        "description": tool.description,
        "parameters": _without_mail(tool.parameters),
    }


def _without_mail(schema):
    if isinstance(schema, list):
        return [_without_mail(s) for s in schema]
    if not isinstance(schema, dict):
        return schema
    out = {k: _without_mail(v) for k, v in schema.items()}
    if isinstance(out.get("enum"), list):
        out["enum"] = [v for v in out["enum"] if v not in MAIL_CLASS]
    return out


@dataclass(frozen=True)
class Decision:
    """`go`: send the request; `after_turn`: a device action once the reply ends; else refused
    with `verdict`."""

    go: bool = False
    after_turn: bool = False
    verdict: Verdict | None = None


GO = Decision(go=True)


def _refused(words: str, guard: str) -> Decision:
    return Decision(verdict=Verdict(False, words, guard))


def decide(state, spec, tool: LimbTool, args: dict) -> Decision:
    """The module docstring's order. `state` is the turn's `TurnState`."""
    name = tool.name
    if is_mail_class(tool, args):
        return _refused(MAIL_REFUSED.format(name=name), "not_allowed")
    if tool.writes and name in (state.client_facts.get("relay_timed_out_writes") or []):
        return _refused(TIMED_OUT_REFUSED.format(name=name), "not_allowed")
    if tool.writes and not spec.writes_allowed:
        return _refused(guards.READ_ONLY.format(name=name), "not_allowed")
    acts = tool.writes or tool.actuates
    ruled = name in AFTER_OUTSIDE_TEXT
    if acts and state.tainted and not ruled:
        return _refused(read_through.WRITE_REFUSAL.format(verb="changed", name=name), "tainted")
    if tool.confirm == "engine" and (tool.actuates or (tool.writes and name in spec.confirm)):
        held = guards.hold_for_yes(state, name, args)
        if not held.allow:
            return Decision(verdict=held)
    if state.outside:
        if acts and ruled:
            return Decision(after_turn=True)
        return _refused(OUTSIDE_REFUSED.format(name=name), "tainted")
    return GO


def request(state, tool: LimbTool, call, tainted_before: bool) -> dict:
    """The `tool_request` event's data (wire section 8). `tainted` is the turn's mark as it stood
    before this call's own mark, so a read-through call does not refuse itself on the limb (the
    phone's `ReadThroughWriteGuard.refusal` takes the same)."""
    return {
        "turn_id": state.turn_id,
        "call_id": uuid.uuid4().hex,
        "name": tool.name,
        "args": dict(call.args),
        "writes": tool.writes,
        "read_through": reads_outside(tool, call.args),
        "actuates": tool.actuates,
        "tainted": tainted_before,
        "deadline_ms": tool.deadline_ms,
        "needs": list(tool.needs),
        "confirm": tool.confirm,
    }
