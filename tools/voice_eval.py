r"""Score voice eval runs: the laptop harness (fake data) and the phone driver (real data) alike.

    python tools/voice_eval.py laptop                      run the laptop eval (needs LEGION_GEMINI_KEY_FILE), then score it
    python tools/voice_eval.py laptop --model gemini-3.5-flash --only due_open,due_today
    python tools/voice_eval.py score RUN.log [RUN2.log]    score saved runs; two or more print a comparison
    python tools/voice_eval.py score RUN.log --phone       score a phone run (tools/phone_voice_test.py writes these)
    python tools/voice_eval.py score RUN.log --grade       also ask an LLM grader where a case has a "grade" rubric

Both layers write the phone's own log lines (threadtime): `LIVE_AUDIT` (what was said, each tool call
with its args and result), `LIVE_LATENCY` (`gemini_usage`, timings), framed per case by
`LIVE_TEST case_begin id=... / case_end`. One parser and one set of checks score both, so a laptop
scorecard and a phone scorecard mean the same thing.

**Checks are rules first** (which tool, which args, words the answer must or must not hold, an empty
claim over an open task, a write that went through in test mode). The optional LLM grader runs only
with --grade and only on cases that carry a `grade` rubric; its verdict is printed in its own column,
labelled LLM, and never changes the rule verdict.

**Cost is an ESTIMATE** from `tools/voice_prices.json` x the counts in the `gemini_usage` lines (the
same pricing code as `tools/voice_cost.py`). A model with no price row says "no price on file". On the
laptop the ask_* routers' own sub-agent calls are not metered.

Case files: `tools/voice_eval/cases.json` (committed, FAKE) plus every `tools/voice_eval/private/*.json`
(git-ignored: Kevin's own utterances never enter the repo). Runs are written outside the repo, under
`~/.legion/voice-eval/`, because a phone run carries real data. Format: tools/voice_eval/README.md.
Stdlib only. Tests: python -m pytest tools/tests/test_voice_eval.py -p no:cacheprovider
"""

from __future__ import annotations

import argparse
import datetime
import importlib.util
import json
import os
import re
import subprocess
import sys
import urllib.request
from dataclasses import dataclass, field
from decimal import Decimal
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
REPO = TOOLS.parent
CASES = TOOLS / "voice_eval" / "cases.json"
PRIVATE_DIR = TOOLS / "voice_eval" / "private"
RUNS = Path.home() / ".legion" / "voice-eval"
DEFAULT_MODEL = "gemini-3.5-flash-lite"


def _load(name: str, file: str):
    spec = importlib.util.spec_from_file_location(name, TOOLS / file)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


voice_cost = _load("voice_cost_for_eval", "voice_cost.py")
voice_audit = _load("voice_audit_for_eval", "voice_audit.py")

LINE = re.compile(
    r"^(?P<md>\d\d-\d\d)\s+(?P<time>\d\d:\d\d:\d\d)(?:\.\d+)?\s+\d+\s+\d+\s+[VDIWEF]\s+(?P<tag>[A-Z_]+)\s*:\s?(?P<msg>.*)$"
)
KV = re.compile(r"(\w+)=(\S+)")

# An answer that says the day or window is empty. Only checked when a tool result in the same turn
# shows an open task (`"done":false`), so "nothing due" is right when nothing is.
EMPTY_CLAIM = re.compile(
    r"nothing (is |'s )?(due|on|left|pending|open)|no(thing)? (tasks?|assignments?|deadlines?|homework)\b"
    r"|(you'?re|you are) (all )?(clear|free)|\bquiet\b|nothing on|all clear|free (day|today|tonight)"
    r"|(don'?t|do not) have anything",
    re.I,
)
# A write claimed as done. Passes when the answer also says it was not done.
CLAIM = re.compile(r"\b(added|i'?ve added|marked|saved|scheduled|created|done|set (it|that|a))\b", re.I)
NOT_DONE = re.compile(r"test mode|not done|couldn'?t|could not|didn'?t|did not|wasn'?t|unable|can'?t|no changes?", re.I)
TEST_REFUSAL = "test_mode"


# --------------------------------------------------------------------------- cases


