"""Tool-routing eval on a TEXT model (live cost diet, 2026-11-10). NEEDS A GEMINI KEY.

Two halves:
- Offline (`LiveToolEvalCasesTest`, no key): every old tool has an invented utterance and each
  expected call exists on the diet surface. It writes `app/build/live-setup/eval_expected.jsonl`,
  where each case lists every call that counts as right (`accept`: tool or tool/action).
- This script: sends each utterance with the exact system instruction and tool list to a text
  model and scores the call it makes. A text model is a PROXY: the Live model is not served on
  generateContent. `tools/live_setup_probe.py` asks the real Live model.

    # new surface (after the payload test and LiveToolEvalCasesTest ran)
    python tools/live_tool_eval.py --key-file <k> --out <outside the repo>/eval-new.json
    # old surface, same sentences, scored on the old tool names
    python tools/live_tool_eval.py --key-file <k> --baseline --setup <dir>/base2/setup.json --out <...>/eval-old.json

Function calling is AUTO by default: mode ANY on the full list is refused by the API ("schema
produces a constraint that has too many states"), because forced calling compiles every
declaration into one grammar. Live does not force calls, so AUTO is also the honest comparison.

Replies are classed PASS, WRONG (another call), EMPTY (no parts: finishReason and thought tokens
are kept to tell why), TEXT (words, no call), BLOCKED, ERROR (HTTP code). `--thinking-budget 0`
(the default) turns thinking off on 2.5 Flash; -1 leaves the model's default. Empty candidates
with no parts and finishReason STOP after thinking are a known 2.5 Flash shape with large tool
lists; running both settings shows whether thinking explains them.

A first call that only gathers what the real call needs (the time, the record to delete, the
category names) gets a canned result and one more round (`--follow`, default 1), as the phone's
loop would; a right call in that round counts as PASS (preamble).

Cost: one generateContent per case and round, each carrying the whole setup as input (about 11k
tokens new, about 23k old) at the model's text-input price: on gemini-2.5-flash about $0.30 to
$0.70 for 104 cases. The key is never printed. Results hold invented utterances only.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from collections import Counter
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
BUILD = REPO / "app" / "build" / "live-setup"
API = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"

# Calls that only gather what the real call needs; answered with a canned result for one more round.
PREAMBLE = {
    ("day", "get_current_time"): {"success": True, "now": "Tuesday 2026-11-10 09:05, UTC-06:00"},
    ("get_current_time", None): {"success": True, "now": "Tuesday 2026-11-10 09:05, UTC-06:00"},
    ("records", "query_records"): {"success": True, "records": [{"recordId": 7, "summary": "Fern, repotted"}]},
    ("records", "describe_aspect"): {"success": True, "recordTypes": ["Plant"], "fields": ["name", "date"]},
    ("records", "list_aspects"): {"success": True, "aspects": ["Plants"]},
    ("money", "list_budget_categories"): {"success": True, "categories": ["Groceries", "Dining", "Utilities"]},
}


def call_model(model: str, key: str, body: dict) -> dict:
    req = urllib.request.Request(
        API.format(model=model), data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json", "x-goog-api-key": key}, method="POST",
    )
    with urllib.request.urlopen(req, timeout=90) as resp:
        return json.loads(resp.read().decode("utf-8"))


def classify(out: dict) -> tuple[str, dict | None, dict]:
    """(kind, functionCall or None, info) for one response."""
    info = {"thoughts": (out.get("usageMetadata") or {}).get("thoughtsTokenCount")}
    if out.get("promptFeedback", {}).get("blockReason"):
        return "BLOCKED", None, {**info, "why": out["promptFeedback"]["blockReason"]}
    cands = out.get("candidates") or []
    if not cands:
        return "EMPTY", None, {**info, "why": "no candidates"}
    cand = cands[0]
    info["finish"] = cand.get("finishReason")
    parts = (cand.get("content") or {}).get("parts") or []
    for p in parts:
        if "functionCall" in p:
            return "CALL", p["functionCall"], info
    if any(p.get("text", "").strip() for p in parts if not p.get("thought")):
        return "TEXT", None, info
    return "EMPTY", None, info


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--model", default="gemini-2.5-flash")
    ap.add_argument("--key-file")
    ap.add_argument("--setup", default=str(BUILD / "setup.json"))
    ap.add_argument("--expected", default=str(BUILD / "eval_expected.jsonl"))
    ap.add_argument("--baseline", action="store_true", help="score on the OLD tool names (covers)")
    ap.add_argument("--mode", default="AUTO", choices=["AUTO", "ANY"])
    ap.add_argument("--thinking-budget", type=int, default=0, help="0 off, -1 model default")
    ap.add_argument("--follow", type=int, default=1, help="extra rounds after a preamble call")
    ap.add_argument("--limit", type=int)
    ap.add_argument("--out")
    args = ap.parse_args()
    key = (Path(args.key_file).read_text(encoding="utf-8").strip() if args.key_file
           else os.environ.get("GEMINI_API_KEY", "").strip())
    if not key:
        raise SystemExit("No key: set GEMINI_API_KEY or pass --key-file.")
    setup = json.loads(Path(args.setup).read_text(encoding="utf-8"))
    tools = [t for t in setup["tools"] if "functionDeclarations" in t]
    # The discriminator per declaration: `domain` on the routers, `action` elsewhere.
    disc_of = {d["name"]: ("domain" if "domain" in d.get("parameters", {}).get("properties", {}) else "action")
               for t in tools for d in t["functionDeclarations"]}
    cases = [json.loads(x) for x in Path(args.expected).read_text(encoding="utf-8").splitlines() if x]
    if args.limit:
        cases = cases[: args.limit]

    gen = {}
    if args.thinking_budget >= 0:
        gen["thinkingConfig"] = {"thinkingBudget": args.thinking_budget}

    results = []
    for c in cases:
        accept = [c["covers"]] if args.baseline else c["accept"]
        contents = [{"role": "user", "parts": [{"text": c["say"]}]}]
        calls, kind, info, passed, preamble = [], None, {}, False, False
        for _round in range(1 + args.follow):
            body = {
                "systemInstruction": {"parts": [{"text": setup["systemInstruction"]}]},
                "tools": tools,
                "toolConfig": {"functionCallingConfig": {"mode": args.mode}},
                "contents": contents,
                **({"generationConfig": gen} if gen else {}),
            }
            try:
                out = call_model(args.model, key, body)
            except urllib.error.HTTPError as e:
                kind, info = "ERROR", {"why": e.code, "body": e.read().decode("utf-8", "replace")[:300]}
                break
            except (urllib.error.URLError, TimeoutError) as e:
                kind, info = "ERROR", {"why": type(e).__name__}
                break
            kind, fc, info = classify(out)
            if kind != "CALL":
                break
            disc = disc_of.get(fc["name"], "action")
            got = fc["name"] + ("/" + str((fc.get("args") or {}).get(disc)) if (fc.get("args") or {}).get(disc) else "")
            calls.append(got)
            if got in accept or fc["name"] in accept:
                passed = True
                break
            canned = PREAMBLE.get((fc["name"], (fc.get("args") or {}).get(disc)))
            if canned is None:
                break
            preamble = True
            contents += [
                {"role": "model", "parts": [{"functionCall": fc}]},
                {"role": "user", "parts": [{"functionResponse": {"name": fc["name"], "response": canned}}]},
            ]
        verdict = "PASS" if passed else ("WRONG" if kind == "CALL" else kind)
        results.append({**c, "verdict": verdict, "calls": calls, "preamble": preamble, **info})
        mark = {"PASS": "ok  ", "WRONG": "MISS"}.get(verdict, verdict[:4])
        print(f"{mark} {'|'.join(accept)[:50]:50} got {' > '.join(calls) or '-':40} | {c['say']}")

    n = len(results)
    counts = Counter(r["verdict"] for r in results)
    print()
    print(f"{counts['PASS']}/{n} right ({100 * counts['PASS'] / max(1, n):.0f}%), model {args.model}, "
          f"mode {args.mode}, thinking {args.thinking_budget}, {'OLD names' if args.baseline else 'new surface'}")
    print("verdicts:", dict(counts))
    print("passes after a preamble call:", sum(1 for r in results if r["verdict"] == "PASS" and r["preamble"]))
    empties = [r for r in results if r["verdict"] == "EMPTY"]
    if empties:
        print("EMPTY finish reasons:", dict(Counter(str(r.get("finish")) for r in empties)),
              "with thoughts:", sum(1 for r in empties if r.get("thoughts")))
    print()
    print("selection accuracy per old tool (worst first):")
    groups: dict[str, list[str]] = {}
    for r in results:
        groups.setdefault(r["covers"], []).append(r["verdict"])
    for name, vs in sorted(groups.items(), key=lambda kv: (kv[1].count("PASS") / len(kv[1]), kv[0])):
        print(f"  {name:32} {vs.count('PASS')}/{len(vs)}  {','.join(v for v in vs if v != 'PASS')}")
    if not args.baseline:
        tool_ok = sum(1 for r in results if r["calls"] and any(
            a.split("/")[0] == r["calls"][-1].split("/")[0] for a in r["accept"]))
        print(f"\nright declaration (action ignored): {tool_ok}/{n}")
    if args.out:
        Path(args.out).write_text(json.dumps(results, indent=1), encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
