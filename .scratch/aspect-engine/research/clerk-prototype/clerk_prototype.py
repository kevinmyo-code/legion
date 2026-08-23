"""
THROWAWAY prototype for .scratch/aspect-engine/issues/07-aspect-clerk-prototype.md.
NOT app code. Talks to the real Gemini REST generateContent endpoint with function
calling, using GEMINI_API_KEY read from local.properties, mirroring the shape of
ai/SubAgent.kt's investigate() loop (bounded model-call count, tool-result feedback,
force-answer on the last round) closely enough to be a fair latency/reliability probe.

Usage: python clerk_prototype.py
Writes results to results.json in this directory and prints a summary table.
"""
import json
import re
import statistics
import time
import urllib.request
import urllib.error
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[4]
LOCAL_PROPS = REPO_ROOT / "local.properties"

API_URL = "https://generativelanguage.googleapis.com/v1beta/models"

# Same family SubAgent.kt (ai/SubAgent.kt DEFAULT_MODEL) and GeminiKeyValidator.kt use.
# No "pro" model id exists anywhere in the LEGION codebase today (grepped 2026-08-23), and
# "gemini-3.5-pro" 404s against ListModels as of this run - the 3.5 generation only ships
# flash/flash-lite. The nearest actual Pro-tier model in the same 3.x line, per a live
# ListModels call against this key on 2026-08-23, is gemini-3.1-pro-preview (there is also
# an older gemini-2.5-pro and an alias gemini-pro-latest, but 3.1-pro-preview is the closest
# generation to the 3.5-flash-lite already in production). Used as the "Pro" side below.
MODEL_FLASH = "gemini-3.5-flash-lite"
MODEL_PRO = "gemini-3.1-pro-preview"

MAX_MODEL_CALLS = 4  # mirrors SubAgent.investigate's default
RUNS_PER_CASE = 3


def read_key() -> str:
    text = LOCAL_PROPS.read_text(encoding="utf-8")
    for line in text.splitlines():
        if line.startswith("GEMINI_API_KEY="):
            return line.split("=", 1)[1].strip()
    raise RuntimeError("GEMINI_API_KEY not found in local.properties")


API_KEY = read_key()


# ---------------------------------------------------------------------------
# Fake in-memory record store, seeded with two aspects.
# ---------------------------------------------------------------------------
class FakeStore:
    def __init__(self):
        self.aspects = {
            "workouts": {
                "description": "Exercise sets logged by the user.",
                "fields": {
                    "exercise": "text - name of the exercise, e.g. 'bench press'",
                    "weight": "number - weight used, in pounds",
                    "reps": "number - repetitions performed",
                    "date": "text - ISO date (YYYY-MM-DD) the set was performed",
                },
            },
            "groceries": {
                "description": "Grocery list items to buy.",
                "fields": {
                    "item": "text - name of the grocery item",
                    "quantity": "number - how many/much to buy",
                    "category": "text - produce/dairy/meat/pantry/other",
                    "bought": "text - 'yes' or 'no'",
                },
            },
        }
        self.records = {"workouts": [], "groceries": []}
        self._next_id = 1

    def reset(self):
        self.records = {"workouts": [], "groceries": []}
        self._next_id = 1

    def seed_today_bench(self):
        # Used for the query-then-update and delete cases, so the model has
        # something real to find rather than operating on an empty store.
        self.records["workouts"].append({
            "id": self._next_id,
            "exercise": "bench press",
            "weight": 185,
            "reps": 5,
            "date": "2026-08-23",
        })
        self._next_id += 1

    # -- tool implementations -----------------------------------------
    def list_aspects(self, args):
        return {"aspects": [{"name": k, "description": v["description"]} for k, v in self.aspects.items()]}

    def describe_aspect(self, args):
        name = args.get("aspect")
        a = self.aspects.get(name)
        if a is None:
            return {"error": f"no such aspect '{name}'"}
        return {"aspect": name, "description": a["description"], "fields": a["fields"]}

    def query_records(self, args):
        name = args.get("aspect")
        if name not in self.records:
            return {"error": f"no such aspect '{name}'"}
        rows = self.records[name]
        filters = args.get("filters") or {}
        out = []
        for r in rows:
            ok = True
            for k, v in filters.items():
                if str(r.get(k)) != str(v):
                    ok = False
                    break
            if ok:
                out.append(r)
        return {"aspect": name, "count": len(out), "records": out}

    def create_record(self, args):
        name = args.get("aspect")
        if name not in self.aspects:
            return {"error": f"no such aspect '{name}'"}
        fields = self.aspects[name]["fields"]
        values = args.get("values") or {}
        unknown = [k for k in values if k not in fields]
        if unknown:
            return {"error": f"unknown field(s) {unknown} for aspect '{name}'", "known_fields": list(fields)}
        missing = [k for k in fields if k not in values]
        if missing:
            return {"error": f"missing required field(s) {missing} for aspect '{name}'"}
        rec = dict(values)
        rec["id"] = self._next_id
        self._next_id += 1
        self.records[name].append(rec)
        return {"created": True, "id": rec["id"], "aspect": name, "values": values}

    def update_record(self, args):
        name = args.get("aspect")
        rec_id = args.get("id")
        values = args.get("values") or {}
        if name not in self.records:
            return {"error": f"no such aspect '{name}'"}
        for r in self.records[name]:
            if r["id"] == rec_id:
                fields = self.aspects[name]["fields"]
                unknown = [k for k in values if k not in fields]
                if unknown:
                    return {"error": f"unknown field(s) {unknown}"}
                r.update(values)
                return {"updated": True, "id": rec_id, "aspect": name, "values": values}
        return {"error": f"no record id {rec_id} in aspect '{name}'"}

    def delete_record(self, args):
        name = args.get("aspect")
        rec_id = args.get("id")
        if name not in self.records:
            return {"error": f"no such aspect '{name}'"}
        before = len(self.records[name])
        self.records[name] = [r for r in self.records[name] if r["id"] != rec_id]
        after = len(self.records[name])
        if before == after:
            return {"error": f"no record id {rec_id} in aspect '{name}'"}
        return {"deleted": True, "id": rec_id, "aspect": name}


