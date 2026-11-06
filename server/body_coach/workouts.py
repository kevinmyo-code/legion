"""The workout programme's reads and writes: rows in, `programme.py`'s rules, rows out.

One place for the web, the phone and the engine tools (which call the routed views), so a target
Alfred says is the target the screen shows. Every read is scoped to the request's household.

**Where the history lives.** The session rows (`workout_sessions`, `workout_sets`) are the record
the rules read. Finishing a session ALSO writes the legacy `workout_set_logs` rows from its
actuals, once, with origin guids derived from the session, so the phone's existing training history
and progression charts keep working and a repeat finish writes nothing twice. A tick never writes a
legacy row; only finish does.
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass
from datetime import date, timedelta

from django.db import transaction
from django.db.models.functions import Now
from django.utils import timezone

from body_coach import programme as rules
from body_coach import programme_change as change
from body_coach.models import (
    SESSION_CANCELLED,
    SESSION_FINISHED,
    SESSION_IN_PROGRESS,
    STATUS_ACCEPTED,
    STATUS_DISMISSED,
    STATUS_EXPIRED,
    STATUS_PENDING,
    ProgrammeProposal,
    TrainingProgramme,
    WorkoutSession,
    WorkoutSet,
)
from body_coach.service import today_of
from household.tenancy import household_of, scoped
from legacy.enums import Provenance
from legacy.models.body import WorkoutSetLog

HISTORY_SESSIONS = 6
TRUST_REPORTED = "REPORTED"


class WorkoutRefused(Exception):
    def __init__(self, message: str, code: int = 400):
        super().__init__(message)
        self.message = message
        self.code = code


# =============================================================================
# Programme
# =============================================================================


def active_programme(request) -> TrainingProgramme | None:
    return scoped(TrainingProgramme, request).filter(active=True).first()


def ensure_programme(request) -> TrainingProgramme:
    row = active_programme(request)
    if row is not None:
        return row
    return TrainingProgramme.objects.create(
        household=household_of(request),
        template=rules.DEFAULT_TEMPLATE,
        training_days=",".join(rules.DEFAULT_TRAINING_DAYS),
        started_on=today_of(request),
    )


def parse_days(value) -> list[str]:
    if not isinstance(value, list) or not value:
        raise WorkoutRefused(
            "Nothing was saved. training_days must be a list of weekdays, e.g. "
            '["mon", "wed", "fri"].'
        )
    days = []
    for v in value:
        key = str(v).strip().lower()[:3]
        if key not in rules.WEEKDAYS:
            raise WorkoutRefused(f"Nothing was saved. {v!r} is not a weekday.")
        if key not in days:
            days.append(key)
    if len(days) > 6:
        raise WorkoutRefused("Nothing was saved. Keep at least one rest day a week.")
    return sorted(days, key=rules.WEEKDAYS.index)


def state_of(row: TrainingProgramme) -> change.State:
    return change.State(
        template=row.template if row.template in rules.TEMPLATES else rules.DEFAULT_TEMPLATE,
        training_days=tuple(row.day_list()),
        swaps={k: v for k, v in (row.swaps or {}).items() if v in rules.CATALOG},
        exclude_equipment=frozenset(e for e in (row.exclude_equipment or []) if e in rules.EQUIPMENT),
        avoid=frozenset(j for j in (row.avoid or []) if j in rules.JOINTS),
        cardio=bool(row.cardio),
    )


def filters_of(row: TrainingProgramme) -> rules.Filters:
    return state_of(row).filters


def programme_dict(row: TrainingProgramme | None) -> dict | None:
    if row is None:
        return None
    state = state_of(row)
    return {
        "id": str(row.id),
        "template": row.template,
        "template_name": rules.TEMPLATE_NAMES.get(state.template, state.template),
        "training_days": row.day_list(),
        "days_per_week": len(row.day_list()),
        # Kevin, 2026-11-06: sessions a week is the target; the weekdays above are inert.
        "sessions_per_week": len(row.day_list()),
        "swaps": {k: rules.CATALOG[v].name for k, v in (row.swaps or {}).items() if v in rules.CATALOG},
        "started_on": row.started_on.isoformat(),
        # body coach 2: the filters and every day of the template, so a voice can answer "what
        # is my programme" and "what would Upper 2 be" from one read.
        "exclude_equipment": sorted(state.exclude_equipment),
        "avoid": sorted(state.avoid),
        "cardio": state.cardio,
        "summary": change.summary(state),
        "days": change.days_preview(state),
    }


# =============================================================================
# History and the plan
# =============================================================================


def _counted_sessions(request, programme: TrainingProgramme):
    return scoped(WorkoutSession, request).filter(programme=programme).exclude(
        status=SESSION_CANCELLED
    )


def history_for(request, exercise_key: str, before: WorkoutSession | None = None) -> list[rules.Performance]:
    """Finished sessions with this exercise, newest first, as the rules read them."""
    sessions = scoped(WorkoutSession, request).filter(
        status=SESSION_FINISHED, sets__exercise_key=exercise_key
    )
    if before is not None:
        sessions = sessions.exclude(pk=before.pk)
    sessions = sessions.distinct().order_by("-finished_at", "-started_at")[:HISTORY_SESSIONS]
    out = []
    for session in sessions:
        rows = [r for r in session.sets.all() if r.exercise_key == exercise_key]
        planned = [r for r in rows if r.rule != rules.RULE_EXTRA]
        if not rows:
            continue
        # The range is the one the session was planned with, read from its own rows (body coach
        # 2, decision 8), so a later template change never rewrites a past session's range. The
        # rules read past a deload session, whose stored range is the shortened one.
        first = (planned or rows)[0]
        out.append(
            rules.Performance(
                sets=tuple(rules.DoneSet(r.reps_done, r.weight_lbs, r.done) for r in rows),
                planned_sets=len(planned) or len(rows),
                reps_low=first.reps_low,
                reps_high=first.reps_high,
                deload=session.deload,
            )
        )
    return out


@dataclass
class Plan:
    day_key: str
    session_number: int
    deload: bool
    targets: list[rules.Target]
    template: str = rules.DEFAULT_TEMPLATE
    location: str = rules.LOCATION_GYM

    @property
    def label(self) -> str:
        return rules.day_label(self.template, self.day_key)

    def as_dict(self) -> dict:
        return {
            "day_key": self.day_key,
            "day_label": self.label,
            "session_number": self.session_number,
            "deload": self.deload,
            "calibrating": any(t.calibrating for t in self.targets),
            "set_count": sum(t.sets for t in self.targets),
            "exercises": [target_dict(t) for t in self.targets],
        }


def target_dict(t: rules.Target) -> dict:
    return {
        "exercise_key": t.exercise_key,
        "name": t.name,
        "kind": t.kind,
        "slot_key": t.slot_key,
        "sets": t.sets,
        "reps_low": t.reps_low,
        "reps_high": t.reps_high,
        "unit": t.unit,
        "weight_lbs": t.weight_lbs,
        "calibrating": t.calibrating,
        "rule": t.rule,
        "words": t.words,
        "alternatives": list(t.alternatives),
    }


def session_filters(programme: TrainingProgramme, location: str | None) -> rules.Filters:
    """The programme's filters, and at home only a kettlebell and bodyweight (Kevin, 2026-11-06:
    "I'm at home today" switches that session to home variants)."""
    filters = filters_of(programme)
    return rules.at_home(filters) if location == rules.LOCATION_HOME else filters


def next_plan(
    request,
    programme: TrainingProgramme,
    day_key: str | None = None,
    location: str | None = None,
) -> Plan:
    counted = _counted_sessions(request, programme)
    state = state_of(programme)
    finished = counted.filter(status=SESSION_FINISHED)
    # After a split change the new template starts from its first day (decision 9).
    since = (
        finished.filter(finished_at__gte=programme.template_since)
        if programme.template_since
        else finished
    )
    last = since.order_by("-finished_at").first()
    day = day_key or rules.next_day_key(last.day_key if last else None, state.template)
    number = finished.count() + 1
    deload = rules.is_deload(number, len(programme.day_list()))
    filters = session_filters(programme, location)
    targets = [
        rules.target_for(slot, ex, history_for(request, ex), deload=deload)
        for slot, ex in rules.slots_for(state.template, day, state.swaps, filters)
    ]
    return Plan(day, number, deload, targets, state.template, location or rules.LOCATION_GYM)


# =============================================================================
# Sessions
# =============================================================================


def in_progress(request) -> WorkoutSession | None:
    return scoped(WorkoutSession, request).filter(status=SESSION_IN_PROGRESS).first()


def _materialise(session: WorkoutSession, targets: list[rules.Target], order_from: int = 0) -> None:
    WorkoutSet.objects.bulk_create(
        [
            WorkoutSet(
                household=session.household,
                session=session,
                slot_key=t.slot_key,
                slot_order=order_from + i,
                exercise_key=t.exercise_key,
                exercise_name=t.name,
                kind=t.kind,
                set_number=n,
                reps_low=t.reps_low,
                reps_high=t.reps_high,
                target_weight_lbs=t.weight_lbs,
                rule=t.rule,
                rule_words=t.words,
            )
            for i, t in enumerate(targets)
            for n in range(1, t.sets + 1)
        ]
    )


def _relocate(request, session: WorkoutSession, location: str) -> WorkoutSession:
    """Rebuild a running session for gym or home, only while none of its sets is ticked."""
    if session.location == location or session.sets.filter(done=True).exists():
        return session
    plan = next_plan(request, session.programme, session.day_key, location)
    session.sets.all().delete()
    session.location = location
    session.save(update_fields=["location"])
    _materialise(session, plan.targets)
    return session


def start(
    request,
    session_id: uuid.UUID | None = None,
    day_key: str | None = None,
    location: str | None = None,
):
    """Start today's session, or return the one already running. Returns (session, created).
    `location` "home" builds it from home variants; a running session with nothing ticked yet
    is rebuilt for the location asked."""
    if day_key is not None and day_key not in rules.ALL_DAY_KEYS:
        raise WorkoutRefused(f"Nothing was started. day_key must be A to D; got {day_key!r}.")
    if location is not None and location not in rules.LOCATIONS:
        raise WorkoutRefused(f"Nothing was started. location must be gym or home; got {location!r}.")
    with transaction.atomic():
        if session_id is not None:
            existing = scoped(WorkoutSession, request).filter(pk=session_id).first()
            if existing is not None:
                return existing, False
            if WorkoutSession.objects.filter(pk=session_id).exists():
                raise WorkoutRefused("Nothing was started. That session id is taken.", 409)
        running = in_progress(request)
        if running is not None:
            if location is not None:
                running = _relocate(request, running, location)
            return running, False
        programme = ensure_programme(request)
        keys = rules.day_keys(state_of(programme).template)
        if day_key is not None and day_key not in keys:
            raise WorkoutRefused(
                f"Nothing was started. This programme's days are {', '.join(keys)}; got {day_key!r}."
            )
        plan = next_plan(request, programme, day_key, location)
        now = timezone.now()
        session = WorkoutSession.objects.create(
            id=session_id or uuid.uuid4(),
            household=household_of(request),
            programme=programme,
            day_key=plan.day_key,
            session_number=plan.session_number,
            deload=plan.deload,
            location=plan.location,
            session_date=today_of(request),
            started_at=now,
        )
        _materialise(session, plan.targets)
    return session, True


def _session_or_refusal(request, session_id, verb: str) -> WorkoutSession:
    if session_id is None:
        session = in_progress(request)
        if session is None:
            raise WorkoutRefused(f"Nothing was {verb}. No workout is in progress.", 409)
        return session
    session = scoped(WorkoutSession, request).filter(pk=session_id).first()
    if session is None:
        raise WorkoutRefused(f"Nothing was {verb}. There is no workout with that id.", 404)
    return session


def _check_actuals(reps, weight) -> None:
    if reps is not None and (isinstance(reps, bool) or not isinstance(reps, int) or not 0 <= reps <= 1000):
        raise WorkoutRefused(f"Nothing was saved. reps must be a whole number 0 to 1000; got {reps!r}.")
    if weight is not None and (
        isinstance(weight, bool) or not isinstance(weight, (int, float)) or not 0 <= weight < 2000
    ):
        raise WorkoutRefused(
            f"Nothing was saved. weight_lbs must be a number 0 to 2000; got {weight!r}."
        )


def _apply(row: WorkoutSet, done: bool, reps, weight) -> None:
    row.done = done
    if done:
        row.reps_done = reps if reps is not None else (row.reps_done if row.reps_done is not None else row.reps_high)
        if weight is not None:
            row.weight_lbs = float(weight)
        elif row.weight_lbs is None:
            row.weight_lbs = row.target_weight_lbs
        row.done_at = timezone.now()
    else:
        row.done_at = None
        if reps is not None:
            row.reps_done = reps
        if weight is not None:
            row.weight_lbs = float(weight)
    row.save()


def update_sets(request, session_id, changes) -> WorkoutSession:
    """Per-set upserts keyed by (exercise_key, set_number). Idempotent: a replay changes nothing."""
    if not isinstance(changes, list) or not changes:
        raise WorkoutRefused("Nothing was saved. sets must be a non-empty list.")
    session = _session_or_refusal(request, session_id, "saved")
    if session.status == SESSION_CANCELLED:
        raise WorkoutRefused("Nothing was saved. That workout was cancelled.", 409)
    with transaction.atomic():
        rows = {(r.exercise_key, r.set_number): r for r in session.sets.select_for_update()}
        for change in changes:
            if not isinstance(change, dict):
                raise WorkoutRefused("Nothing was saved. Each set must be a JSON object.")
            key = (change.get("exercise_key"), change.get("set_number"))
            row = rows.get(key)
            if row is None:
                raise WorkoutRefused(
                    f"Nothing was saved. This workout has no set {key[1]!r} of {key[0]!r}."
                )
            reps, weight = change.get("reps"), change.get("weight_lbs")
            _check_actuals(reps, weight)
            _apply(row, bool(change.get("done", True)), reps, weight)
    return session


def log_by_name(request, exercise: str, reps, weight, session_id=None):
    """"Log bench 135 for 8": ticks the next open set of the named exercise in today's workout,
    starting it when none is running. A set past the plan is kept as an extra set."""
    _check_actuals(reps, weight)
    if not exercise or not str(exercise).strip():
        raise WorkoutRefused("Nothing was logged. Say which exercise.")
    if session_id is not None:
        session = _session_or_refusal(request, session_id, "logged")
        started = False
    else:
        session, started = start(request)
    if session.status != SESSION_IN_PROGRESS:
        raise WorkoutRefused(f"Nothing was logged. That workout is {session.status}.", 409)
    rows = list(session.sets.order_by("slot_order", "set_number"))
    keys = list(dict.fromkeys(r.exercise_key for r in rows))
    key = rules.match_exercise(str(exercise), keys)
    if key is None:
        names = ", ".join(dict.fromkeys(r.exercise_name for r in rows))
        raise WorkoutRefused(
            f"Nothing was logged. {exercise!r} is not one exercise in today's workout (Day "
            f"{session.day_key}: {names})."
        )
    mine = [r for r in rows if r.exercise_key == key]
    open_row = next((r for r in mine if not r.done), None)
    with transaction.atomic():
        if open_row is None:
            last = mine[-1]
            open_row = WorkoutSet.objects.create(
                household=session.household,
                session=session,
                slot_key=last.slot_key,
                slot_order=last.slot_order,
                exercise_key=last.exercise_key,
                exercise_name=last.exercise_name,
                kind=last.kind,
                set_number=last.set_number + 1,
                reps_low=last.reps_low,
                reps_high=last.reps_high,
                target_weight_lbs=last.target_weight_lbs,
                rule=rules.RULE_EXTRA,
                rule_words="An extra set past the plan.",
            )
        _apply(open_row, True, reps, weight)
    return session, open_row, started


def swap(request, exercise: str, to: str | None):
    """Swap a slot's exercise for one of its alternatives, on the programme and in a running
    workout (sets of it not yet ticked). Returns (old_key, new_key, session or None)."""
    programme = ensure_programme(request)
    state = state_of(programme)
    filters = state.filters
    held = {ex: slots[0] for ex, slots in change.held(state).items()}
    key = rules.match_exercise(str(exercise or ""), list(held))
    if key is None:
        names = ", ".join(rules.CATALOG[k].name for k in held)
        raise WorkoutRefused(
            f"Nothing was swapped. {exercise!r} is not one exercise in the programme ({names})."
        )
    slot = held[key]
    new = rules.swap_choice(slot, key, to)
    # The programme's equipment and joints bind an immediate swap too (body coach 2): a swap to
    # something it filters out would not show up in the workout, so it is refused, or skipped
    # when no exercise was named.
    if new is not None and not to:
        choices = list(slot.choices())
        i = choices.index(key) if key in choices else 0
        turn = choices[i + 1:] + choices[:i]
        options = [k for k in turn if k not in held and filters.allows(k)]
        new = options[0] if options else None
    elif new is not None and filters.why_not(new):
        raise WorkoutRefused(
            f"Nothing was swapped. {rules.CATALOG[new].name} {filters.why_not(new)}, which the "
            f"programme is set to avoid."
        )
    if new is None:
        allowed = ", ".join(
            rules.CATALOG[k].name for k in slot.choices() if k != key and filters.allows(k)
        )
        raise WorkoutRefused(
            f"Nothing was swapped. {rules.CATALOG[key].name} can be swapped for: "
            f"{allowed or 'nothing the programme allows'}."
        )
    session = in_progress(request)
    with transaction.atomic():
        swaps = dict(programme.swaps or {})
        if new == slot.exercise:
            swaps.pop(slot.key, None)
        else:
            swaps[slot.key] = new
        programme.swaps = swaps
        programme.updated_at = Now()
        programme.save(update_fields=["swaps", "updated_at"])
        if session is not None:
            rows = list(session.sets.filter(slot_key=slot.key))
            if rows and not any(r.done for r in rows):
                order = rows[0].slot_order
                session.sets.filter(slot_key=slot.key).delete()
                target = rules.target_for(
                    slot, new, history_for(request, new), deload=session.deload
                )
                _materialise(session, [target], order_from=order)
            else:
                session = None
    return key, new, session


def finish(request, session_id) -> tuple[WorkoutSession, bool]:
    session = _session_or_refusal(request, session_id, "finished")
    if session.status == SESSION_FINISHED:
        return session, False
    if session.status == SESSION_CANCELLED:
        raise WorkoutRefused("Nothing was logged. That workout was cancelled.", 409)
    with transaction.atomic():
        session.status = SESSION_FINISHED
        session.finished_at = timezone.now()
        session.save(update_fields=["status", "finished_at"])
        _write_legacy(session)
    return session, True


def cancel(request, session_id) -> WorkoutSession:
    session = _session_or_refusal(request, session_id, "cancelled")
    if session.status == SESSION_FINISHED:
        raise WorkoutRefused(
            "Nothing was cancelled. That workout is already finished and logged.", 409
        )
    session.status = SESSION_CANCELLED
    session.save(update_fields=["status"])
    return session


def _write_legacy(session: WorkoutSession) -> None:
    """One `workout_set_logs` row per exercise and (reps, weight), from the done sets. Timed
    exercises are skipped: that table's `reps` would read seconds as reps."""
    groups: dict[tuple, int] = {}
    names: dict[str, str] = {}
    for row in session.sets.filter(done=True).order_by("slot_order", "set_number"):
        if row.kind in (rules.KIND_TIMED, rules.KIND_CARDIO):
            continue
        key = (row.exercise_key, row.reps_done, row.weight_lbs)
        groups[key] = groups.get(key, 0) + 1
        names[row.exercise_key] = row.exercise_name
    for (ex, reps, weight), count in groups.items():
        guid = f"workout:{session.id}:{ex}:{reps}:{weight}"
        if WorkoutSetLog.objects.filter(household=session.household, origin_guid=guid).exists():
            continue
        WorkoutSetLog.objects.create(
            id=uuid.uuid4(),
            household=session.household,
            exercise=names[ex],
            sets=count,
            reps=reps if reps else None,
            weight_value=weight,
            weight_unit="lbs" if weight is not None else None,
            logged_at=session.finished_at,
            trust_tier=TRUST_REPORTED,
            provenance=Provenance.USER,
            created_at=Now(),
            updated_at=Now(),
            deleted_at=None,
            origin_guid=guid,
        )


