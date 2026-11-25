"""The store rep's tools, and the one tool that starts the scenario.

Cascade only and recruiter only, like `about_kevin`'s spoken form: `door_tools(user)` is what the
web cascade's door (`assistant.cascade.WebDoor`) declares and runs for a visitor, through
`engine_mcp.tools.run_engine_tool`, so each still gets the write fence, the read-through guard and
the schema check. They are kept out of the registry and out of Live: on Live nothing speaks
code's lines.

Code speaks what must be exact (`SPEAK_VERBATIM`): the scenario's greeting, the order read-back,
the placed order, the hand-off line. The model never states a total; it passes SKUs and
quantities and code prices them from the catalogue.

- `start_store_demo` / `leave_store_demo`: the scenario switch (`scenario.switch`). Not writes:
  they change which tools the visit is offered, never a row of anyone's data.
- `shop_find`: a catalogue lookup. Read.
- `shop_quote`: prices the order and reads it back, asking for a yes. Keeps the quote on the visit
  (a cart, not an order), so not a write, and it ends the turn: the yes must be the visitor's.
  Code reads the shopper's own words first (`wants_to_buy`): while they are still asking, nothing
  is priced.
- `shop_place_order`: places the quoted order, only in a turn AFTER the read-back. A write.
- `shop_handoff`: "let me get someone". Says where the callback form is, or, with the switch off
  or the day's callbacks used up, that no person can be reached from here.
"""

from __future__ import annotations

import re
import secrets
from datetime import timedelta

from django.db import transaction
from django.utils import timezone

from assistant import read_through
from demo.models import ShopOrder
from demo.shop import callback, catalogue, scenario
from demo.shop.prompt import POLICY
from engine_mcp.tools import (
    ENDS_TURN,
    FOR_MODEL,
    SPEAK_EXACT,
    SPEAK_VERBATIM,
    EngineTool,
    ToolResult,
    ok,
    refused,
)

QUOTE_LIFE = timedelta(minutes=15)

GREETING = (
    f"Sure. You're now talking to the rep at {catalogue.SHOP_NAME}, a made-up outdoor shop: "
    "invented products, invented prices, and nothing is ever charged or shipped.",
    "What are you shopping for today?",
)
# The greeting when a conversation opens while the visit is already in the scenario (the page's
# switch was used before the orb): the shop's hello, with the recording notice the guide's carries.
SHOP_HELLO = (
    f"Hi, you're through to {catalogue.SHOP_NAME}, a made-up outdoor shop, and I'm its rep for "
    "this demo. This call may be recorded to improve the assistant. What are you shopping for?"
)
LEFT = ("Done, I'm back to being Kevin's LEGION guide.", "What would you like to try next?")
NOT_A_VISIT = (
    "Nothing changed. The store-rep demo runs only on a visitor's own demo visit, and this "
    "conversation has none."
)
HANDOFF_FORM = (
    "I can pass you to a person.",
    "Leave your name and a phone number or email in the callback form under the orb, and Kevin "
    "gets a message with a summary of this call. Nobody is dialled automatically.",
)
HANDOFF_OFF = (
    "I can't reach a person from this demo.",
    "In a real deployment this is where I'd hand you to a teammate, with a summary of our call.",
)
HANDOFF_FULL = (
    "I can't reach a person right now: today's callback requests are used up.",
    "You can still reach Kevin through the contact links on this page.",
)
# Said when every sentence of a rep's answer was a claim no tool backed (`guards`), in place of
# "no written reply came back": what is true whatever they asked.
ONLY_REAL_OFFERS = (
    "I can only go by the shop's own prices and its one bundle: there's no other discount, and "
    f"delivery takes {catalogue.DELIVERY_WORDS}."
)
HANDOFF_DONE = ("You've already asked for a callback on this visit, and that request stands.",)

_OBJECTION = re.compile(
    r"\b(expensive|pricey|cheaper|cheap|discount\w*|deal|budget|afford\w*|too much|lower price|"
    r"price|cost\w*|coupon\w*|sale)\b",
    re.I,
)


def _looked_up(calls) -> tuple[list[dict], list[dict]]:
    """(the items, the bundles) this turn's lookups returned, in their order."""
    items, bundles = [], []
    for c in calls:
        if c.name == SHOP_FIND.name and c.ok:
            data = c.data or {}
            items += [i for i in data.get("items") or [] if i.get("sku") in catalogue.BY_SKU]
            bundles += data.get("bundles") or []
    return items, bundles