# ---------------------------------------------------------------------------
# Six CRUD meta-tools (ticket 06's surface minus aspect_clerk itself, minus the
# schema-editing pair create_aspect/update_aspect which route through a
# different, Pro-tier generator subagent per ticket 06's Answer and are out of
# scope for this clerk prototype).
# ---------------------------------------------------------------------------
TOOL_DECLARATIONS = [
    {
        "name": "list_aspects",
        "description": "List every aspect (record type) the user has, with a one-line description each.",
        "parameters": {"type": "OBJECT", "properties": {}},
    },
    {
        "name": "describe_aspect",
        "description": (
            "Get the exact field names and types for one aspect. ALWAYS call this before "
            "create_record or update_record on an aspect you have not already described in this "
            "conversation - field names are not guessable and a call with a wrong or invented "
            "field name will be rejected."
        ),
        "parameters": {
            "type": "OBJECT",
            "properties": {"aspect": {"type": "STRING", "description": "aspect name, e.g. 'workouts'"}},
            "required": ["aspect"],
        },
    },
    {
        "name": "query_records",
        "description": "Find existing records in an aspect, optionally filtered by exact field values.",
        "parameters": {
            "type": "OBJECT",
            "properties": {
                "aspect": {"type": "STRING"},
                "filters": {"type": "OBJECT", "description": "field name -> exact value to match"},
            },
            "required": ["aspect"],
        },
    },
    {
        "name": "create_record",
        "description": (
            "Create one new record in an aspect. 'values' must use exactly the field names "
            "describe_aspect returned - never invent a field name. To log several rows in one "
            "instruction (e.g. three sets of an exercise), call this tool once per row."
        ),
        "parameters": {
            "type": "OBJECT",
            "properties": {
                "aspect": {"type": "STRING"},
                "values": {"type": "OBJECT", "description": "field name -> value, using describe_aspect's field names"},
            },
            "required": ["aspect", "values"],
        },
    },
    {
        "name": "update_record",
        "description": "Update one existing record by id. Find the id first with query_records.",
        "parameters": {
            "type": "OBJECT",
            "properties": {
                "aspect": {"type": "STRING"},
                "id": {"type": "INTEGER"},
                "values": {"type": "OBJECT"},
            },
            "required": ["aspect", "id", "values"],
        },
    },
    {
        "name": "delete_record",
        "description": "Delete one existing record by id. Find the id first with query_records.",
        "parameters": {
            "type": "OBJECT",
            "properties": {"aspect": {"type": "STRING"}, "id": {"type": "INTEGER"}},
            "required": ["aspect", "id"],
        },
    },
]

