"""The late-answer path (design 2026-11-20):
work goes to the background while the conversation stays free, and Alfred says the result at
his next turn. DARK: nothing here reads or writes a row unless `LEGION_LATE_ANSWERS=on`.

Three parts, each a public function a door or the runner calls:

- **`intercept`** (a door, before any model call): his whole utterance, matched by code.
  "Look into X and tell me later" queues a task and answers at once with a code line; "anything
  pending?" and "cancel that" are answered by code. Anything else is not ours (None).
- **`run_due`** (`manage.py late_tasks`, driven by the warm-service tick): claims queued tasks,
  and running ones whose lease ran out, with `select_for_update(skip_locked=True)`, and runs each
  through the ordinary turn (`turn.run_turn`) under the read-only `background` spec, in its OWN
  conversation: never his live one, so his history, turn numbers and waiting yes are untouched.
  `writes: none` makes the guard refuse every write, so a background task cannot change anything.
  The answer becomes an owed line (`OwedLine`), built by code.
- **`claim`** (a door, at the start of his next turn): at most one owed line, claimed with one
  atomic update, so phone, web and head unit can never all say it. Said first, as a `sentence`
  with `role: "late"`; it is NOT put into that turn's model context, so a web-search answer cannot
  taint the turn he is in.

Never for a recruiter (every entry returns None for one), never on a head unit: a task is not
started from one (a rebuilt request would lose the unit's narrow scope) and nothing is said on
one (whoever holds the unit may not be him).

Logs carry numbers and names only, never the question or the answer.
"""

from __future__ import annotations

import logging
import re
import time
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta

from django.db import transaction
from django.db.models import F, Q, Sum
from django.utils import timezone as dj_timezone

from agents.legion import flags
from agents.models import (
    LATE_CANCELLED,
    LATE_DONE,
    LATE_FAILED,
    LATE_QUEUED,
    LATE_RUNNING,
    AgentConversation,
    AgentTurn,
    LateHeartbeat,
    LateTask,
    OwedLine,
)
from headunit_signin import scope as headunit_scope
from household.models import User, is_recruiter
from household.tenancy import household_of
from llm import cap as member_cap

log = logging.getLogger(__name__)

FEATURE = "agent_late"
# Each web search a task runs, metered at its grounding fee (the search's own tokens are in the
# `web_search` row the tool writes). ESTIMATE: Google's list price for a grounded request.
SEARCH_FEATURE = "agent_late_search"
SEARCH_FEE_MICRO_USD = 35_000
SPEC = "background"
SOURCE = "late_task"
CLIENT = "background"

# Limits (design section 1).
MAX_PER_DAY = 6
MAX_OPEN = 2
# What a member's background work may cost in a UTC day: model rounds (`FEATURE`) plus searches
# (`SEARCH_FEATURE`). Worst case of one task: 2 attempts x 30,000 (the spec's model cap) + 2
# searches x 35,000 = 130,000, so a day is at most this plus one task that started under it.
DAILY_MICRO_USD = 500_000
# Per task, whatever the attempts: at most 2 searches; per model round at most 2 calls.
MAX_SEARCHES = 2
MAX_CALLS_PER_ROUND = 2
# No call starts after this many seconds of a run. A model round that starts before the spec's 45 s
# turn limit is bounded by its own 12 s timeout, and a tool started before this by its own (a web
# search 10 s), so a run ends inside about 60 s: well inside the 120 s lease, so no tick claims a
# task still running.
RUN_SECONDS = 35
MAX_ATTEMPTS = 2
# One task a tick call (`late_tasks --limit 1`): the background spec's 45 s turn fits inside the
# scheduler's 60 s attempt deadline (`deploy/cloudrun/install_schedule.py`), and the lease outlives
# the turn, so no later tick claims a task still running.
LEASE = timedelta(seconds=120)
OWED_TTL = timedelta(hours=24)
# A task still queued this long after the ask is given up, its slot freed and a line owed: the
# answer would come too late to be the one he asked for.
QUEUE_TTL = timedelta(minutes=10)
# The scheduler ticks every minute; three minutes without a tick means no runner is behind an ask.
TICK_FRESH = timedelta(minutes=3)
MAX_SPOKEN_PER_DAY = 8
TOPIC_CHARS = 60
ANSWER_CHARS = 400