def _item_line(item: dict) -> str:
    product = catalogue.BY_SKU[item["sku"]]
    return f"The {product.name} is {catalogue.money(product.price_cents)}, " + (
        "and in stock." if item.get("in_stock") else "and out of stock."
    )


def fallback_lines(calls, words: str) -> list[str]:
    """What code says when every sentence of the rep's answer was dropped (`guards`): built from
    this turn's own lookup, never the model's words. An objection ("too expensive") gets the
    plain terms plus the cheaper in-stock option or the bundle the lookup listed; any other
    question gets what the lookup found; with no lookup, the plain terms alone."""
    items, _bundles = _looked_up(calls)
    if not items:
        return [ONLY_REAL_OFFERS]
    if _OBJECTION.search(words or ""):
        option = _cheaper_option(calls)
        return [ONLY_REAL_OFFERS, *([option] if option else [])]
    return [_item_line(i) for i in items[:2]]


def _cheaper_option(calls) -> str | None:
    """The cheaper in-stock product of the same kind as the first one this turn's lookup found,
    else the bundle it listed, as code's own sentence; None when it found neither."""
    items, bundles = _looked_up(calls)
    if not items:
        return None
    first = catalogue.BY_SKU[items[0]["sku"]]
    cheaper = sorted(
        (
            i
            for i in items
            if i.get("in_stock")
            and catalogue.BY_SKU[i["sku"]].category == first.category
            and catalogue.BY_SKU[i["sku"]].price_cents < first.price_cents
        ),
        key=lambda i: catalogue.BY_SKU[i["sku"]].price_cents,
    )
    if cheaper:
        return _item_line(cheaper[0])
    for bundle in catalogue.BUNDLES:
        if any(list(bundle.skus) == b.get("skus") for b in bundles):
            full = sum(catalogue.BY_SKU[s].price_cents for s in bundle.skus)
            return (
                f"The bundle, the {bundle.label}, is "
                f"{catalogue.money(full - bundle.saving_cents)}, saving "
                f"{catalogue.money(bundle.saving_cents)}."
            )
    return None


def objection_addition(calls, words: str, said: list[str]) -> str | None:
    """An objection turn that kept some sentences but lost the one naming an option (the live
    run, 2026-11-22: only "There are no discounts or coupons here." was left): code adds the
    cheaper in-stock option or the bundle from this turn's lookup. None when it was not an
    objection, a surviving sentence already names a product, or the lookup offers nothing."""
    from demo.shop.guards import products_named

    if not _OBJECTION.search(words or "") or any(products_named(s) for s in said):
        return None
    return _cheaper_option(calls)


def _spoken(lines: tuple[str, ...], for_model: str, ends_turn: bool = True, **data) -> ToolResult:
    return ok(
        for_model,
        {
            SPEAK_VERBATIM: list(lines),
            SPEAK_EXACT: True,
            ENDS_TURN: ends_turn,
            FOR_MODEL: for_model,
            **data,
        },
    )


def _no_args() -> dict:
    return {"type": "object", "properties": {}, "required": [], "additionalProperties": False}


# ---- the switch -------------------------------------------------------------------------------


def _start(request, arguments: dict) -> ToolResult:
    if not scenario.switch(request.user, True):
        return refused(NOT_A_VISIT)
    return _spoken(
        GREETING,
        f"Done: the visitor is now talking to the {catalogue.SHOP_NAME} store rep; the greeting "
        "was said. The turn ends here.",
    )


def _leave(request, arguments: dict) -> ToolResult:
    if not scenario.switch(request.user, False):
        return refused(NOT_A_VISIT)
    return _spoken(LEFT, "Done: back to the LEGION guide. The turn ends here.")


START_STORE_DEMO = EngineTool(
    name="start_store_demo",
    title="Start the store-rep demo",
    description=(
        "Switches this demo to a store-rep scenario: you become the sales rep of a made-up "
        "outdoor shop, with a catalogue, prices and orders. Call it when the visitor asks for the "
        "store rep, a sales or support call, or to pretend to be a shop. Code says the greeting."
    ),
    input_schema=_no_args(),
    handler=_start,
    writes=False,
)

