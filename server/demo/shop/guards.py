"""What a store rep's sentence may not claim unless a tool backed it (the money check, for prices).

The engine's money check (`agents.legion.speech_rules.backed_cents`) compares two-place decimals
only, and only in a turn where a money tool ran. A rep talks prices in every turn, so while the
store-rep scenario is on, each model sentence is also read here, and dropped (counted as money)
unless every claim in it matches a fact the shop states. Where it can, this is an ALLOWLIST (two
blind reviews, 2026-11-22: every blocklist of sales phrases had a paraphrase that got through):

- **Prices.** Every amount must be a shop figure, and tied to what the sentence names: with
  products named, each amount is one of THEIR prices (or a shipping rule, an order total code
  computed, or, with the bundle listed and only bundle products named, the bundle's price or
  saving); with none named, a figure this turn's tools returned. "The tent is $98.00" and "It
  runs $289.00 but for you it's $98.00" are dropped.
- **Shipping and delivery.** A sentence on either topic may state only the policy, in its own
  words: "3 to 5 business days", "$8.00", "free on orders over $75.00". Any other timing word
  (a weekday, "tomorrow", "within 48 hours", "before the weekend") or any other free or waived
  shipping ("on us", "won't charge you shipping", "no shipping charge") is dropped, negated or
  not: an honest "it can't ship tomorrow" is lost too, and the turn's fallback line says the
  policy instead (`tools.ONLY_REAL_OFFERS`).
- **Deals.** No discount, coupon, sale, price match, free item, money off, superlative price
  ("cheapest anywhere"), unless a negation governs it in its own clause ("we don't price match",
  "no coupons"). A clause that opens with "don't" or "never" is an imperative, not a refusal
  ("Honestly, don't miss the coupon"), and so is a negation of miss/forget/wait/skip. Money off
  passes only as the bundle's real saving, about the bundle's products, in a turn that listed it.
- **Stock.** A count only as this turn's lookup returned it; urgency ("only 2 left") never.
- **Words for figures** ("ten bucks off") never: code speaks digits.

Membership checks only, nothing computed.
"""

from __future__ import annotations

import re

from agents.legion.speech_rules import _cents_in
from demo.shop import catalogue

LOOKUP_TOOL = "shop_find"

_NUM = r"(?:\d{1,3}(?:,\d{3})+|\d+)"
_AMOUNT = re.compile(
    rf"(?:\$|\busd\s?)\s?(?P<a>{_NUM})(?:\.(?P<ac>\d{{2}}))?"
    rf"|(?P<b>{_NUM})(?:\.(?P<bc>\d{{2}}))?\s*(?:dollars?|bucks?|usd)\b",
    re.I,
)
_AMOUNT_TEXT = rf"(?:\$\s?{_NUM}(?:\.\d{{2}})?|{_NUM}(?:\.\d{{2}})?\s*(?:dollars?|bucks?|usd)\b)"
# Money off: an amount before off/cheaper/less/saving, or after save/saving/knock/take/shave.
_AMOUNT_OFF = re.compile(
    rf"(?P<x>{_AMOUNT_TEXT})\s*(?:off|cheaper|less|lower|saving|savings|discount)\b"
    rf"|\b(?:save|saves|saving|savings of|knock\w*|take|shave)\s+(?:you\s+)?"
    rf"(?:an?\s+extra\s+)?(?P<y>{_AMOUNT_TEXT})",
    re.I,
)
_PERCENT_OFF = re.compile(
    r"\b\d+\s*(%|percent)\s*(off|discount|cheaper|less|lower|savings?)\b", re.I
)
_DEAL = re.compile(
    r"\b(discount\w*|coupon\w*|promo\w*|voucher\w*|price[- ]match\w*|on sale|sale price|"
    r"clearance|markdown\w*|for free|free gift|complimentary|throw (it|that|one) in|"
    r"knock\w* (it )?(off|down)|half[- ](price|off)|bogo|buy one,? get one|steal|bargain|"
    r"cheapest|best price|lowest price|beat (any|that|their) price|free|no extra (cost|charge)|"
    r"at no (extra )?cost|on the house|thrown in|throw\w* (it |that |one |them )?in|"
    r"match (whatever|any|their|that|the|other|it)\w*|matches? (any|their|other)\w*)\b",
    re.I,
)
_BUNDLE = re.compile(r"\bbundle\w*\b", re.I)

# ---- shipping and delivery: an allowlist ----------------------------------------------------
_SHIPPING_TOPIC = re.compile(
    r"\b(ship\w*|deliver\w*|arriv\w*|postage|courier|door|on us|charg\w*|fees?|waiv\w*|"
    r"get it|have it|show up|be there|dispatch\w*)\b",
    re.I,
)
# The policy, the only shipping and delivery facts there are (`catalogue`), removed before the
# rest of the sentence is read.
_POLICY_FACTS = re.compile(
    r"3 to 5 business days|free ((on|for) orders )?(over|above) \$75(\.00)?|"
    r"orders (over|above) \$75(\.00)?|\$75(\.00)?|\$8(\.00)?",
    re.I,
)
_DAY = r"(mon|tues|wednes|thurs|fri|satur|sun)days?"
_TIMING = re.compile(
    rf"\b(today|tonight|tomorrow|overnight|next[- ]day|same[- ]day|express|expedit\w*|rush\w*|"
    rf"priority|asap|soon|quick\w*|fast\w*|hours?|days?|weeks?|weekend|{_DAY}|before|within|"
    rf"by|in time|morning|evening)\b",
    re.I,
)
_FREE = re.compile(
    r"\b(free|on us|waiv\w*|no (shipping )?charge|won't charge|not charge|includ\w*|"
    r"no extra|at no cost|on the house)\b",
    re.I,
)