# Code's words. None names a person, a figure or a place.
ACK = "On it. I'll tell you when it's ready."
TOO_MANY_OPEN = "I'm already working on two things in the background. Ask again when one is done."
TOO_MANY_TODAY = "That's as many background tasks as I take in a day. Ask me again tomorrow."
SPENT = "Background work has used today's allowance, so I didn't start that one."
NOT_FROM_UNIT = "I can't start background work from the car yet. Ask me on the phone or the web."
CANCELLED = "Cancelled. I won't look into that any further."
STILL_WORKING = "Still working on {topics}."
LAST_ONE = "Nothing new. The last one was: {text}"
MORE = "{n} more {are} ready; ask what's pending."
FAILED_LINE = "I couldn't finish looking into {topic}."
EXPIRED_LINE = "I couldn't get to {topic} in time."
NO_RUNNER = "I can't run things in the background right now, so I didn't start that."
ASK_WHAT = "What should I look into? Say it with the topic, and I'll tell you later."
# A topic that is only a pronoun means nothing in the task's own, empty conversation.
_BARE_TOPICS = frozenset({"it", "that", "this", "them", "those", "these", "that one", "this one"})

_ASKS = (
    re.compile(
        r"^\W*(?:(?:can|could|would) you\s+|please\s+)*"
        r"(?:look into|find out|research|dig into|check on|figure out|look up)\s+(?P<q>.+?)"
        r"\W*\s+(?:and\s+)?(?:then\s+)?(?:tell|let) me(?: know)?\s+"
        r"(?:later|when (?:it'?s|you'?re|you are|it is) (?:done|ready|back))\W*$",
        re.IGNORECASE,
    ),
    re.compile(
        r"^\W*(?:(?:can|could|would) you\s+|please\s+)*"
        r"(?:look into|find out|research|dig into|check on|figure out|look up)\s+(?P<q>.+?)"
        r"\s+in the background\W*$",
        re.IGNORECASE,
    ),
    re.compile(
        r"^\W*(?:(?:can|could|would) you\s+|please\s+)*"
        r"get back to me (?:on|about)\s+(?P<q>.+?)\W*$",
        re.IGNORECASE,
    ),
)
_PENDING = re.compile(
    r"^\W*(?:is\s+)?(?:anything|what'?s|what is)\s+(?:pending|ready|back)(?:\s+yet)?\W*$"
    r"|^\W*(?:any|are there any)\s+(?:answers|results)\s+(?:yet|back|ready)\W*$",
    re.IGNORECASE,
)
# Names the background work: ours whenever a task is open.
_CANCEL = re.compile(
    r"^\W*(?:cancel|stop)\s+(?:the (?:research|background task|background work)"
    r"|what you(?:'re| are) looking into|looking into (?:that|it))\W*$",
    re.IGNORECASE,
)
# "Cancel it" / "cancel that": ours ONLY when the last thing said was our own acknowledgement;
# after a timer, a waiting change or the model's "shall I?", it is the model's (review item B).
_CANCEL_BARE = re.compile(r"^\W*cancel\s+(?:that|it)\W*$", re.IGNORECASE)


def enabled() -> bool:
    return flags.late_answers_enabled()


def _eligible(request) -> bool:
    user = getattr(request, "user", None)
    return enabled() and user is not None and user.is_authenticated and not is_recruiter(user)


def ask_of(words: str) -> str | None:
    """The question in "look into X and tell me later", or None."""
    for pattern in _ASKS:
        match = pattern.match(words or "")
        if match:
            question = match.group("q").strip(" ,.")
            return question or None
    return None


