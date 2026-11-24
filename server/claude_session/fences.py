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
   no worker has ever beaten is bound to no token, and it is nobody's: it runs nothing, no member
   may queue work onto it or name it in a workflow, it is on nobody's status screen and its
   sessions are nobody's to read. Nobody may BIND one that already holds another member's tasks
   either, which would take that member's machine over (`machine_refusal`). "Nobody's" is the
   whole of it, in the querysets and in the refusals both: reading the same NULL as
   "everybody's" in a queryset and "nobody may take it" in a refusal is what let one member's
   task on an unbound machine refuse its real worker's own next heartbeat.
   The cost, named rather than hidden: a machine whose session hooks have reported while its
   worker service was never running is on nobody's status screen until that worker's first
   heartbeat binds it. The first heartbeat after a hook still works - the heartbeat and the hooks
   look a machine up household-wide (`_machine_any`) and refuse through `machine_refusal`, never
   through `own_machines`.
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


def own_machines(request):
    """Rule 2, as a filter: the machines this member's own worker has bound, and no others.

    An unbound machine is nobody's on both sides of the fence. It used to be admitted here as
    "everybody's", which let a member queue work onto a machine that her worker will never claim
    while `machine_refusal` read that work as a reason to refuse the real worker's next
    heartbeat: one POST and the laptop went dark (`tools/claude_session/engine_client.py` treats
    a 403 as the engine being down and retries in silence)."""
    return scoped(ClaudeMachine, request).filter(token__user=request.user)


def own_tasks(request):
    return scoped(ClaudeTask, request).filter(user=request.user)


def own_files(request):
    return scoped(ClaudeTaskFile, request).filter(task__user=request.user)


def own_sessions(request):
    """Rule 2 again, through the session's machine: the sessions on the machines this member's
    own worker has bound. An unbound machine's sessions are nobody's to read, in both
    directions - a session of his on a machine whose worker has not beaten yet is not hers, and
    a session she reports on a machine name of her own is not his. What leaks here is prose, a
    session's `name` and its `last_prompt_summary`, not merely the fact that a session exists."""
    return scoped(ClaudeCodeSession, request).filter(machine__token__user=request.user)


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
    # Unbound, so rule 2 says nobody's - unless its own history says otherwise. No member can
    # queue work onto an unbound machine any more (`_mine`), so the tasks below are a machine
    # that WAS bound and whose `DeviceToken` row went (`SET_NULL`, on a user delete): without
    # this, another member could then heartbeat as it, bind it to their own token and lock that
    # member's worker out of it (every later heartbeat of theirs reading as another member's
    # machine).
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