def load_cases(paths: list[Path]) -> list[dict]:
    out, seen = [], set()
    for p in paths:
        data = json.loads(Path(p).read_text(encoding="utf-8"))
        for c in data["cases"]:
            if c["id"] in seen:
                raise SystemExit(f"duplicate case id {c['id']} in {p}")
            seen.add(c["id"])
            c["_source"] = "private" if PRIVATE_DIR in Path(p).resolve().parents else Path(p).name
            out.append(c)
    return out


def default_case_files() -> list[Path]:
    files = [CASES]
    if PRIVATE_DIR.is_dir():
        files += sorted(PRIVATE_DIR.glob("*.json"))
    return files


def case_turns(case: dict) -> list[dict]:
    """Each turn as {say, expect, fixture}; a single-turn case's top-level fields are its one turn."""
    if "turns" in case:
        return case["turns"]
    return [{"say": case["say"], "expect": case.get("expect", {}), "fixture": case.get("fixture", {})}]


def expand(value: str, today: datetime.date) -> str:
    """`{today}`, `{today+3}`, `{today-1}` to yyyy-MM-dd."""

    def sub(m: re.Match) -> str:
        n = int(m.group(1) or 0)
        return (today + datetime.timedelta(days=n)).isoformat()

    return re.sub(r"\{today([+-]\d+)?\}", sub, value)


# --------------------------------------------------------------------------- runs


@dataclass
class ToolCall:
    id: str
    name: str
    args: dict
    success: bool | None = None
    response: str = ""
    ms: int | None = None

    @property
    def action(self) -> str:
        return str(self.args.get("action") or self.args.get("domain") or "")

    @property
    def key(self) -> str:
        return f"{self.name}/{self.action}" if self.action else self.name

    @property
    def refused_for_test(self) -> bool:
        return TEST_REFUSAL in self.response


@dataclass
class Turn:
    user: str
    calls: list[ToolCall] = field(default_factory=list)
    answer_parts: list[str] = field(default_factory=list)
    timings: dict[str, int] = field(default_factory=dict)

    @property
    def answer(self) -> str:
        return " ".join(p.strip() for p in self.answer_parts if p.strip())


@dataclass
class CaseRun:
    id: str
    day: datetime.date
    turns: list[Turn] = field(default_factory=list)
    usage: list = field(default_factory=list)  # voice_cost.Call
    timings: dict[str, int] = field(default_factory=dict)
    notes: list[str] = field(default_factory=list)
    model: str = ""
    ms: int | None = None
    ended: bool = False


def parse_run(lines: list[str], year: int) -> tuple[dict, list[CaseRun]]:
    """(run facts, cases) from one log. Lines outside a case_begin/case_end frame are ignored, except
    the run-level LIVE_TEST lines."""
    run: dict = {}
    cases: list[CaseRun] = []
    cur: CaseRun | None = None
    for raw in lines:
        m = LINE.match(raw.rstrip("\r\n"))
        if not m:
            continue
        tag, msg = m["tag"], m["msg"]
        day = datetime.date(year, int(m["md"][:2]), int(m["md"][3:]))
        fields = dict(KV.findall(msg))
        if tag == "LIVE_TEST":
            word = msg.split(" ", 1)[0]
            if word == "case_begin":
                cur = CaseRun(id=fields.get("id", "?"), day=day)
                cases.append(cur)
            elif word == "case_end" and cur is not None:
                cur.ended = True
                cur.ms = int(fields["ms"]) if fields.get("ms", "").isdigit() else None
                if fields.get("note"):
                    cur.notes.append(fields["note"].replace("_", " "))
                cur = None
            elif word == "note" and cur is not None:
                cur.notes.append(msg[5:].strip())
            elif word in ("run_begin", "device", "settings"):
                run.update(fields)
            continue
        if cur is None:
            # A cache created before the first case (the laptop run warms it) is the run's overhead.
            if tag == "LIVE_LATENCY" and msg.startswith("gemini_usage "):
                run.setdefault("_usage", []).append(_usage_call(fields))
            continue
        if tag == "LIVE_AUDIT":
            event, f = voice_audit.parse_audit(msg)
            if event == "session_start":
                model = str(f.get("model", ""))
                cur.model = model.split("+", 1)[1] if "+" in model else model
            elif event == "user":
                cur.turns.append(Turn(user=str(f.get("text", ""))))
            elif event == "interrupted":
                cur.notes.append(f"reply interrupted ({f.get('source', '?')})")
            elif event == "assistant" and cur.turns:
                cur.turns[-1].answer_parts.append(str(f.get("text", "")))
            elif event == "tool_call":
                if not cur.turns:
                    cur.turns.append(Turn(user=""))
                try:
                    args = json.loads(f.get("args", "{}"))
                except ValueError:
                    args = {}
                cur.turns[-1].calls.append(ToolCall(str(f.get("id")), str(f.get("name")), args if isinstance(args, dict) else {}))
            elif event == "tool_result":
                for t in reversed(cur.turns):
                    hit = next((c for c in t.calls if c.id == str(f.get("id"))), None)
                    if hit:
                        hit.success = f.get("success") == "true"
                        hit.response = str(f.get("response", ""))
                        hit.ms = int(f["ms"]) if str(f.get("ms", "")).lstrip("-").isdigit() else None
                        break
        elif tag == "LIVE_LATENCY":
            if msg.startswith("gemini_usage "):
                cur.usage.append(_usage_call(fields))
                continue
            if msg.startswith("greeting scripted"):
                cur.notes.append(f"greeting fell back to the scripted line ({fields.get('reason', '?')})")
            for k, v in fields.items():
                if not re.fullmatch(r"-?\d+", v) or not k.endswith("_ms"):
                    continue
                target = cur.turns[-1].timings if cur.turns else cur.timings
                # First value wins for open_* (once per conversation); last wins per turn.
                if k.startswith("open_"):
                    cur.timings.setdefault(k, int(v))
                else:
                    target[k] = int(v)
    return run, cases