# =============================================================================
# Bodies
# =============================================================================


def set_dict(row: WorkoutSet) -> dict:
    return {
        "id": str(row.id),
        "exercise_key": row.exercise_key,
        "name": row.exercise_name,
        "kind": row.kind,
        "slot_key": row.slot_key,
        "set_number": row.set_number,
        "reps_low": row.reps_low,
        "reps_high": row.reps_high,
        "unit": rules.unit_of(row.kind),
        "target_weight_lbs": row.target_weight_lbs,
        "rule": row.rule,
        "words": row.rule_words,
        "done": row.done,
        "reps": row.reps_done,
        "weight_lbs": row.weight_lbs,
    }


def session_dict(session: WorkoutSession | None) -> dict | None:
    if session is None:
        return None
    rows = list(session.sets.order_by("slot_order", "set_number"))
    planned = [r for r in rows if r.rule != rules.RULE_EXTRA]
    return {
        "id": str(session.id),
        "day_key": session.day_key,
        "day_label": rules.day_label(state_of(session.programme).template, session.day_key),
        "session_number": session.session_number,
        "location": session.location,
        "deload": session.deload,
        "status": session.status,
        "session_date": session.session_date.isoformat(),
        "started_at": session.started_at.isoformat(),
        "finished_at": session.finished_at.isoformat() if session.finished_at else None,
        "sets_done": sum(1 for r in rows if r.done),
        "set_count": len(planned),
        "calibrating": any(r.rule == rules.RULE_CALIBRATE for r in rows),
        "sets": [set_dict(r) for r in rows],
    }