SYSTEM_INSTRUCTION = """You are the aspect clerk, an executor that turns one natural-language
instruction from the user into exact record writes against their personal data aspects
(workouts, groceries, etc). You have tools to list aspects, describe an aspect's exact fields,
query existing records, and create/update/delete records.

Rules:
- NEVER guess a field name. Call describe_aspect for an aspect before writing to it for the
  first time in this conversation.
- If an instruction implies multiple rows (e.g. "bench 3x5" means three sets of 5 reps), write
  one record per row - do not collapse them into one record or invent a field to hold a count.
- If the instruction is genuinely ambiguous or missing information you cannot reasonably infer
  (e.g. which aspect, which record to update), do not guess or fabricate a value - ask a
  clarifying question in your final answer instead of writing anything.
- When you are done (or when you must stop), your final answer MUST literally state, in words,
  exactly how many rows were written and how many failed, e.g. "Wrote 3 rows to workouts, 0
  failed." or "Wrote 0 rows, 0 failed: I need to know which aspect you mean." Never claim a row
  was written unless a create_record/update_record/delete_record call actually returned success
  for it in this conversation.
"""


def post(model: str, contents, force_answer: bool):
    body = {
        "systemInstruction": {"parts": [{"text": SYSTEM_INSTRUCTION}]},
        "contents": contents,
        "tools": [{"functionDeclarations": TOOL_DECLARATIONS}],
    }
    if force_answer:
        body["toolConfig"] = {"functionCallingConfig": {"mode": "NONE"}}
    url = f"{API_URL}/{model}:generateContent?key={API_KEY}"
    data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        err_body = e.read().decode("utf-8", errors="replace")
        return {"_http_error": e.code, "_body": err_body}


def extract_calls(resp):
    calls = []
    cand = (resp.get("candidates") or [{}])[0]
    parts = (cand.get("content") or {}).get("parts") or []
    for p in parts:
        fc = p.get("functionCall")
        if fc:
            calls.append(fc)
    return calls


def extract_text(resp):
    cand = (resp.get("candidates") or [{}])[0]
    parts = (cand.get("content") or {}).get("parts") or []
    text = "".join(p.get("text", "") for p in parts if "text" in p)
    return text or None


def run_loop(model: str, store: FakeStore, instruction: str, max_calls: int = MAX_MODEL_CALLS):
    """Mirrors SubAgent.investigate's shape: bounded rounds, tool results fed back,
    forced tool-free answer on round max_calls+1 if still not done."""
    contents = [{"role": "user", "parts": [{"text": instruction}]}]
    trace = []
    dispatch = {
        "list_aspects": store.list_aspects,
        "describe_aspect": store.describe_aspect,
        "query_records": store.query_records,
        "create_record": store.create_record,
        "update_record": store.update_record,
        "delete_record": store.delete_record,
    }
    post_number = 0
    t0 = time.time()
    while True:
        post_number += 1
        force = post_number > max_calls
        resp = post(model, contents, force)
        if "_http_error" in resp:
            trace.append({"round": post_number, "http_error": resp["_http_error"], "body": resp["_body"][:500]})
            return {"trace": trace, "final_text": None, "latency_s": time.time() - t0, "error": "http_error"}

        calls = extract_calls(resp)
        if not calls or force:
            text = extract_text(resp)
            trace.append({"round": post_number, "final_text": text, "forced": force})
            return {"trace": trace, "final_text": text, "latency_s": time.time() - t0, "error": None}

        # echo model turn verbatim
        cand = (resp.get("candidates") or [{}])[0]
        model_content = cand.get("content")
        if model_content:
            contents.append(model_content)

        call_names = [c.get("name") for c in calls]
        round_results = []
        function_response_parts = []
        for c in calls:
            name = c.get("name")
            args = c.get("args") or {}
            fn = dispatch.get(name)
            result = fn(args) if fn else {"error": "unknown tool"}
            round_results.append({"name": name, "args": args, "result": result})
            function_response_parts.append({
                "functionResponse": {"name": name, "response": {"result": json.dumps(result)}}
            })

        nudge = None
        if (post_number + 1) >= max_calls:
            nudge = "Answer now with what you have."
        if nudge:
            function_response_parts.append({"text": nudge})

        contents.append({"role": "user", "parts": function_response_parts})
        trace.append({"round": post_number, "calls": call_names, "results": round_results})