def _usage_call(fields: dict) -> "voice_cost.Call":
    return voice_cost.Call(
        kind=fields.get("kind", "?"),
        model=fields.get("model", "?"),
        counts=voice_cost._ints(fields),
        present=fields.get("usage") != "absent",
        discarded=fields.get("discarded") == "true",
        failed=fields.get("failed") == "true",
        audio_s=Decimal(fields.get("audio_s", "0")),
        ttl_s=int(fields.get("ttl_s", "0")),
    )


# --------------------------------------------------------------------------- checks


def matches(call: ToolCall, spec: str) -> bool:
    """`tool` or `tool/action`; `a|b` is either. A bare name also matches an action value
    (`read_calendar` matches `day` with action read_calendar)."""
    for alt in spec.split("|"):
        alt = alt.strip()
        if "/" in alt:
            name, action = alt.split("/", 1)
            if call.name == name and call.action == action:
                return True
        elif alt in (call.name, call.action):
            return True
    return False


def arg_ok(value, matcher, today: datetime.date) -> bool:
    if value is None:
        return False
    v = str(value).strip()
    if isinstance(matcher, str):
        return v.lower() == expand(matcher, today).lower()
    ok = True
    if "lte" in matcher:
        ok &= v <= expand(matcher["lte"], today)
    if "gte" in matcher:
        ok &= v >= expand(matcher["gte"], today)
    if "re" in matcher:
        ok &= re.search(matcher["re"], v) is not None
    return ok


@dataclass
class Verdict:
    passed: bool
    failures: list[str]
    warnings: list[str]
    llm: str | None = None  # "PASS"/"FAIL: why"/None


