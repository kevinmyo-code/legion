"""The body coach's engine tools (fat-loss ticket 08), registered in `engine_mcp/tools.py`.

Map decision 3: voice writes ride `ask_engine` and the web assistant, so profile, waist, saved
meals and the proposal accept/dismiss are engine tools. **Every one calls the routed REST view**
(`engine_mcp.dispatch.call_route`, reads included), so a figure Alfred says is the figure the
screen shows and a write over a tool is a write over REST. Each result says in words what did
and did not happen, and labels every estimate as one (CLAUDE.md section 4 rule 5).

Recruiters: none of these is on `assistant.surface.RECRUITER_TOOLS` (default deny), and the
routes refuse a recruiter again (`household.recruiter_routes`).
"""

from __future__ import annotations

import json
import re

from engine_mcp.dispatch import call_route

BASE = "/api/body/coach"


def _json(value) -> str:
    return json.dumps(value, default=str, ensure_ascii=False)


def _detail(response) -> str:
    data = getattr(response, "data", None)
    if isinstance(data, dict) and "detail" in data:
        return str(data["detail"])
    return f"HTTP {response.status_code}"


def _not_done(response, verb: str):
    from engine_mcp.tools import failed, refused

    detail = _detail(response)
    text = detail if detail.startswith("Nothing was") else f"Nothing was {verb}. {detail}"
    if response.status_code in (400, 403, 404, 405, 409):
        return refused(text)
    return failed(text)


def _read(request, path: str):
    from engine_mcp.tools import failed

    response = call_route(request, "GET", path)
    if response.status_code != 200:
        if response.status_code == 403:
            return None, _not_done(response, "read")
        return None, failed(
            f"Nothing was read. The engine answered HTTP {response.status_code}: "
            f"{_detail(response)}. Nothing is known from this call."
        )
    return response.data, None


# =============================================================================
# Reads
# =============================================================================


def get_energy_estimate(request, args):
    from engine_mcp.tools import ok

    data, error = _read(request, f"{BASE}/energy/")
    if error:
        return error
    lines = [data["words"]]
    if data["maintenance_kcal"] is not None:
        lines.append(
            f"Maintenance about {data['maintenance_kcal']} kcal a day (estimate, "
            f"{data['basis_words']})."
        )
        if data["formula_maintenance_kcal"] is not None and data["basis"] == "trend":
            lines.append(f"The formula alone says {data['formula_maintenance_kcal']} kcal.")
    if data["suggested_target_kcal"] is not None:
        lines.append(
            f"Suggested target {data['suggested_target_kcal']} kcal a day for losing "
            f"{data['target_rate_pct']:.2f}% of bodyweight a week (estimate)."
        )
    if data["protein_g"] is not None:
        lines.append(
            f"Protein about {data['protein_g']} g a day ({data['protein_g_per_kg']:.1f} g/kg of "
            f"{data['protein_basis']}, estimate)."
        )
    if data["weight_lbs"] is not None:
        lines.append(f"Built on {data['weight_lbs']} lbs ({data['weight_source']} weight).")
    if data["missing"]:
        lines.append(
            "Missing, so not computed: " + ", ".join(data["missing"]) + ". Ask for these; "
            "never guess them."
        )
    target = data["current_target"]
    if target:
        lines.append(
            f"Current meal target {target['calories_kcal']} kcal, protein "
            f"{target['protein_g']:g} g, from {target['effective_from']}."
        )
    else:
        lines.append("No meal target is set.")
    proposal = data["proposal"]
    if proposal:
        lines.append(
            f"Pending proposal {proposal['id']}: {proposal['words']} Accept or dismiss it only "
            f"when the person says so."
        )
    plan = data.get("plan")
    if plan and plan["status"] == "draft":
        lines.append(
            "A draft plan is waiting; 'lock it in' is lock_body_plan, never a meal target."
        )
    facts = profile_words(data.get("profile"))
    if facts:
        lines.append(facts)
    lines.append(COACH_SAY)
    return ok(" ".join(lines), structured=data)


