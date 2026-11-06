"""`/api/body/coach/workout/`: the deterministic workout programme over REST (workouts, 2026-11-06).

| Route | Does |
|---|---|
| `GET workout/` | the programme, this week (done of planned), today's session or the next plan, `words` |
| `PUT workout/programme/` | create the programme, or change its training days (`training_days`) |
| `POST workout/start/` | start today's workout (`id` may be minted by the phone), or return the running one |
| `PATCH workout/sets/` | tick or edit sets by `(exercise_key, set_number)`; a replay changes nothing |
| `POST workout/log/` | "log bench 135 for 8": the next open set of the named exercise |
| `POST workout/swap/` | swap an exercise for one of its listed alternatives |
| `POST workout/finish/` | finish and log (`workout_set_logs` rows from the actuals, once) |
| `POST workout/cancel/` | drop the running workout; nothing is logged |

**Every target comes from `body_coach/programme.py`.** Nothing here accepts a target weight from
a client: a tick carries only what was done. Authentication is the API default; a recruiter is
refused (`household.recruiter_routes`). Weights are pounds. The engine tools call these views.
"""

from __future__ import annotations

import uuid

from drf_spectacular.utils import OpenApiResponse, extend_schema
from rest_framework import serializers, status
from rest_framework.response import Response
from rest_framework.views import APIView

from api.schema import DetailSerializer
from body_coach import workouts as svc
from body_coach.programme import CATALOG

TAGS = ["body-coach"]


def _refusal(exc: svc.WorkoutRefused) -> Response:
    return Response({"detail": exc.message}, status=exc.code)


def _body(request) -> dict:
    data = request.data
    if not isinstance(data, dict):
        raise svc.WorkoutRefused("Nothing was saved. The body must be a JSON object.")
    return data


def _uuid(value, name: str, verb: str) -> uuid.UUID | None:
    if value in (None, ""):
        return None
    try:
        return uuid.UUID(str(value))
    except ValueError as exc:
        raise svc.WorkoutRefused(f"Nothing was {verb}. {name} must be a UUID; got {value!r}.") from exc


def _number(value, name: str):
    if value is None:
        return None
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise svc.WorkoutRefused(f"Nothing was saved. {name} must be a number; got {value!r}.")
    return value


class WorkoutBodySerializer(serializers.Serializer):
    words = serializers.CharField()


_OK = {200: OpenApiResponse(description="The workout body, with `words`.")}
_REFUSED = {
    400: DetailSerializer,
    404: DetailSerializer,
    409: DetailSerializer,
}


class WorkoutTodayView(APIView):
    @extend_schema(
        operation_id="api_body_coach_workout",
        tags=TAGS,
        responses=_OK,
        description="The programme, this week's sessions done of planned, today's session (running "
        "or finished) or the next plan with every target from the programme's rules, and words.",
    )
    def get(self, request):
        return Response(svc.today_body(request))


class WorkoutProgrammeView(APIView):
    @extend_schema(
        operation_id="api_body_coach_workout_programme",
        tags=TAGS,
        request=None,
        responses={**_OK, **_REFUSED},
        description="Creates the programme (3 full-body days, A and B) or sets its training_days.",
    )
    def put(self, request):
        try:
            data = _body(request)
            programme = svc.ensure_programme(request)
            if "training_days" in data:
                days = svc.parse_days(data["training_days"])
                programme.training_days = ",".join(days)
                programme.save(update_fields=["training_days"])
        except svc.WorkoutRefused as exc:
            return _refusal(exc)
        days = ", ".join(programme.day_list())
        return Response(
            {
                "programme": svc.programme_dict(programme),
                "words": f"Programme saved: full body, A and B alternating, on {days}.",
            }
        )


class WorkoutStartView(APIView):
    @extend_schema(
        operation_id="api_body_coach_workout_start",
        tags=TAGS,
        request=None,
        responses={201: OpenApiResponse(description="Started."), **_OK, **_REFUSED},
        description="Starts today's workout from the programme's rules, or returns the one "
        "already running. `id` (a UUID the phone minted) makes a replay idempotent.",
    )
    def post(self, request):
        try:
            data = _body(request)
            session, created = svc.start(
                request, _uuid(data.get("id"), "id", "started"), data.get("day_key")
            )
        except svc.WorkoutRefused as exc:
            return _refusal(exc)
        d = svc.session_dict(session)
        if created:
            words = f"Started Day {d['day_key']}: " + "; ".join(
                _exercise_lines(d)
            ) + "."
            if d["calibrating"]:
                words += (
                    " Calibration: where there is no weight, pick one you could lift for 2 more "
                    "reps than the top of the range."
                )
        elif session.status == "in_progress":
            words = f"Day {d['day_key']} is already in progress: {d['sets_done']} of {d['set_count']} sets ticked."
        else:
            words = f"That workout is already {session.status}."
        return Response(
            {"session": d, "created": created, "words": words},
            status=status.HTTP_201_CREATED if created else status.HTTP_200_OK,
        )