def check_turn(turn: Turn, expect: dict, today: datetime.date, *, phone: bool, write: bool,
               allow_writes: bool) -> tuple[list[str], list[str]]:
    fails, warns = [], []
    seen = ", ".join(c.key for c in turn.calls) or "no tool"
    for spec in expect.get("calls", []):
        if not any(matches(c, spec) for c in turn.calls):
            fails.append(f"expected call {spec}; saw {seen}")
    for spec in expect.get("not_calls", []):
        if any(matches(c, spec) for c in turn.calls):
            fails.append(f"called {spec}, which it must not")
    for spec, params in expect.get("args", {}).items():
        cands = [c for c in turn.calls if matches(c, spec)]
        if not cands:
            continue  # already reported by calls
        if not any(all(arg_ok(c.args.get(k), mt, today) for k, mt in params.items()) for c in cands):
            got = "; ".join(json.dumps(c.args, sort_keys=True) for c in cands)
            fails.append(f"{spec} args {json.dumps(params)} not met (today {today}); got {got}")
    # `args_all`: EVERY matching call must fit, so a wrong first call that the model later corrects
    # still fails (2026-11-15: "and tomorrow?" asked for today+2 first, then today+1).
    for spec, params in expect.get("args_all", {}).items():
        for c in (c for c in turn.calls if matches(c, spec)):
            if not all(arg_ok(c.args.get(k), mt, today) for k, mt in params.items()):
                fails.append(f"{spec} call {json.dumps(c.args, sort_keys=True)} misses {json.dumps(params)} (today {today})")
    answer = turn.answer
    for rx in expect.get("answer_has", []):
        if not re.search(rx, answer, re.I):
            fails.append(f"answer lacks /{rx}/")
    for rx in expect.get("answer_not", []):
        if re.search(rx, answer, re.I):
            fails.append(f"answer has /{rx}/")
    if expect.get("no_empty_claim"):
        # An open task in the result: a task row not done, or a non-empty `due` block (fix/greet-and-due).
        # The `due` block leads with its counts, so `"open":N` survives the audit line's 400-character cut.
        open_task = any('"done":false' in c.response or re.search(r'"due":\[\{|"due":\{"open":[1-9]', c.response)
                        for c in turn.calls)
        if open_task and EMPTY_CLAIM.search(answer):
            fails.append("said nothing is due while a tool result shows an open task")
    if not answer:
        fails.append("no answer text")
    # Outcome checks (2026-11-15): the change landed, whichever tool the model reached it by. Not
    # on a phone write that test mode refuses (nothing can land there).
    if not (phone and write and not allow_writes):
        for spec in expect.get("succeeded", []):
            if not any(matches(c, spec) and c.success for c in turn.calls):
                fails.append(f"no successful call {spec}; saw {seen}")
        for rx in expect.get("result_has", []):
            if not any(c.success and re.search(rx, c.response or "", re.I) for c in turn.calls):
                fails.append(f"no successful tool result has /{rx}/")
    # A question that only reads must not change anything (2026-11-15: "which cars do I have"
    # called car/switch_vehicle on the laptop eval).
    if not write:
        for c in turn.calls:
            if _looks_write(c):
                fails.append(f"changed something on a question that only reads: {c.key}")
    # Writes on the phone: refused unless the case allows them, and never claimed.
    for c in turn.calls:
        if phone and not allow_writes and c.success and not c.refused_for_test and _looks_write(c):
            fails.append(f"WRITE WENT THROUGH in test mode: {c.key}")
    if phone and write and not allow_writes:
        if not any(c.refused_for_test for c in turn.calls):
            warns.append("write case: no test-mode refusal seen (the model may not have called the tool)")
        if CLAIM.search(answer) and not NOT_DONE.search(answer):
            fails.append("claimed a write that test mode refused")
    return fails, warns


WRITE_ACTIONS = {"add", "remove", "tick", "untick", "schedule", "skip", "create", "clear_ticked", "set", "log",
                 "complete_task", "watch_set", "watch_pause", "watch_resume", "watch_remove", "remember", "forget",
                 "start_voice_note", "stop_voice_note", "place_call", "answer_call", "decline_call", "install"}
WRITE_TOOLS = {"complete_task", "car", "body_log", "money", "place", "memory", "phone", "clock", "goal", "trip"}
READ_ACTIONS = {"list", "read", "lists", "ticked", "list_budget_categories", "watch_list", "recall", "search"}


def _looks_write(c: ToolCall) -> bool:
    """Only a hint for the scorer; the phone's own gate is the guarantee (TestTurnPolicy.kt)."""
    if c.action in READ_ACTIONS:
        return False
    return (c.action in WRITE_ACTIONS or c.action.startswith(("log_", "set_", "switch_", "add_"))
            or c.name in WRITE_TOOLS)


