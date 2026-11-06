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
from body_coach.models import (
    SESSION_CANCELLED,
    SESSION_FINISHED,
    SESSION_IN_PROGRESS,
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


def programme_dict(row: TrainingProgramme | None) -> dict | None:
    if row is None:
        return None
    return {
        "id": str(row.id),
        "template": row.template,
        "training_days": row.day_list(),
        "days_per_week": len(row.day_list()),
        "swaps": {k: rules.CATALOG[v].name for k, v in (row.swaps or {}).items() if v in rules.CATALOG},
        "started_on": row.started_on.isoformat(),
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
        out.append(
            rules.Performance(
                sets=tuple(rules.DoneSet(r.reps_done, r.weight_lbs, r.done) for r in rows),
                planned_sets=len(planned) or len(rows),
                reps_low=_template_low(rows[0]),
                reps_high=_template_high(rows[0]),
                deload=session.deload,
            )
        )
    return out


def _template_slot(set_row: WorkoutSet) -> rules.Slot | None:
    for day in rules.TEMPLATES[rules.DEFAULT_TEMPLATE].values():
        for slot in day:
            if slot.key == set_row.slot_key:
                return slot
    return None


def _template_high(set_row: WorkoutSet) -> int:
    slot = _template_slot(set_row)
    return slot.reps_high if slot else set_row.reps_high


def _template_low(set_row: WorkoutSet) -> int:
    slot = _template_slot(set_row)
    return slot.reps_low if slot else set_row.reps_low


@dataclass
class Plan:
    day_key: str
    session_number: int
    deload: bool
    targets: list[rules.Target]

    def as_dict(self) -> dict:
        return {
            "day_key": self.day_key,
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


def next_plan(request, programme: TrainingProgramme, day_key: str | None = None) -> Plan:
    counted = _counted_sessions(request, programme)
    last = counted.filter(status=SESSION_FINISHED).order_by("-finished_at").first()
    day = day_key or rules.next_day_key(last.day_key if last else None)
    number = counted.filter(status=SESSION_FINISHED).count() + 1
    deload = rules.is_deload(number, len(programme.day_list()))
    targets = [
        rules.target_for(slot, ex, history_for(request, ex), deload=deload)
        for slot, ex in rules.slots_for(programme.template, day, programme.swaps)
    ]
    return Plan(day, number, deload, targets)


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


def start(request, session_id: uuid.UUID | None = None, day_key: str | None = None):
    """Start today's session, or return the one already running. Returns (session, created)."""
    if day_key is not None and day_key not in rules.DAY_KEYS:
        raise WorkoutRefused(f"Nothing was started. day_key must be A or B; got {day_key!r}.")
    with transaction.atomic():
        if session_id is not None:
            existing = scoped(WorkoutSession, request).filter(pk=session_id).first()
            if existing is not None:
                return existing, False
            if WorkoutSession.objects.filter(pk=session_id).exists():
                raise WorkoutRefused("Nothing was started. That session id is taken.", 409)
        running = in_progress(request)
        if running is not None:
            return running, False
        programme = ensure_programme(request)
        plan = next_plan(request, programme, day_key)
        now = timezone.now()
        session = WorkoutSession.objects.create(
            id=session_id or uuid.uuid4(),
            household=household_of(request),
            programme=programme,
            day_key=plan.day_key,
            session_number=plan.session_number,
            deload=plan.deload,
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
    held = {}
    for day in rules.DAY_KEYS:
        for slot, ex in rules.slots_for(programme.template, day, programme.swaps):
            held[ex] = slot
    key = rules.match_exercise(str(exercise or ""), list(held))
    if key is None:
        names = ", ".join(rules.CATALOG[k].name for k in held)
        raise WorkoutRefused(
            f"Nothing was swapped. {exercise!r} is not one exercise in the programme ({names})."
        )
    slot = held[key]
    new = rules.swap_choice(slot, key, to)
    if new is None:
        allowed = ", ".join(rules.CATALOG[k].name for k in slot.choices() if k != key)
        raise WorkoutRefused(
            f"Nothing was swapped. {rules.CATALOG[key].name} can be swapped for: {allowed}."
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
        if row.kind == rules.KIND_TIMED:
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
        "unit": "seconds" if row.kind == rules.KIND_TIMED else "reps",
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
        "session_number": session.session_number,
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
    weekday = rules.WEEKDAYS[today.weekday()]
    training_day = weekday in week["training_days"]
    plan = None
    if running is None:
        plan = (
            next_plan(request, programme)
            if programme
            else _default_plan(request)
        ).as_dict()
    words = _today_words(running, finished_today, plan, week, training_day)
    if running is not None:
        status = TODAY_IN_PROGRESS
        first_key, first_number = running.day_key, running.session_number
    else:
        status = TODAY_DONE if finished_today else TODAY_NOT_STARTED if training_day else TODAY_REST
        first_key, first_number = plan["day_key"], plan["session_number"]
    upcoming = rules.upcoming_sessions(
        today,
        week["training_days"],
        first_key,
        today_open=status in (TODAY_IN_PROGRESS, TODAY_NOT_STARTED),
        first_number=first_number,
        count=max(2, len(week["training_days"])),
    )
    days = programme_days(request, programme, upcoming)
    upcoming_list = [upcoming_dict(u) for u in upcoming]
    return {
        "programme": programme_dict(programme),
        "today": today.isoformat(),
        "today_weekday": rules.WEEKDAY_NAMES[today.weekday()],
        "training_day": training_day,
        "today_status": status,
        "week": week,
        "session": session_dict(running or finished_today),
        "next": plan,
        "days": days,
        "upcoming": upcoming_list,
        "words": words,
        "programme_words": _programme_words(today, status, week, days, upcoming_list),
    }


# =============================================================================
# The whole programme, and the calendar ahead (workout reads, 2026-11-06)
# =============================================================================

TODAY_REST = "rest"
TODAY_NOT_STARTED = "not_started"
TODAY_IN_PROGRESS = "in_progress"
TODAY_DONE = "done"


def programme_days(request, programme: TrainingProgramme | None, upcoming: list[rules.Upcoming]) -> list[dict]:
    """Every day of the programme (A and B) with the rules' current targets, and the date each
    one next comes round. A deload is not applied here: `next` and `upcoming` say which session is
    one, and its lighter targets are the rules' when that session starts."""
    template = programme.template if programme else rules.DEFAULT_TEMPLATE
    swaps = programme.swaps if programme else {}
    out = []
    for day in rules.DAY_KEYS:
        targets = [
            rules.target_for(slot, ex, history_for(request, ex))
            for slot, ex in rules.slots_for(template, day, swaps)
        ]
        when = next((u for u in upcoming if u.day_key == day), None)
        out.append(
            {
                "day_key": day,
                "next_on": when.on.isoformat() if when else None,
                "next_weekday": when.weekday_name if when else None,
                "calibrating": any(t.calibrating for t in targets),
                "exercises": [target_dict(t) for t in targets],
            }
        )
    return out


def upcoming_dict(u: rules.Upcoming) -> dict:
    return {
        "day_key": u.day_key,
        "date": u.on.isoformat(),
        "weekday": u.weekday_name,
        "session_number": u.session_number,
        "deload": u.deload,
        "is_today": u.is_today,
    }


def _when(u: dict) -> str:
    return f"today ({u['weekday']} {u['date']})" if u["is_today"] else f"{u['weekday']} {u['date']}"


def _programme_words(today: date, status: str, week: dict, days: list[dict], upcoming: list[dict]) -> str:
    names = [rules.WEEKDAY_NAMES[rules.WEEKDAYS.index(d)] for d in week["training_days"]]
    on = names[0] if len(names) == 1 else ", ".join(names[:-1]) + " and " + names[-1]
    parts = [
        f"Programme: full body, Day A and Day B alternating, {len(names)} sessions a week on {on}."
    ]
    for d in days:
        parts.append(f"Day {d['day_key']}: " + "; ".join(describe_target(t) for t in d["exercises"]) + ".")
    if any(d["calibrating"] for d in days):
        parts.append(
            "Where it says your pick, it is a calibration: choose a weight you could lift for 2 "
            "more reps than the top of the range."
        )
    lead = f"Today is {rules.WEEKDAY_NAMES[today.weekday()]} {today.isoformat()}"
    first = upcoming[0]
    if status == TODAY_IN_PROGRESS:
        parts.append(f"{lead}: Day {first['day_key']} is in progress.")
    elif status == TODAY_NOT_STARTED:
        parts.append(f"{lead}, a training day: Day {first['day_key']}, not done yet.")
    elif status == TODAY_DONE:
        parts.append(f"{lead}: today's workout is done.")
    else:
        parts.append(f"{lead}, a rest day.")
    sessions = "; ".join(
        f"Day {u['day_key']} {_when(u)}" + (" (deload)" if u["deload"] else "") for u in upcoming
    )
    parts.append(f"Next sessions: {sessions}.")
    parts.append(f"This week: {week['done']} of {week['planned']} sessions done.")
    return " ".join(parts)


def _default_plan(request) -> Plan:
    """What the first workout would be before any programme row exists (nothing is written)."""
    targets = [
        rules.target_for(slot, ex, history_for(request, ex))
        for slot, ex in rules.slots_for(rules.DEFAULT_TEMPLATE, "A")
    ]
    return Plan("A", 1, False, targets)


def describe_target(t: dict) -> str:
    reps = f"{t['reps_low']}-{t['reps_high']}" if t["reps_low"] != t["reps_high"] else f"{t['reps_low']}"
    unit = " s" if t["unit"] == "seconds" else ""
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
            f"Day {d['day_key']} is in progress: {d['sets_done']} of {d['set_count']} sets "
            f"ticked.{tail}"
        )
    if finished_today is not None:
        d = session_dict(finished_today)
        return f"Today's workout (Day {d['day_key']}) is logged: {d['sets_done']} sets.{tail}"
    lines = "; ".join(describe_target(t) for t in plan["exercises"])
    lead = "Today's workout" if training_day else "Not a training day. The next workout"
    extra = ""
    if plan["calibrating"]:
        extra = " Calibration: where it says your pick, choose a weight you could lift for 2 more reps than the top of the range."
    if plan["deload"]:
        extra += " This is a deload session: lighter and one set fewer."
    return f"{lead} is Day {plan['day_key']}: {lines}.{extra}{tail}"


def finish_words(session: WorkoutSession, request) -> str:
    d = session_dict(session)
    programme = session.programme
    nxt = next_plan(request, programme)
    done_day = {
        ex: rules.target_for(slot, ex, history_for(request, ex))
        for slot, ex in rules.slots_for(programme.template, session.day_key, programme.swaps)
    }
    ups = [t for t in done_day.values() if t.rule == rules.RULE_ADD]
    up_words = (
        " Next time up: " + ", ".join(f"{t.name} to {t.weight_lbs:g} lb" for t in ups) + "."
        if ups
        else ""
    )
    return (
        f"Logged Day {d['day_key']}: {d['sets_done']} of {d['set_count']} sets.{up_words} Next "
        f"workout is Day {nxt.day_key}."
    )