# ---------------------------------------------------------------------------
# Test cases
# ---------------------------------------------------------------------------
CASES = [
    {
        "key": "single_create",
        "instruction": "Log a workout: bench press, 185 pounds, 5 reps, today (2026-08-23).",
        "seed": "none",
        "expect_rows": 1,
        "expect_aspect": "workouts",
    },
    {
        "key": "multi_create",
        "instruction": "Log today's workout (2026-08-23): bench 3x5 at 185.",
        "seed": "none",
        "expect_rows": 3,
        "expect_aspect": "workouts",
    },
    {
        "key": "query_then_update",
        "instruction": "Change today's bench press entry to 190 pounds instead of 185.",
        "seed": "bench",
        "expect_rows": 1,
        "expect_aspect": "workouts",
        "op": "update",
    },
    {
        "key": "delete",
        "instruction": "Delete the bench press entry from today, I logged it by mistake.",
        "seed": "bench",
        "expect_rows": 1,
        "expect_aspect": "workouts",
        "op": "delete",
    },
    {
        "key": "ambiguous",
        "instruction": "Log my workout.",
        "seed": "none",
        "expect_rows": 0,
        "expect_aspect": None,
    },
]


def score_reliability(case, run_result, store: FakeStore):
    """Heuristic scoring per the ticket's three reliability questions."""
    trace = run_result["trace"]
    all_calls = []
    for step in trace:
        if "calls" in step:
            all_calls.extend(step["calls"])

    describe_before_write = True
    described = set()
    for step in trace:
        if "calls" not in step:
            continue
        for cr in step["results"]:
            n = cr["name"]
            if n == "describe_aspect":
                described.add(cr["args"].get("aspect"))
            if n in ("create_record", "update_record"):
                asp = cr["args"].get("aspect")
                if asp not in described:
                    describe_before_write = False

    hallucinated_field = False
    for step in trace:
        if "calls" not in step:
            continue
        for cr in step["results"]:
            if cr["name"] in ("create_record", "update_record"):
                res = cr["result"]
                if isinstance(res, dict) and res.get("error", "").startswith("unknown field"):
                    hallucinated_field = True

    write_calls = [c for c in all_calls if c in ("create_record", "update_record", "delete_record")]
    aspect_key = case["expect_aspect"]
    actual_rows = len(store.records.get(aspect_key, [])) if aspect_key else 0

    final_text = run_result["final_text"] or ""
    states_outcome = bool(re.search(r"\bwrote\b|\bfailed\b|\bcreated\b|\bupdated\b|\bdeleted\b|\bneed to know\b|\bclarify\b|\bwhich\b", final_text, re.I))

    return {
        "num_model_calls": len([s for s in trace if "calls" in s]) + 1,
        "describe_before_write": describe_before_write if write_calls else "n/a (no writes)",
        "hallucinated_field": hallucinated_field,
        "write_call_count": len(write_calls),
        "final_row_count_in_aspect": actual_rows,
        "final_text": final_text,
        "states_outcome_in_words": states_outcome,
    }


def run_case_for_model(model: str, case: dict):
    latencies = []
    reliability_runs = []
    for run_i in range(RUNS_PER_CASE):
        store = FakeStore()
        if case["seed"] == "bench":
            store.seed_today_bench()
        result = run_loop(model, store, case["instruction"])
        latencies.append(result["latency_s"])
        rel = score_reliability(case, result, store)
        reliability_runs.append(rel)
        print(f"  [{model}] {case['key']} run {run_i+1}/{RUNS_PER_CASE}: "
              f"{result['latency_s']:.2f}s, calls={rel['num_model_calls']}, "
              f"rows={rel['final_row_count_in_aspect']}, "
              f"final='{(result['final_text'] or '')[:80]}'")
    return {
        "model": model,
        "case": case["key"],
        "latencies_s": latencies,
        "median_latency_s": statistics.median(latencies),
        "reliability_runs": reliability_runs,
    }


def main():
    all_results = []
    for case in CASES:
        for model in (MODEL_FLASH, MODEL_PRO):
            print(f"=== {case['key']} / {model} ===")
            all_results.append(run_case_for_model(model, case))

    out_path = Path(__file__).parent / "results.json"
    out_path.write_text(json.dumps(all_results, indent=2), encoding="utf-8")
    print(f"\nWrote {out_path}")

    print("\n=== SUMMARY (median latency, seconds) ===")
    print(f"{'case':<20}{'flash':>10}{'pro':>10}")
    by_case = {}
    for r in all_results:
        by_case.setdefault(r["case"], {})[r["model"]] = r["median_latency_s"]
    for case_key, models in by_case.items():
        f = models.get(MODEL_FLASH, float("nan"))
        p = models.get(MODEL_PRO, float("nan"))
        print(f"{case_key:<20}{f:>10.2f}{p:>10.2f}")


if __name__ == "__main__":
    main()