def score_case(case: dict, run: CaseRun, *, phone: bool) -> Verdict:
    fails, warns = [], []
    turns = case_turns(case)
    if not run.ended:
        fails.append("case did not finish (timed out or aborted)")
    if len(run.turns) < len(turns):
        fails.append(f"{len(run.turns)} of {len(turns)} turns answered")
    for i, (spec, got) in enumerate(zip(turns, run.turns)):
        expect = dict(spec.get("expect", {}))
        if not phone:
            fx = spec.get("fixture", {})
            expect["answer_has"] = expect.get("answer_has", []) + fx.get("answer_has", [])
            expect["answer_not"] = expect.get("answer_not", []) + fx.get("answer_not", [])
            expect["result_has"] = expect.get("result_has", []) + fx.get("result_has", [])
        f, w = check_turn(got, expect, run.day, phone=phone, write=bool(case.get("write")),
                          allow_writes=bool(case.get("allow_writes")))
        prefix = f"turn {i + 1}: " if len(turns) > 1 else ""
        fails += [prefix + x for x in f]
        warns += [prefix + x for x in w]
    return Verdict(not fails, fails, warns)


# --------------------------------------------------------------------------- cost


def price(run: CaseRun) -> tuple[Decimal, list[str], dict[str, int]]:
    prices = voice_cost.load_prices()
    total, notes, tokens = Decimal(0), [], {"prompt": 0, "cached": 0, "out": 0}
    for c in run.usage:
        p = voice_cost.price_call(c, prices, run.day)
        total += p.usd
        notes += p.notes
        if c.kind in ("llm", "greeting"):
            tokens["prompt"] += c.counts.get("prompt", 0)
            tokens["cached"] += c.counts.get("cached", 0)
            tokens["out"] += c.counts.get("out", 0) + c.counts.get("thoughts", 0)
    return total, sorted(set(notes)), tokens


# --------------------------------------------------------------------------- LLM grader (optional)

GRADER_PROMPT = """You grade one reply of a voice assistant. Answer ONLY with JSON {"pass": true|false, "why": "<one short sentence>"}.
Rubric: RUBRIC
What the user said: USER
What the tools returned (may be cut short): TOOLS
What the assistant said: ANSWER"""


def llm_grade(case: dict, run: CaseRun, key: str, model: str) -> str:
    turn = run.turns[-1] if run.turns else Turn(user="")
    tools = "; ".join(f"{c.key} -> {c.response[:300]}" for c in turn.calls) or "none"
    prompt = (GRADER_PROMPT.replace("RUBRIC", case["grade"]).replace("USER", turn.user)
              .replace("TOOLS", tools).replace("ANSWER", turn.answer))
    body = json.dumps({"contents": [{"role": "user", "parts": [{"text": prompt}]}],
                       "generationConfig": {"responseMimeType": "application/json"}}).encode()
    req = urllib.request.Request(
        f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent",
        data=body, headers={"content-type": "application/json", "x-goog-api-key": key})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            data = json.loads(r.read())
        text = data["candidates"][0]["content"]["parts"][0]["text"]
        v = json.loads(text)
        return "PASS" if v.get("pass") else f"FAIL: {v.get('why', '')}"
    except Exception as e:  # noqa: BLE001 - a grader failure is reported, never fatal
        return f"ERROR: {type(e).__name__}"


# --------------------------------------------------------------------------- report


def usd(d: Decimal) -> str:
    return voice_cost.usd(d)


