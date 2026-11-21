"""`show_part`: "where is the track bar nut?" opens the 3D front end on that nut (option B).

**Kept out of the engine registry on purpose**, like `demo.tools.ABOUT_KEVIN`: `TOOLS` is what
`/mcp` lists (the phone, the head unit, Claude Code) and every door reads. This tool is for a
member's web assistant only, behind `garage.flags`, so `assistant.surface` adds it for a member
when the switch is on and the tool door runs it through `run_engine_tool`. With the switch off it
is in no list, no declaration and no prompt.

**Code speaks the answer.** For one fastener the result's `speak_verbatim` lines say what and
where it is and its torque, from the checked table only (`garage.specs`), and end the turn. The
model is handed `for_model` words with no figure in them, so even the Live fallback, which does
not speak verbatim lines, has no number to say. A fastener with no checked row is said to be not
verified; no number.

**Ids come from code.** The person's words go through `garage.resolve`; when two parts or ends
match, the result lists them and the model asks, then calls again with the id it was given.
"""

from __future__ import annotations

from engine_mcp.tools import (
    ENDS_TURN,
    FOR_MODEL,
    SPEAK_EXACT,
    SPEAK_VERBATIM,
    EngineTool,
    ToolResult,
    ok,
)
from garage import resolve as r
from garage import specs
from garage import xj_front as m

NAME = "show_part"
CARD = "part3d"
# What a result hands the torque guard (`speech_rules.backed_torque`): the numbers code spoke.
SPOKEN_FIGURES = "spoken_figures"

DESCRIPTION = (
    "Shows the Jeep Cherokee XJ front end (suspension and steering) in 3D over the screen, flies "
    "to the part or nut asked about and highlights it, and has the answer spoken for you: where "
    "it is and its torque from the owner's checked table. Call it for any question about where a "
    "front-end part or fastener is, or its torque, socket or thread size: track bar, drag link, "
    "tie rod, pitman arm, ball joints, control arms, sway bar and links, shocks, hubs, steering "
    "box, stabilizer. Pass the person's whole question as `words`. If the result lists "
    "candidates, ask which one (driver or passenger, upper or lower, which end) and call again "
    "with the `part_id` or `fastener_id` it gave; never make up an id. Never say a torque, "
    "socket or thread figure yourself, from this or any other tool or from memory: only this "
    "tool's spoken lines may, and only from checked rows."
)

INPUT_SCHEMA = {
    "type": "object",
    "properties": {
        "words": {"type": "string", "minLength": 1, "maxLength": 200},
        "part_id": {"type": "string", "pattern": r"^xj(\.[a-z0-9_]+)+$", "maxLength": 80},
        "fastener_id": {
            "type": "string",
            "pattern": r"^xj(\.[a-z0-9_]+)+@[a-z0-9_]+$",
            "maxLength": 120,
        },
        "side": {"type": "string", "enum": [m.DRIVER, m.PASSENGER]},
    },
    "required": ["words"],
    "additionalProperties": False,
}


def _checked() -> dict:
    from garage.models import FastenerSpec

    return {s.fastener_id: s for s in FastenerSpec.objects.filter(model=m.MODEL)}


def _side_words(f: m.Fastener) -> str:
    side = f.side or m.PARTS_BY_ID[f.part].side
    return f", {side} side" if side and side not in f.name else ""


def _label(f: m.Fastener) -> str:
    return f"{m.PARTS_BY_ID[f.part].name}, {f.name}{_side_words(f)}"


def _fact(f: m.Fastener, spec) -> dict:
    fact = {"fastener_id": f.id, "name": f.name, "kind": f.kind, "checked": spec is not None}
    if spec is not None:
        fact.update(
            figure=specs.figure_text(spec),
            socket=spec.socket,
            thread=spec.thread,
            applies_to=spec.applies_to,
            source=spec.source,
            page=spec.source_page,
            verified_on=spec.verified_on.isoformat(),
        )
    return fact


def _card(part: m.Part, fastener: m.Fastener | None, checked: dict) -> dict:
    mine = m.fasteners_of(part.id)
    view = (fastener or part).view
    return {
        "card": CARD,
        "title": _label(fastener) if fastener else part.name,
        "scope": "XJ front end",
        "model": m.MODEL_URL,
        "part_id": part.id,
        "fastener_id": fastener.id if fastener else None,
        "highlight": [part.id] + ([fastener.id] if fastener else []),
        "view": view.as_dict(),
        "where": (fastener or part).where,
        "facts": [_fact(f, checked.get(f.id)) for f in mine],
        "note": "Torque is spoken and shown only from rows the owner checked against a source.",
    }


