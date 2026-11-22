"""The relay, engine side (ADR 0098; engine-brain ticket 04, slice E2): which limb tools a turn is
offered, and whether one call may go to the limb.

**Which tools.** The tools of the limb that MADE the turn: the `Limb` row of the request's own
device token (`limb_of`). A session (the web), a head unit's token and the late-answer runner's
rebuilt request have no limb, so they are offered none and nothing here queries for them.

**Deny by default** (`relayable`). A manifest tool is offered only when:
- it declares a `tool_class` the ENGINE knows: `PLAIN_CLASSES` (results are the household's own or
  the device's: timers, music, the car, places) or `OUTSIDE_CLASSES` (results are text other people
  wrote: web pages, notifications, contacts' free text, clipboard, text on a screen, a camera
  look). No class, or one not listed (`mail`, `messages`, anything new), and it is dropped;
- it is not mail-like by name (`_MAIL_NAME`: mail, sms, messages, chat, ...), whatever it declares:
  mail is never relayed by the engine until ticket 05;
- a name that sounds like outside text (`_OUTSIDE_NAME`) carries an outside class: a tool whose
  NAME gives it away and whose class says otherwise is refused;
- no engine tool has its name (the engine's own tool wins).
So a new `read_sms` or `read_notifications` is refused with no code change. A folded tool keeps
its declaration, but mail values are removed from its enums.

**The trust boundary, plainly.** The manifest comes from the owner's own app, signed in with his
own device token, which can already read and write everything he can. `tool_class` is the app's
own claim; the engine checks it only against names (the patterns above) and against its own
action table (`OUTSIDE_ACTIONS`). A mail reader the app named `summary` and classed `household`
would be offered, untainted. These checks catch the app's honest mistakes and a new tool nobody
classified; they do not defend against a hostile manifest. A third-party limb (a device or app
not his) would need the engine to own each tool's class itself (an engine-side registry keyed by
tool, not by the manifest's word) before it could attach (ADR 0098, "Trust boundary").

**Taint is the engine's** (`reads_outside`): an outside class taints every call of the tool,
whatever the manifest's `read_through` says; so does any call that names one of the engine's
`OUTSIDE_ACTIONS` (a folded tool's calendar read with others' invites, a place search, caller
names); the manifest's flags can only add taint.

**Whether a call may go** (`decide`), in order:
1. mail class (the tool, a declared mail action, or any argument naming one): refused;
2. relay hops past `MAX_RELAY_HOPS` in this turn, or limb calls past `MAX_CALLS_PER_ROUND` in
   one round: refused;
3. a write of a tool whose earlier write in this turn timed out: refused (it may still land; a
   retry would be the double run the wire contract forbids, section 8);
4. a write in a read-only spec, or a write or acting call on the phone's test door
   (`client_facts.test_mode`): refused;
5. a write or an actuating call in a turn marked tainted (ADR 0090, ADR 0098): refused, unless the
   owner's ruling K1 names the tool in `AFTER_OUTSIDE_TEXT` (open: empty, the strict default);
6. a write or an actuating call whose manifest says `confirm: engine` (the default): held for his
   yes in a later turn (`guards.hold_for_yes`). Only `confirm: limb` (the limb runs its own gate)
   skips this, so no limb write goes out unconfirmed by someone;
7. outside text already in the turn (`TurnState.outside`): never paused for, whatever the call
   (plan 1119 section 5 and K2): saving the pause would save that text. A tool K1 allows becomes a
   device action the limb runs once the reply ends (`done.device_actions`, each with its own call
   id and result post); anything else is refused.
8. otherwise it goes: a read-through call marks the turn tainted first and is saved (fail closed),
   and pauses alone (`runtime.tools.RELAY_ALONE`).
"""

from __future__ import annotations

import json
import logging
import re
import uuid
from dataclasses import dataclass, field
from pathlib import Path

from agents.legion import guards, redact
from agents.legion.client_facts import TEST_MODE_WORDS
from agents.legion.guards import Verdict
from agents.models import Limb
from household.models import DeviceToken
from household.tenancy import household_of

log = logging.getLogger(__name__)

# The engine's classes and word lists: ONE file, read by the server's tests and the phone's
# manifest test alike (`server/contracts/limb/v1/tool_classes.json`).
CLASSES_FILE = (
    Path(__file__).resolve().parents[2] / "contracts" / "limb" / "v1" / "tool_classes.json"
)
# If the file is missing or broken, the RELAY fails closed (no class is known, so no limb tool
# is offered, and every engine read that could leave the engine is held) and nothing else breaks:
# `gateway.py` imports this module, and the live web door imports `gateway`.
_CLOSED = {
    "plain_classes": [],
    "outside_classes": [],
    "mail_class_names": [],
    "mail_name_words": [],
    "outside_name_words": [],
    "outside_actions": [],
    "max_calls_per_round": 0,
    "engine_tools_leaving_engine": None,
}


