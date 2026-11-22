"""The after-call card: what a business system would receive when the call ends.

Built by code, with no model call, from what the engine already keeps for the visit:

- the conversation's own record (`devicelogs.web_conversation`): the visitor's words and the
  assistant's as the engine wrote them (typed turns marked typed), each tool call, and each
  turn's `cascade_turn` timing line, whose `first_sound_ms` is the engine-side time from the
  utterance reaching the engine to the first audio it sent back (a filler line counts; upload and
  playback on the visitor's side are not in it);
- the visit's orders and callback request (`demo.models`).

No cost figure: the demo's cost strip is the tester visit's only (Kevin, 2026-11-19).
"""

from __future__ import annotations

import json
import re

from demo.models import CallbackRequest, ShopOrder
from demo.shop import catalogue
from devicelogs.models import DEVICE_LABEL_WEB, REASON_CONVERSATION, DeviceLog

ORDER_PLACED = "order_placed"
NEEDS_HUMAN = "needs_human"
QUOTED = "quoted_not_placed"
BROWSING = "browsing"

OUTCOME_WORDS = {
    ORDER_PLACED: "Order placed",
    NEEDS_HUMAN: "Needs a human",
    QUOTED: "Quoted, not placed",
    BROWSING: "No sale",
}

_LINE = re.compile(r"^(?P<stamp>\S+ \S+)  0  (?P<tid>\d) I (?P<tag>\w+): (?P<msg>.*)$")
_TURN = re.compile(
    r"^(?:(?P<user>user final=true(?P<typed> typed=true)?)|(?P<ai>assistant)) "
    r"text=(?P<text>\".*\")$"
)
_TOOL = re.compile(r"^tool_call id=\S+ name=(?P<name>\S+) async=\S+ args=(?P<args>.*)$")
_RESULT = re.compile(r"^tool_result id=\S+ name=(?P<name>\S+) success=(?P<ok>true|false) ")
_TIMING = re.compile(r"^cascade_turn status=(?P<status>\S+) spoken=(?P<spoken>\S+) (?P<kv>.*)$")


def latest_conversation(user) -> DeviceLog | None:
    return (
        DeviceLog.objects.filter(
            user=user,
            demo_visitor=True,
            reason=REASON_CONVERSATION,
            device_label=DEVICE_LABEL_WEB,
        )
        .order_by("-window_start", "-received_at")
        .first()
    )


def _text(raw: str) -> str:
    try:
        value = json.loads(raw)
    except ValueError:
        return ""
    return value if isinstance(value, str) else ""


def parse(body: str) -> dict:
    """The record's lines as transcript, tool calls and per-turn timings."""
    transcript, tools, turns = [], [], []
    skus: list[str] = []
    for raw in body.splitlines():
        m = _LINE.match(raw)
        if m is None or m.group("tid") != "2":
            continue
        msg = m.group("msg")
        if (t := _TURN.match(msg)) is not None:
            words = _text(t.group("text"))
            if words:
                who = "visitor" if t.group("user") else "assistant"
                transcript.append(
                    {"who": who, "text": words, "typed": bool(t.group("typed")), "at": m["stamp"]}
                )
        elif (c := _TOOL.match(msg)) is not None:
            tools.append({"name": c.group("name"), "ok": None})
            try:
                args = json.loads(c.group("args"))
            except ValueError:
                args = {}
            for item in (args.get("items") or []) if isinstance(args, dict) else []:
                if isinstance(item, dict) and item.get("sku") in catalogue.BY_SKU:
                    skus.append(item["sku"])
            if isinstance(args, dict) and args.get("sku") in catalogue.BY_SKU:
                skus.append(args["sku"])
        elif (r := _RESULT.match(msg)) is not None:
            for call in reversed(tools):
                if call["name"] == r.group("name") and call["ok"] is None:
                    call["ok"] = r.group("ok") == "true"
                    break
        elif (g := _TIMING.match(msg)) is not None and g.group("status") not in (
            "nothing_heard",
            "echo",
        ):
            kv = dict(p.split("=", 1) for p in g.group("kv").split() if "=" in p)

            def ms(key: str, values=kv) -> int | None:
                try:
                    value = int(values.get(key, "-1"))
                except ValueError:
                    return None
                return value if value >= 0 else None

            turns.append(
                {
                    "kind": "greeting" if g.group("status") == "greeting" else "turn",
                    "spoken": g.group("spoken") == "true",
                    "first_audio_ms": ms("first_sound_ms"),
                    "answer_audio_ms": ms("tts_first_audio_ms"),
                    "stt_ms": ms("stt_ms"),
                    "turn_ms": ms("turn_ms"),
                }
            )
    return {"transcript": transcript, "tools": tools, "turns": turns, "skus": skus}