def _topic(question: str) -> str:
    text = " ".join(question.split())
    return text if len(text) <= TOPIC_CHARS else text[: TOPIC_CHARS - 3].rstrip() + "..."


def _midnight(now: datetime) -> datetime:
    return now.astimezone(UTC).replace(hour=0, minute=0, second=0, microsecond=0)


def spent_today_micro_usd(user, now: datetime | None = None) -> int:
    from llm.models import LlmUsage

    now = now or dj_timezone.now()
    total = LlmUsage.objects.filter(
        user=user, feature__in=(FEATURE, SEARCH_FEATURE), created_at__gte=_midnight(now)
    ).aggregate(spent=Sum("cost_estimate_micro_usd"))["spent"]
    return int(total or 0)


def _over_allowance(user) -> bool:
    return member_cap.over_cap(user) or spent_today_micro_usd(user) >= DAILY_MICRO_USD


def _mine(model, request):
    return model.objects.filter(household=household_of(request), owner_user=request.user)


@dataclass(frozen=True)
class Reply:
    """Code's answer to a late-answer phrase. `owed` is set when the words carry an owed line's
    text: the door then keeps them out of the model's history (`history=False`) and, when that
    line was built on web pages (`read_third_party`), taints the turn and the next one."""

    text: str
    owed: OwedLine | None = None

    @property
    def history(self) -> bool:
        return self.owed is None


# The taint the agents door gives a turn that said a line built on web pages, and the one it gives
# the turn after it (which does not carry on further). ADR 0090: no write follows outside text.
CARRY = "late_carry"
AFTER_CARRY = "late_after_carry"


def intercept(request, words: str, client: str, last_reply: str = "") -> Reply | None:
    """Code's whole answer to his utterance when it is a late-answer phrase, else None. Called by
    a door before any model call; a None leaves the turn exactly as it was. `last_reply` is the
    last thing the assistant said in this conversation."""
    if not _eligible(request):
        return None
    question = ask_of(words)
    if question is not None:
        return Reply(_start(request, question, client))
    if headunit_scope.is_headunit(request):
        # Whoever holds the unit may not be him: no topic, answer or cancel of his from there.
        return None
    if _PENDING.match(words or ""):
        return _pending(request, client)
    if _CANCEL.match(words or "") or (
        _CANCEL_BARE.match(words or "") and (last_reply or "").strip() == ACK
    ):
        words = _cancel(request)
        return Reply(words) if words is not None else None
    return None


def saw_tick(now: datetime | None = None) -> None:
    """The runner ran (`manage.py late_tasks`)."""
    LateHeartbeat.objects.update_or_create(pk=1, defaults={"seen_at": now or dj_timezone.now()})


def runner_is_fresh(now: datetime | None = None) -> bool:
    seen = LateHeartbeat.objects.filter(pk=1).values_list("seen_at", flat=True).first()
    return seen is not None and seen >= (now or dj_timezone.now()) - TICK_FRESH


def expire_stale(now: datetime | None = None, **mine) -> int:
    """Gives up tasks queued longer than `QUEUE_TTL` (all, or one member's with `household` and
    `owner_user`): failed, their slot free, one line owed each. Returns how many."""
    now = now or dj_timezone.now()
    stale = list(
        LateTask.objects.filter(status=LATE_QUEUED, created_at__lt=now - QUEUE_TTL, **mine)
    )
    for task in stale:
        if LateTask.objects.filter(pk=task.pk, status=LATE_QUEUED).update(
            status=LATE_FAILED, finished_at=now
        ):
            _owe(task, EXPIRED_LINE.format(topic=_topic(task.question)))
    return len(stale)


