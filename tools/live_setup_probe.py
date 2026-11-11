"""Probe the REAL Live service with the phone's own setup message (live cost diet, 2026-11-10).
NEEDS A GEMINI KEY. Run by the key holder; the key is never printed.

`LiveSetupMessageExportTest` writes `app/build/live-setup/live_setup_message.json`: the exact
`setup` message `GeminiLiveSession.buildSetupJson` builds (model gemini-3.8-live, Puck, AUDIO out,
googleSearch plus the diet's declarations with their BLOCKING/NON_BLOCKING stamps, session
resumption, compression, input transcription, manual activity). This script opens the same
endpoint the app opens (`wss://generativelanguage.googleapis.com/ws/...BidiGenerateContent`,
key as a query parameter, as the app does), sends that message, and:

  --setup-only   waits for setupComplete and reports ACCEPTED or the close reason. No turn is
                 sent, so no generation: free by the audit's reading (a setup with no turn
                 reports no usage). Answers "does Live accept this schema at all".
  (default)      then, per eval sentence, a fresh session: one text turn (clientContent,
                 turnComplete true, as the phone's typed and opener turns are sent), and prints
                 which tool and action the Live model calls. A call that only gathers (the time,
                 the record to delete) gets a canned result and one more round.

  --old <setup.json>   the same envelope with the OLD system instruction and declarations (the
                 payload test's setup.json from the pre-diet tree), stamped as the app stamped
                 them then, scored on the old tool names.

    python tools/live_setup_probe.py --key-file <k> --setup-only
    python tools/live_setup_probe.py --key-file <k> --setup-only --old <dir>/base2/setup.json
    python tools/live_setup_probe.py --key-file <k> --limit 20 --out <outside the repo>/probe-new.json
    python tools/live_setup_probe.py --key-file <k> --old <dir>/base2/setup.json --limit 20 --out <...>/probe-old.json

Cost (per-generation billing, the audit's central case, $0.75/M text in on gemini-3.8-live): every
generation carries the setup (~11.4k real tokens new, ~22.9k old) plus a short spoken reply
(~$0.018/min audio out). About $0.01 per new case and $0.02 per old case, x1.3 for preamble
rounds: all 104 cases about $1.5 new and $3 old; --limit 20 about $0.3 and $0.6. The `usage`
line per case prints the real prompt token count, which also settles per-generation billing.
The static system instruction plus a fixed invented context (--context) stands in for the
phone's: safety block, date and live context need a device.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import sys
from collections import Counter
from pathlib import Path

import websockets

REPO = Path(__file__).resolve().parents[1]
BUILD = REPO / "app" / "build" / "live-setup"
WS_URL = ("wss://generativelanguage.googleapis.com/ws/"
          "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent")
OLD_NON_BLOCKING = {"ask_fleet", "ask_body", "ask_goals", "ask_pantry"}
CONTEXT = ("(Current context, use naturally if relevant:\nIt is Tuesday 10 November 2026, 09:05, "
           "UTC-06:00. Location unknown.)")
PREAMBLE = {
    ("day", "get_current_time"), ("get_current_time", None), ("records", "query_records"),
    ("query_records", None), ("records", "describe_aspect"), ("money", "list_budget_categories"),
    ("list_budget_categories", None),
}


def build_message(path: Path, old: Path | None, context: str) -> dict:
    msg = json.loads(path.read_text(encoding="utf-8"))
    setup = msg["setup"]
    if old is not None:
        o = json.loads(old.read_text(encoding="utf-8"))
        setup["systemInstruction"]["parts"][0]["text"] = o["systemInstruction"]
        fns = [d for t in o["tools"] if "functionDeclarations" in t for d in t["functionDeclarations"]]
        for d in fns:
            d.setdefault("behavior", "NON_BLOCKING" if d["name"] in OLD_NON_BLOCKING else "BLOCKING")
        setup["tools"] = [{"googleSearch": {}}, {"functionDeclarations": fns}]
    if context:
        setup["systemInstruction"]["parts"][0]["text"] += "\n\n" + context
    return msg


def disc_map(msg: dict) -> dict:
    out = {}
    for t in msg["setup"]["tools"]:
        for d in t.get("functionDeclarations", []):
            props = d.get("parameters", {}).get("properties", {})
            out[d["name"]] = "domain" if "domain" in props else "action"
    return out


async def recv_json(ws, timeout: float) -> dict:
    raw = await asyncio.wait_for(ws.recv(), timeout=timeout)
    return json.loads(raw.decode("utf-8") if isinstance(raw, bytes) else raw)


URL = WS_URL  # --url replaces it, for a local fake server in a dry run


async def open_session(key: str, msg: dict, timeout: float):
    ws = await websockets.connect(f"{URL}?key={key}", max_size=None, open_timeout=timeout)
    await ws.send(json.dumps(msg))
    while True:
        m = await recv_json(ws, timeout)
        if "setupComplete" in m:
            return ws


async def setup_only(key: str, msg: dict, timeout: float) -> str:
    try:
        ws = await open_session(key, msg, timeout)
        await ws.close()
        return "ACCEPTED"
    except websockets.ConnectionClosed as e:
        return f"REJECTED close {e.code}: {e.reason}"
    except (asyncio.TimeoutError, OSError, websockets.InvalidHandshake) as e:
        return f"NO ANSWER ({type(e).__name__}: {getattr(e, 'response', None) and e.response.status_code})"


async def one_case(key: str, msg: dict, say: str, discs: dict, timeout: float, follow: int) -> dict:
    calls, usage, spoke, said = [], [], False, []
    try:
        ws = await open_session(key, msg, timeout)
    except websockets.ConnectionClosed as e:
        return {"verdict": "SETUP_REJECTED", "why": f"{e.code} {e.reason}", "calls": calls}
    try:
        await ws.send(json.dumps({"clientContent": {
            "turns": [{"role": "user", "parts": [{"text": say}]}], "turnComplete": True}}))
        rounds = 0
        while True:
            m = await recv_json(ws, timeout)
            if "usageMetadata" in m:
                usage.append(m["usageMetadata"].get("promptTokenCount"))
            if "toolCall" in m:
                fcs = m["toolCall"].get("functionCalls", [])
                gathered = True
                for fc in fcs:
                    disc = discs.get(fc["name"], "action")
                    act = (fc.get("args") or {}).get(disc)
                    calls.append(fc["name"] + (f"/{act}" if act else ""))
                    gathered = gathered and (fc["name"], act) in PREAMBLE
                if not (gathered and rounds < follow):
                    break
                rounds += 1
                await ws.send(json.dumps({"toolResponse": {"functionResponses": [
                    {"id": fc.get("id"), "name": fc["name"],
                     "response": {"success": True, "note": "probe: canned result"}} for fc in fcs]}}))
                continue
            sc = m.get("serverContent") or {}
            if sc.get("modelTurn"):
                spoke = True
            if (sc.get("outputTranscription") or {}).get("text"):
                said.append(sc["outputTranscription"]["text"])
            if sc.get("turnComplete"):
                break
    except websockets.ConnectionClosed as e:
        return {"verdict": "CLOSED", "why": f"{e.code} {e.reason}", "calls": calls, "usage": usage}
    except asyncio.TimeoutError:
        return {"verdict": "TIMEOUT", "calls": calls, "usage": usage}
    finally:
        await ws.close()
    return {"verdict": "CALL" if calls else ("SPOKE" if spoke else "NOTHING"), "calls": calls, "usage": usage,
            "said": "".join(said)[:200]}


async def main_async(args) -> int:
    key = (Path(args.key_file).read_text(encoding="utf-8").strip() if args.key_file
           else os.environ.get("GEMINI_API_KEY", "").strip())
    if not key:
        raise SystemExit("No key: set GEMINI_API_KEY or pass --key-file.")
    try:
        return await run(args, key)
    except Exception as e:  # never let a traceback carry the URL (and so the key) to the console
        raise SystemExit(f"probe failed: {type(e).__name__}: {str(e).replace(key, '<key>')}") from None


async def run(args, key: str) -> int:
    old = Path(args.old) if args.old else None
    msg = build_message(Path(args.message), old, "" if args.setup_only else args.context)
    label = "OLD setup" if old else "NEW setup"
    if args.setup_only:
        print(f"{label}: {await setup_only(key, msg, args.timeout)}")
        return 0
    discs = disc_map(msg)
    cases = [json.loads(x) for x in Path(args.expected).read_text(encoding="utf-8").splitlines() if x]
    if args.match:
        cases = [c for c in cases if args.match.lower() in c["say"].lower()]
    if args.limit:
        cases = cases[: args.limit]
    results = []
    out_path = Path(args.out) if args.out else None
    for c in cases:
        accept = [c["covers"]] if old else c["accept"]
        try:
            r = await one_case(key, msg, c["say"], discs, args.timeout, args.follow)
        except Exception as e:  # one bad case must not lose a paid run; never print the URL
            r = {"verdict": "ERROR", "why": f"{type(e).__name__}: {str(e).replace(key, '<key>')}", "calls": []}
        ok = any(x in accept or x.split("/")[0] in accept for x in r["calls"])
        verdict = "PASS" if ok else ("WRONG" if r["verdict"] == "CALL" else r["verdict"])
        results.append({**c, **r, "verdict": verdict})
        print(f"{verdict[:6]:6} {'|'.join(accept)[:45]:45} got {' > '.join(r['calls']) or '-':38} "
              f"prompt_tokens={r.get('usage')} | {c['say']}" + (f" | said: {r['said']}" if r.get("said") else ""))
        if out_path:  # written as it goes
            out_path.write_text(json.dumps(results, indent=1), encoding="utf-8")
    n = len(results)
    counts = Counter(r["verdict"] for r in results)
    print(f"\n{label}: {counts['PASS']}/{n} right; verdicts {dict(counts)}")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--key-file")
    ap.add_argument("--message", default=str(BUILD / "live_setup_message.json"))
    ap.add_argument("--old", help="pre-diet setup.json (systemInstruction + tools) to probe instead")
    ap.add_argument("--expected", default=str(BUILD / "eval_expected.jsonl"))
    ap.add_argument("--setup-only", action="store_true")
    ap.add_argument("--context", default=CONTEXT)
    ap.add_argument("--limit", type=int)
    ap.add_argument("--match", help="only sentences containing this text")
    ap.add_argument("--follow", type=int, default=1)
    ap.add_argument("--timeout", type=float, default=30.0)
    ap.add_argument("--out")
    ap.add_argument("--url", help="a different endpoint (a local fake server for a dry run)")
    args = ap.parse_args()
    if args.url:
        global URL
        URL = args.url
    return asyncio.run(main_async(args))


if __name__ == "__main__":
    sys.exit(main())