# Urgency is never backed by anything the shop states: always dropped.
_URGENCY = re.compile(
    r"\b(only \d+( \w+)? left|\d+ left|low stock|selling fast|going fast|last (one|few)|"
    r"almost gone|while (it|they|stocks?) lasts?|limited stock|won't last)\b",
    re.I,
)
# "14 in stock": the number must be one this turn's lookup returned.
_IN_STOCK_COUNT = re.compile(r"\b(\d+)\s+(?:\w+\s+)?(?:in stock|available)\b", re.I)
_STOCK = re.compile(r"\b(in stock|out of stock|sold out|restock\w*|back in stock)\b", re.I)
# Code speaks digits; a money or percentage figure in words cannot be checked, so it is not said.
_WORD_FIGURE = re.compile(
    r"\b(one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|"
    r"fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|"
    r"eighty|ninety|hundred|thousand|half|a few|a couple)\b[\s-]*(dollars?|bucks?|percent|off)\b",
    re.I,
)

# ---- what a refusal looks like --------------------------------------------------------------
_NEGATION = re.compile(
    r"\b(no(?!\s+(worries|problem|doubt))|not|never|without|cannot|can't|cant|can not|"
    r"won't|don't|do not|doesn't|isn't|aren't|unable\s+to)\b(?P<between>(?:\s+[\w']+){0,2})\s*$",
    re.I,
)
# A clause that opens with these gives an order ("don't miss it"), it refuses nothing.
_IMPERATIVE = re.compile(r"^\s*(don't|do not|never)\b", re.I)
# A negation of these is a push, not a refusal ("you can't miss the coupon").
_PUSH_VERBS = re.compile(r"\b(miss\w*|forget\w*|wait\w*|skip\w*|overlook\w*|pass|sleep)\b", re.I)
_CLAUSE_BREAK = re.compile(r"[,.;:!?—]")


def _refused(text: str, start: int) -> bool:
    """A negation governs the phrase starting at `start`: in its own clause, at most two words
    before it, the clause does not open as an imperative, and the words between are no push."""
    clause = _CLAUSE_BREAK.split(text[:start])[-1]
    if _IMPERATIVE.match(clause):
        return False
    found = _NEGATION.search(clause)
    return found is not None and not _PUSH_VERBS.search(found.group("between") or "")


def _offers(pattern: re.Pattern, text: str) -> bool:
    """A match of `pattern` in `text` that no negation governs."""
    return any(not _refused(text, m.start()) for m in pattern.finditer(text))


def _cents(raw: str) -> int:
    m = _AMOUNT.search(raw)
    whole = m.group("a") or m.group("b")
    return int(whole.replace(",", "")) * 100 + int(m.group("ac") or m.group("bc") or "00")


# In a sentence that names a product, a bare number after a price word is a price too ("The tent
# is just 98 now", "For the tent I can do USD 98"), unless a unit says it is something else.
_LOOSE_AMOUNT = re.compile(
    rf"(?:\b(?:is|are|at|for|costs?|just|only|runs?|goes for|priced(?: at)?|now|do|usd)\s+"
    rf"(?:about\s+|around\s+|usd\s*)?)(?P<n>{_NUM})(?:\.(?P<c>\d{{2}}))?\b"
    r"(?!\.\d|\s*(?:%|percent|l\b|litres?|liters?|-person|people|persons?|pairs?|in stock|left|"
    r"days?|hours?|weeks?|kg|g\b|lbs?|pounds?|cm|mm|inch\w*|f\b|degrees?|lumens?|nights?|"
    r"of\b|to\b|x\b|times))",
    re.I,
)


def amounts(sentence: str, loose: bool = False) -> set[int]:
    """Every dollar amount in `sentence`, in cents ("$129", "$129.00", "10 bucks", "USD 98");
    with `loose` (the sentence names a product), a bare price-like number too."""
    text = sentence or ""
    out = {_cents(m.group(0)) for m in _AMOUNT.finditer(text)}
    if loose:
        stripped = _AMOUNT.sub(" ", text)
        for m in _LOOSE_AMOUNT.finditer(stripped):
            out.add(int(m.group("n").replace(",", "")) * 100 + int(m.group("c") or "00"))
    return out