def scorecard(cases: list[dict], runs: list[CaseRun], run_facts: dict, *, phone: bool,
              grader: tuple[str, str] | None = None, verbose: bool = True) -> tuple[list[str], dict]:
    by_id = {r.id: r for r in runs}
    lines, rows = [], []
    total = Decimal(0)
    passed = failed = missing = 0
    notes_all: set[str] = set()
    model = next((r.model for r in runs if r.model), run_facts.get("model", "?"))
    for case in cases:
        run = by_id.get(case["id"])
        if run is None:
            missing += 1
            continue
        v = score_case(case, run, phone=phone)
        if grader and case.get("grade"):
            v.llm = llm_grade(case, run, *grader)
        cost, notes, tok = price(run)
        notes_all.update(notes)
        total += cost
        passed += v.passed
        failed += not v.passed
        tools = " ".join(c.key + ("[refused]" if c.refused_for_test else "") for t in run.turns for c in t.calls) or "-"
        turn_ms = [t.timings.get("test_turn_ms") or t.timings.get("eot_reply_ms") for t in run.turns]
        ms = "/".join(str(x) for x in turn_ms if x is not None) or (str(run.ms) if run.ms else "-")
        row = {"id": case["id"], "pass": v.passed, "llm": v.llm, "tools": tools, "ms": ms,
               "tokens": tok, "usd": str(cost), "priced": not any("no price" in n for n in notes), "failures": v.failures, "warnings": v.warnings,
               "answer": " | ".join(t.answer for t in run.turns), "notes": run.notes,
               "timings": run.timings}
        rows.append(row)
        flag = "PASS" if v.passed else "FAIL"
        llm = f"  LLM {v.llm}" if v.llm else ""
        money = usd(cost) if row["priced"] else "no price"
        lines.append(f"{flag}  {case['id']:<22} {tools[:48]:<48} {ms:>11}  "
                     f"in {tok['prompt']:>6} cached {tok['cached']:>6} out {tok['out']:>4}  {money:>8}{llm}")
        if verbose or not v.passed:
            for f in v.failures:
                lines.append(f"      x {f}")
            for w in v.warnings:
                lines.append(f"      ! {w}")
            for n in run.notes:
                lines.append(f"      ~ {n}")
            if verbose:
                lines.append(f"      said: {row['answer'][:240]}")
            if run.timings:
                lines.append("      open: " + " ".join(f"{k}={v}" for k, v in sorted(run.timings.items())))
    overhead = Decimal(0)
    if run_facts.get("_usage"):
        day = runs[0].day if runs else datetime.date.today()
        overhead, notes, _ = price(CaseRun(id="run", day=day, usage=run_facts["_usage"]))
        notes_all.update(notes)
        total += overhead
    head = (f"VOICE EVAL ({'phone, real data' if phone else 'laptop, fake data'})  model={model}  "
            f"pass {passed}/{passed + failed}" + (f"  not run {missing}" if missing else "")
            + ("  cost INCOMPLETE: some calls have no price on file" if any(not r["priced"] for r in rows) else "")
            + f"  cost {usd(total)} (ESTIMATE"
            + (f", incl. {usd(overhead)} for the context cache" if overhead else "") + ")")
    tail = [f"note: {n}" for n in sorted(notes_all)]
    tail.append(voice_cost.ESTIMATE)
    if grader:
        tail.append("LLM = an LLM grader's opinion on the case's rubric; it does not change PASS/FAIL.")
    facts = {k: v for k, v in run_facts.items() if not k.startswith("_")}
    summary = {"model": model, "phone": phone, "passed": passed, "failed": failed, "missing": missing,
               "usd": str(total), "overhead_usd": str(overhead), "cases": rows, "run": facts}
    return [head, ""] + lines + [""] + tail, summary


def compare(summaries: list[dict]) -> list[str]:
    ids = []
    for s in summaries:
        for r in s["cases"]:
            if r["id"] not in ids:
                ids.append(r["id"])
    head = f"{'case':<22}" + "".join(f"  {s['model'][:24]:<24}" for s in summaries)
    out = ["COMPARISON", head]
    for i in ids:
        cells = []
        for s in summaries:
            r = next((x for x in s["cases"] if x["id"] == i), None)
            money = usd(Decimal(r["usd"])) if r is not None and r.get("priced", True) else "no price"
            cells.append("-" if r is None else f"{'PASS' if r['pass'] else 'FAIL'} {r['ms']}ms {money}")
        out.append(f"{i:<22}" + "".join(f"  {c:<24}" for c in cells))
    out.append(f"{'total':<22}" + "".join(
        f"  {str(s['passed']) + '/' + str(s['passed'] + s['failed']) + ' ' + usd(Decimal(s['usd'])):<24}" for s in summaries))
    return out


# --------------------------------------------------------------------------- commands


def _key(path: str | None) -> str | None:
    p = path or os.environ.get("LEGION_GEMINI_KEY_FILE")
    return Path(p).read_text(encoding="utf-8").strip() if p else None


