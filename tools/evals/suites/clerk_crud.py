"""Suite 1: clerk CRUD reliability.

Ports the ticket-07 clerk prototype's matrix
(.scratch/aspect-engine/research/clerk-prototype/clerk_prototype.py) onto this harness's
runner. The six tool declarations below are mirrored from
service/EngineToolbox.kt.declarations() - same names and same parameter shape
(aspectName/recordTypeName/fields/recordId/filters), not the throwaway prototype's looser
aspect/values naming - so this suite exercises the actual production tool surface shape a
regression in EngineToolbox.declarations() would move the prompts_fingerprint against.

Pass criteria, matching EngineToolbox's own doc comment and CLAUDE.md §7's outcome rule:
  - describe-before-write: describe_aspect called for a record type before the first
    create_record/update_record against it in the conversation.
  - correct final row count for the case's expected record type.
  - zero hallucinated fields (a create/update rejected for an unknown field name would mean
    the model invented one; this harness's fake store rejects unknown fields the same way
    RecordStore does).
  - the final answer states an outcome in words - rows written/failed, or a clarifying
    question - never silence about what happened.
"""
from __future__ import annotations

import re
from typing import Optional

from common import GeminiClient, MODEL_FLASH, extract_calls, extract_text
from suites.base import Case, RunResult, Verdict

NAME = "clerk_crud"
DESCRIPTION = "Aspect-engine CRUD: describe-before-write, correct row counts, no hallucinated fields."

MAX_MODEL_CALLS = 4  # mirrors ai/SubAgent.kt's investigate() default bound


# ---------------------------------------------------------------------------
# Fake in-memory record store, aspectName/recordTypeName/fields shaped like
# data/local/EngineRecord + FieldDef rather than the prototype's flatter aspect/values.
# ---------------------------------------------------------------------------
class FakeStore:
    def __init__(self):
        self.schema = {
            ("bio", "workout_set"): ["exercise", "weightLbs", "reps", "date"],
            ("log", "grocery_item"): ["item", "quantity", "category", "bought"],
        }
        self.records: dict = {k: [] for k in self.schema}
        self._next_id = 1

    def seed_today_bench(self):
        rec = {
            "id": self._next_id,
            "exercise": "bench press",
            "weightLbs": 185,
            "reps": 5,
            "date": "2026-08-23",
        }
        self.records[("bio", "workout_set")].append(rec)
        self._next_id += 1

    # -- tool implementations, EngineToolbox-shaped results -----------------
    def list_aspects(self, args):
        aspects = {}
        for (a, rt) in self.schema:
            aspects.setdefault(a, []).append(rt)
        return {"aspects": [{"name": a, "recordTypes": rts} for a, rts in aspects.items()]}

    def describe_aspect(self, args):
        aspect = args.get("aspectName")
        rts = [rt for (a, rt) in self.schema if a == aspect]
        if not rts:
            return {"success": False, "message": f"There's no aspect called \"{aspect}\"."}
        return {
            "aspect": aspect,
            "recordTypes": [
                {"name": rt, "fields": self.schema[(aspect, rt)]} for rt in rts
            ],
        }

    def query_records(self, args):
        key = (args.get("aspectName"), args.get("recordTypeName"))
        if key not in self.records:
            return {"success": False, "message": f"No record type '{key[1]}' in aspect '{key[0]}'."}
        rows = self.records[key]
        filters = args.get("filters") or {}
        out = [r for r in rows if all(str(r.get(k)) == str(v) for k, v in filters.items())]
        return {"count": len(out), "records": out}

    def create_record(self, args):
        key = (args.get("aspectName"), args.get("recordTypeName"))
        fields = args.get("fields") or {}
        if key not in self.schema:
            return {"success": False, "message": f"No record type '{key[1]}' in aspect '{key[0]}'."}
        known = self.schema[key]
        unknown = [k for k in fields if k not in known]
        if unknown:
            return {"success": False, "message": f"unknown field(s) {unknown} for {key[1]} - known fields: {known}"}
        rec = dict(fields)
        rec["id"] = self._next_id
        self._next_id += 1
        self.records[key].append(rec)
        return {"success": True, "id": rec["id"]}

    def update_record(self, args):
        rec_id = args.get("recordId")
        fields = args.get("fields") or {}
        for key, rows in self.records.items():
            for r in rows:
                if r["id"] == rec_id:
                    unknown = [k for k in fields if k not in self.schema[key]]
                    if unknown:
                        return {"success": False, "message": f"unknown field(s) {unknown}"}
                    r.update(fields)
                    return {"success": True, "id": rec_id}
        return {"success": False, "message": f"No record with id {rec_id}."}

    def delete_record(self, args):
        rec_id = args.get("recordId")
        for key, rows in self.records.items():
            before = len(rows)
            self.records[key] = [r for r in rows if r["id"] != rec_id]
            if len(self.records[key]) != before:
                return {"success": True, "id": rec_id}
        return {"success": False, "message": f"No record with id {rec_id}."}