LEAVE_STORE_DEMO = EngineTool(
    name="leave_store_demo",
    title="Leave the store-rep demo",
    description=(
        "Ends the store-rep scenario and goes back to the LEGION guide. Call it when the visitor "
        "wants to stop the shop demo or asks about Kevin or LEGION. Code says the line."
    ),
    input_schema=_no_args(),
    handler=_leave,
    writes=False,
)


# ---- the catalogue ----------------------------------------------------------------------------


def _placed(visit) -> dict[str, int]:
    out: dict[str, int] = {}
    for lines in ShopOrder.objects.filter(visit=visit, status=ShopOrder.PLACED).values_list(
        "lines", flat=True
    ):
        for line in lines:
            out[line["sku"]] = out.get(line["sku"], 0) + int(line["qty"])
    return out


def _find(request, arguments: dict) -> ToolResult:
    visit = scenario.visit_of(request.user)
    placed = _placed(visit) if visit is not None else {}
    found = catalogue.find(arguments.get("query") or "", arguments.get("sku") or "")
    if not found:
        return ok(
            "Nothing in the catalogue matches. Say the shop does not carry it and offer what it "
            "does: packs, tents, sleeping bags and pads, headlamps, socks.",
            {"items": [], "bundles": []},
        )
    lines, items = [], []
    for p in found:
        count = catalogue.available(p.sku, placed)
        # Only an add-on the visit could buy: an out-of-stock one is never suggested (the first
        # live run, 2026-11-22, offered the out-of-stock bladder).
        goes = ", ".join(
            f"{catalogue.BY_SKU[s].name} ({s}, {catalogue.money(catalogue.BY_SKU[s].price_cents)})"
            for s in p.pairs_with
            if catalogue.available(s, placed)
        )
        lines.append(
            f"{p.sku} {p.name}: {catalogue.money(p.price_cents)}, {catalogue.stock_words(count)}. "
            f"{p.blurb}" + (f" Goes with: {goes}." if goes else "")
        )
        items.append({"sku": p.sku, "price_cents": p.price_cents, "in_stock": count})
    bundles = catalogue.bundles_for({p.sku for p in found})
    for b in bundles:
        full = sum(catalogue.BY_SKU[s].price_cents for s in b.skus)
        lines.append(
            f"Bundle: {b.label} ({' + '.join(b.skus)}) is "
            f"{catalogue.money(full - b.saving_cents)}, saving {catalogue.money(b.saving_cents)}. "
            "It is the shop's only offer."
        )
    # The policy line rides in the result so its figures are this turn's too: the engine's own
    # money check backs a figure only from the turn's tool text (`speech_rules.backed_cents`).
    text = "\n".join(lines) + (
        f"\nPolicies: {POLICY}\nSay prices exactly as written. Stock and the bundle above are the "
        "only ones there are. Use a product's own words above to say why it suits what they told "
        "you. With more than one product of the same kind here, name both prices and what "
        "separates them before you recommend one. What a product 'goes with' is offered only "
        "after they say they want that product."
    )
    data = {
        "items": items,
        "bundles": [{"skus": list(b.skus), "saving_cents": b.saving_cents} for b in bundles],
    }
    return ok(text, data)


SHOP_FIND = EngineTool(
    name="shop_find",
    title="Find products",
    description=(
        "Looks up the shop's catalogue: name, SKU, price, stock, what goes with it, and the one "
        "bundle offer. Call it before naming any product, price or stock, every turn you need one."
    ),
    input_schema={
        "type": "object",
        "properties": {
            "query": {"type": "string", "description": "What they want, in a few words."},
            "sku": {"type": "string", "description": "One SKU, when they named it."},
        },
        "required": [],
        "additionalProperties": False,
    },
    handler=_find,
    writes=False,
)


# ---- the order --------------------------------------------------------------------------------


def _items(arguments: dict) -> tuple[dict[str, int], str | None]:
    items: dict[str, int] = {}
    for raw in arguments.get("items") or []:
        sku = str(raw.get("sku", "")).strip().upper()
        qty = raw.get("qty", 1)
        if sku not in catalogue.BY_SKU:
            return {}, f"Nothing was quoted. {sku or 'That'} is not a SKU this shop sells."
        if not isinstance(qty, int) or not 1 <= qty <= catalogue.MAX_QTY:
            return {}, f"Nothing was quoted. A quantity is 1 to {catalogue.MAX_QTY}."
        items[sku] = items.get(sku, 0) + qty
    if not items:
        return {}, "Nothing was quoted: no items were named."
    return items, None


