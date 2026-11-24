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

import dataclasses

from demo import profile as profile_text
from engine_mcp.tools import (
    ENDS_TURN,
    FOR_MODEL,
    REPLAY_AS,
    SPEAK_EXACT,
    SPEAK_VERBATIM,
    EngineTool,
    ToolResult,
    ok,
    refused,
)

NOT_SHARED = profile_text.NOT_SHARED

NO_PROFILE = (
    "Nothing was read. Kevin hasn't shared a profile on this engine yet, so there is nothing "
    f'to answer from. Say: "{NOT_SHARED}."'
)

DESCRIPTION = (
    "What Kevin, the person who built LEGION, has written about himself for recruiters: "
    "his experience, skills, location, work authorisation, availability, what he is looking "
    "for, and how to reach him, one section each. Answer questions about Kevin ONLY from this "
    "text, in its own words. Anything about Kevin it does not say - including anything you "
    f'could guess from it - is not known: say "{NOT_SHARED}." Never use the demo household\'s '
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
        f'anything about Kevin it does not say, say "{NOT_SHARED}." A general question (what a '
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
# The cascade's about_kevin: code speaks the profile; the model only chooses (2026-11-18)
#
# Asked "Tell me about Kevin.", the model reworded the profile it was handed (a graduation date
# moved, a degree level appeared). On the cascade (ADR 0102) code can speak, so there the model is
# never handed a sentence of the profile: it is declared the section TITLES only, picks sections
# by id, and this tool returns their exact sentences (`demo.profile.speak`) under
# `structured[SPEAK_VERBATIM]`; the cascade says and shows them and ends the turn. The model gets
# `FOR_MODEL` (which titles were read) and history replays `REPLAY_AS`, never the sentences.
#
# Live (the page's one-command voice rollback) has no code speaker, so `ABOUT_KEVIN` above stays
# exactly as it was and only the cascade's door swaps this one in (`assistant.cascade.WebDoor`).
# =============================================================================

SECTIONS_PLACEHOLDER = "{sections}"

SPOKEN_DESCRIPTION = (
    "Says what Kevin, the person who built LEGION, has written about himself for recruiters, in "
    "his own words: code reads the sections you pick aloud and shows them, and that is the "
    "answer; the turn ends there. Call it FIRST for any question about Kevin himself, and say "
    "nothing about him yourself, before or after: you are never shown his profile, so anything "
    "you said about him would be made up. Pick sections by id: "
    f"{SECTIONS_PLACEHOLDER}. For a general question about him (who he is, his background, "
    f'"tell me about him"), pick {profile_text.OVERVIEW} alone: code reads his sections in his '
    "own order. For a pointed one, pick a section only when its title names it; when no "
    "title does (his visa, work authorisation or sponsorship, say, unless a title names it), "
    "call it with no sections, never with the nearest ones: "
    f'code then says "{NOT_SHARED}." For more of a long section, call it again with rest_of set '
    "to that id (and from_sentence when the history says where it stopped). offer adds one "
    "closing question (resume or contact) when it would help. Never "
    "use the demo household's data to answer about Kevin; that household is made up. This tool "
    "is for facts ABOUT KEVIN only. A general question that merely comes up - what a visa such as "
    "the H-1B1 is, what a term means, how something works - is not about Kevin: never call this "
    "tool for it and never answer it with that sentence; answer it plainly from general "
    "knowledge or web_search, and say which."
)


def _row_and_sections(user):
    from demo.models import RecruiterContent

    row = RecruiterContent.for_user(user)
    content = (row.content or {}) if row is not None else {}
    order = content.get(profile_text.PROFILE_ORDER)
    return row, profile_text.sections_of(content.get("profile"), order), bool(order)


def spoken_for(user) -> EngineTool:
    """The cascade's `about_kevin` as `user`'s assistant is told about it: the section ids and
    titles of THIS visitor's content version, never a sentence of them."""
    _row, sections, _ordered = _row_and_sections(user)
    listing = "; ".join(f"{s.id} ({s.title})" for s in sections) or "none (nothing shared yet)"
    if sections:
        listing = f"{profile_text.OVERVIEW} (a general question about him); {listing}"
    schema = dict(ABOUT_KEVIN_SPOKEN.input_schema)
    properties = dict(schema["properties"])
    if sections:
        ids = [profile_text.OVERVIEW, *(s.id for s in sections)]
        properties["sections"] = {
            **properties["sections"],
            "items": {"type": "string", "enum": ids},
        }
        properties["rest_of"] = {**properties["rest_of"], "enum": ids[1:]}
    schema["properties"] = properties
    description = ABOUT_KEVIN_SPOKEN.description.replace(SECTIONS_PLACEHOLDER, listing)
    return dataclasses.replace(ABOUT_KEVIN_SPOKEN, input_schema=schema, description=description)


def _about_kevin_spoken(request, arguments: dict) -> ToolResult:
    user = getattr(request, "user", None)
    row, sections, ordered = _row_and_sections(user)
    if not sections:
        return refused(NO_PROFILE)
    chosen = [c for c in arguments.get("sections") or [] if isinstance(c, str)]
    start = arguments.get("from_sentence")
    spoken = profile_text.speak(
        sections,
        chosen,
        arguments.get("rest_of") or None,
        from_sentence=start if isinstance(start, int) else None,
        ordered=ordered,
    )
    links = (row.content or {}).get("links") or {}
    closing = spoken.more or profile_text.offer_line(
        arguments.get("offer"),
        has_resume=row.has_asset("resume"),
        has_contact=any(links.values()),
    )
    lines = [*spoken.lines, *([closing] if closing else [])]
    if spoken.read:
        titles = ", ".join(spoken.read)
        for_model = (
            f"Done: read aloud and shown, word for word, from Kevin's profile: {titles}. The "
            "turn ends here."
        )
        part = f", {spoken.part}" if spoken.part else ""
        replay = f"(Read aloud from Kevin's profile: {titles}{part}.)"
        if closing:
            # Fixed words and titles only: kept so a "yes" or "go on" next turn has its question.
            replay += f" {closing}"
    else:
        # Code's fixed lines only (not shared, all read, which one): nothing of his to hide.
        for_model = f'Done: said "{" ".join(lines)}" The turn ends here.'
        replay = None
    return ok(
        for_model,
        {
            SPEAK_VERBATIM: lines,
            SPEAK_EXACT: True,
            ENDS_TURN: True,
            FOR_MODEL: for_model,
            REPLAY_AS: replay,
            "sections": list(spoken.read),
        },
    )


ABOUT_KEVIN_SPOKEN = EngineTool(
    name="about_kevin",
    title="About Kevin",
    description=SPOKEN_DESCRIPTION,
    input_schema={
        "type": "object",
        "properties": {
            "sections": {
                "type": "array",
                "items": {"type": "string"},
                "description": "Section ids that answer the question; empty when none does.",
            },
            "offer": {
                "type": "string",
                "enum": list(profile_text.OFFER_KEYS),
                "description": "One closing question to add, or none.",
            },
            "rest_of": {
                "type": "string",
                "description": "A long section's id, to read on from where it stopped.",
            },
            "from_sentence": {
                "type": "integer",
                "minimum": 1,
                "description": (
                    "With rest_of: the sentence to carry on from (the history says which were "
                    "read). Leave it out after the first part."
                ),
            },
        },
        "required": [],
        "additionalProperties": False,
    },
    handler=_about_kevin_spoken,
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
    if row is None or not row.has_asset("resume"):
        return None
    return {
        "card": "resume",
        "title": RESUME_TITLE,
        "image": "/api/demo/asset/resume_image" if row.has_asset("resume_image") else None,
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