def week_start(day: date) -> date:
    return day - timedelta(days=day.weekday())


def week_dict(request, programme: TrainingProgramme | None, today: date) -> dict:
    start_day = week_start(today)
    finished = (
        scoped(WorkoutSession, request)
        .filter(status=SESSION_FINISHED, session_date__gte=start_day, session_date__lt=start_day + timedelta(days=7))
        .order_by("session_date", "finished_at")
    )
    done = [{"date": s.session_date.isoformat(), "day_key": s.day_key} for s in finished]
    planned = len(programme.day_list()) if programme else len(rules.DEFAULT_TRAINING_DAYS)
    days = programme.day_list() if programme else list(rules.DEFAULT_TRAINING_DAYS)
    return {
        "week_start": start_day.isoformat(),
        "done": len(done),
        "planned": planned,
        "sessions": done,
        "training_days": days,
    }


def today_body(request) -> dict:
    today = today_of(request)
    programme = active_programme(request)
    running = in_progress(request)
    finished_today = (
        scoped(WorkoutSession, request)
        .filter(status=SESSION_FINISHED, session_date=today)
        .order_by("-finished_at")
        .first()
    )
    week = week_dict(request, programme, today)
    # Days are flexible (Kevin, 2026-11-06): a session is due while this week's count is short.
    training_day = week["done"] < week["planned"]
    plan = None
    if running is None:
        plan = (
            next_plan(request, programme)
            if programme
            else _default_plan(request)
        ).as_dict()
    words = _today_words(running, finished_today, plan, week, training_day)
    return {
        "programme": programme_dict(programme),
        "today": today.isoformat(),
        "training_day": training_day,
        "week": week,
        "session": session_dict(running or finished_today),
        "next": plan,
        # body coach 2: a programme change waiting for Accept or Dismiss, or None.
        "proposal": proposal_dict(pending_proposal(request)),
        "words": words,
    }


