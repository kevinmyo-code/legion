"""Which member a `/api/claude/` request may act for (2026-11-24).

`views.py`'s module docstring recorded the gap this closes, as ADR 0067 accepted it for v1:
"the phone, the web, the laptop worker and the session hooks all hold ordinary household device
tokens; nothing here tells them apart". Household scoping (`household.tenancy.scoped`) is the only
fence those routes had, and `household.permissions.IsHouseholdMember` carries no role check, so
every member of the household reached every other member's Claude work: reading the requests Kevin
sent, posting `confirms: true` against a card that was waiting for HIS yes, queueing a task on his
Alienware, and heartbeating as his machine (`_touch_machine` binds `ClaudeMachine.token` to
whatever token beats, so the identity moved with it).

Three rules, and each one is about a row's owner, never about a role (ADR 0045: `owner` governs
membership only, and `IsHouseholdOwner` "must never appear on a data route"):

1. **A task is its creating member's** (`ClaudeTask.user`, set at create and at every mint).
   Another member's task answers exactly like a task that does not exist.
2. **A machine is the member whose worker heartbeats it** (`ClaudeMachine.token.user`). A machine
   no worker has ever beaten is bound to no token; it is nobody's laptop, it runs nothing, and it
   stays household-wide rather than becoming unreachable - except that nobody may BIND one that
   already holds another member's tasks, which would take that member's machine over
   (`machine_refusal`).
3. **The worker's own verbs belong to the worker bound to that machine.** Heartbeat, claim, finish
   and upload are the laptop's, not the phone's: a member's app token may not take over a machine
   identity, and nothing but the token the machine's own heartbeat bound may claim its queue or
   post its results.

**Why not `household.tenancy.visible()`**, which is this repo's idiom for a private row
(`OWNER_PATHS`): there is no such thing as a SHARED Claude task, and `visible()` reads a null
owner as shared by construction. These rows are one member's queue, not a household table with
private rows in it, so the fence is strict ownership. `views._owned_delivery` already took that
posture for a delivered file (ADR 0080, "the requesting member's own devices").
"""
from __future__ import annotations

from django.db.models import Q

from claude_session.models import ClaudeCodeSession, ClaudeMachine, ClaudeTask, ClaudeTaskFile
from household.models import DeviceToken
from household.tenancy import scoped

ANOTHER_MEMBERS_MACHINE = (
    "Nothing was changed. That machine is another member's laptop: it heartbeats with that "
    "member's own device token, and only that member's devices speak for it."
)

WORKER_VERB_REFUSAL = (
    "Nothing was changed. Only a Claude worker's own token may speak for a machine. A member's "
    "app token cannot heartbeat as a laptop, claim its queue or report its results."
)

NOT_THIS_MACHINES_WORKER = (
    "Nothing was changed. This token is not the worker bound to that machine. A machine's queue "
    "is claimed, finished and uploaded by the worker whose heartbeat named it, and by nothing "
    "else."
)


def _mine(user) -> Q:
    """Rule 2, as a filter: my worker's machines, plus every machine no worker has bound."""
    return Q(token__user=user) | Q(token__isnull=True)


def own_machines(request):
    return scoped(ClaudeMachine, request).filter(_mine(request.user))


def own_tasks(request):
    return scoped(ClaudeTask, request).filter(user=request.user)


def own_files(request):
    return scoped(ClaudeTaskFile, request).filter(task__user=request.user)


def own_sessions(request):
    return scoped(ClaudeCodeSession, request).filter(
        Q(machine__token__user=request.user) | Q(machine__token__isnull=True)
    )


def machine_refusal(request, machine: ClaudeMachine) -> str | None:
    """Rule 2 for a machine already read household-wide: None when this request may speak for it.

    The heartbeat and the session hook look a machine up across the household on purpose - the
    name is unique per household, so a narrowed lookup would miss another member's row and try to
    create a second one with the same name, which the unique constraint answers with a 500 rather
    than a refusal."""
    if machine.token_id is not None:
        if DeviceToken.objects.filter(pk=machine.token_id, user=request.user).exists():
            return None
        return ANOTHER_MEMBERS_MACHINE
    # Unbound, so rule 2 says nobody's - unless its own history says otherwise. A machine's
    # token is `SET_NULL`, and a machine a session hook named has none yet, so "unbound" is not
    # only "brand new": without this, a member could heartbeat as a machine that already holds
    # another member's work, bind it to their own token and lock that member's worker out of it
    # (every later heartbeat of theirs would read as another member's machine).
    if ClaudeTask.objects.filter(machine=machine).exclude(user=request.user).exists():
        return ANOTHER_MEMBERS_MACHINE
    return None


def worker_refusal(request) -> str | None:
    """Rule 3's first half: a worker's token, whatever machine it ends up naming.

    `is_claude_token` is true for a token bound to a machine, one named `claude-session-*` by the
    installer, or one issued with the `claude_worker` scope, so every real worker passes - a
    legacy worker token still carrying plain `write` included - and an ordinary app token does
    not. The inverse of `views._not_the_worker`, over the same predicate, so the two fences cannot
    disagree about what a worker is."""
    from claude_session.tokens import is_claude_token

    if is_claude_token(request):
        return None
    return WORKER_VERB_REFUSAL


def bound_refusal(request, machine: ClaudeMachine | None) -> str | None:
    """Rule 3 in full: this request's token is the one `machine`'s own heartbeat bound.

    Stricter than `worker_refusal` and subsumes it: a bound token is a Claude token by
    definition (`claude_session.tokens.worker_fence`). Fails closed for a machine that is None or
    that no worker has beaten - a queue nobody heartbeats for is a queue nobody claims."""
    auth = getattr(request, "auth", None)
    if (
        machine is not None
        and machine.token_id is not None
        and isinstance(auth, DeviceToken)
        and machine.token_id == auth.pk
    ):
        return None
    return NOT_THIS_MACHINES_WORKER
