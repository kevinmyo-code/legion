"""The money specialist's eval on a TEXT model (blind review M1, 2026-11-20). NEEDS A GEMINI KEY.

Offline half: `MoneySpecialistEvalCasesTest` checks the cases and writes
`app/build/live-setup/money_specialist.json` (the specialist's own instruction and its four tools).
This script sends each invented sentence with that setup and scores the call it makes against
`tools/live_tool_eval/money_specialist_cases.jsonl`: the tool, the view (an empty string in the list
accepts no view), and whether a category is named. The key is never printed.

    python tools/money_specialist_eval.py --key-file <k> [--model gemini-3.5-flash-lite]
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO / "tools"))
from live_tool_eval import call_model  # noqa: E402

SETUP = REPO / "app" / "build" / "live-setup" / "money_specialist.json"
CASES = REPO / "tools" / "live_tool_eval" / "money_specialist_cases.jsonl"


def verdict(case: dict, calls: list[tuple[str, dict]]) -> bool:
    if not calls:
        return False
    name, args = calls[0]
    if name != case["tool"]:
        return False
    if "view" in case and (args.get("view") or "") not in case["view"]:
        return False
    if "category" in case and bool((args.get("category") or "").strip()) != case["category"]:
        return False
    return True


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--key-file", required=True)
    ap.add_argument("--model", default="gemini-3.5-flash-lite")
    a = ap.parse_args()
    key = Path(a.key_file).read_text(encoding="utf-8").strip()
    setup = json.loads(SETUP.read_text(encoding="utf-8"))
    cases = [json.loads(line) for line in CASES.read_text(encoding="utf-8").splitlines() if line.strip()]
    right = tokens = 0
    for case in cases:
        body = {
            "systemInstruction": {"parts": [{"text": setup["system"]}]},
            "contents": [{"role": "user", "parts": [{"text": case["say"]}]}],
            "tools": [{"functionDeclarations": setup["tools"]}],
        }
        out = call_model(a.model, key, body)
        tokens += (out.get("usageMetadata") or {}).get("totalTokenCount", 0)
        parts = ((out.get("candidates") or [{}])[0].get("content") or {}).get("parts", [])
        calls = [(p["functionCall"]["name"], p["functionCall"].get("args") or {}) for p in parts if "functionCall" in p]
        ok = verdict(case, calls)
        right += ok
        print(f"{'ok ' if ok else 'XX '} {case['say']:<48} -> {calls or 'TEXT'}")
    print(f"{right}/{len(cases)} right, model {a.model}, tokens {tokens}")
    return 0 if right == len(cases) else 1


if __name__ == "__main__":
    sys.exit(main())