def _default_plan(request) -> Plan:
    """What the first workout would be before any programme row exists (nothing is written)."""
    targets = [
        rules.target_for(slot, ex, history_for(request, ex))
        for slot, ex in rules.slots_for(rules.DEFAULT_TEMPLATE, "A")
    ]
    return Plan("A", 1, False, targets)


def describe_target(t: dict) -> str:
    reps = f"{t['reps_low']}-{t['reps_high']}" if t["reps_low"] != t["reps_high"] else f"{t['reps_low']}"
    unit = {"seconds": " s", "minutes": " min"}.get(t["unit"], "")
    weight = t.get("weight_lbs", t.get("target_weight_lbs"))
    if t["kind"] != rules.KIND_WEIGHTED:
        at = ""
    elif weight is None:
        at = " @ your pick"
    else:
        at = f" @ {weight:g} lb"
    return f"{t['name']} {t['sets']} x {reps}{unit}{at}"


def _today_words(running, finished_today, plan, week, training_day) -> str:
    tail = f" This week: {week['done']} of {week['planned']} sessions."
    if running is not None:
        d = session_dict(running)
        return (
            f"{d['day_label']} is in progress: {d['sets_done']} of {d['set_count']} sets "
            f"ticked.{tail}"
        )
    if finished_today is not None:
        d = session_dict(finished_today)
        return f"Today's workout ({d['day_label']}) is logged: {d['sets_done']} sets.{tail}"
    lines = "; ".join(describe_target(t) for t in plan["exercises"])
    lead = "Next session" if training_day else "This week's sessions are done. The next one"
    extra = ""
    if plan["calibrating"]:
        extra = " Calibration: where it says your pick, choose a weight you could lift for 2 more reps than the top of the range."
    if plan["deload"]:
        extra += " This is a deload session: lighter and one set fewer."
    return f"{lead} is {plan['day_label']}: {lines}.{extra}{tail}"


