"""Which Claude machine a device token speaks for (memory-and-vault map, decision G).

ADR 0067: every laptop worker holds an ordinary household device token, named
`claude-session-<Name>` by `tools/claude_session/install.ps1`, and reports its machine's scope
(`full` for the Alienware, `listed` for the work laptop) on every heartbeat.
`claude_session.views._touch_machine` binds the token to the machine it heartbeats as
(`ClaudeMachine.token`). Memory and the ID vault read the binding here:

- **Memory** (Kevin, 2026-11-05: *"claude can write too"*): a token bound to a `full` machine may
  read and write memories; one bound to a `listed` machine may not (the work laptop never touches
  memory); a `claude-session-*` token bound to no machine yet is refused too, fail closed.
- **The ID vault** (ruling 5, "never readable by Claude"): refuses every `claude-session-*` token
  and every token bound to any machine.

**The honest limit, said where the rule lives.** The scope is what the worker reports, and the
token name is a convention of the installer. A member's token is a member's token: a worker
installed by hand under another name, or a phone token copied onto a laptop, is not recognised.
ADR 0067's own follow-up, "worker endpoints restricted to machine tokens", is what closes this.

**The `claude_worker` scope (architecture upgrade 2, 2026-11-10)** closes the other direction: a
token issued with it reaches only `WORKER_ROUTES`, whatever its name
(`household.authentication.DeviceTokenAuthentication` refuses the rest), and is always a worker's
token here (unbound until it heartbeats). Additive: a worker token issued before it keeps `write`
and every route until Kevin re-issues it.

`WORKER_ROUTES` is wider than `/api/claude/` on purpose, because the worker family sends the same
token to three more places: the memory review (`tools/claude_session/review_memories.py`,
`/api/member-memories/review/`), a book ship (`ship.py`: `/api/books/convert`,
`/api/books/library`, and undo.py's DELETE there) and a phone update ship (`release_apk.py`:
`/api/releases/`). Everything else is refused: memories outside the review, the vault, every
household table, `/mcp`.
"""
from __future__ import annotations

from household.models import DeviceToken

WORKER_TOKEN_PREFIX = "claude-session-"

# (path prefix, methods). Exact prefixes of what the worker family calls (see the module doc).
WORKER_ROUTES: tuple[tuple[str, frozenset[str]], ...] = (
    ("/api/claude/", frozenset({"GET", "POST", "PUT", "PATCH", "DELETE"})),
    ("/api/member-memories/review/", frozenset({"GET", "POST"})),
    ("/api/books/convert", frozenset({"POST"})),
    ("/api/books/library", frozenset({"POST", "DELETE"})),
    ("/api/releases/", frozenset({"GET", "POST"})),
)
_SAFE_EXTRA = frozenset({"HEAD", "OPTIONS"})


def worker_route_allowed(path: str, method: str) -> bool:
    """May a `claude_worker` token make this request?"""
    m = (method or "").upper()
    for prefix, methods in WORKER_ROUTES:
        base = prefix.rstrip("/")
        if path == base or path.startswith(base + "/"):
            return m in methods or m in _SAFE_EXTRA
    return False

FENCE_NONE = "none"  # not a Claude worker's token, as far as the engine can tell
FENCE_FULL = "full"
FENCE_LISTED = "listed"
FENCE_UNBOUND = "unbound"  # named like a worker's token, never heartbeated as a machine


def worker_fence(request) -> str:
    """What the engine knows about the Claude machine behind this request's token."""
    from claude_session.models import SCOPE_FULL, ClaudeMachine

    token = getattr(request, "auth", None)
    if not isinstance(token, DeviceToken):
        return FENCE_NONE
    scopes = set(ClaudeMachine.objects.filter(token=token).values_list("scope", flat=True))
    if scopes:
        # A token two machines ever claimed is only as wide as the narrower one.
        return FENCE_FULL if scopes == {SCOPE_FULL} else FENCE_LISTED
    if (
        token.name.startswith(WORKER_TOKEN_PREFIX)
        or token.scope == DeviceToken.SCOPE_CLAUDE_WORKER
    ):
        return FENCE_UNBOUND
    return FENCE_NONE


def is_claude_token(request) -> bool:
    """True for any token the engine can tie to a Claude worker, whatever its scope."""
    return worker_fence(request) != FENCE_NONE
