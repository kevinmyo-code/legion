"""The MCP protocol half: the official SDK, driven once per request.

engine-mcp ticket 01 (Kevin, 2026-10-02) put the MCP server inside Django,
and ticket 02's research found the SDK 2.2 is ASGI on paper but can be driven
per request from synchronous code. This module is that bridge:

- **A fresh low-level `Server` and Starlette app per request.** The session
  manager's `run()` may be entered once per instance, and nothing here is
  meant to outlive the request: spec 2026-07-28 is stateless, and a legacy
  (2025-11-25) client gets `stateless_http=True`, so no `Mcp-Session-Id` is
  ever minted and no instance needs sticky routing on Cloud Run.
- **The low-level server, not `MCPServer`.** The registry (`tools.py`) owns
  each tool's JSON Schema and its result, `is_error` included; the
  decorator-based server would derive both from Python signatures instead.
- **The ORM runs on the request's own thread.** The view drives the ASGI app
  with `async_to_sync`, and `tools/call` hops back with
  `sync_to_async(thread_sensitive=True)`, which asgiref runs on the thread that
  called `async_to_sync`. So a tool uses the request's database connection (and
  inside a test, the test's transaction), never a stray worker thread's. The
  SDK's own default - sync tools on an anyio worker thread - would open a
  connection per thread that nothing closes.
- **Host checking is Django's.** `ALLOWED_HOSTS` has already validated the
  Host header by the time the view runs, and there is no cookie this endpoint
  honours (device tokens only), so the SDK's DNS-rebinding guard - which
  answers 421 to any public host it was not told about - is switched off
  rather than taught a second copy of `ALLOWED_HOSTS`.
"""

from __future__ import annotations

import contextvars

import mcp_types as types
from asgiref.sync import sync_to_async
from mcp.server.lowlevel import Server
from mcp.server.transport_security import TransportSecuritySettings

from engine_mcp.tools import TOOLS, run_tool

MCP_PATH = "/mcp"

# Set by the view for the duration of one request. Read by `tools/call`.
CURRENT_REQUEST: contextvars.ContextVar = contextvars.ContextVar("engine_mcp_request")
# A dict the view owns; `tools/call` writes the tool's outcome into it so the
# audit row can record it without parsing the response body.
CALL_STATE: contextvars.ContextVar = contextvars.ContextVar("engine_mcp_call_state")

INSTRUCTIONS = (
    "LEGION's engine: one household's own records - events, checklists, the bought log, "
    "places, body, ledger, pantry, fleet. Every result says in words what happened; repeat "
    "it, and never report a write as done unless its result says it was committed. Rows "
    "marked UNVERIFIED and values marked ESTIMATES must be said as such."
)


def _as_mcp_tool(tool) -> types.Tool:
    return types.Tool(
        name=tool.name,
        title=tool.title,
        description=tool.description,
        input_schema=tool.input_schema,
        annotations=types.ToolAnnotations(
            title=tool.title,
            read_only_hint=not tool.writes,
            destructive_hint=tool.destructive,
            idempotent_hint=True if not tool.writes else None,
            open_world_hint=False,
        ),
    )


async def _list_tools(ctx, params) -> types.ListToolsResult:
    return types.ListToolsResult(tools=[_as_mcp_tool(tool) for tool in TOOLS])


async def _call_tool(ctx, params) -> types.CallToolResult:
    request = CURRENT_REQUEST.get()
    result = await sync_to_async(run_tool, thread_sensitive=True)(
        request, params.name, params.arguments or {}
    )
    state = CALL_STATE.get(None)
    if state is not None:
        state["outcome"] = result.outcome
    return types.CallToolResult(
        content=[types.TextContent(type="text", text=result.text)],
        structured_content=result.structured,
        is_error=result.is_error,
    )


def build_app():
    """One server, one app, one session manager: for one request."""
    server = Server(
        "legion-engine",
        version="1",
        instructions=INSTRUCTIONS,
        on_list_tools=_list_tools,
        on_call_tool=_call_tool,
    )
    app = server.streamable_http_app(
        streamable_http_path=MCP_PATH,
        json_response=True,
        stateless_http=True,
        transport_security=TransportSecuritySettings(enable_dns_rebinding_protection=False),
    )
    return app, server.session_manager


async def handle(scope: dict, body: bytes) -> tuple[int, list[tuple[bytes, bytes]], bytes]:
    """Run one HTTP request through the SDK and collect its answer."""
    app, session_manager = build_app()
    received = False

    async def receive():
        nonlocal received
        if received:
            return {"type": "http.disconnect"}
        received = True
        return {"type": "http.request", "body": body, "more_body": False}

    status = 500
    headers: list[tuple[bytes, bytes]] = []
    chunks: list[bytes] = []

    async def send(message):
        nonlocal status, headers
        if message["type"] == "http.response.start":
            status = message["status"]
            headers = list(message.get("headers", []))
        elif message["type"] == "http.response.body":
            chunks.append(message.get("body", b""))

    async with session_manager.run():
        await app(scope, receive, send)
    return status, headers, b"".join(chunks)