def _start(request, question: str, client: str) -> str:
    if headunit_scope.is_headunit(request):
        return NOT_FROM_UNIT
    if question.lower().strip(" .,!?") in _BARE_TOPICS:
        return ASK_WHAT
    expire_stale(household=household_of(request), owner_user=request.user)
    if not runner_is_fresh():
        return NO_RUNNER
    with transaction.atomic():
        # One ask at a time per member: the counts below and the create are one step, so two asks
        # at once cannot both take the last open slot.
        User.objects.select_for_update().filter(pk=request.user.pk).first()
        return _start_locked(request, question, client)


def _start_locked(request, question: str, client: str) -> str:
    mine = _mine(LateTask, request)
    if mine.filter(status__in=(LATE_QUEUED, LATE_RUNNING)).count() >= MAX_OPEN:
        return TOO_MANY_OPEN
    if mine.filter(created_at__gte=_midnight(dj_timezone.now())).count() >= MAX_PER_DAY:
        return TOO_MANY_TODAY
    if _over_allowance(request.user):
        return SPENT
    LateTask.objects.create(
        household=household_of(request),
        owner_user=request.user,
        origin_client=client,
        question=question,
    )
    log.warning("late_task status=queued")
    return ACK


def _pending(request, client: str) -> Reply | None:
    """Code's answer to "anything pending?" while the late-answer path has something to say;
    None (the model's turn, as before) when it has nothing: no task, no line in 24 hours."""
    expire_stale(household=household_of(request), owner_user=request.user)
    line, more = claim(request, client)
    if line is not None:
        return Reply(f"{line.text} {more}".strip(), owed=line)
    running = list(
        _mine(LateTask, request)
        .filter(status__in=(LATE_QUEUED, LATE_RUNNING))
        .order_by("created_at")
        .values_list("question", flat=True)
    )
    if running:
        return Reply(STILL_WORKING.format(topics=" and ".join(_topic(q) for q in running)))
    last = (
        _mine(OwedLine, request)
        .filter(delivered_at__gte=dj_timezone.now() - OWED_TTL)
        .order_by("-delivered_at")
        .first()
    )
    if last is not None:
        return Reply(LAST_ONE.format(text=last.text), owed=last)
    return None


def _cancel(request) -> str | None:
    """Cancels his newest open task; None (the model's turn: a timer, music, a waiting change)
    when none is open."""
    task = (
        _mine(LateTask, request)
        .filter(status__in=(LATE_QUEUED, LATE_RUNNING))
        .order_by("-created_at")
        .first()
    )
    if task is None:
        return None
    changed = LateTask.objects.filter(pk=task.pk, status__in=(LATE_QUEUED, LATE_RUNNING)).update(
        status=LATE_CANCELLED, finished_at=dj_timezone.now()
    )
    if changed and task.run_conversation:
        # A running task's turn stops at its next round or tool call (the loop's own check).
        AgentTurn.objects.filter(conversation_id=task.run_conversation).update(
            cancel_requested=True
        )
    log.warning("late_task status=cancelled")
    return CANCELLED


def claim(request, client: str) -> tuple[OwedLine | None, str]:
    """The one owed line to say first this turn, claimed for good, and the words for any others
    still waiting ("" when none). (None, "") when nothing is owed or it may not be said here."""
    if not _eligible(request) or headunit_scope.is_headunit(request):
        return None, ""
    now = dj_timezone.now()
    mine = _mine(OwedLine, request)
    if mine.filter(delivered_at__gte=_midnight(now)).count() >= MAX_SPOKEN_PER_DAY:
        return None, ""
    waiting = mine.filter(delivered_at__isnull=True, expires_at__gt=now).order_by("ready_at")
    for line in waiting[:3]:
        # One atomic update: a second door asking at the same moment updates nothing.
        if OwedLine.objects.filter(pk=line.pk, delivered_at__isnull=True).update(
            delivered_at=now, delivered_client=client
        ):
            line.delivered_at = now
            others = waiting.exclude(pk=line.pk).count()
            log.warning("late_owed delivered=1 waiting=%d", others)
            more = MORE.format(n=others, are="is" if others == 1 else "are") if others else ""
            return line, more
    return None, ""