def _readback(q: catalogue.Quote) -> tuple[str, ...]:
    parts = [
        f"{line['qty']} {catalogue.BY_SKU[line['sku']].name} at "
        f"{catalogue.money(line['unit_cents'])}"
        for line in q.lines
    ]
    said = ["Here's the order: " + ", and ".join(parts) + "."]
    extras = []
    if q.bundle_saving_cents:
        extras.append(f"The bundle takes {catalogue.money(q.bundle_saving_cents)} off.")
    extras.append(
        "Shipping is free."
        if not q.shipping_cents
        else f"Shipping is {catalogue.money(q.shipping_cents)}."
    )
    said.append(" ".join(extras))
    said.append(f"That's {catalogue.money(q.total_cents)} in all. Shall I place it?")
    return tuple(said)


NO_INTENT = (
    "Nothing was quoted: their own words do not say they want to buy yet. Recommend a product "
    "and say why it suits what they told you, or ask whether to put it in an order, and quote "
    "only once they say yes."
)
# Words that commit to this purchase. Checked first, so a message that both asks and commits
# ("which one? I'll take the 40L") still quotes. Bare "buy" is not here: "which one should I
# buy?" is a question.
_BUY = re.compile(
    r"\b(buy (it|that|this|them|the|a|one|two|both)|purchas\w*|checkout|check out|"
    r"ring (it|me|that) up|"
    r"add (it|that|them|this|those|one|two|the|a|some)|"
    r"order (it|that|this|them|the|a|one|two|both|please|now)|place (the|my|that) order|"
    r"(i|we)(?:'|’)?(?:ll| will|d| would) (take|have|get|go with|buy|order|do)|"
    r"(i|we)(?:'|’)?(?:d| would) like (the|a|one|two|both|that|this|it)|"
    r"let(?:'|’)?s (do|get|take|buy|order)|sign me up|i(?:'|’)?m sold)\b",
    re.I,
)
# Asking for advice or a listing, not ordering. A blocklist on purpose, and a short one: a
# refused real order is worse for this demo than an early read-back, so only a message that
# plainly asks for advice or what the shop has is held back. "One headlamp please" is an order
# and passes. A price question is not here: code's read-back is how a total is ever said at all,
# and the placing guard (`is_yes`, below) is what keeps a price question from buying anything.
# "Cheaper" is not here either: it is the objection turn, where the frame steers the rep to the
# cheaper product and the shopper then takes it. Nor is a past "recommended" or "suggested":
# that is the shopper taking what the rep just put up, which is the whole point of consulting.
_BROWSING = re.compile(
    r"\b(recommend(s|ing)?\b|suggest(s|ing)?\b|what (do|are|is|have)|what(?:'|’)?s|"
    r"what (sort|kind|type)s? of|which (one|of|is)|"
    r"any (ideas|suggestions|recommendations)|help me (choose|pick|decide)|"
    r"(just )?(looking|browsing)|difference|not sure|thinking about|tell me about|"
    r"what about|options?)\b",
    re.I,
)


def wants_to_buy(words: str) -> bool:
    """True when the shopper's own message may be priced into an order ("One headlamp please",
    "I'll take the 40L", "the cheaper one then", "the one you recommended"); False while they are
    still asking ("recommend me a pack", "which one is lighter?", "tell me about the tent"). A
    yes alone passes, because nothing in it asks: what a yes may PLACE is `is_yes`'s business."""
    text = words or ""
    return bool(_BUY.search(text)) or not _BROWSING.search(text)