def _ambiguous(match: r.Match) -> ToolResult:
    listed = []
    for c in match.candidates:
        if isinstance(c, m.Fastener):
            listed.append({"fastener_id": c.id, "name": _label(c)})
        else:
            side = f", {c.side} side" if c.side else ""
            listed.append({"part_id": c.id, "name": f"{c.name}{side}"})
    names = "; ".join(f"{x['name']} ({x.get('fastener_id') or x.get('part_id')})" for x in listed)
    return ok(
        "Nothing is on the screen yet: more than one thing on the model matches. Ask which one "
        f"they mean, in plain words, then call show_part again with its id. They are: {names}.",
        {"match": r.AMBIGUOUS, "candidates": listed},
    )


def _none() -> ToolResult:
    parts = ", ".join(sorted({p.name for p in m.PARTS}))
    return ok(
        "Nothing is on the screen: no part on the model matches those words. Say that part is "
        f"not on the model yet, or ask them to name it another way. Parts on it: {parts}. Do "
        "not describe where it is from memory.",
        {"match": r.NONE},
    )


def _part(match: r.Match, checked: dict) -> ToolResult:
    part = match.part
    mine = m.fasteners_of(part.id)
    side = f", {part.side} side" if part.side else ""
    lines = [f"{part.name}{side}. {part.where}"]
    if mine:
        names = ", ".join(f.name for f in mine)
        lines.append(f"It has {len(mine)} fastening points: {names}. Which one do you want?")
    listed = [{"fastener_id": f.id, "name": f.name} for f in mine]
    return ok(
        "On screen now: the part, highlighted.",
        {
            "match": r.PART,
            "part_id": part.id,
            "fasteners": listed,
            SPEAK_VERBATIM: lines,
            SPEAK_EXACT: True,
            ENDS_TURN: True,
            FOR_MODEL: (
                f"The {part.name} is on the screen now, highlighted, and code has said where it "
                "is and asked which fastening point they want. Its fastener ids: "
                + ", ".join(f.id for f in mine)
                + ". When they answer, call show_part with that fastener_id. Say no figure."
            ),
            SPOKEN_FIGURES: [],
        },
        card=_card(part, None, checked),
    )


def _fastener(match: r.Match, checked: dict) -> ToolResult:
    f = match.fastener
    spec = checked.get(f.id)
    lines = [f"{_label(f)}. {f.where}"]
    spoken: list[str] = []
    if spec is None:
        lines.append(specs.NOT_VERIFIED)
    else:
        lines.append(specs.figure_line(spec))
        size = specs.size_line(spec)
        if size:
            lines.append(size)
        if "castle" in f.kind.lower():
            lines.append(specs.CASTLE)
        spoken = sorted(specs.spoken_numbers(spec))
    return ok(
        "On screen now: the fastener, highlighted.",
        {
            "match": r.FASTENER,
            "part_id": f.part,
            "fastener_id": f.id,
            "checked": spec is not None,
            SPEAK_VERBATIM: lines,
            SPEAK_EXACT: True,
            ENDS_TURN: True,
            FOR_MODEL: (
                f"The {_label(f)} is on the screen now, highlighted. Code has already said where "
                "it is and its torque status; that was the whole answer. Add nothing with a "
                "number in it."
            ),
            SPOKEN_FIGURES: spoken,
        },
        card=_card(m.PARTS_BY_ID[f.part], f, checked),
    )


def show_part(request, args: dict) -> ToolResult:
    words = args["words"]
    side = args.get("side")
    if args.get("fastener_id") or args.get("part_id"):
        match = r.by_id(args.get("part_id"), args.get("fastener_id"), words, side)
    else:
        match = r.resolve(words, side)
    if match.kind == r.NONE:
        return _none()
    if match.kind == r.AMBIGUOUS:
        return _ambiguous(match)
    checked = _checked()
    if match.kind == r.PART:
        return _part(match, checked)
    return _fastener(match, checked)


SHOW_PART = EngineTool(
    name=NAME,
    title="Show a Jeep front-end part",
    description=DESCRIPTION,
    input_schema=INPUT_SCHEMA,
    handler=show_part,
    writes=False,
)
