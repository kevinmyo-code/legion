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
    return ok(" ".join(lines), structured=data)


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
    return ok(
        f"Saved the body profile ({said}). The engine committed it.{still}{line}",
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
