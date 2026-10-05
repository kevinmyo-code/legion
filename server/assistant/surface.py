"""The web assistant's tool surface (web-assistant ticket 04).

Ticket 04 (2026-10-04): "Everything the engine has" - the `/mcp` tools, the
bought log's four, and tick history (`last_ticked`), which moved to the
engine for this. So the web surface is the WHOLE registry, read from
`engine_mcp.tools.TOOLS` rather than listed again here: one registry, two
doors, and a tool added to the engine reaches both the day it lands.

The same list feeds the locked token's declarations (`views.AssistantSessionView`)
and the tool door's allow-list (`views.AssistantToolView`), so what Gemini is
told it may call and what the engine will run cannot disagree.

`EXCLUDED_FROM_WEB` is where a tool would go that the phone and Claude Code
may call but a browser may not. It is empty: nothing has been ruled out.
"""

from __future__ import annotations

from engine_mcp.tools import TOOLS, TOOLS_BY_NAME, WRITE_SCOPE_SENTENCE

EXCLUDED_FROM_WEB: frozenset[str] = frozenset()

WEB_TOOL_NAMES: tuple[str, ...] = tuple(t.name for t in TOOLS if t.name not in EXCLUDED_FROM_WEB)


def web_tools():
    return [TOOLS_BY_NAME[name] for name in WEB_TOOL_NAMES]


def web_description(tool) -> str:
    """The registry's description, less the device-token scope sentence: a
    signed-in member writes as themselves, and telling the model about a
    token it does not hold invites it to say so. The outcome rule stays."""
    return tool.description.replace(WRITE_SCOPE_SENTENCE, "")


def refusal_for(name: str) -> str | None:
    """None when the web assistant may call `name`; otherwise why not, in words."""
    if name in WEB_TOOL_NAMES:
        return None
    if name in TOOLS_BY_NAME:
        return (
            f"Nothing was read or written. {name} is not available to the assistant on the web; "
            f"it can be used from the phone app."
        )
    return (
        f"Nothing was read or written. The web assistant has no tool called {name!r}. If it is "
        f"something only LEGION's phone app does - music, mail, the car's live readings, "
        f"navigation, the garage, calls, alarms - it cannot be done from here. Tools here: "
        f"{', '.join(WEB_TOOL_NAMES)}."
    )