# Kevin's first coach voice session (2026-11-06): a routine calorie answer came back with "not
# medical advice ... consult a healthcare professional", and "I'm 32, 200 lb, 5 ft 10" got the same
# estimate read again instead of a check against the profile. Said beside the figures, per result.
COACH_SAY = (
    "Say 'estimate' and nothing more: no medical disclaimer on a routine figure. 'See a "
    "professional' is only for pain, injury, a medical condition or medication, a target at or "
    "below 800 kcal, or signs of disordered eating."
)


def profile_words(profile: dict | None) -> str:
    """The profile the estimate was built on, and what to do when the person states a different
    fact: say what the profile holds, offer to update it, write only after a yes."""
    if not profile:
        return ""
    held = [
        f"height {profile['height_ft_in']} ({profile['height_cm']:g} cm)"
        if profile.get("height_cm") is not None
        else None,
        f"born {profile['birth_year']} (about {profile['age_about']})"
        if profile.get("birth_year") is not None
        else None,
        profile.get("sex"),
        f"activity {profile['activity_level']}" if profile.get("activity_level") else None,
    ]
    said = ", ".join(h for h in held if h)
    if not said:
        return ""
    return (
        f"Built on the profile: {said}. If the person states a height, age, sex or activity that "
        "differs, say what the profile holds and ask whether to update it; update only after a "
        "yes (set_body_profile), then read the estimate again. A different weight is a weigh-in "
        "to log, on a yes."
    )


def get_weight_trend(request, args):
    from engine_mcp.tools import ok

    data, error = _read(request, f"{BASE}/trend/")
    if error:
        return error
    lines = [data["words"]]
    if data["in_band"] is not None:
        lines.append(
            "That is inside the 0.5 to 1.0% a week loss band."
            if data["in_band"]
            else "That is outside the 0.5 to 1.0% a week loss band."
        )
    if data["waist_change_in_28d"] is not None:
        lines.append(f"Waist change over four weeks: {data['waist_change_in_28d']:+.1f} in.")
    elif data["waist"]:
        latest = data["waist"][0]
        lines.append(f"Latest waist {latest['waist_in']} in on {latest['measured_on']}.")
    structured = {k: v for k, v in data.items() if k != "points"}
    structured["points_shown"] = len(data["points"])
    return ok(" ".join(lines), structured=structured)


def _say_meal(row) -> str:
    extra = []
    if row.get("carbs_g") is not None:
        extra.append(f"{row['carbs_g']:g} g carbs")
    if row.get("fat_g") is not None:
        extra.append(f"{row['fat_g']:g} g fat")
    tail = f", {', '.join(extra)}" if extra else ""
    return (
        f"[{row['id']}] {row['name']}: {row['calories_kcal']} kcal, {row['protein_g']:g} g "
        f"protein{tail} (estimates)"
    )


def list_saved_meals(request, args):
    from engine_mcp.tools import ok

    data, error = _read(request, f"{BASE}/saved-meals/")
    if error:
        return error
    rows = data["results"]
    if not rows:
        return ok(
            "No saved meals yet (a real empty result). Save one with save_meal.",
            structured={"results": []},
        )
    lines = "\n".join(_say_meal(row) for row in rows)
    return ok(
        f"{len(rows)} saved meals; every macro is an estimate.\n{lines}", structured=data
    )


# =============================================================================
# Writes
# =============================================================================


def set_body_profile(request, args):
    from engine_mcp.tools import ok

    response = call_route(request, "PUT", f"{BASE}/profile/", dict(args))
    if response.status_code != 200:
        return _not_done(response, "saved")
    row = response.data
    said = ", ".join(f"{k} {v}" for k, v in args.items())
    still = (
        " Still missing for the estimate: " + ", ".join(row["missing"]) + "."
        if row["missing"]
        else " The calculator has everything it needs."
    )
    line = (
        " The weekly weight line is off."
        if row["weekly_line_on"] is False
        else " The weekly weight line is on."
    )
    goal = (
        f" Goal weight {row['goal_weight_lbs']} lbs."
        if row.get("goal_weight_lbs") is not None
        else ""
    )
    return ok(
        f"Saved the body profile ({said}). The engine committed it.{still}{line}{goal}",
        structured={"profile": row},
    )