def finish_words(session: WorkoutSession, request) -> str:
    d = session_dict(session)
    programme = session.programme
    nxt = next_plan(request, programme)
    done_day = {
        ex: rules.target_for(slot, ex, history_for(request, ex))
        for slot, ex in rules.slots_for(
            state_of(programme).template,
            session.day_key,
            programme.swaps,
            session_filters(programme, session.location),
        )
    }
    ups = [t for t in done_day.values() if t.rule == rules.RULE_ADD]
    up_words = (
        " Next time up: " + ", ".join(f"{t.name} to {t.weight_lbs:g} lb" for t in ups) + "."
        if ups
        else ""
    )
    return (
        f"Logged {d['day_label']}: {d['sets_done']} of {d['set_count']} sets.{up_words} Next "
        f"workout is {nxt.label}."
    )


# =============================================================================
# Programme change proposals (body coach 2, 2026-11-06). Rules in `programme_change.py`.
# =============================================================================


def trained_exercises(request) -> set[str]:
    """Exercises with a finished, done set on file: their progress carries to a new programme."""
    return set(
        scoped(WorkoutSet, request)
        .filter(done=True, session__status=SESSION_FINISHED)
        .values_list("exercise_key", flat=True)
        .distinct()
    )


def pending_proposal(request) -> ProgrammeProposal | None:
    return scoped(ProgrammeProposal, request).filter(status=STATUS_PENDING).first()