def products_named(sentence: str) -> set[str]:
    """The SKUs the sentence names (`catalogue.ALIASES`: each name's own words and a few
    spoken forms); a word two products share names both."""
    low = (sentence or "").lower()
    return {
        sku
        for sku, aliases in catalogue.ALIASES.items()
        if any(re.search(rf"(?<![\w-]){re.escape(a)}(?:s|es)?(?![\w-])", low) for a in aliases)
    }


def _turn_facts(calls) -> tuple[set[int], set[int] | None, bool]:
    """(the cents this turn's shop tools returned, the stock counts its lookups returned or None
    when no lookup ran, a lookup listed a bundle)."""
    cents: set[int] = set()
    counts: set[int] | None = None
    bundled = False
    for c in calls:
        if not c.ok:
            continue
        _cents_in(c.data, cents)
        if c.name == LOOKUP_TOOL:
            data = c.data or {}
            counts = (counts or set()) | {i.get("in_stock") for i in data.get("items") or []}
            bundled = bundled or bool(data.get("bundles"))
    return cents, counts, bundled


def visit_cents(visit) -> set[int]:
    """The totals code computed for this visit's orders (quoted or placed): a later turn may say
    them again."""
    from demo.models import ShopOrder

    if visit is None:
        return set()
    return set(ShopOrder.objects.filter(visit=visit).values_list("total_cents", flat=True))


def _in_bundle(skus: set[str]) -> bool:
    """Every product named belongs to a bundle (none named counts: "save $10.00 with the
    bundle")."""
    return skus <= {s for b in catalogue.BUNDLES for s in b.skus}


def _prices_allowed(text: str, said: set[int], turn: set[int], known: set[int], bundled: bool):
    """Every amount said is one the sentence may tie to what it names (the module text)."""
    if not said:
        return True
    named = products_named(text)
    allowed = {catalogue.SHIPPING_CENTS, catalogue.FREE_SHIPPING_OVER_CENTS} | known
    if bundled and _in_bundle(named):
        allowed |= catalogue.bundle_prices() | catalogue.bundle_savings()
    if named:
        allowed |= {catalogue.BY_SKU[s].price_cents for s in named}
    else:
        allowed |= turn & catalogue.backed_cents()
    return said <= allowed


def _money_off(text: str, bundled: bool) -> bool:
    """Money off that is not the bundle's real saving, about the bundle's own products, in a turn
    that listed the bundle ("$10.00 off the tent" is not)."""
    on_bundle = bundled and _in_bundle(products_named(text))
    for m in _AMOUNT_OFF.finditer(text):
        if _refused(text, m.start()):
            continue
        cents = _cents(m.group("x") or m.group("y"))
        if not (on_bundle and cents in catalogue.bundle_savings()):
            return True
    return False


# Nothing in this demo ever ships: a claim that something is on its way is never true here.
_SHIPPED = re.compile(
    r"\b(on (its|it's|the|their|your) way|shipped|ships today|out for delivery|in transit|"
    r"dispatched|sent out|headed your way)\b",
    re.I,
)


def _shipping_unbacked(text: str) -> bool:
    """A sentence about shipping or delivery that says more than the policy, or says anything
    has shipped."""
    if _SHIPPED.search(text):
        return True
    if not _SHIPPING_TOPIC.search(text):
        return False
    rest = _AMOUNT.sub(" ", _POLICY_FACTS.sub(" ", text))
    return bool(_TIMING.search(rest) or _FREE.search(rest))


def claim_reason(sentence: str, calls, known: set[int] = frozenset()) -> str | None:
    """Why the rep's `sentence` must not be said, as a rule name (logged, never the text), or
    None when it may be. `calls` are this turn's door calls, as the cascade keeps them (name, ok,
    data); `known` the visit's own order totals (`visit_cents`)."""
    text = sentence or ""
    turn, counts, bundled = _turn_facts(calls)
    said = amounts(text, loose=bool(products_named(text)))
    if not said <= catalogue.backed_cents() | turn | set(known):
        return "unknown_amount"
    if not _prices_allowed(text, said, turn, set(known), bundled):
        return "price_mismatch"
    if _WORD_FIGURE.search(text):
        return "word_figure"
    # "free" is said only inside the policy ("free on orders over $75.00").
    if _PERCENT_OFF.search(text) or _offers(_DEAL, _POLICY_FACTS.sub(" ", text)):
        return "deal"
    if _money_off(text, bundled):
        return "money_off"
    if _shipping_unbacked(text):
        return "shipping"
    if _BUNDLE.search(text) and not bundled:
        return "bundle_unlisted"
    if _URGENCY.search(text):
        return "urgency"
    # The amounts out first: "$129.00 in stock" is no count of 0 (the live run, 2026-11-22).
    bare = _AMOUNT.sub(" ", text)
    if counts is None:
        return "stock_unlooked" if _STOCK.search(text) or _IN_STOCK_COUNT.search(bare) else None
    if any(int(m.group(1)) not in counts for m in _IN_STOCK_COUNT.finditer(bare)):
        return "stock_count"
    return None


def unbacked_claim(sentence: str, calls, known: set[int] = frozenset()) -> bool:
    """True when the rep's `sentence` must not be said (`claim_reason`)."""
    return claim_reason(sentence, calls, known) is not None