def _exercise_lines(d: dict) -> list[str]:
    seen: dict[str, dict] = {}
    counts: dict[str, int] = {}
    for s in d["sets"]:
        if s["rule"] == "extra":
            continue
        seen.setdefault(s["exercise_key"], s)
        counts[s["exercise_key"]] = counts.get(s["exercise_key"], 0) + 1
    return [svc.describe_target({**s, "sets": counts[k]}) for k, s in seen.items()]


class WorkoutSetsView(APIView):
    @extend_schema(
        operation_id="api_body_coach_workout_sets",
        tags=TAGS,
        request=None,
        responses={**_OK, **_REFUSED},
        description="Ticks or edits sets: `sets` is a list of {exercise_key, set_number, done, "
        "reps, weight_lbs}. Only what was done; a target is never taken from a client.",
    )
    def patch(self, request):
        try:
            data = _body(request)
            session = svc.update_sets(
                request, _uuid(data.get("session_id"), "session_id", "saved"), data.get("sets")
            )
        except svc.WorkoutRefused as exc:
            return _refusal(exc)
        d = svc.session_dict(session)
        return Response(
            {"session": d, "words": f"Saved. {d['sets_done']} of {d['set_count']} sets ticked."}
        )


class WorkoutLogView(APIView):
    @extend_schema(
        operation_id="api_body_coach_workout_log",
        tags=TAGS,
        request=None,
        responses={**_OK, **_REFUSED},
        description="Logs one set of a named exercise into today's workout (starting it when none "
        "is running): `exercise`, `reps`, optional `weight_lbs`.",
    )
    def post(self, request):
        try:
            data = _body(request)
            reps = data.get("reps")
            weight = _number(data.get("weight_lbs"), "weight_lbs")
            session, row, started = svc.log_by_name(
                request,
                data.get("exercise"),
                reps,
                weight,
                _uuid(data.get("session_id"), "session_id", "logged"),
            )
        except svc.WorkoutRefused as exc:
            return _refusal(exc)
        d = svc.session_dict(session)
        unit = "seconds" if row.kind == "timed" else "reps"
        at = f" at {row.weight_lbs:g} lb" if row.weight_lbs is not None else ""
        lead = f"Started Day {d['day_key']}. " if started else ""
        extra = " (an extra set past the plan)" if row.rule == "extra" else ""
        words = (
            f"{lead}Ticked {row.exercise_name} set {row.set_number}: {row.reps_done} {unit}{at}"
            f"{extra}. {d['sets_done']} of {d['set_count']} sets done."
        )
        return Response({"session": d, "set": svc.set_dict(row), "words": words})


class WorkoutSwapView(APIView):
    @extend_schema(
        operation_id="api_body_coach_workout_swap",
        tags=TAGS,
        request=None,
        responses={**_OK, **_REFUSED},
        description="Swaps `exercise` for `to` (one of its listed alternatives) or for the next "
        "alternative. Kept on the programme; a running workout changes too if that exercise has "
        "no ticked set. The new exercise's target comes from the rules (calibration if new).",
    )
    def post(self, request):
        try:
            data = _body(request)
            old, new, session = svc.swap(request, data.get("exercise"), data.get("to"))
        except svc.WorkoutRefused as exc:
            return _refusal(exc)
        words = f"Swapped {CATALOG[old].name} for {CATALOG[new].name} in the programme."
        if session is not None:
            words += " Today's workout has it too."
        return Response(
            {
                "programme": svc.programme_dict(svc.active_programme(request)),
                "session": svc.session_dict(session or svc.in_progress(request)),
                "words": words,
            }
        )


class WorkoutFinishView(APIView):
    @extend_schema(
        operation_id="api_body_coach_workout_finish",
        tags=TAGS,
        request=None,
        responses={**_OK, **_REFUSED},
        description="Finishes the workout and logs it: done sets become `workout_set_logs` rows, "
        "once. A replay of a finished workout changes nothing.",
    )
    def post(self, request):
        try:
            data = _body(request)
            session, changed = svc.finish(request, _uuid(data.get("session_id"), "session_id", "finished"))
        except svc.WorkoutRefused as exc:
            return _refusal(exc)
        words = svc.finish_words(session, request) if changed else "That workout was already logged."
        return Response({"session": svc.session_dict(session), "finished": changed, "words": words})


class WorkoutCancelView(APIView):
    @extend_schema(
        operation_id="api_body_coach_workout_cancel",
        tags=TAGS,
        request=None,
        responses={**_OK, **_REFUSED},
        description="Drops the running workout. Nothing is logged and it does not count.",
    )
    def post(self, request):
        try:
            data = _body(request)
            session = svc.cancel(request, _uuid(data.get("session_id"), "session_id", "cancelled"))
        except svc.WorkoutRefused as exc:
            return _refusal(exc)
        return Response(
            {"session": svc.session_dict(session), "words": "Workout cancelled. Nothing was logged."}
        )
