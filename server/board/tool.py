"""`board_status`: "what's in flight?" answered from the member's factory board, spoken by code.

The result follows the cascade's code-spoken contract (`engine_mcp.tools.SPEAK_VERBATIM` and kin,
`assistant.cascade.VERBATIM_TOOLS`): the lines `board.reading.spoken_lines` builds are said and
shown word for word and end the turn. The model is handed `FOR_MODEL` only: freshness and counts,
written by code, never a card's text. History replays the same. So card text, which agents write
and which could carry words planted by a web page an agent read, never enters the model's context
on this path, so it is NOT in `assistant.read_through.THIRD_PARTY_TOOLS`: that set is the phone's
list too (`AskEngineReadThroughTest` pins them equal), and the write guard exists for text a model
reads, which this tool never hands one. If a later slice lets a model read card text, add it there
together with the phone's list in one phone update.

Not on `/mcp` (`engine_mcp.server.NOT_ON_MCP`): its structured result carries the card text, and a
`/mcp` client's model could read it. The phone reads `GET /api/board` and speaks `spoken` itself.
"""

from __future__ import annotations

from board import flags, reading

OFF = (
    "Nothing was read. The factory board is switched off on this engine, so I don't know what's "
    "in flight."
)
NOT_FOR_RECRUITER = "Nothing was read. A demo visit has no laptop board."


def board_status(request, arguments: dict):
    from engine_mcp.tools import (
        ENDS_TURN,
        FOR_MODEL,
        REPLAY_AS,
        SPEAK_EXACT,
        SPEAK_VERBATIM,
        ok,
        refused,
    )
    from household.models import is_recruiter

    if not flags.board_enabled():
        return refused(OFF)
    if is_recruiter(getattr(request, "user", None)):
        return refused(NOT_FOR_RECRUITER)
    r = reading.read(request)
    said = reading.spoken_lines(request, r)
    facts = " ".join(reading.model_lines(request, r))
    for_model = (
        f"Done: read aloud and shown, word for word, from the member's factory board. {facts} "
        "The cards' own words are not shown to you; never describe them. The turn ends here."
    )
    # What a door that cannot speak by code (Live, the web tool door) hands its model: the facts,
    # and no claim that anything was said.
    text = (
        f"From the member's factory board: {facts} The cards' own words are spoken only by "
        "LEGION's own voice and are not shown to you; never describe them."
    )
    return ok(
        text,
        {
            SPEAK_VERBATIM: said,
            SPEAK_EXACT: True,
            ENDS_TURN: True,
            FOR_MODEL: for_model,
            REPLAY_AS: f"(Read the factory board aloud. {facts})",
        },
    )


# ---- add_backlog_idea (Kevin, 2026-11-22: "yes score each idea", "read it back first") ---------

IDEAS_OFF = "Nothing was added. The factory board is switched off on this engine."
IDEAS_NOT_FOR_RECRUITER = "Nothing was added. A demo visit has no laptop board."
NEEDS_TEXT = "Nothing was added: say the idea in words, or pass the id and confirm to answer one."


def add_backlog_idea(request, arguments: dict):
    """Draft (text): score it, store a draft, and read it back by code; the turn ends there, so
    his answer is his next turn. Confirm (id, confirm true): his yes saves the draft he heard.
    Confirm false: his no drops it. The read-back and the outcome are spoken by code."""
    from board import ideas
    from engine_mcp.tools import (
        ENDS_TURN,
        FOR_MODEL,
        REPLAY_AS,
        SPEAK_EXACT,
        SPEAK_VERBATIM,
        ok,
        refused,
    )
    from household.models import is_recruiter
    from household.tenancy import household_of

    if not flags.board_enabled():
        return refused(IDEAS_OFF)
    user = getattr(request, "user", None)
    if is_recruiter(user):
        return refused(IDEAS_NOT_FOR_RECRUITER)
    idea_id = arguments.get("id")
    answer = arguments.get("confirm")
    try:
        if idea_id and answer is True:
            idea = ideas.confirm(owner_user=user, idea_id=idea_id)
            said, note = ideas.SAVED, f"Saved backlog idea {idea.id} on his yes."
        elif idea_id and answer is False:
            idea = ideas.discard(owner_user=user, idea_id=idea_id)
            said, note = ideas.DROPPED, f"Dropped backlog idea {idea.id} on his no."
        elif (arguments.get("text") or "").strip():
            idea = ideas.draft(
                household=household_of(request), owner_user=user, text=arguments["text"]
            )
            said = ideas.readback(idea)
            note = (
                f"Read back backlog idea {idea.id} and asked whether to add it; nothing is saved. "
                f"Only on his yes in his next turn call add_backlog_idea with id {idea.id} and "
                f"confirm true; on a no, confirm false."
            )
        else:
            return refused(NEEDS_TEXT)
    except ideas.Refused as exc:
        return refused(str(exc))
    return ok(
        f"{note} Said to him: {said}",
        {
            SPEAK_VERBATIM: [said],
            SPEAK_EXACT: True,
            ENDS_TURN: True,
            FOR_MODEL: f"Done: said to him word for word. {note} The turn ends here.",
            REPLAY_AS: f"({note})",
            "id": str(idea.id),
            "status": idea.status,
        },
    )