def _load() -> dict:
    try:
        raw = json.loads(CLASSES_FILE.read_text(encoding="utf-8"))
        return {key: raw[key] for key in _CLOSED}
    except (OSError, ValueError, KeyError, TypeError):
        log.warning("agents relay: %s is missing or broken; the relay fails closed", CLASSES_FILE)
        return dict(_CLOSED)


_LISTS = _load()


def _words(key: str) -> re.Pattern:
    if not _LISTS[key]:
        return re.compile(r"(?!x)x")  # matches nothing
    words = "|".join(re.escape(w) for w in sorted(_LISTS[key], key=len, reverse=True))
    return re.compile(rf"(^|_)({words})(_|$)", re.IGNORECASE)


# Results that are the household's own or the device's state: relayable, untainted.
PLAIN_CLASSES = frozenset(_LISTS["plain_classes"])
# Results that are text other people wrote: relayable, and every call taints the turn.
OUTSIDE_CLASSES = frozenset(_LISTS["outside_classes"])
# Calls that read text other people wrote, by the ENGINE's own word, whatever the manifest says:
# a call whose name, or any string argument (a folded tool's action), is one of these taints the
# turn. The phone's `day` tool is split this way: its calendar read carries invite titles and
# descriptions other people wrote (tainted); its lists and timers do not (plain).
OUTSIDE_ACTIONS = frozenset(_LISTS["outside_actions"])
# The phone tools whose results carry mail (`ask_mail`, the brief's `get_sitrep` with its mail
# sections, parcel and flight status read from mail): matched against a call's name AND every
# string argument, so a folded call (`look_up` with `domain: ask_mail`) is caught.
MAIL_CLASS = frozenset(_LISTS["mail_class_names"])
_MAIL_NAME = _words("mail_name_words")
_OUTSIDE_NAME = _words("outside_name_words")
# ENGINE tools whose call sends his words, or his place, out of the engine (a search provider, a
# weather service, GitHub, a recall database): held until the phone's go like a write
# (`hold.py`). None when the file is broken: then every engine read is held (fail closed).
LEAVES_ENGINE = (
    None
    if _LISTS["engine_tools_leaving_engine"] is None
    else frozenset(_LISTS["engine_tools_leaving_engine"])
)


def leaves_engine(name: str) -> bool:
    return True if LEAVES_ENGINE is None else name in LEAVES_ENGINE


# DECISION K1 (open, the owner's; plan 1119 "Decisions for Kevin"): the limb tools that may still
# act in a turn that read outside text, as the phone allows navigation, timers, music, volume and
# screens today (`ReadThroughWriteGuard.ALLOWED_AFTER_READ`). Empty is the strict default (ADR 0098:
# an actuating tool is never callable in a tainted turn). A name here never makes the turn pause
# with outside text in it (K2): once outside text was read, the call runs on the limb as a device
# action after the reply (`done.device_actions`, a call id and a result post like any relayed
# call), never mid-turn. His ruling is a change to this one line; `tests/test_agents_relay_api.py`
# covers the empty set and {timer, music, volume}.
AFTER_OUTSIDE_TEXT: frozenset[str] = frozenset()

# The engine waits this long past a call's deadline before it calls the call timed out.
GRACE_MS = 5_000
# Pauses one turn may make. The loop's rounds already bound it (4 rounds, the last tool-free: at
# most 3); this keeps the bound if a spec ever raises `max_rounds`.
MAX_RELAY_HOPS = 3
# Limb calls one round may ask. The limb runs them one at a time, so a round's whole time is the
# sum of their deadlines: 4 x the 120 s manifest maximum + the grace stays under the 10 minute idle
# close, so a slow round is never closed while the device is still working on it.
MAX_CALLS_PER_ROUND = int(_LISTS["max_calls_per_round"])

