"""`about_kevin`: a recruiter asks about Kevin himself (recruiter-demo ticket 10).

One read tool, recruiters only. It returns the `profile` sections Kevin wrote
in his private content.json (`demo.content`, loaded into
`demo.models.RecruiterContent`), and nothing else: never household data, which
a recruiter's role cannot read anyway, and never an inference.

**Kept out of the engine registry on purpose.** `engine_mcp.tools.TOOLS` is
what `/mcp` lists and what every member's web assistant is declared; this tool
is for strangers reading about Kevin, and "recruiters only" is the rule. So it
lives here, `assistant.surface.RECRUITER_ONLY_TOOLS` names it, and the
assistant's tool door runs it through `engine_mcp.tools.run_engine_tool`, the
same checks a registry tool gets. A member's web assistant is never told of
it, and asking for it by name is refused as an unknown tool.
"""

from __future__ import annotations

from engine_mcp.tools import EngineTool, ToolResult, ok, refused

NOT_SHARED = "Kevin hasn't shared that here; ask him directly"

NO_PROFILE = (
    "Nothing was read. Kevin hasn't shared a profile on this engine yet, so there is nothing "
    f"to answer from. Say: \"{NOT_SHARED}.\""
)

DESCRIPTION = (
    "What Kevin, the person who built LEGION, has written about himself for recruiters: "
    "his experience, skills, location, work authorisation, availability, what he is looking "
    "for, and how to reach him, one section each. Answer questions about Kevin ONLY from this "
    "text, in its own words. Anything about Kevin it does not say - including anything you "
    f"could guess from it - is not known: say \"{NOT_SHARED}.\" Never use the demo household's "
    "data to answer about Kevin; that household is made up. This rule is for facts ABOUT KEVIN "
    "only. A general question that merely comes up - what a visa such as the H-1B1 is, what a "
    "term means, how something works - is not about Kevin: never answer it with that sentence; "
    "answer it plainly from general knowledge or web_search, and say which."
)


def _about_kevin(request, arguments: dict) -> ToolResult:
    from demo.models import RecruiterContent

    row = RecruiterContent.for_user(getattr(request, "user", None))
    profile = (row.content or {}).get("profile") if row is not None else None
    if not profile:
        return refused(NO_PROFILE)
    sections = "\n\n".join(f"{name}:\n{text.strip()}" for name, text in profile.items())
    return ok(
        "Kevin's profile, as he wrote it. Answer questions about Kevin only from this; for "
        f"anything about Kevin it does not say, say \"{NOT_SHARED}.\" A general question (what a "
        "visa or a term is) is not about Kevin: answer that from general knowledge or web_search."
        f"\n\n{sections}",
        {"sections": list(profile)},
    )


ABOUT_KEVIN = EngineTool(
    name="about_kevin",
    title="About Kevin",
    description=DESCRIPTION,
    input_schema={
        "type": "object",
        "properties": {},
        "required": [],
        "additionalProperties": False,
    },
    handler=_about_kevin,
)