def read_third_party(line: OwedLine) -> bool:
    """True when the owed line's answer was built on text other people wrote (its task read the
    web): the door marks the turn that says it, so memory never keeps it (ADR 0075, 0090)."""
    try:
        task_id = uuid.UUID(line.source_id)
    except ValueError:
        return False
    return line.source == SOURCE and LateTask.objects.filter(pk=task_id, tainted=True).exists()


def late_event(line: OwedLine, more: str = ""):
    """The owed line as the stream's first `sentence`: `role: "late"` (additive to wire v1; the
    contract's `owed` role stays write facts only)."""
    from agents.runtime import events as ev

    return ev.Event(
        "sentence", {"text": f"{line.text} {more}".strip(), "role": "late", "owed_id": str(line.pk)}
    )


# -- the runner --------------------------------------------------------------------------------


@dataclass
class _Claimed:
    task: LateTask
    give_up: bool


def _claim_due(now: datetime, limit: int) -> list[_Claimed]:
    out = []
    with transaction.atomic():
        due = (
            LateTask.objects.select_for_update(skip_locked=True)
            .filter(
                Q(status=LATE_QUEUED) | Q(status=LATE_RUNNING, lease_until__lt=now),
            )
            .order_by("created_at")[:limit]
        )
        for task in due:
            if task.attempts >= MAX_ATTEMPTS:
                task.status = LATE_FAILED
                task.finished_at = now
                task.save(update_fields=["status", "finished_at"])
                out.append(_Claimed(task, give_up=True))
                continue
            task.status = LATE_RUNNING
            task.attempts += 1
            task.lease_until = now + LEASE
            task.started_at = task.started_at or now
            task.save(update_fields=["status", "attempts", "lease_until", "started_at"])
            out.append(_Claimed(task, give_up=False))
    return out


def member_request(user):
    """A DRF request as `user`, signed in like a web member (no device token): what the turn code
    needs to run tools under their `visible()`. Never a head-unit request (none is queued). The
    eval runner builds the same (`agents/evals/runner.py`); runtime code does not import evals."""
    from django.test.client import RequestFactory
    from rest_framework.request import Request

    raw = RequestFactory().post("/api/agents/turn", data={}, content_type="application/json")
    raw._force_auth_user = user
    raw._force_auth_token = None
    return Request(raw)


def _owe(task: LateTask, text: str) -> None:
    OwedLine.objects.get_or_create(
        household_id=task.household_id,
        source=SOURCE,
        source_id=str(task.pk),
        defaults={
            "owner_user_id": task.owner_user_id,
            "text": text,
            "expires_at": dj_timezone.now() + OWED_TTL,
        },
    )


def _answer_text(text: str) -> str:
    text = " ".join((text or "").split())
    if len(text) <= ANSWER_CHARS:
        return text
    cut = text[:ANSWER_CHARS]
    end = max(cut.rfind(". "), cut.rfind("? "), cut.rfind("! "))
    return cut[: end + 1] if end > 0 else cut.rstrip() + "..."


NO_MORE_SEARCHES = (
    "Not searched: this background task has used its searches. Answer with what you have."
)
TOO_MANY_CALLS = "Not run: one step may make at most two calls. Answer with what you have."
TIME_UP = "Not run: this background task is out of time. Answer with what you have."


