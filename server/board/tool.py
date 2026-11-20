"""`board_status`: "what's in flight?" answered from the member's factory board, spoken by code.

The result follows the cascade's code-spoken contract (`engine_mcp.tools.SPEAK_VERBATIM` and kin,
`assistant.cascade.VERBATIM_TOOLS`): the lines `board.reading.spoken_lines` builds are said and
shown word for word and end the turn. The model is handed `FOR_MODEL` only: freshness and counts,
written by code, never a card's text. History replays the same. So card text, which agents write
and which could carry words planted by a web page an agent read, never enters the model's context
on this path. The tool is also in `assistant.read_through.THIRD_PARTY_TOOLS`: a turn that read the
board may not write.

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
    return ok(
        for_model,
        {
            SPEAK_VERBATIM: said,
            SPEAK_EXACT: True,
            ENDS_TURN: True,
            FOR_MODEL: for_model,
            REPLAY_AS: f"(Read the factory board aloud. {facts})",
        },
    )