def log_waist(request, args):
    from engine_mcp.tools import ok

    response = call_route(request, "POST", f"{BASE}/waist/", dict(args))
    if response.status_code != 201:
        return _not_done(response, "saved")
    row = response.data
    return ok(
        f"Logged a waist of {row['waist_in']} in ({row['waist_cm']} cm) on {row['measured_on']}. "
        f"The engine committed it.",
        structured={"waist": row},
    )


def save_meal(request, args):
    from engine_mcp.tools import ok

    response = call_route(request, "POST", f"{BASE}/saved-meals/", dict(args))
    if response.status_code != 201:
        return _not_done(response, "saved")
    return ok(
        f"Saved {_say_meal(response.data)}. The engine committed it; nothing was logged as "
        f"eaten.",
        structured={"saved_meal": response.data},
    )


def _norm(name: str) -> str:
    words = re.sub(r"[^a-z0-9 ]", " ", name.casefold()).split()
    while words and words[0] in ("my", "the", "a", "our"):
        words = words[1:]
    return " ".join(words)


def _find_meal(request, args, verb: str):
    """(row, None) for the saved meal named by `id` or a loose `name`, or (None, refusal)."""
    from engine_mcp.tools import refused

    data, error = _read(request, f"{BASE}/saved-meals/")
    if error:
        return None, error
    rows = data["results"]
    if args.get("id"):
        for row in rows:
            if row["id"] == args["id"]:
                return row, None
        return None, refused(f"Nothing was {verb}. There is no saved meal with that id.")
    wanted = _norm(args.get("name") or "")
    if not wanted:
        return None, refused(f"Nothing was {verb}. Name the saved meal, or give its id.")
    exact = [row for row in rows if _norm(row["name"]) == wanted]
    loose = exact or [
        row for row in rows if wanted in _norm(row["name"]) or _norm(row["name"]) in wanted
    ]
    if len(loose) == 1:
        return loose[0], None
    names = ", ".join(row["name"] for row in rows) or "none"
    if not loose:
        return None, refused(
            f"Nothing was {verb}. No saved meal matches {args.get('name')!r}. Saved meals: "
            f"{names}."
        )
    return None, refused(
        f"Nothing was {verb}. {args.get('name')!r} matches more than one saved meal: "
        f"{', '.join(row['name'] for row in loose)}. Ask which."
    )


def delete_saved_meal(request, args):
    from engine_mcp.tools import ok

    row, error = _find_meal(request, args, "deleted")
    if error:
        return error
    response = call_route(request, "DELETE", f"{BASE}/saved-meals/{row['id']}/")
    if response.status_code != 204:
        return _not_done(response, "deleted")
    return ok(
        f"Deleted the saved meal {row['name']!r}. Meals already logged from it are untouched."
    )


def log_saved_meal(request, args):
    from engine_mcp.tools import ok

    row, error = _find_meal(request, args, "logged")
    if error:
        return error
    response = call_route(request, "POST", f"{BASE}/saved-meals/{row['id']}/log/")
    if response.status_code != 201:
        return _not_done(response, "logged")
    return ok(
        f"{response.data['words']} The engine committed one meal entry.",
        structured=response.data,
    )


def _proposal_id(request, args, verb: str):
    from engine_mcp.tools import refused

    if args.get("id"):
        return args["id"], None
    data, error = _read(request, f"{BASE}/energy/")
    if error:
        return None, error
    if not data["proposal"]:
        return None, refused(
            f"Nothing was {verb}. There is no pending energy proposal; the meal target is as it "
            f"was."
        )
    return data["proposal"]["id"], None


def accept_energy_proposal(request, args):
    from engine_mcp.tools import ok

    proposal_id, error = _proposal_id(request, args, "accepted")
    if error:
        return error
    response = call_route(request, "POST", f"{BASE}/proposals/{proposal_id}/accept/")
    if response.status_code != 200:
        return _not_done(response, "accepted")
    return ok(f"{response.data['words']} The engine committed it.", structured=response.data)