def proposal_dict(row: ProgrammeProposal | None) -> dict | None:
    if row is None:
        return None
    after = change.State.from_dict(row.after)
    return {
        "id": str(row.id),
        "status": row.status,
        "request": row.request,
        "before": row.before,
        "after": row.after,
        "after_summary": change.summary(after),
        "after_days": change.days_preview(after),
        "diff": row.diff,
        "words": row.words,
        "created_at": row.created_at.isoformat() if row.created_at else None,
    }


def propose(request, data) -> ProgrammeProposal:
    """Build a proposal from structured choices and store it pending; an older pending one
    expires. Nothing about the programme itself changes here."""
    try:
        choices = change.read_choices(data)
        programme = ensure_programme(request)
        before = state_of(programme)
        after = change.build_after(before, choices)
    except change.ChangeRefused as exc:
        raise WorkoutRefused(exc.message) from exc
    if after == before:
        raise WorkoutRefused(
            f"Nothing was proposed. The programme already is that: {change.summary(before)}."
        )
    d = change.diff(before, after, trained_exercises(request))
    with transaction.atomic():
        scoped(ProgrammeProposal, request).filter(status=STATUS_PENDING).update(
            status=STATUS_EXPIRED, decided_at=Now()
        )
        row = ProgrammeProposal.objects.create(
            household=household_of(request),
            programme=programme,
            request=choices.as_dict(),
            before=before.as_dict(),
            after=after.as_dict(),
            diff=d,
            words=change.words(before, after, d, choices),
        )
    row.refresh_from_db()
    return row