TOOL_DECLARATIONS = [
    {
        "name": "list_aspects",
        "description": "Lists every aspect and the record types each one holds. Read-only.",
        "parameters": {"type": "OBJECT", "properties": {}},
    },
    {
        "name": "describe_aspect",
        "description": (
            "Describes one aspect's record types and every field on each. Read-only. Call "
            "this before your first create_record/update_record against a record type you "
            "have not already described in this conversation - field names are not "
            "guessable."
        ),
        "parameters": {
            "type": "OBJECT",
            "properties": {"aspectName": {"type": "STRING"}},
            "required": ["aspectName"],
        },
    },
    {
        "name": "query_records",
        "description": "Finds existing records, optionally filtered by exact field values.",
        "parameters": {
            "type": "OBJECT",
            "properties": {
                "aspectName": {"type": "STRING"},
                "recordTypeName": {"type": "STRING"},
                "filters": {"type": "OBJECT"},
            },
            "required": ["aspectName", "recordTypeName"],
        },
    },
    {
        "name": "create_record",
        "description": (
            "Creates one new record. 'fields' must use exactly the field names "
            "describe_aspect returned - never invent one. To log several rows from one "
            "instruction, call this once per row."
        ),
        "parameters": {
            "type": "OBJECT",
            "properties": {
                "aspectName": {"type": "STRING"},
                "recordTypeName": {"type": "STRING"},
                "fields": {"type": "OBJECT"},
            },
            "required": ["aspectName", "recordTypeName", "fields"],
        },
    },
    {
        "name": "update_record",
        "description": "Updates fields on one existing record by id. Find the id with query_records first.",
        "parameters": {
            "type": "OBJECT",
            "properties": {"recordId": {"type": "INTEGER"}, "fields": {"type": "OBJECT"}},
            "required": ["recordId", "fields"],
        },
    },
    {
        "name": "delete_record",
        "description": "Deletes one existing record by id. Find the id with query_records first.",
        "parameters": {
            "type": "OBJECT",
            "properties": {"recordId": {"type": "INTEGER"}},
            "required": ["recordId"],
        },
    },
]

SYSTEM_INSTRUCTION = """You are the aspect clerk, an executor that turns one natural-language
instruction into exact record writes against the user's personal data aspects. You have tools
to list aspects, describe an aspect's exact fields, query existing records, and
create/update/delete records.

Rules:
- NEVER guess a field name. Call describe_aspect for an aspect before writing to it for the
  first time in this conversation.
- If an instruction implies multiple rows (e.g. "bench 3x5" means three sets of 5 reps), write
  one record per row - do not collapse them into one record or invent a field to hold a count.
- If the instruction is genuinely ambiguous or missing information you cannot reasonably infer,
  do not guess or fabricate a value - ask a clarifying question in your final answer instead of
  writing anything.
- Your final answer MUST literally state, in words, how many rows were written and how many
  failed, e.g. "Wrote 3 rows, 0 failed." Never claim a row was written unless a
  create_record/update_record/delete_record call actually returned success for it.
"""

CASES = [
    Case(
        key="single_create",
        calls_estimate=2,
        data={
            "instruction": "Log a workout: bench press, 185 pounds, 5 reps, today (2026-08-23).",
            "seed": None,
            "expect_key": ("bio", "workout_set"),
            "expect_rows": 1,
        },
    ),
    Case(
        key="multi_create",
        calls_estimate=4,
        data={
            "instruction": "Log today's workout (2026-08-23): bench 3x5 at 185.",
            "seed": None,
            "expect_key": ("bio", "workout_set"),
            "expect_rows": 3,
        },
    ),
    Case(
        key="query_then_update",
        calls_estimate=2,
        data={
            "instruction": "Change today's bench press entry to 190 pounds instead of 185.",
            "seed": "bench",
            "expect_key": ("bio", "workout_set"),
            "expect_rows": 1,
        },
    ),
    Case(
        key="delete",
        calls_estimate=2,
        data={
            "instruction": "Delete the bench press entry from today, I logged it by mistake.",
            "seed": "bench",
            "expect_key": ("bio", "workout_set"),
            "expect_rows": 0,
        },
    ),
    Case(
        key="ambiguous",
        calls_estimate=1,
        data={
            "instruction": "Log my workout.",
            "seed": None,
            "expect_key": ("bio", "workout_set"),
            "expect_rows": 0,
        },
    ),
]