def dismiss_energy_proposal(request, args):
    from engine_mcp.tools import ok

    proposal_id, error = _proposal_id(request, args, "dismissed")
    if error:
        return error
    response = call_route(request, "POST", f"{BASE}/proposals/{proposal_id}/dismiss/")
    if response.status_code != 200:
        return _not_done(response, "dismissed")
    return ok(response.data["words"], structured=response.data)


# =============================================================================
# The workout programme (workouts, 2026-11-06). Every target is the rules' (`programme.py`);
# these tools carry what was said and done, never a number of their own.
# =============================================================================

WORKOUT = f"{BASE}/workout"


def get_todays_workout(request, args):
    from engine_mcp.tools import ok

    data, error = _read(request, f"{WORKOUT}/")
    if error:
        return error
    # workout reads, 2026-11-06: the whole programme (Day A and Day B), today's status and the
    # sessions ahead ride along, so "what's Day B" or "when's my next workout" has an answer.
    programme = data.get("programme_words")
    return ok(
        data["words"]
        + (f" {programme}" if programme else "")
        + " Every target comes from the programme's rules; never change a number.",
        structured=data,
    )


def _workout_write(request, path: str, args: dict, verb: str, ok_codes=(200, 201)):
    from engine_mcp.tools import ok

    response = call_route(request, "POST", path, args)
    if response.status_code not in ok_codes:
        return _not_done(response, verb)
    return ok(f"{response.data['words']} The engine committed it.", structured=response.data)


def start_workout(request, args):
    body = {"location": args["location"]} if args.get("location") else {}
    return _workout_write(request, f"{WORKOUT}/start/", body, "started")


def log_workout_set_to_session(request, args):
    body = {"exercise": args.get("exercise"), "reps": args.get("reps")}
    if args.get("weight_lbs") is not None:
        body["weight_lbs"] = args["weight_lbs"]
    return _workout_write(request, f"{WORKOUT}/log/", body, "logged")


def swap_exercise(request, args):
    body = {"exercise": args.get("exercise")}
    if args.get("to"):
        body["to"] = args["to"]
    return _workout_write(request, f"{WORKOUT}/swap/", body, "swapped")


def finish_workout(request, args):
    return _workout_write(request, f"{WORKOUT}/finish/", {}, "finished")


# =============================================================================
# Programme change proposals (body coach 2, 2026-11-06). The model passes CHOICES; the engine
# builds every exercise and number from `programme.py` and says what changes in words.
# =============================================================================

PROPOSAL_CHOICES = (
    "sessions_per_week",
    "split",
    "without_equipment",
    "with_equipment",
    "avoid",
    "clear_avoid",
    "cardio",
    "swap_exercise",
    "swap_to",
)


def propose_programme_change(request, args):
    from engine_mcp.tools import ok

    body = {k: args[k] for k in PROPOSAL_CHOICES if args.get(k) is not None}
    response = call_route(request, "POST", f"{WORKOUT}/proposals/", body)
    if response.status_code != 201:
        return _not_done(response, "proposed")
    return ok(
        f"{response.data['words']} Read this proposal back as it is and ask whether to accept it; "
        f"name no exercise, set, rep or weight that is not in it. The engine stored it pending; "
        f"the programme has not changed.",
        structured=response.data,
    )


def _programme_proposal_id(request, args, verb: str):
    from engine_mcp.tools import refused

    if args.get("id"):
        return args["id"], None
    data, error = _read(request, f"{WORKOUT}/proposals/")
    if error:
        return None, error
    if not data["proposal"]:
        return None, refused(
            f"Nothing was {verb}. There is no pending programme proposal; the programme is as it "
            f"was."
        )
    return data["proposal"]["id"], None


def accept_programme_change(request, args):
    from engine_mcp.tools import ok

    proposal_id, error = _programme_proposal_id(request, args, "accepted")
    if error:
        return error
    response = call_route(request, "POST", f"{WORKOUT}/proposals/{proposal_id}/accept/")
    if response.status_code != 200:
        return _not_done(response, "accepted")
    return ok(f"{response.data['words']} The engine committed it.", structured=response.data)