def _proposal_or_refusal(request, proposal_id, verb: str) -> ProgrammeProposal:
    if proposal_id is None:
        row = pending_proposal(request)
        if row is None:
            raise WorkoutRefused(
                f"Nothing was {verb}. There is no pending programme proposal; the programme is as "
                f"it was.",
                404,
            )
        return row
    row = scoped(ProgrammeProposal, request).filter(pk=proposal_id).first()
    if row is None:
        raise WorkoutRefused(f"Nothing was {verb}. There is no programme proposal with that id.", 404)
    if row.status != STATUS_PENDING:
        raise WorkoutRefused(f"Nothing was {verb}. That proposal is already {row.status}.", 409)
    return row


def accept_proposal(request, proposal_id) -> tuple[ProgrammeProposal, TrainingProgramme]:
    """Apply the stored "after" to the active programme, in place (decision 9). Refused, with
    nothing changed, when the programme moved since the proposal was made. A running workout is
    left as it is; the change starts with the next one."""
    with transaction.atomic():
        row = _proposal_or_refusal(request, proposal_id, "accepted")
        programme = (
            scoped(TrainingProgramme, request)
            .select_for_update()
            .filter(pk=row.programme_id)
            .first()
        )
        if (
            programme is None
            or not programme.active
            or state_of(programme).as_dict() != row.before
        ):
            raise WorkoutRefused(
                "Nothing was accepted. The programme changed after this proposal was made; ask "
                "for the change again.",
                409,
            )
        after = change.State.from_dict(row.after)
        fields = [
            "template",
            "training_days",
            "swaps",
            "exclude_equipment",
            "avoid",
            "cardio",
            "updated_at",
        ]
        if after.template != programme.template:
            programme.template_since = timezone.now()
            fields.append("template_since")
        programme.template = after.template
        programme.training_days = ",".join(after.training_days)
        programme.swaps = dict(after.swaps)
        programme.exclude_equipment = sorted(after.exclude_equipment)
        programme.avoid = sorted(after.avoid)
        programme.cardio = after.cardio
        programme.updated_at = Now()
        programme.save(update_fields=fields)
        ProgrammeProposal.objects.filter(pk=row.pk).update(
            status=STATUS_ACCEPTED, decided_at=Now(), decided_by=request.user
        )
    row.refresh_from_db()
    programme.refresh_from_db()
    return row, programme