def _quote(request, arguments: dict) -> ToolResult:
    visit = scenario.visit_of(request.user)
    if visit is None:
        return refused(NOT_A_VISIT)
    # A read-back with a total is the cart, so it needs the shopper's own intent, not the rep's
    # eagerness (the live run of 2026-11-24 offered to add something on every turn, unasked).
    if not wants_to_buy(read_through.heard_words(request)):
        return refused(NO_INTENT)
    items, problem = _items(arguments)
    if problem:
        return refused(problem)
    placed = _placed(visit)
    for sku, qty in items.items():
        count = catalogue.available(sku, placed)
        if qty > count:
            name = catalogue.BY_SKU[sku].name
            return refused(
                f"Nothing was quoted. {name} has {catalogue.stock_words(count)}, fewer than "
                f"{qty}. Say so and offer what is in stock."
            )
    q = catalogue.quote(items)
    ref = read_through.turn_of(request)
    with transaction.atomic():
        # One open quote per visit: a new one replaces the last.
        ShopOrder.objects.filter(visit=visit, status=ShopOrder.QUOTED).delete()
        order = ShopOrder.objects.create(
            visit=visit,
            ref=f"LP-{secrets.randbelow(9000) + 1000}",
            lines=list(q.lines),
            bundle_saving_cents=q.bundle_saving_cents,
            shipping_cents=q.shipping_cents,
            total_cents=q.total_cents,
            conversation_id=ref.conversation_id if ref else None,
            quoted_turn=ref.turn if ref else None,
        )
    return _spoken(
        _readback(q),
        f"Done: read the order back, {catalogue.money(q.total_cents)} in all, and asked them to "
        "confirm. Not placed yet. The turn ends here.",
        quote_ref=order.ref,
        total_cents=q.total_cents,
    )


SHOP_QUOTE = EngineTool(
    name="shop_quote",
    title="Read the order back",
    description=(
        "Prices an order from SKUs and quantities and reads it back aloud with its total, asking "
        "them to confirm. Call it when they say they want to buy. Code works out every price, the "
        "bundle and shipping; you never state a total yourself."
    ),
    input_schema={
        "type": "object",
        "properties": {
            "items": {
                "type": "array",
                "minItems": 1,
                "maxItems": 6,
                "items": {
                    "type": "object",
                    "properties": {
                        "sku": {"type": "string"},
                        "qty": {"type": "integer", "minimum": 1, "maximum": catalogue.MAX_QTY},
                    },
                    "required": ["sku", "qty"],
                    "additionalProperties": False,
                },
            }
        },
        "required": ["items"],
        "additionalProperties": False,
    },
    handler=_quote,
    writes=False,
)

NO_QUOTE = (
    "Nothing was placed: there is no order read back to them yet. Call shop_quote first, so they "
    "hear the order and its total."
)
SAME_TURN = (
    "Nothing was placed. They have not said yes to the read-back yet: it was read in this same "
    "turn. Wait for their answer."
)
NOT_NEXT_TURN = (
    "Nothing was placed: the read-back was not the last thing they heard before this answer. "
    "Call shop_quote again so they hear the order and its total, then wait for their yes."
)
STALE = "Nothing was placed: the read-back is over 15 minutes old. Call shop_quote again."
NOT_A_YES = (
    "Nothing was placed: their message was not a plain yes to the read-back. Answer what they "
    "said, and ask them to confirm the order if they want it."
)

# A yes to the read-back, checked by code on this turn's own words (the review of 2026-11-22: the
# model alone deciding "that was a yes" placed an order on "how much is shipping?"). Any negation,
# hesitation, question or change to the order ("and add socks", "the cheaper one") means no.
_YES = re.compile(
    r"\b(yes|yeah|yep|yup|sure|ok|okay|alright|all right|please do|place it|place the order|"
    r"go ahead|confirm\w*|do it|let'?s do it|sounds good|that'?s right|correct|buy it|order it|"
    r"absolutely|definitely|perfect)\b",
    re.I,
)
_NOT_YES = re.compile(
    r"\?|\b(no|nope|not|don'?t|do not|wait|hold on|hold off|cancel|stop|actually|but|instead|"
    r"change|never ?mind|later|maybe|hmm|how|what|why|which|when|and|add|also|plus|too|or|"
    r"cheaper|guess|like|another|other|else|swap|switch|remove|without|more|less)\b",
    re.I,
)


def is_yes(words: str) -> bool:
    """True for a plain affirmative ("Yes, place it", "go ahead"): one yes word and no negation,
    hesitation or question."""
    text = (words or "").strip()
    return bool(text) and bool(_YES.search(text)) and not _NOT_YES.search(text)