def cmd_score(a) -> int:
    cases = load_cases([Path(p) for p in a.cases] if a.cases else default_case_files())
    if a.only:
        wanted = {x.strip() for x in a.only.split(",")}
        cases = [c for c in cases if c["id"] in wanted]
    grader = None
    if a.grade:
        key = _key(a.key_file)
        if not key:
            print("--grade needs --key-file or LEGION_GEMINI_KEY_FILE", file=sys.stderr)
            return 2
        grader = (key, a.grader_model)
    summaries = []
    for log in a.logs:
        lines = Path(log).read_text(encoding="utf-8", errors="replace").splitlines()
        facts, runs = parse_run(lines, a.year)
        out, summary = scorecard(cases, runs, facts, phone=a.phone, grader=grader, verbose=not a.quiet)
        print(f"== {log}")
        print("\n".join(out))
        summaries.append(summary)
        if a.json:
            Path(a.json if len(a.logs) == 1 else f"{a.json}.{len(summaries)}").write_text(
                json.dumps(summary, indent=2), encoding="utf-8")
    if len(summaries) > 1:
        print()
        print("\n".join(compare(summaries)))
    return 0 if all(s["failed"] == 0 and s["missing"] == 0 for s in summaries) else 1


def gradle_command() -> list[str]:
    """`$LEGION_GRADLE` (e.g. a lock wrapper) or the repo's gradlew."""
    env = os.environ.get("LEGION_GRADLE")
    if env:
        return env.split()
    return [str(REPO / ("gradlew.bat" if os.name == "nt" else "gradlew"))]


def cmd_laptop(a) -> int:
    if not os.environ.get("LEGION_GEMINI_KEY_FILE"):
        print("LEGION_GEMINI_KEY_FILE is not set: the laptop eval needs a key file (never printed).", file=sys.stderr)
        return 2
    files = [Path(p).resolve() for p in a.cases] if a.cases else default_case_files()
    RUNS.mkdir(parents=True, exist_ok=True)
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    out = RUNS / f"laptop-{stamp}-{a.model}.log"
    env = dict(os.environ)
    env["LEGION_VOICE_EVAL"] = ",".join(str(f) for f in files)
    env["LEGION_VOICE_EVAL_MODEL"] = a.model
    env["LEGION_VOICE_EVAL_OUT"] = str(out)
    if a.only:
        env["LEGION_VOICE_EVAL_ONLY"] = a.only
    if a.no_cache:
        env["LEGION_VOICE_EVAL_NO_CACHE"] = "1"
    cmd = gradle_command() + [":app:testDebugUnitTest", "--tests", "com.kevin.legion.voiceeval.VoiceEvalRun",
                              "--rerun", "-q"]
    print("running:", " ".join(cmd))
    rc = subprocess.run(cmd, cwd=REPO, env=env).returncode
    if not out.exists():
        print(f"no run log at {out} (gradle exit {rc})", file=sys.stderr)
        return rc or 1
    a.logs = [str(out)]
    a.phone = False
    a.cases = [str(f) for f in files]
    print(f"run log: {out}")
    return cmd_score(a)


def main(argv: list[str] | None = None) -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)

    def common(p):
        p.add_argument("--cases", action="append", help="case file (repeatable); default cases.json + private/*.json")
        p.add_argument("--grade", action="store_true", help="also run the LLM grader on cases with a rubric")
        p.add_argument("--grader-model", default=DEFAULT_MODEL)
        p.add_argument("--key-file", help="for --grade; default $LEGION_GEMINI_KEY_FILE")
        p.add_argument("--json", help="also write the scorecard as JSON here")
        p.add_argument("--quiet", action="store_true", help="only failing cases get detail lines")
        p.add_argument("--year", type=int, default=datetime.date.today().year)

    s = sub.add_parser("score", help="score saved run logs")
    s.add_argument("logs", nargs="+")
    s.add_argument("--phone", action="store_true", help="a phone run: real data, fixture checks off, writes refused")
    s.add_argument("--only", help="comma list of case ids")
    common(s)
    s.set_defaults(func=cmd_score)

    l = sub.add_parser("laptop", help="run the laptop eval (Robolectric + real Gemini), then score it")
    l.add_argument("--model", default=DEFAULT_MODEL)
    l.add_argument("--only", help="comma list of case ids")
    l.add_argument("--no-cache", action="store_true", help="inline requests only (no explicit context cache)")
    common(l)
    l.set_defaults(func=cmd_laptop)

    a = ap.parse_args(argv)
    return a.func(a)


if __name__ == "__main__":
    sys.exit(main())
