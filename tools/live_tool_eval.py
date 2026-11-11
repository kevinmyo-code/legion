"""The LIVE half of the tool-routing eval (live cost diet, 2026-11-10). NEEDS A GEMINI KEY.

The offline half (`LiveToolEvalCasesTest`) checks that every old tool has an invented utterance
and that each expected call exists on the diet surface; it writes
`app/build/live-setup/eval_expected.jsonl`. `LiveSetupPayloadSizeTest` writes the exact setup
(`app/build/live-setup/setup.json`). This script sends each utterance with that system
instruction and tool list to a model with function calling forced (mode ANY) and checks the
first call: the tool, and for an action tool the `action` value.

    ./gradlew :app:testDebugUnitTest --tests '*LiveSetupPayloadSizeTest' --tests '*LiveToolEvalCasesTest'
    GEMINI_API_KEY=... python tools/live_tool_eval.py [--model gemini-2.5-flash] [--out results.json]

Caveat: Live models are not served on generateContent, so this uses a text model with the same
declarations as a PROXY for how the Live model routes. A pass here is evidence, not proof; the
phone checklist still owes a spoken run. Paid: one small call per case (about 104). The key is
never printed. Results hold invented utterances only.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
BUILD = REPO / "app" / "build" / "live-setup"
API = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="gemini-2.5-flash")
    ap.add_argument("--key-file")
    ap.add_argument("--out")
    args = ap.parse_args()
    key = (Path(args.key_file).read_text(encoding="utf-8").strip() if args.key_file
           else os.environ.get("GEMINI_API_KEY", "").strip())
    if not key:
        raise SystemExit("No key: set GEMINI_API_KEY or pass --key-file.")
    setup = json.loads((BUILD / "setup.json").read_text(encoding="utf-8"))
    # googleSearch cannot be combined with forced function calling; the function list is the test.
    tools = [t for t in setup["tools"] if "functionDeclarations" in t]
    cases = [json.loads(x) for x in (BUILD / "eval_expected.jsonl").read_text(encoding="utf-8").splitlines() if x]

    results, ok = [], 0
    for c in cases:
        body = {
            "systemInstruction": {"parts": [{"text": setup["systemInstruction"]}]},
            "tools": tools,
            "toolConfig": {"functionCallingConfig": {"mode": "ANY"}},
            "contents": [{"role": "user", "parts": [{"text": c["say"]}]}],
        }
        req = urllib.request.Request(
            API.format(model=args.model), data=json.dumps(body).encode("utf-8"),
            headers={"Content-Type": "application/json", "x-goog-api-key": key}, method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                out = json.loads(resp.read().decode("utf-8"))
            parts = out["candidates"][0]["content"]["parts"]
            call = next(p["functionCall"] for p in parts if "functionCall" in p)
            got_tool, got_action = call["name"], (call.get("args") or {}).get(c.get("key", "action"))
        except (urllib.error.URLError, KeyError, IndexError, StopIteration) as e:
            got_tool, got_action = f"ERROR:{type(e).__name__}", None
        passed = got_tool == c["tool"] and (c["action"] is None or got_action == c["action"])
        ok += passed
        results.append({**c, "got_tool": got_tool, "got_action": got_action, "pass": passed})
        mark = "ok  " if passed else "MISS"
        want = c["tool"] + (f"/{c['action']}" if c["action"] else "")
        got = got_tool + (f"/{got_action}" if got_action else "")
        print(f"{mark} {want:45} got {got:45} | {c['say']}")
    print(f"{ok}/{len(cases)} routed as expected ({100 * ok / max(1, len(cases)):.0f}%) on {args.model}")
    # Selection accuracy per tool: by the call the model should make (tool, or tool/action), and
    # by the old tool each case exercises. Kevin uses these to decide on tool search.
    for label, key in (("per expected call", lambda r: r["tool"] + (f"/{r['action']}" if r["action"] else "")),
                       ("per old tool", lambda r: r["covers"])):
        print()
        print(f"selection accuracy {label}:")
        groups: dict[str, list[bool]] = {}
        for r in results:
            groups.setdefault(key(r), []).append(r["pass"])
        for name, marks in sorted(groups.items(), key=lambda kv: (sum(kv[1]) / len(kv[1]), kv[0])):
            print(f"  {name:50} {sum(marks)}/{len(marks)}")
    # Tool-level only (right declaration, action ignored): what tool search would change.
    tool_ok = sum(r["got_tool"] == r["tool"] for r in results)
    print()
    print(f"right declaration (action ignored): {tool_ok}/{len(results)}")
    if args.out:
        Path(args.out).write_text(json.dumps(results, indent=1), encoding="utf-8")
    return 0 if ok == len(cases) else 1


if __name__ == "__main__":
    sys.exit(main())
