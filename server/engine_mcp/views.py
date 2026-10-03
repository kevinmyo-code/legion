"""`/mcp`: the engine's MCP endpoint (engine-mcp ticket 10).

What happens to one request, in order:

1. **The switch** (ticket 08, Kevin 2026-10-02: off by default). Unless
   `LEGION_MCP` is on, the answer is 404 in words, before authentication, so
   an engine that never opted in exposes nothing but that sentence.
2. **Device-token authentication only** (ticket 05). No session auth: the
   browser has no business here, and leaving it out means no cookie can ever
   carry a request to this endpoint. `IsHouseholdMember` refuses a user in no
   household. The token's scope is checked per TOOL in `tools.py`, because
   every MCP call is a POST whether it reads or writes
   (`scope_checked_per_call`).
3. **The per-token throttle** (ticket 08). Its own scope, keyed on the token,
   so a looping model spends its own budget and never the phone's.
4. **The SDK** (`server.py`) answers the JSON-RPC request.
5. **The audit row** (ticket 08): one `McpCall` per request that got past
   authentication - token, method, tool name, outcome. Never the arguments,
   never the result body.
"""

from __future__ import annotations

import json

from asgiref.sync import async_to_sync
from django.conf import settings
from django.http import HttpResponse
from drf_spectacular.utils import extend_schema
from rest_framework import exceptions
from rest_framework.throttling import SimpleRateThrottle
from rest_framework.views import APIView

from engine_mcp import server as mcp_server
from engine_mcp.models import McpCall, Outcome
from household.authentication import DeviceTokenAuthentication
from household.models import DeviceToken
from household.permissions import IsHouseholdMember
from household.tenancy import household_of

MCP_OFF = (
    "Nothing was read or written. This engine's MCP endpoint is switched off: the operator "
    "has not set LEGION_MCP=on in the server's environment (deploy/.env, or the Cloud Run "
    "service's variables). It is off by default."
)
GET_REFUSAL = (
    "Nothing was streamed. This engine answers MCP over POST only, one JSON response per "
    "request; it opens no event stream."
)

# Headers copied into the ASGI scope. The SDK reads the MCP routing headers,
# Accept and Content-Type; nothing else of the caller's is handed through.
_FORWARDED_HEADERS = ("CONTENT_TYPE", "CONTENT_LENGTH")


def mcp_enabled() -> bool:
    return bool(getattr(settings, "LEGION_MCP", False))


class McpTokenThrottle(SimpleRateThrottle):
    """One budget per device token. `ScopedRateThrottle` keys on the USER,
    which would put the phone and a looping Claude Code session - same person,
    two tokens - in one bucket, and the model would lock the phone out."""

    scope = "mcp"

    def get_cache_key(self, request, view):
        token = getattr(request, "auth", None)
        if not isinstance(token, DeviceToken):
            return None
        return self.cache_format % {"scope": self.scope, "ident": token.pk}


def _peek(body: bytes) -> tuple[str, str | None]:
    """The JSON-RPC method and, for `tools/call`, the tool name - for the
    audit row only. The SDK parses the body again and is the authority on
    what it means."""
    try:
        message = json.loads(body)
    except (ValueError, UnicodeDecodeError):
        return "unparseable", None
    if not isinstance(message, dict) or not isinstance(message.get("method"), str):
        return "unparseable", None
    method = message["method"]
    tool = None
    if method == "tools/call":
        params = message.get("params")
        if isinstance(params, dict) and isinstance(params.get("name"), str):
            tool = params["name"]
    return method, tool


def _scope_for(request, body: bytes) -> dict:
    meta = request.META
    headers = []
    for key, value in meta.items():
        if key.startswith("HTTP_") and key != "HTTP_AUTHORIZATION":
            name = key[5:].replace("_", "-").lower()
            headers.append((name.encode("latin-1"), str(value).encode("latin-1")))
    for key in _FORWARDED_HEADERS:
        if meta.get(key):
            headers.append((key.replace("_", "-").lower().encode("latin-1"), meta[key].encode()))
    if not meta.get("CONTENT_LENGTH"):
        headers.append((b"content-length", str(len(body)).encode()))
    host = request.get_host()
    return {
        "type": "http",
        "asgi": {"version": "3.0", "spec_version": "2.3"},
        "http_version": "1.1",
        "method": request.method,
        "scheme": "https" if request.is_secure() else "http",
        "path": mcp_server.MCP_PATH,
        "raw_path": mcp_server.MCP_PATH.encode(),
        "root_path": "",
        "query_string": b"",
        "headers": headers,
        "client": (meta.get("REMOTE_ADDR", ""), 0),
        "server": (host.split(":")[0], int(meta.get("SERVER_PORT") or 0) or None),
    }


class McpView(APIView):
    authentication_classes = [DeviceTokenAuthentication]
    permission_classes = [IsHouseholdMember]
    throttle_classes = [McpTokenThrottle]
    # `IsHouseholdMember` refuses a read-scoped token on every unsafe method;
    # here every call is a POST, so the scope is checked per tool instead.
    scope_checked_per_call = True

    def initial(self, request, *args, **kwargs):
        if not mcp_enabled():
            raise exceptions.NotFound(MCP_OFF)
        super().initial(request, *args, **kwargs)

    def throttled(self, request, wait):
        method, tool = _peek(request.body)
        _audit(request, method, tool, Outcome.THROTTLED)
        raise exceptions.Throttled(
            wait,
            detail=(
                "Nothing was read or written. This token has made too many MCP calls in a "
                "short time; the engine refused this one before running it."
            ),
        )

    # Not part of the REST contract `openapi.yaml` describes: MCP clients
    # discover tools through `tools/list`, not through the OpenAPI schema.
    @extend_schema(exclude=True)
    def get(self, request):
        return HttpResponse(
            json.dumps({"detail": GET_REFUSAL}),
            status=405,
            content_type="application/json",
            headers={"Allow": "POST"},
        )

    @extend_schema(exclude=True)
    def post(self, request):
        body = request.body
        method, tool = _peek(body)
        state: dict = {}
        request_token = mcp_server.CURRENT_REQUEST.set(request)
        state_token = mcp_server.CALL_STATE.set(state)
        try:
            status, headers, content = async_to_sync(mcp_server.handle)(
                _scope_for(request, body), body
            )
        except Exception:
            _audit(request, method, tool, Outcome.FAILED)
            raise
        finally:
            mcp_server.CURRENT_REQUEST.reset(request_token)
            mcp_server.CALL_STATE.reset(state_token)
        _audit(request, method, tool, _outcome(status, content, state))
        response = HttpResponse(content, status=status)
        for name, value in headers:
            if name.lower() in (b"content-length",):
                continue
            response[name.decode("latin-1")] = value.decode("latin-1")
        return response


def _outcome(status: int, content: bytes, state: dict) -> str:
    if "outcome" in state:
        return state["outcome"]
    if status >= 400:
        return Outcome.FAILED
    try:
        message = json.loads(content) if content else {}
    except ValueError:
        return Outcome.OK
    if isinstance(message, dict) and "error" in message:
        return Outcome.FAILED
    return Outcome.OK


def _audit(request, method: str, tool: str | None, outcome: str) -> None:
    token = request.auth if isinstance(request.auth, DeviceToken) else None
    McpCall.objects.create(
        household=household_of(request),
        token=token,
        token_name=token.name if token else "",
        token_scope=token.scope if token else "",
        method=method[:200],
        tool=tool[:200] if tool else None,
        outcome=outcome,
    )