def dismiss_proposal(request, proposal_id) -> ProgrammeProposal:
    row = _proposal_or_refusal(request, proposal_id, "dismissed")
    ProgrammeProposal.objects.filter(pk=row.pk).update(
        status=STATUS_DISMISSED, decided_at=Now(), decided_by=request.user
    )
    row.refresh_from_db()
    return row


def accept_words(request, programme: TrainingProgramme) -> str:
    nxt = next_plan(request, programme)
    words = f"The programme is now {change.summary(state_of(programme))}. Next workout is {nxt.label}."
    if in_progress(request) is not None:
        words += " The workout already running stays as it was."
    return words


def alternatives_body(request, exercise) -> dict:
    """"Alternatives to bench": the slot's other choices, each allowed by the programme's
    equipment and joints or not and why, and whether its progress would carry (decision 11)."""
    if not exercise or not str(exercise).strip():
        raise WorkoutRefused("Nothing was read. Say which exercise.")
    programme = active_programme(request)
    state = (
        state_of(programme)
        if programme
        else change.State(rules.DEFAULT_TEMPLATE, rules.DEFAULT_TRAINING_DAYS)
    )
    holding = change.held(state)
    key = rules.match_exercise(str(exercise), list(holding))
    if key is None:
        names = ", ".join(rules.CATALOG[k].name for k in holding)
        raise WorkoutRefused(
            f"Nothing was read. {exercise!r} is not one exercise in the programme ({names}).", 404
        )
    trained = trained_exercises(request)
    slot = holding[key][0]
    options = rules.alternatives_for(slot, key, state.filters)
    for o in options:
        o["progress_carries"] = o["exercise_key"] in trained
    allowed = [o["name"] for o in options if o["allowed"]]
    blocked = [f"{o['name']} ({o['why_not']})" for o in options if not o["allowed"]]
    words = (
        f"{rules.CATALOG[key].name} can be swapped for "
        f"{change.join_words(allowed) or 'nothing the programme allows'}."
    )
    if blocked:
        words += f" Ruled out by the programme: {change.join_words(blocked)}."
    words += (
        " These are the only options. One picked becomes a proposal to accept, or swaps now in "
        "today's workout."
    )
    return {
        "exercise": rules.CATALOG[key].name,
        "exercise_key": key,
        "slot_key": slot.key,
        "options": options,
        "words": words,
    }