def _place(request, arguments: dict) -> ToolResult:
    visit = scenario.visit_of(request.user)
    if visit is None:
        return refused(NOT_A_VISIT)
    ref = read_through.turn_of(request)
    with transaction.atomic():
        order = (
            ShopOrder.objects.select_for_update()
            .filter(visit=visit, status=ShopOrder.QUOTED)
            .order_by("-created_at")
            .first()
        )
        if order is None:
            return refused(NO_QUOTE)
        if ref is None or ref.turn is None:
            return refused(SAME_TURN)
        same_conversation = order.conversation_id == ref.conversation_id
        if same_conversation and order.quoted_turn == ref.turn:
            return refused(SAME_TURN)
        # Only the answer to the read-back itself: the turn right after it, in the same
        # conversation (a "yes" to a later question about socks never places the old quote).
        if not (same_conversation and order.quoted_turn == ref.turn - 1):
            return refused(NOT_NEXT_TURN)
        if timezone.now() - order.created_at > QUOTE_LIFE:
            return refused(STALE)
        if not is_yes(read_through.heard_words(request)):
            return refused(NOT_A_YES)
        placed = _placed(visit)
        for line in order.lines:
            if line["qty"] > catalogue.available(line["sku"], placed):
                return refused(
                    f"Nothing was placed: {catalogue.name_of(line)} is no longer in "
                    "stock in that number. Say so and offer to quote again."
                )
        order.status = ShopOrder.PLACED
        order.placed_at = timezone.now()
        order.save(update_fields=["status", "placed_at"])
    total = catalogue.money(order.total_cents)
    return _spoken(
        (
            f"Done: order {order.ref} is placed, {total} in all, arriving in "
            f"{catalogue.DELIVERY_WORDS}.",
            "It's a demo shop, so nothing is charged and nothing ships.",
        ),
        "Done: the order is placed, and code has already told them its number, total and "
        "delivery. Say nothing more about the order; only ask if they need anything else.",
        ends_turn=False,
        order_ref=order.ref,
        total_cents=order.total_cents,
    )


SHOP_PLACE_ORDER = EngineTool(
    name="shop_place_order",
    title="Place the order",
    description=(
        "Places the order shop_quote read back. Call it only when their message AFTER the "
        "read-back says yes to it; code says the confirmation. Never say an order is placed "
        "unless this returned Done."
    ),
    input_schema=_no_args(),
    handler=_place,
    writes=True,
)


# ---- a person ---------------------------------------------------------------------------------


def _handoff(request, arguments: dict) -> ToolResult:
    state = callback.state_for(request.user)
    lines = {
        callback.OFF: HANDOFF_OFF,
        callback.DAY_FULL: HANDOFF_FULL,
        callback.ALREADY: HANDOFF_DONE,
    }.get(state, HANDOFF_FORM)
    return _spoken(lines, f"Done: said how to reach a person ({state}). The turn ends here.")


SHOP_HANDOFF = EngineTool(
    name="shop_handoff",
    title="Get a person",
    description=(
        "When they ask for a person, a manager, a human or a callback: code says how, or that "
        "no one can be reached from here. Never promise a call yourself."
    ),
    input_schema=_no_args(),
    handler=_handoff,
    writes=False,
)

REP_TOOLS = (SHOP_FIND, SHOP_QUOTE, SHOP_PLACE_ORDER, SHOP_HANDOFF, LEAVE_STORE_DEMO)
ALL_TOOLS = {t.name: t for t in (*REP_TOOLS, START_STORE_DEMO)}
# What the conversation's history notes as done when it went through (`assistant.views`).
WRITE_TOOLS = frozenset(t.name for t in ALL_TOOLS.values() if t.writes)


def door_tools(user, guide_tools: dict) -> dict:
    """The tools the web cascade's door offers visitor `user`: the rep's alone while the visit is
    in the store-rep scenario; otherwise the guide's, plus the switch. The door asks only for a
    recruiter; a visitor with no visit row keeps the guide's as they are."""
    if scenario.visit_of(user) is None:
        return guide_tools
    if scenario.active(user):
        return {t.name: t for t in REP_TOOLS}
    return {**guide_tools, START_STORE_DEMO.name: START_STORE_DEMO}


def not_here(name: str) -> str:
    """A tool the store-rep door did not declare: refused in words, nothing ran."""
    return (
        f"Nothing was read or written. {name!r} is not available here: in the store-rep demo the "
        f"tools are {', '.join(t.name for t in REP_TOOLS)}; otherwise the guide's."
    )
