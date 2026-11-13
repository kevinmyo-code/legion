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
    writes=False,
)


# =============================================================================
# show_resume: Kevin's one-page resume over the page (demo-modals, Kevin 2026-11-05: "asking for
# the resume should pop up a modal thats scrollable showing my 1 page resume")
#
# Recruiters only, like `about_kevin`, and for the same reason. The card carries LINKS, never
# bytes: the page image and the PDF are `RecruiterContent` files of the visitor's own content
# version, served by `GET /api/demo/asset/<kind>` behind the same fence as the page. The image
# is there because Android Chrome draws no PDF inside a page; the PDF stays the download.
# A version that loaded no resume (the LinkedIn one) is refused in words, and draws nothing.
# =============================================================================

RESUME_TITLE = "Kevin's resume"
RESUME_ALT = (
    "Kevin's one-page resume, as an image of the page. Download the PDF for the same page with "
    "selectable text."
)

NO_RESUME = (
    "Nothing was shown. This version of the page has no resume to show. Say Kevin can share his "
    "resume directly, and do not describe one."
)

SHOW_RESUME_DESCRIPTION = (
    "Opens Kevin's one-page resume over the page, scrollable, with a Download PDF button. Call it "
    "whenever the visitor asks for, or to see, Kevin's resume or CV. You cannot read it: never "
    "describe what is on it from this tool; for facts about Kevin, use about_kevin. Say only "
    "what the result says."
)


def resume_card(row) -> dict | None:
    """The `resume` card for a content row, or None when it holds no resume at all."""
    if row is None or row.resume is None:
        return None
    return {
        "card": "resume",
        "title": RESUME_TITLE,
        "image": "/api/demo/asset/resume_image" if row.resume_image is not None else None,
        "pdf": "/api/demo/asset/resume",
        "alt": RESUME_ALT,
    }


def _show_resume(request, arguments: dict) -> ToolResult:
    from demo.models import RecruiterContent

    card = resume_card(RecruiterContent.for_user(getattr(request, "user", None)))
    if card is None:
        return refused(NO_RESUME)
    if card["image"] is None:
        text = (
            "A box with a Download PDF button for Kevin's resume is open over the page; no image "
            "of the page is loaded on this engine, so the visitor downloads it to read it. Say so "
            "in a sentence, and never describe what is on it."
        )
    else:
        text = (
            "Kevin's one-page resume is on the screen now, over the page, with a Download PDF "
            "button. Say so in a sentence and offer to answer questions about his experience. You "
            "cannot see the page: never quote or describe it; facts about Kevin come from "
            "about_kevin."
        )
    return ok(text, {"image": card["image"] is not None}, card=card)


SHOW_RESUME = EngineTool(
    name="show_resume",
    title="Show Kevin's resume",
    description=SHOW_RESUME_DESCRIPTION,
    input_schema={
        "type": "object",
        "properties": {},
        "required": [],
        "additionalProperties": False,
    },
    handler=_show_resume,
    writes=False,
)