MAIL_REFUSED = (
    "Nothing was read: {name} would read mail or messages through the device, and that is not "
    "done here yet. Say so plainly."
)
ROUND_REFUSED = (
    "Nothing was done: this round already asked the device {calls} things, the most one round "
    "may. Ask for the rest after these answers."
)
HOPS_REFUSED = (
    "Nothing was done: this turn already asked the device {hops} times, the most one turn may. "
    "Answer with what you have."
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
    tool_class: str = ""

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
            tool_class=str(raw.get("tool_class") or ""),
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


def _mail_word(value) -> bool:
    return isinstance(value, str) and (value in MAIL_CLASS or bool(_MAIL_NAME.search(value)))


def is_mail_class(tool: LimbTool, args: dict | None = None) -> bool:
    args = args or {}
    named = (
        tool.name in MAIL_CLASS or redact.is_mail_tool(tool.name) or _MAIL_NAME.search(tool.name)
    )
    passed = any(_mail_word(v) for v in args.values())
    return bool(tool.mail or named or passed or _matches(tool.mail_when, args))


def drop_reason(tool: LimbTool) -> str | None:
    """Why the engine will not offer this tool (the attach answer's `dropped`), or None."""
    if is_mail_class(tool):
        return "mail_like"
    if tool.tool_class not in PLAIN_CLASSES | OUTSIDE_CLASSES:
        return "unknown_class"
    if _OUTSIDE_NAME.search(tool.name) and tool.tool_class not in OUTSIDE_CLASSES:
        return "outside_name_plain_class"
    return None


def relayable(tool: LimbTool) -> bool:
    """Deny by default (module docstring)."""
    return drop_reason(tool) is None


def reads_outside(tool: LimbTool, args: dict) -> bool:
    """The call's result is outside text (ADR 0090): the engine's class first; the manifest's own
    flags can only add to it."""
    named = tool.name in OUTSIDE_ACTIONS or any(
        isinstance(v, str) and v in OUTSIDE_ACTIONS for v in (args or {}).values()
    )
    return (
        tool.tool_class in OUTSIDE_CLASSES
        or named
        or tool.read_through
        or _matches(tool.read_through_when, args)
    )


def dropped(manifest: dict, engine_names) -> list[dict]:
    """Every manifest tool the engine will not offer, and why (`drop_reason`, or `engine_tool`
    when an engine tool has its name)."""
    out = []
    for raw in manifest.get("tools") or []:
        tool = LimbTool.from_manifest(raw)
        reason = "engine_tool" if tool.name in engine_names else drop_reason(tool)
        if reason:
            out.append({"name": tool.name, "reason": reason})
    return out


def tools_of(limb: Limb | None, engine_names) -> dict[str, LimbTool]:
    if limb is None:
        return {}
    out = {}
    for raw in (limb.manifest or {}).get("tools") or []:
        tool = LimbTool.from_manifest(raw)
        if tool.name in engine_names or not relayable(tool):
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
        out["enum"] = [v for v in out["enum"] if not _mail_word(v)]
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
    if is_mail_class(tool, args) or not relayable(tool):
        return _refused(MAIL_REFUSED.format(name=name), "not_allowed")
    hops = int(state.client_facts.get("stream_no", 1)) - 1
    if hops >= MAX_RELAY_HOPS:
        return _refused(HOPS_REFUSED.format(hops=hops), "budget")
    if calls_this_round(state) >= MAX_CALLS_PER_ROUND:
        return _refused(ROUND_REFUSED.format(calls=MAX_CALLS_PER_ROUND), "budget")
    if tool.writes and name in (state.client_facts.get("relay_timed_out_writes") or []):
        return _refused(TIMED_OUT_REFUSED.format(name=name), "not_allowed")
    if tool.writes and not spec.writes_allowed:
        return _refused(guards.READ_ONLY.format(name=name), "not_allowed")
    acts = tool.writes or tool.actuates
    if acts and state.client_facts.get("test_mode"):
        return _refused(TEST_MODE_WORDS.format(name=name), "not_allowed")
    ruled = name in AFTER_OUTSIDE_TEXT
    if acts and state.tainted and not ruled:
        return _refused(guards.tainted_refusal(state, name), "tainted")
    if acts and tool.confirm != "limb":
        held = guards.hold_for_yes(state, name, args)
        if not held.allow:
            return Decision(verdict=held)
    if state.outside:
        if acts and ruled:
            return Decision(after_turn=True)
        return _refused(OUTSIDE_REFUSED.format(name=name), "tainted")
    return GO


def calls_this_round(state) -> int:
    """Limb calls already sent in the model round now running (`budget.rounds` counts rounds)."""
    facts = state.client_facts.get("relay_round_calls") or {}
    return int(facts.get(str(state.budget.rounds), 0))


def count_call(state) -> None:
    facts = state.client_facts.setdefault("relay_round_calls", {})
    key = str(state.budget.rounds)
    facts[key] = int(facts.get(key, 0)) + 1


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


def issued_record(
    requests: list[dict], recorded: dict | None = None, asked_at_ms: int | None = None
) -> dict:
    """`pending_relay` of a turn that is over but may still get results: every call it asked
    (no arguments: they may carry outside text), so a late result is recorded
    (`relay_turn._record_late`)."""
    recorded = recorded or {}
    issued = {}
    for req in requests:
        row = recorded.get(req["call_id"]) or {}
        issued[req["call_id"]] = {
            "name": req["name"],
            "writes": bool(req.get("writes")),
            "read_through": bool(req.get("read_through")),
            "limb_id": req.get("limb_id"),
            "recorded": row.get("outcome"),
            "by": row.get("by"),
            "asked_at_ms": asked_at_ms,
        }
    return {"issued": issued}
