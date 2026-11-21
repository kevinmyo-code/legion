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

import re
from dataclasses import dataclass

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


@dataclass(frozen=True)
class _Table:
    """What may be said: the rows that fit the owner's truck, and why each other stored row may
    not (no profile, another year/engine/axle, a stock figure on kit hardware)."""

    truck: specs.Truck | None
    speakable: dict
    held: dict

    def line(self, f: m.Fastener) -> str | None:
        return self.held.get(f.id)


def _checked() -> _Table:
    from garage.models import FastenerSpec, GarageTruck

    row = GarageTruck.objects.filter(model=m.MODEL).first()
    truck = (
        specs.Truck(row.year, row.engine, row.axle, row.lift_in, frozenset(row.kit_fasteners))
        if row is not None
        else None
    )
    speakable, held = {}, {}
    for spec in FastenerSpec.objects.filter(model=m.MODEL):
        why = specs.refusal(spec, truck, spec.fastener_id)
        if why is None:
            speakable[spec.fastener_id] = spec
        else:
            held[spec.fastener_id] = why
    return _Table(truck, speakable, held)


def _side_words(f: m.Fastener) -> str:
    side = f.side or m.PARTS_BY_ID[f.part].side
    return f", {side} side" if side and side not in f.name else ""


def _label(f: m.Fastener) -> str:
    return f"{m.PARTS_BY_ID[f.part].name}, {f.name}{_side_words(f)}"


def _fact(f: m.Fastener, table: _Table) -> dict:
    spec = table.speakable.get(f.id)
    fact = {"fastener_id": f.id, "name": f.name, "kind": f.kind, "checked": spec is not None}
    if spec is not None:
        fact.update(
            figure=specs.figure_text(spec),
            socket=spec.socket,
            thread=spec.thread,
            applies_to=_applies_words(spec, table.truck),
            source=spec.source,
            page=spec.source_page,
            verified_on=spec.verified_on.isoformat(),
        )
    elif f.id in table.held:
        fact["held"] = table.held[f.id]
    return fact


def _applies_words(spec, truck: specs.Truck) -> str:
    years = (
        str(spec.year_from)
        if spec.year_from == spec.year_to
        else f"{spec.year_from} to {spec.year_to}"
    )
    return (
        f"{years}, engine {spec.engine}, axle {spec.axle}, {specs.hardware_words(spec)}; "
        f"your truck: {truck.year}, {truck.engine}, {truck.axle}, {truck.lift_in} in lift"
    )


def _card(part: m.Part, fastener: m.Fastener | None, table: _Table) -> dict:
    # The fastener asked about first; the card sets it apart and folds the rest away.
    mine = sorted(m.fasteners_of(part.id), key=lambda f: fastener is None or f.id != fastener.id)
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
        "facts": [_fact(f, table) for f in mine],
        "note": "Torque is spoken and shown only from rows the owner checked against a source.",
    }


_DIGIT_WORDS = {"1": "one", "2": "two", "3": "three", "4": "four", "5": "five"}


def _in_words(text: str) -> str:
    """A candidate's name as code says it: small numbers in words ("frame bolt two of three"), so
    nothing spoken in the question reads as a figure and the answer resolves (`resolve.tokens`)."""
    return re.sub(r"\b[1-5]\b", lambda x: _DIGIT_WORDS[x.group(0)], text)


def _ambiguous(match: r.Match) -> ToolResult:
    listed = []
    for c in match.candidates:
        if isinstance(c, m.Fastener):
            listed.append({"fastener_id": c.id, "name": _label(c)})
        else:
            side = f", {c.side} side" if c.side else ""
            listed.append({"part_id": c.id, "name": f"{c.name}{side}"})
    said = [_in_words(x["name"]) for x in listed]
    choices = ", ".join(said[:-1]) + f", or {said[-1]}" if len(said) > 2 else " or ".join(said)
    ids = "; ".join(f"{x['name']} ({x.get('fastener_id') or x.get('part_id')})" for x in listed)
    # Code asks the question itself and the turn ends there: the model's own wording of the
    # choices could carry numbers ("frame bolt 2 of 3") that the torque guard would then drop,
    # leaving no answer at all (re-check, 2026-11-20).
    return ok(
        "Nothing is on the screen yet: more than one thing on the model matches, and code has "
        f"asked which one. When they answer, call show_part with that id. They are: {ids}.",
        {
            "match": r.AMBIGUOUS,
            "candidates": listed,
            SPEAK_VERBATIM: [f"Which one do you mean: {choices}?"],
            SPEAK_EXACT: True,
            ENDS_TURN: True,
            FOR_MODEL: (
                "More than one thing on the model matches; code has asked which one, and that "
                f"was the whole answer. When they answer, call show_part with that id: {ids}. "
                "Say no figure."
            ),
            SPOKEN_FIGURES: [],
        },
    )


def _none() -> ToolResult:
    parts = ", ".join(sorted({p.name for p in m.PARTS}))
    return ok(
        "Nothing is on the screen: no part on the model matches those words. Say that part is "
        f"not on the model yet, or ask them to name it another way. Parts on it: {parts}. Do "
        "not describe where it is from memory.",
        {"match": r.NONE},
    )


def _part(match: r.Match, table: _Table) -> ToolResult:
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
        card=_card(part, None, table),
    )


def _fastener(match: r.Match, table: _Table) -> ToolResult:
    f = match.fastener
    spec = table.speakable.get(f.id)
    lines = [f"{_label(f)}. {f.where}"]
    spoken: list[str] = []
    if spec is None:
        lines.append(table.line(f) or specs.NOT_VERIFIED)
    else:
        lines.append(specs.figure_line(spec, table.truck))
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
        card=_card(m.PARTS_BY_ID[f.part], f, table),
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
    table = _checked()
    if match.kind == r.PART:
        return _part(match, table)
    return _fastener(match, table)


SHOW_PART = EngineTool(
    name=NAME,
    title="Show a Jeep front-end part",
    description=DESCRIPTION,
    input_schema=INPUT_SCHEMA,
    handler=show_part,
    writes=False,
)