class _ToolLimit:
    """The runner's cap on one task's calls (`LegionGateway.limit`): at most `MAX_CALLS_PER_ROUND`
    a round and `MAX_SEARCHES` searches a task (counted on the row, so a second attempt shares
    them). A search it lets through is metered at once at `SEARCH_FEE_MICRO_USD`."""

    def __init__(self, task: LateTask, clock=time.monotonic):
        self.task = task
        self._clock = clock
        self._started = clock()
        self._round = -1
        self._in_round = 0

    def __call__(self, state, call) -> str | None:
        if self._clock() - self._started > RUN_SECONDS:
            return TIME_UP
        if state.budget.rounds != self._round:
            self._round, self._in_round = state.budget.rounds, 0
        self._in_round += 1
        if self._in_round > MAX_CALLS_PER_ROUND:
            return TOO_MANY_CALLS
        if call.name != "web_search":
            return None
        taken = LateTask.objects.filter(pk=self.task.pk, searches__lt=MAX_SEARCHES).update(
            searches=F("searches") + 1
        )
        if not taken:
            return NO_MORE_SEARCHES
        # Marked before the search runs, and never cleared: a later attempt that searches nothing
        # still owes a line built on what this one read.
        LateTask.objects.filter(pk=self.task.pk).update(tainted=True)
        from llm import client as llm_client

        llm_client.record(
            household=self.task.owner_user.household,
            feature=SEARCH_FEATURE,
            model="web_search",
            user=self.task.owner_user,
            cost_estimate_micro_usd=SEARCH_FEE_MICRO_USD,
        )
        return None


def _run_one(task: LateTask, model) -> str:
    from agents.legion.turn import run_turn

    started = time.monotonic()
    user = task.owner_user
    request = member_request(user)
    if _over_allowance(user):
        status, text = LATE_FAILED, FAILED_LINE.format(topic=_topic(task.question))
    else:
        conversation = AgentConversation.objects.create(
            household_id=task.household_id, owner_user=user, client=CLIENT
        )
        LateTask.objects.filter(pk=task.pk).update(run_conversation=conversation.pk)
        gen = run_turn(
            request,
            task.question,
            model,
            conversation=conversation,
            agent=SPEC,
            feature=FEATURE,
            do_prune=False,
            tool_limit=_ToolLimit(task),
        )
        try:
            while True:
                next(gen)
        except StopIteration as stop:
            state = stop.value
        answer = _answer_text(state.final_text or state.words)
        ok = state.status == "done" and bool(answer)
        status = LATE_DONE if ok else LATE_FAILED
        topic = _topic(task.question)
        text = f"About {topic}: {answer}" if ok else FAILED_LINE.format(topic=topic)
        mine = LateTask.objects.filter(pk=task.pk, attempts=task.attempts)
        mine.update(cost_micro_usd=state.budget.cost_micro_usd, answer=answer if ok else "")
        if state.tainted:
            mine.update(tainted=True)  # set, never cleared
    # A cancel that landed while it ran wins, and so does a later attempt that claimed the task
    # (`attempts` moved on): nothing is owed by this run.
    finished = LateTask.objects.filter(
        pk=task.pk, status=LATE_RUNNING, attempts=task.attempts
    ).update(status=status, finished_at=dj_timezone.now(), lease_until=None)
    if finished:
        _owe(task, text)
    log.warning(
        "late_task status=%s owed=%d ms=%d",
        status if finished else LATE_CANCELLED,
        int(bool(finished)),
        int((time.monotonic() - started) * 1000),
    )
    return status


def run_due(model_for, *, limit: int = 1, now: datetime | None = None) -> int:
    """Runs up to `limit` due tasks, one claimed at a time (so none waits out its lease behind
    another); returns how many it claimed. Does nothing with the switch off. `model_for()` makes
    the model client, once per task."""
    if not enabled():
        return 0
    saw_tick(now)
    expire_stale(now)
    count = 0
    for _ in range(limit):
        claimed = _claim_due(now or dj_timezone.now(), 1)
        if not claimed:
            break
        count += 1
        item = claimed[0]
        if item.give_up:
            _owe(item.task, FAILED_LINE.format(topic=_topic(item.task.question)))
            log.warning("late_task status=failed owed=1 ms=0")
            continue
        try:
            _run_one(item.task, model_for())
        except Exception:  # noqa: BLE001 - one task's failure never stops the others
            # The lease runs out and the next tick tries again, up to MAX_ATTEMPTS.
            log.warning("late_task status=error", exc_info=True)
    return count