def order_json(order: ShopOrder) -> dict:
    return {
        "ref": order.ref,
        "status": order.status,
        "lines": [
            {
                "sku": line["sku"],
                "name": catalogue.name_of(line),
                "qty": line["qty"],
                "unit_cents": line["unit_cents"],
                "line_cents": line["line_cents"],
            }
            for line in order.lines
        ],
        "bundle_saving_cents": order.bundle_saving_cents,
        "shipping_cents": order.shipping_cents,
        "total_cents": order.total_cents,
    }


def _summary(outcome: str, parsed: dict, orders: list[ShopOrder], callback) -> list[str]:
    said = []
    visitor_turns = sum(1 for t in parsed["transcript"] if t["who"] == "visitor")
    said.append(f"{visitor_turns} visitor turn{'s' if visitor_turns != 1 else ''}.")
    names = list(dict.fromkeys(catalogue.BY_SKU[s].name for s in parsed["skus"]))
    if names:
        said.append("Discussed: " + ", ".join(names[:4]) + ".")
    placed = [o for o in orders if o.status == ShopOrder.PLACED]
    if placed:
        said.append(
            "Placed "
            + "; ".join(f"{o.ref} for {catalogue.money(o.total_cents)}" for o in placed)
            + "."
        )
    elif outcome == QUOTED:
        said.append("An order was read back but not confirmed.")
    if callback is not None:
        said.append(
            "Asked for a person; Kevin was notified."
            if callback.delivery == "sent"
            else "Asked for a person; the request is saved."
        )
    elif any(t["name"] == "shop_handoff" for t in parsed["tools"]):
        said.append("Asked for a person.")
    return said


def build(user) -> dict | None:
    """The card for the visitor's latest conversation, or None when there is none."""
    from demo.shop import scenario
    from devicelogs.views import inflate_capped

    row = latest_conversation(user)
    if row is None:
        return None
    parsed = parse(inflate_capped(bytes(row.body_gz)).decode("utf-8", errors="replace"))
    visit = scenario.visit_of(user)
    orders = (
        list(ShopOrder.objects.filter(visit=visit, created_at__gte=row.window_start))
        if visit
        else []
    )
    callback = (
        CallbackRequest.objects.filter(visit=visit, created_at__gte=row.window_start).first()
        if visit
        else None
    )
    if any(o.status == ShopOrder.PLACED for o in orders):
        outcome = ORDER_PLACED
    elif callback is not None or any(t["name"] == "shop_handoff" for t in parsed["tools"]):
        outcome = NEEDS_HUMAN
    elif orders:
        outcome = QUOTED
    else:
        outcome = BROWSING
    measured = [t["first_audio_ms"] for t in parsed["turns"] if t["first_audio_ms"] is not None]
    record = {
        "record_type": "call",
        "channel": "web_voice_demo",
        "store": catalogue.SHOP_NAME,
        "conversation_id": str(row.batch_id),
        "started_at": row.window_start.isoformat(),
        "ended_at": row.window_end.isoformat() if row.window_end else None,
        "outcome": outcome,
        "products_discussed": list(dict.fromkeys(parsed["skus"])),
        "orders": [order_json(o) for o in orders if o.status == ShopOrder.PLACED],
        "open_quote": next((order_json(o) for o in orders if o.status == ShopOrder.QUOTED), None),
        "handoff": {
            "requested": outcome == NEEDS_HUMAN,
            "contact_left": callback is not None,
            "notified": bool(callback and callback.delivery == "sent"),
        },
        "visitor_turns": sum(1 for t in parsed["transcript"] if t["who"] == "visitor"),
        "tools_called": [t["name"] for t in parsed["tools"]],
    }
    return {
        "outcome": outcome,
        "outcome_words": OUTCOME_WORDS[outcome],
        "summary": _summary(outcome, parsed, orders, callback),
        "transcript": parsed["transcript"],
        "turns": parsed["turns"],
        "median_first_audio_ms": sorted(measured)[len(measured) // 2] if measured else None,
        "record": record,
    }
