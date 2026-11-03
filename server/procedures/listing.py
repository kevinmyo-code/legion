"""Which procedures a caller may run, and the `run_procedure` declaration it is shown
(procedures ticket 12, rulings 04 and 07).

- **Visible**: `models.visible_to(household)` (the built-ins and the household's own, never
  another's), with an active version. A household's own procedure wins over a built-in of the
  same name.
- **A recruiter** (ruling 07) may run only a procedure whose EVERY `tool` step names a tool in
  `assistant.surface.RECRUITER_TOOLS`. Checked here at list time (the declaration's enum) and
  again at run time (`runner`, before any step), and each step still goes through
  `surface.refusal_for` as it runs.
- **The declaration** is the registry's `run_procedure` with its `name` narrowed to an enum of
  what this caller may run, made per request with `dataclasses.replace` (the pattern
  `surface._recruiter_tool` uses; `EngineTool` is frozen). With nothing to run the static schema
  stays as it is (an empty enum is not a schema Gemini takes) and the description says so.
"""

from __future__ import annotations

import dataclasses

from household.models import is_recruiter
from procedures.models import ProcedureVersion, visible_to


def tool_names(definition: dict) -> list[str]:
    return [s["tool"] for s in definition.get("steps", []) if s.get("kind") == "tool"]


def recruiter_may_run(definition: dict) -> bool:
    from assistant.surface import RECRUITER_TOOLS

    return all(name in RECRUITER_TOOLS for name in tool_names(definition))


def recruiter_refusal(definition: dict) -> str | None:
    """Why a recruiter may not run this procedure, in words, or None."""
    from assistant.surface import RECRUITER_TOOLS

    outside = sorted({n for n in tool_names(definition) if n not in RECRUITER_TOOLS})
    if not outside:
        return None
    return (
        f"Nothing ran. The procedure {definition.get('name')!r} is not available on this demo "
        f"visit: it uses {', '.join(outside)}, which a visit cannot use."
    )


def active(household) -> list[ProcedureVersion]:
    """The active version of every procedure `household` may see, by name; the household's own
    over a built-in of the same name."""
    versions = (
        ProcedureVersion.objects.filter(is_active=True, procedure__in=visible_to(household))
        .select_related("procedure")
        .order_by("procedure__name")
    )
    by_name: dict[str, ProcedureVersion] = {}
    for version in versions:
        name = version.procedure.name
        if name not in by_name or version.procedure.household_id is not None:
            by_name[name] = version
    return [by_name[name] for name in sorted(by_name)]


def find(household, name: str) -> ProcedureVersion | None:
    """The active version of `name` for `household`, or None. Never reads another household's."""
    for version in active(household):
        if version.procedure.name == name:
            return version
    return None


def runnable(user, household) -> list[ProcedureVersion]:
    versions = active(household)
    if is_recruiter(user):
        versions = [v for v in versions if recruiter_may_run(v.definition)]
    return versions


def _line(version: ProcedureVersion) -> str:
    kind = "writes, asks first" if version.writes else "read-only"
    return f"{version.procedure.name} ({kind}): {version.definition.get('description', '')}"


def tool_for(tool, user, household):
    """The registry's `run_procedure` as `user` is told about it."""
    versions = runnable(user, household) if household is not None else []
    schema = dict(tool.input_schema)
    properties = dict(schema["properties"])
    if versions:
        properties["name"] = {**properties["name"], "enum": [v.procedure.name for v in versions]}
        listing = " Procedures: " + "; ".join(_line(v) for v in versions) + "."
    else:
        listing = " No procedure is available here yet, so do not call it."
    schema["properties"] = properties
    return dataclasses.replace(tool, input_schema=schema, description=tool.description + listing)


def household_of_user(user):
    """The user's household for a declaration built outside a request (`tools_for(user)`)."""
    return getattr(user, "household", None)