def dismiss_programme_change(request, args):
    from engine_mcp.tools import ok

    proposal_id, error = _programme_proposal_id(request, args, "dismissed")
    if error:
        return error
    response = call_route(request, "POST", f"{WORKOUT}/proposals/{proposal_id}/dismiss/")
    if response.status_code != 200:
        return _not_done(response, "dismissed")
    return ok(response.data["words"], structured=response.data)


def get_exercise_alternatives(request, args):
    from engine_mcp.tools import ok, refused

    exercise = str(args.get("exercise") or "").strip()
    response = call_route(request, "GET", f"{WORKOUT}/alternatives/{exercise}/")
    if response.status_code == 404:
        return refused(_detail(response))
    if response.status_code != 200:
        return _not_done(response, "read")
    return ok(
        f"{response.data['words']} Recommend only from these; never name another exercise.",
        structured=response.data,
    )


# =============================================================================
# What to eat from what's left (body coach 2, 2026-11-06). `body_coach/suggest.py`.
# =============================================================================


def suggest_meal(request, args):
    from engine_mcp.tools import ok

    data, error = _read(request, f"{BASE}/meal-options/")
    if error:
        return error
    ids = "; ".join(f"{o['rank']} = {o['id']}" for o in data["options"])
    tail = (
        f" Suggest only from these numbered options, with their figures as estimates; to log "
        f"one, call log_meal_option with its option_id ({ids})."
        if data["options"]
        else ""
    )
    return ok(data["words"] + tail, structured=data)


def log_meal_option(request, args):
    from engine_mcp.tools import ok

    response = call_route(
        request, "POST", f"{BASE}/meal-options/log/", {"option_id": args.get("option_id")}
    )
    if response.status_code != 201:
        return _not_done(response, "logged")
    return ok(
        f"{response.data['words']} The engine committed one meal entry.",
        structured=response.data,
    )


# =============================================================================
# The plan (body coach 2, Kevin 2026-11-06): draft with Alfred, lock it in, adjust as he goes.
# =============================================================================

PLAN = f"{BASE}/plan"
PLAN_CHOICES = (*PROPOSAL_CHOICES, "rate_pct", "alfred_sets_target")


def get_body_plan(request, args):
    from engine_mcp.tools import ok

    data, error = _read(request, f"{PLAN}/")
    if error:
        return error
    tail = ""
    if data["draft"]:
        tail = (
            " A draft is waiting: read it back and ask whether to lock it in; lock only on a yes "
            "(lock_body_plan)."
        )
    return ok(
        f"{data['words']} {data['history_words']}{tail} Every figure is the engine's and an "
        f"estimate; never invent or change one.",
        structured=data,
    )


def draft_body_plan(request, args):
    from engine_mcp.tools import ok

    body = {k: args[k] for k in PLAN_CHOICES if args.get(k) is not None}
    response = call_route(request, "POST", f"{PLAN}/draft/", body)
    if response.status_code != 201:
        return _not_done(response, "drafted")
    return ok(
        f"{response.data['words']} Read this draft back and ask whether to lock it in. The "
        f"engine stored a draft; nothing is live.",
        structured=response.data,
    )


def _plan_id(request, args, verb: str):
    from engine_mcp.tools import refused

    if args.get("id"):
        return args["id"], None
    data, error = _read(request, f"{PLAN}/")
    if error:
        return None, error
    if not data["draft"]:
        return None, refused(f"Nothing was {verb}. There is no draft plan; the plan is as it was.")
    return data["draft"]["id"], None


def lock_body_plan(request, args):
    from engine_mcp.tools import ok

    plan_id, error = _plan_id(request, args, "locked")
    if error:
        return error
    response = call_route(request, "POST", f"{PLAN}/{plan_id}/lock/")
    if response.status_code != 200:
        return _not_done(response, "locked")
    return ok(f"{response.data['words']} The engine committed it.", structured=response.data)


def discard_body_plan(request, args):
    from engine_mcp.tools import ok

    plan_id, error = _plan_id(request, args, "discarded")
    if error:
        return error
    response = call_route(request, "POST", f"{PLAN}/{plan_id}/discard/")
    if response.status_code != 200:
        return _not_done(response, "discarded")
    return ok(response.data["words"], structured=response.data)