def run_case(client: GeminiClient, model: str, case: Case, run_index: int) -> RunResult:
    store = FakeStore()
    if case.data["seed"] == "bench":
        store.seed_today_bench()

    dispatch = {
        "list_aspects": store.list_aspects,
        "describe_aspect": store.describe_aspect,
        "query_records": store.query_records,
        "create_record": store.create_record,
        "update_record": store.update_record,
        "delete_record": store.delete_record,
    }
    contents = [{"role": "user", "parts": [{"text": case.data["instruction"]}]}]
    trace = []
    calls_used = 0
    post_number = 0
    while True:
        post_number += 1
        force = post_number > MAX_MODEL_CALLS
        resp = client.generate(
            model, contents, system_instruction=SYSTEM_INSTRUCTION,
            tools=TOOL_DECLARATIONS, force_text=force,
        )
        calls_used += 1
        if "_http_error" in resp:
            trace.append({"round": post_number, "http_error": resp["_http_error"], "body": resp["_body"][:500]})
            return RunResult(trace, None, calls_used, error="http_error")

        calls = extract_calls(resp)
        if not calls or force:
            text = extract_text(resp)
            trace.append({"round": post_number, "final_text": text, "forced": force})
            return RunResult({"trace": trace, "final_store": _dump_store(store)}, text, calls_used)

        cand = (resp.get("candidates") or [{}])[0]
        model_content = cand.get("content")
        if model_content:
            contents.append(model_content)

        round_results = []
        function_response_parts = []
        for c in calls:
            fname = c.get("name")
            args = c.get("args") or {}
            fn = dispatch.get(fname)
            result = fn(args) if fn else {"error": "unknown tool"}
            round_results.append({"name": fname, "args": args, "result": result})
            function_response_parts.append(
                {"functionResponse": {"name": fname, "response": {"result": result}}}
            )
        contents.append({"role": "user", "parts": function_response_parts})
        trace.append({"round": post_number, "calls": [c["name"] for c in round_results], "results": round_results})


def _dump_store(store: FakeStore) -> dict:
    return {f"{a}/{rt}": rows for (a, rt), rows in store.records.items()}


def score(case: Case, result: RunResult) -> Verdict:
    if result.error:
        return Verdict(False, f"transport error: {result.error}")

    trace = result.transcript["trace"]
    described = set()
    describe_before_write = True
    hallucinated_field = False
    for step in trace:
        if "calls" not in step:
            continue
        for cr in step["results"]:
            n = cr["name"]
            if n == "describe_aspect":
                described.add(cr["args"].get("aspectName"))
            if n in ("create_record", "update_record"):
                asp = cr["args"].get("aspectName")
                if n == "create_record" and asp not in described:
                    describe_before_write = False
                res = cr["result"]
                if isinstance(res, dict) and str(res.get("message", "")).startswith("unknown field"):
                    hallucinated_field = True

    key = case.data["expect_key"]
    final_store = result.transcript["final_store"]
    store_key = f"{key[0]}/{key[1]}"
    actual_rows = len(final_store.get(store_key, []))
    expect_rows = case.data["expect_rows"]

    final_text = result.final_text or ""
    states_outcome = bool(
        re.search(
            r"\bwrote\b|\bfailed\b|\bcreated\b|\bupdated\b|\bdeleted\b|\bneed to know\b|\bclarify\b|"
            r"\bwhich\b|\bplease provide\b|\bcould you\b|\bcan you (tell|give|provide)\b|\bwhat\b.*\?|\?",
            final_text, re.I,
        )
    )

    problems = []
    if not describe_before_write:
        problems.append("wrote before describing the record type")
    if hallucinated_field:
        problems.append("invented a field name")
    if actual_rows != expect_rows:
        problems.append(f"expected {expect_rows} rows, store has {actual_rows}")
    if not states_outcome:
        problems.append("final answer did not state an outcome in words")

    passed = not problems
    detail = "ok" if passed else "; ".join(problems)
    return Verdict(passed, detail)
