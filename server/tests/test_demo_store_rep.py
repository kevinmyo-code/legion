"""The store-rep demo on /try (`demo/shop/`, Kevin 2026-11-22): the scenario switch, the rep's
honesty about prices, stock and discounts, the order read-back and its confirmation in a later
turn, the hand-off's switch and caps, and the after-call card. Invented shop, invented data."""

# ruff: noqa: F811 - the fixtures are imported from the cascade tests
from __future__ import annotations

from datetime import timedelta

import pytest
from django.utils import timezone

from agents.runtime.model import FakeModel, call, say
from demo.models import CallbackRequest, DemoCallbackDay, DemoVisit, ShopOrder
from demo.shop import callback, catalogue, guards
from demo.shop import tools as shop_tools
from demo.shop.prompt import SHOP_FRAME
from tests.test_assistant_cascade import (  # noqa: F401 - fixtures
    _clean,
    _events,
    _of,
    _post,
    engine,
    member,
    visitor,
    visitor_user,
)

pytestmark = pytest.mark.django_db


@pytest.fixture
def visit(visitor_user):
    return DemoVisit.objects.create(
        user=visitor_user,
        household=visitor_user.household,
        ref="acme",
        expires_at=timezone.now() + timedelta(hours=2),
    )


@pytest.fixture
def shop_visit(visit):
    visit.scenario = "shop"
    visit.save(update_fields=["scenario"])
    return visit


PACK_AND_COVER = {"items": [{"sku": "LP-PK40", "qty": 1}, {"sku": "LP-RC40", "qty": 1}]}


def _said(events) -> list[str]:
    return [s["text"] for s in _of(events, "sentence")]


def _turn(client, text, conversation=None):
    fields = {"text": text}
    if conversation:
        fields["conversation_id"] = conversation
    events = _events(_post(client, **fields))
    return events, _of(events, "turn")[0]["conversation_id"]


def _declared(model) -> set[str]:
    return {d["name"] for d in model.requests[-1].declarations}


# =============================================================================
# The scenario switch
# =============================================================================


def test_pretend_youre_a_store_rep_switches_the_visit_and_code_greets(visitor, visit, engine):
    engine.model = FakeModel([call("start_store_demo")])
    events, _ = _turn(visitor, "Pretend you're a store rep.")
    assert _said(events) == list(shop_tools.GREETING)
    visit.refresh_from_db()
    assert visit.scenario == "shop"
    # The guide's tools plus the switch were declared for that turn.
    assert "start_store_demo" in _declared(engine.model)
    assert "read_records" in _declared(engine.model)


def test_in_the_scenario_only_the_reps_tools_and_frame_are_given(visitor, shop_visit, engine):
    engine.model = FakeModel([say("What are you shopping for?")])
    _turn(visitor, "Hi")
    declared = _declared(engine.model)
    assert {t.name for t in shop_tools.REP_TOOLS} <= declared
    assert "read_records" not in declared and "about_kevin" not in declared
    assert SHOP_FRAME.strip()[:60] in engine.model.requests[-1].system


def test_a_guide_tool_called_in_the_scenario_is_refused_and_nothing_runs(
    visitor, shop_visit, engine
):
    engine.model = FakeModel([call("read_records", {"table": "vehicles"}), say("Sorry.")])
    events, _ = _turn(visitor, "Show my cars")
    tool = _of(events, "tool")[0]
    assert tool["ok"] is False and "not available here" in tool["text"]


def test_the_chip_switch_is_the_same_switch(visitor, visit):
    response = visitor.post("/api/demo/shop/scenario", {"active": True}, format="json")
    assert response.status_code == 200 and response.data["active"] is True
    visit.refresh_from_db()
    assert visit.scenario == "shop"
    off = visitor.post("/api/demo/shop/scenario", {"active": False}, format="json")
    assert off.data["active"] is False


def test_a_member_never_gets_the_shop(member, engine):
    engine.model = FakeModel([say("Nothing is on today.")])
    _events(_post(member, text="What's on today?"))
    assert not {t for t in shop_tools.ALL_TOOLS} & _declared(engine.model)
    assert member.get("/api/demo/shop/state").status_code == 403


# =============================================================================
# Upsell honesty: no invented stock, discounts or prices
# =============================================================================


@pytest.mark.parametrize(
    "sentence,dropped",
    [
        ("The Ridgeline 40L is $129.00.", False),
        ("The Ridgeline 40L is $129.", False),
        ("I can do it for $115.00 today.", True),
        ("I'll give you 15% off.", True),
        ("Use the coupon code TRAIL for a discount.", True),
        ("We can price match that.", True),
        ("I'll throw in the socks for free.", True),
        ("It ships tomorrow if you order now.", True),
        ("Only 2 left, so grab it fast.", True),
        ("Shipping is free over $75.00.", False),
        ("It's 100% merino wool.", False),
        # A refusal may name what it refuses (the first live run dropped one whole).
        ("There's no discount, but the 28L is $98.00.", False),
        ("Sorry, it can't ship tomorrow; delivery takes 3 to 5 business days.", True),
        ("No coupon needed: 20% off today.", True),
        ("We don't price match, but I'll take $20.00 off.", True),
        # Figures in words cannot be checked; code speaks digits.
        ("I'll give you ten percent off.", True),
        ("Ten bucks off if you order now.", True),
        # "No worries" is not a refusal.
        ("No worries, a discount is applied.", True),
        ("It's selling fast.", True),
        # The review (2026-11-22): a negation counts only when it governs the phrase.
        ("Don't wait, it ships tomorrow.", True),
        ("Don't miss out, use coupon TRAIL.", True),
        ("Don't miss our coupon this week.", True),
        ("We don't price match.", False),
        # Shipping and delivery may state only the policy, negated or not (an allowlist).
        ("It can't arrive by Friday; delivery takes 3 to 5 business days.", True),
        ("No coupons, sorry.", False),
        # Money off: only the bundle's saving, and only in a turn that listed the bundle.
        ("I can take $10.00 off the tent for you.", True),
        ("I'll take $8.00 off if you order now.", True),
        ("That's $10.00 cheaper than buying them apart.", True),
        ("The 28L is 20% cheaper.", True),
        ("It's half price this week.", True),
        ("We have a buy one, get one on socks.", True),
        ("BOGO on headlamps today.", True),
        # Free shipping only with its real threshold.
        ("I'll give you free shipping on the headlamp.", True),
        ("Shipping is free on orders over $75.00.", False),
        ("There's no free shipping under $75.00.", True),
        # Delivery no sooner or more definite than the policy.
        ("It'll arrive by Friday.", True),
        ("You'd have it on Monday.", True),
        ("Delivery takes 3 to 5 business days.", False),
        # One product and one amount: the amount is that product's price.
        ("The tent is $98.00.", True),
        ("The tent is $289.00.", False),
        # A total the model worked out itself: never said (code states totals).
        ("Two pairs of socks come to $44.00.", True),
        ("Shipping on the headlamp is $8.00.", False),
        ("The 40L is $129.00 and the 28L is $98.00.", False),
        # The second review's adversarial lines (2026-11-22), each must drop.
        ("Honestly, don't miss the coupon on this one.", True),
        ("You really can't miss the coupon.", True),
        ("That tent normally runs $289.00 but for you it's $98.00 with the bundle.", True),
        ("Shipping's on us for this one.", True),
        ("We won't charge you shipping on the headlamp.", True),
        ("We'll waive the shipping fee.", True),
        ("There's no shipping charge on that.", True),
        ("Shipping is free.", True),
        ("It'll show up within 48 hours.", True),
        ("You'd have it at your door before the weekend.", True),
        ("It ships Thursday.", True),
        ("It arrives tomorrow.", True),
        ("We can ship it overnight.", True),
        ("That's a steal at $36.00 for the Lumen headlamp, cheapest you'll find anywhere.", True),
        ("Cirrus 2 Footprint is $24.00.", True),
        ("I can shave $8.00 off.", True),
        ("Not only is it in stock, there's a coupon too.", True),
        # The third review's lines (2026-11-22).
        ("The tent is just 98 now.", True),
        ("For the tent I can do USD 98.", True),
        ("The tent is 289 bucks.", False),
        ("The 40L is $129.00 and ships in 3 to 5 business days.", False),
        ("The Cirrus is $98.00.", True),
        ("The Cirrus 2-Person Tent is $289.00.", False),
        ("The headlamp comes with shipping included.", True),
        ("You get the socks at no extra cost.", True),
        ("The rain cover is free with it.", True),
        ("I'll have the socks thrown in.", True),
        ("We'll match whatever price you find.", True),
        ("We can price match that.", True),
        ("The tent weighs 2.1 kg and is $289.00.", False),
        ("The headlamp is 400 lumens.", False),
        # Nothing ever ships in this demo (2026-11-22, live run).
        ("That is all set and on its way.", True),
        ("Your order has shipped.", True),
        ("It ships today.", True),
        ("It's out for delivery.", True),
        # What may still be said.
        ("The headlamp is $36.00.", False),
        ("Shipping is $8.00, or free on orders over $75.00.", False),
    ],
)
def test_the_reps_sentence_check(sentence, dropped):
    assert guards.unbacked_claim(sentence, []) is dropped


def _bundle_lookup():
    from types import SimpleNamespace

    data = {
        "items": [{"sku": "LP-PK40", "price_cents": 12_900, "in_stock": 14}],
        "bundles": [{"skus": ["LP-PK40", "LP-RC40"], "saving_cents": 1_000}],
    }
    return SimpleNamespace(name="shop_find", ok=True, data=data)


@pytest.mark.parametrize(
    "sentence,dropped",
    [
        ("Together with the rain cover it's $10.00 off.", False),
        ("Take the bundle and save $10.00.", False),
        ("I'll take $20.00 off the bundle.", True),
        ("I can take $10.00 off the tent for you.", True),
    ],
)
def test_money_off_is_only_the_bundles_saving_in_a_turn_that_listed_it(sentence, dropped):
    assert guards.unbacked_claim(sentence, [_bundle_lookup()]) is dropped


def test_stock_and_the_bundle_may_be_said_after_this_turns_lookup(visitor, shop_visit, engine):
    engine.model = FakeModel(
        [
            call("shop_find", {"query": "backpack"}),
            say(
                "The Ridgeline 40L is $129.00 and there are 14 in stock. "
                "With the rain cover it's a bundle at $143.00. "
                "And I can knock $20.00 off if you buy today."
            ),
        ]
    )
    events, _ = _turn(visitor, "I need a backpack")
    said = _said(events)
    assert "The Ridgeline 40L is $129.00 and there are 14 in stock." in said
    assert "With the rain cover it's a bundle at $143.00." in said
    assert not any("knock" in s for s in said)


def test_stock_without_a_lookup_this_turn_is_not_said(visitor, shop_visit, engine):
    engine.model = FakeModel([say("We have 14 in stock. What size do you need?")])
    events, _ = _turn(visitor, "Is it in stock?")
    assert "We have 14 in stock." not in _said(events)


def test_a_too_expensive_objection_offers_the_real_cheaper_pack_not_a_discount(
    visitor, shop_visit, engine
):
    engine.model = FakeModel(
        [
            call("shop_find", {"query": "small pack"}),
            say(
                "Fair enough. I could do 10% off the 40L. "
                "Or the Ridgeline 28L is $98.00, lighter and cheaper."
            ),
        ]
    )
    events, _ = _turn(visitor, "That's too expensive.")
    said = _said(events)
    assert said == ["Fair enough.", "Or the Ridgeline 28L is $98.00, lighter and cheaper."]


# =============================================================================
# The order: read back by code, placed only on a yes in a later turn
# =============================================================================


def test_the_order_is_read_back_by_code_and_placed_on_the_next_turns_yes(
    visitor, shop_visit, engine
):
    engine.model = FakeModel([call("shop_quote", PACK_AND_COVER)])
    events, conversation = _turn(visitor, "I'll take the 40L and the rain cover.")
    assert _said(events) == [
        "Here's the order: 1 Ridgeline 40L Daypack at $129.00, and 1 Pack Rain Cover at $24.00.",
        "The bundle takes $10.00 off. Shipping is free.",
        "That's $143.00 in all. Shall I place it?",
    ]
    order = ShopOrder.objects.get(visit=shop_visit)
    assert order.status == ShopOrder.QUOTED and order.total_cents == 14_300

    engine.model = FakeModel(
        [call("shop_place_order"), say("That's $150.00 then. Anything else for your trip?")]
    )
    events, _ = _turn(visitor, "Yes, place it.", conversation)
    order.refresh_from_db()
    assert order.status == ShopOrder.PLACED
    said = _said(events)
    assert said[0] == (
        f"Done: order {order.ref} is placed, $143.00 in all, arriving in 3 to 5 business days."
    )
    # After code's confirmation, a model sentence with money in it is never said.
    assert not any("$150.00" in s for s in said)
    assert "Anything else for your trip?" in said


def test_a_place_in_the_same_turn_as_the_read_back_is_refused(visitor, shop_visit, engine):
    engine.model = FakeModel([call("shop_quote", {"items": [{"sku": "LP-HL", "qty": 1}]})])
    _turn(visitor, "One headlamp please")
    # The quote ended its turn; a model that tries to place in the read-back's turn is refused.
    order = ShopOrder.objects.get(visit=shop_visit)
    from assistant import read_through
    from demo.shop.tools import SHOP_PLACE_ORDER

    class Req:
        user = shop_visit.user

    req = Req()
    read_through.set_turn(req, order.conversation_id, order.quoted_turn)
    result = SHOP_PLACE_ORDER.handler(req, {})
    assert result.is_error and "same turn" in result.text
    order.refresh_from_db()
    assert order.status == ShopOrder.QUOTED


@pytest.mark.parametrize("answer", ["No.", "How much is shipping?", "Yes, but in blue?"])
def test_an_order_is_never_placed_on_anything_but_a_plain_yes(visitor, shop_visit, engine, answer):
    engine.model = FakeModel([call("shop_quote", {"items": [{"sku": "LP-HL", "qty": 1}]})])
    _, conversation = _turn(visitor, "One headlamp please")
    # The model decides it was a yes; code reads the words and does not place it.
    engine.model = FakeModel([call("shop_place_order"), say("Let me know when you're ready.")])
    events, _ = _turn(visitor, answer, conversation)
    tool = _of(events, "tool")[0]
    assert tool["ok"] is False and "not a plain yes" in tool["text"]
    assert ShopOrder.objects.get(visit=shop_visit).status == ShopOrder.QUOTED


@pytest.mark.parametrize(
    "words,yes",
    [
        ("Yes, place it.", True),
        ("Yeah go ahead", True),
        ("Confirm.", True),
        ("no", False),
        ("Not yet", False),
        ("How much is shipping?", False),
        ("Yes but wait", False),
        ("Yes, and add socks too", False),
        ("ok so like the socks", False),
        ("Yes please, the cheaper one", False),
        ("Yeah I guess", False),
        ("Yes, or maybe the 28L", False),
        ("", False),
    ],
)
def test_is_yes(words, yes):
    assert shop_tools.is_yes(words) is yes


def test_placing_with_nothing_read_back_is_refused_in_words(visitor, shop_visit, engine):
    engine.model = FakeModel([call("shop_place_order"), say("Sorry, let me read it back first.")])
    events, _ = _turn(visitor, "Just place my order")
    assert _of(events, "tool")[0]["ok"] is False
    assert not ShopOrder.objects.filter(visit=shop_visit, status=ShopOrder.PLACED).exists()


def test_a_quote_over_the_stock_is_refused(visitor, shop_visit, engine):
    engine.model = FakeModel(
        [call("shop_quote", {"items": [{"sku": "LP-HB2", "qty": 1}]}), say("It's out.")]
    )
    events, _ = _turn(visitor, "Add a water bladder")
    tool = _of(events, "tool")[0]
    assert tool["ok"] is False and "out of stock" in tool["text"]
    assert not ShopOrder.objects.filter(visit=shop_visit).exists()


# =============================================================================
# Consult before selling (the live run of 2026-11-24)
# =============================================================================


def _packs_lookup():
    from types import SimpleNamespace

    data = {
        "items": [
            {"sku": "LP-PK40", "price_cents": 12_900, "in_stock": 14},
            {"sku": "LP-PK28", "price_cents": 9_800, "in_stock": 6},
            {"sku": "LP-RC40", "price_cents": 2_400, "in_stock": 40},
        ],
        "bundles": [{"skus": ["LP-PK40", "LP-RC40"], "saving_cents": 1_000}],
    }
    return SimpleNamespace(name="shop_find", ok=True, data=data)


@pytest.mark.parametrize(
    "sentence",
    [
        # What the frame now asks the rep for: the reason, the comparison, the question first,
        # the add-on after. The sentence check has to let all of it through, or the frame asks
        # for answers that never reach the visitor. Invented lines.
        "What will you be using it for?",
        "For a day on the hill I'd take the Ridgeline 40L at $129.00: the padded hip belt "
        "carries a day's water and food.",
        "The Ridgeline 40L is $129.00 with a padded hip belt; the Ridgeline 28L is $98.00, "
        "lighter, with no hip belt.",
        "Since you want to keep it light, the 28L at $98.00 is the lighter one and there are 6 "
        "in stock.",
        "For a loaded day I'd take the 40L for the hip belt.",
        "Would you like me to put the Ridgeline 40L down for you?",
        "Now that you've got the 40L, the Pack Rain Cover at $24.00 fits packs from 28 to 40 "
        "litres.",
    ],
)
def test_a_consulting_answer_survives_the_sentence_check(sentence):
    assert guards.claim_reason(sentence, [_packs_lookup()]) is None


def test_the_lookup_tells_the_rep_to_give_a_reason_compare_and_hold_the_add_on(
    visitor, shop_visit, engine
):
    """Two packs come back from one lookup; the result text is what makes the rep use both and
    keep the add-on until they have chosen (the live run named only the first, every turn)."""
    engine.model = FakeModel([call("shop_find", {"query": "day pack"}), say("Right.")])
    events, _ = _turn(visitor, "Something to carry a day's kit up a hill")
    text = [t for t in _of(events, "tool") if t["name"] == "shop_find"][0]["text"]
    assert "LP-PK40" in text and "LP-PK28" in text
    assert "why it suits what they told you" in text
    assert "name both prices and what separates them" in text
    assert "offered only after they say they want that product" in text


def test_the_frame_asks_for_a_reason_and_holds_the_add_on_until_they_choose():
    assert "Consult before you sell" in SHOP_FRAME
    # Consulting is asking when the use is unknown, not asking instead of looking: a shopper who
    # says what they are after gets a lookup in that same turn.
    assert "call shop_find in the turn they name a kind of thing" in SHOP_FRAME
    assert "name no product that turn" in SHOP_FRAME
    assert "say WHY it suits what THEY said" in SHOP_FRAME
    assert "ONLY after they have said they want a product" in SHOP_FRAME
    assert "never end a reply by offering to add something before they have chosen" in SHOP_FRAME
    # The quote tool is for buying and for the order's total, which only the read-back says.
    assert "only once their own words say they want to buy" in SHOP_FRAME


def test_the_quote_is_code_arithmetic():
    q = catalogue.quote({"LP-SK": 2})
    assert (q.total_cents, q.shipping_cents) == (2 * 2_200 + 800, 800)
    q = catalogue.quote({"LP-PK40": 2, "LP-RC40": 1})
    assert q.bundle_saving_cents == 1_000 and q.total_cents == 2 * 12_900 + 2_400 - 1_000


def test_every_spoken_tool_is_one_code_speaks():
    from agents.legion.speech_rules import VERBATIM_TOOLS

    spoken = {"start_store_demo", "leave_store_demo", "shop_quote", "shop_place_order"}
    assert spoken | {"shop_handoff"} <= VERBATIM_TOOLS


# =============================================================================
# The hand-off: an unset switch, one per visit, a daily cap, nothing dialled
# =============================================================================


def test_with_the_switch_unset_the_rep_says_no_person_can_be_reached(
    visitor, shop_visit, engine, monkeypatch
):
    monkeypatch.delenv(callback.ENV, raising=False)
    engine.model = FakeModel([call("shop_handoff")])
    events, _ = _turn(visitor, "Let me talk to a person.")
    assert _said(events) == list(shop_tools.HANDOFF_OFF)
    form = visitor.post(
        "/api/demo/shop/callback", {"name": "Pat", "contact": "pat@example.com"}, format="json"
    )
    assert form.status_code == 409
    assert not CallbackRequest.objects.exists()


@pytest.fixture
def handoff_on(monkeypatch):
    monkeypatch.setenv(callback.ENV, "on")
    sent = []
    monkeypatch.setattr(callback, "default_sender", lambda: (lambda sub, payload: 201))
    monkeypatch.setattr(callback, "notify", lambda row, send=None: sent.append(row.pk) or "sent")
    return sent


def test_a_callback_is_taken_once_and_kevin_is_told(visitor, shop_visit, handoff_on):
    response = visitor.post(
        "/api/demo/shop/callback",
        {"name": "Pat Doe", "contact": "+1 555 010 2030", "note": "About the tent"},
        format="json",
    )
    assert response.status_code == 201 and response.data["status"] == "sent"
    row = CallbackRequest.objects.get()
    assert row.summary == "Store-rep demo call. No order." and handoff_on == [row.pk]
    again = visitor.post(
        "/api/demo/shop/callback", {"name": "Pat", "contact": "pat@example.com"}, format="json"
    )
    assert again.status_code == 429 and CallbackRequest.objects.count() == 1


def test_handoff_is_refused_when_the_day_is_used_up(visitor, shop_visit, engine, handoff_on):
    DemoCallbackDay.objects.create(day=timezone.now().date(), count=callback.DAILY_CAP)
    engine.model = FakeModel([call("shop_handoff")])
    events, _ = _turn(visitor, "Get me a manager.")
    assert _said(events) == list(shop_tools.HANDOFF_FULL)
    form = visitor.post(
        "/api/demo/shop/callback", {"name": "Pat", "contact": "pat@example.com"}, format="json"
    )
    assert form.status_code == 429 and not CallbackRequest.objects.exists()


def test_the_daily_cap_holds_across_visitors():
    for _ in range(callback.DAILY_CAP):
        assert callback.take_slot()
    assert not callback.take_slot()


@pytest.mark.parametrize(
    "fields",
    [
        {"name": "Pat", "contact": "not a contact"},
        {"name": "http://spam.example", "contact": "pat@example.com"},
        {"name": "Pat", "contact": "pat@example.com", "note": "see www.spam.example"},
        {"name": "Pat", "contact": "pat@example.com", "website": "filled-by-a-bot"},
        {"name": "Pat", "contact": "https://evil.example/@a.io"},
        {"name": "Pat", "contact": "evil.example/call-me"},
        {"name": "Pat", "contact": "pat@example.com", "note": "call pat\u202eexample"},
        {"name": "Pat", "contact": "+1 555 0100", "note": "see evil.io"},
    ],
)
def test_spam_and_junk_are_refused_and_take_no_slot(visitor, shop_visit, handoff_on, fields):
    response = visitor.post("/api/demo/shop/callback", fields, format="json")
    assert response.status_code == 400
    assert not CallbackRequest.objects.exists() and callback.used_today() == 0


def test_without_a_device_the_request_is_saved_and_says_kevin_was_not_told(
    visitor, shop_visit, monkeypatch
):
    monkeypatch.setenv(callback.ENV, "on")
    monkeypatch.setattr(callback, "default_sender", lambda: None)
    response = visitor.post(
        "/api/demo/shop/callback", {"name": "Pat", "contact": "pat@example.com"}, format="json"
    )
    assert response.status_code == 201 and response.data["status"] == "saved"
    assert "not been notified" in response.data["detail"]
    assert CallbackRequest.objects.get().delivery == "push_off"


# =============================================================================
# The after-call card
# =============================================================================


def test_the_after_call_card_is_built_from_the_record(visitor, shop_visit, engine):
    engine.model = FakeModel([call("shop_quote", {"items": [{"sku": "LP-TN2", "qty": 1}]})])
    _, conversation = _turn(visitor, "The 2-person tent please.")
    engine.model = FakeModel([call("shop_place_order"), say("Enjoy the trip.")])
    _turn(visitor, "Yes.", conversation)
    card = visitor.get("/api/demo/shop/call-card")
    assert card.status_code == 200, card.data
    data = card.data
    assert data["outcome"] == "order_placed" and data["outcome_words"] == "Order placed"
    assert [line["who"] for line in data["transcript"]][:2] == ["visitor", "assistant"]
    assert data["transcript"][0]["typed"] is True
    record = data["record"]
    assert record["orders"][0]["total_cents"] == 28_900
    assert record["products_discussed"] == ["LP-TN2"]
    assert record["handoff"] == {"requested": False, "contact_left": False, "notified": False}
    assert len(data["turns"]) == 2
    assert any("Placed" in line for line in data["summary"])


def test_no_conversation_no_card(visitor, visit):
    assert visitor.get("/api/demo/shop/call-card").status_code == 404


def test_an_answer_made_only_of_unbacked_claims_becomes_the_shops_plain_terms(
    visitor, shop_visit, engine
):
    engine.model = FakeModel([say("I'll do 20% off and overnight shipping for you.")])
    events, _ = _turn(visitor, "Discount? Fast shipping?")
    assert _said(events) == [shop_tools.ONLY_REAL_OFFERS]


def test_a_lost_objection_answer_gets_the_terms_and_the_cheaper_pack(visitor, shop_visit, engine):
    engine.model = FakeModel(
        [
            call("shop_find", {"query": "daypack"}),
            say("I'll do 20% off, or the 28L is ninety eight dollars."),
        ]
    )
    events, _ = _turn(visitor, "That's too expensive. Any discount?")
    assert _said(events) == [
        shop_tools.ONLY_REAL_OFFERS,
        "The Ridgeline 28L Daypack is $98.00, and in stock.",
    ]


def test_a_lost_answer_about_something_else_says_what_the_lookup_found(visitor, shop_visit, engine):
    engine.model = FakeModel(
        [
            call("shop_find", {"query": "tent"}),
            say("The tent is two hundred eighty-nine dollars, with three left."),
        ]
    )
    events, _ = _turn(visitor, "I need a tent for two people.")
    said = _said(events)
    assert said[0] == "The Cirrus 2-Person Tent is $289.00, and in stock."
    assert shop_tools.ONLY_REAL_OFFERS not in said


def test_an_out_of_stock_add_on_is_never_offered():
    from types import SimpleNamespace

    result = shop_tools.SHOP_FIND.handler(SimpleNamespace(user=None), {"sku": "LP-PK40"})
    assert "Pack Rain Cover" in result.text
    assert "Hydration Bladder" not in result.text


def _lookup(in_stock: int):
    from types import SimpleNamespace

    data = {"items": [{"sku": "LP-PK40", "price_cents": 12_900, "in_stock": in_stock}]}
    return SimpleNamespace(name="shop_find", ok=True, data={**data, "bundles": []})


def test_a_stock_count_must_be_the_one_this_turns_lookup_returned():
    assert not guards.unbacked_claim("There are 14 in stock.", [_lookup(14)])
    # The live run (2026-11-22): a price before "in stock" is not a count of 0.
    assert not guards.unbacked_claim(
        "We have the Ridgeline 40L Daypack for $129.00 in stock.", [_lookup(14)]
    )
    assert guards.unbacked_claim("There are 2 in stock.", [_lookup(14)])
    assert guards.unbacked_claim("Only 2 left!", [_lookup(2)])
    assert guards.unbacked_claim("There are 14 in stock.", [])


def test_a_shipping_answer_in_a_lookup_turn_is_said(visitor, shop_visit, engine):
    engine.model = FakeModel(
        [
            call("shop_find", {"sku": "LP-SK"}),
            say("Shipping is $8.00 on orders up to $75.00."),
        ]
    )
    events, _ = _turn(visitor, "Socks, and what's shipping?")
    assert _said(events) == ["Shipping is $8.00 on orders up to $75.00."]


def test_a_new_conversation_in_the_scenario_greets_as_the_shop_with_no_guide_chips(
    visitor, shop_visit, engine
):
    events = _events(_post(visitor, greet="true"))
    done = _of(events, "done")[-1]
    assert done["final_text"] == shop_tools.SHOP_HELLO
    assert "offer" not in done


def test_no_guide_chips_after_a_reps_answer(visitor, shop_visit, engine):
    engine.model = FakeModel([say("What are you shopping for?")])
    events, _ = _turn(visitor, "Hello")
    assert "offer" not in _of(events, "done")[-1]


def test_notify_reaches_the_engine_owners_subscription(
    visitor, shop_visit, household_user, monkeypatch
):
    from household.models import HouseholdMember
    from push.models import PushSent, PushSubscription

    member = HouseholdMember.objects.get(user=household_user)
    member.role = HouseholdMember.OWNER
    member.save(update_fields=["role"])
    PushSubscription.objects.create(
        household=member.household,
        user=household_user,
        endpoint="https://push.invalid/x",
        p256dh="k",
        auth="a",
    )
    payloads = []
    monkeypatch.setenv(callback.ENV, "on")
    monkeypatch.setattr(
        callback, "default_sender", lambda: (lambda sub, payload: payloads.append(payload) or 201)
    )
    response = visitor.post(
        "/api/demo/shop/callback",
        {"name": "Pat Doe", "contact": "pat@example.com", "note": "About a tent"},
        format="json",
    )
    assert response.status_code == 201 and response.data["status"] == "sent", response.data
    assert payloads[0]["kind"] == "demo_callback"
    assert "Pat Doe, pat@example.com." in payloads[0]["body"]
    assert PushSent.objects.filter(kind="demo_callback").count() == 1
    assert CallbackRequest.objects.get().delivery == "sent"


def test_one_callback_per_address_per_day(visit, shop_visit, handoff_on, db):
    from household.models import Household, HouseholdMember, User

    other = User.objects.create_user(email="visitor-two@demo.invalid", first_name="Sam")
    home = Household.objects.create(name="Demo visit two", is_demo_visit=True)
    HouseholdMember.objects.create(user=other, household=home, role=HouseholdMember.RECRUITER)
    DemoVisit.objects.create(
        user=other,
        household=home,
        ref="",
        scenario="shop",
        expires_at=timezone.now() + timedelta(hours=2),
    )
    first = callback.request_callback(
        shop_visit.user, name="Pat", contact="pat@example.com", note="", ip="203.0.113.9"
    )
    second = callback.request_callback(
        other, name="Sam", contact="sam@example.com", note="", ip="203.0.113.9"
    )
    assert first.status == "sent"
    assert second.http == 429 and "this network" in second.words
    assert CallbackRequest.objects.count() == 1 and callback.used_today() == 1
    assert "203.0.113.9" not in CallbackRequest.objects.get().address_key


def test_a_renamed_sku_never_breaks_the_card_or_the_state(visitor, shop_visit, engine):
    ShopOrder.objects.create(
        visit=shop_visit,
        ref="LP-1000",
        status=ShopOrder.PLACED,
        lines=[
            {"sku": "LP-GONE", "name": "Old Pack", "qty": 1, "unit_cents": 5000, "line_cents": 5000}
        ],
        total_cents=5800,
        shipping_cents=800,
    )
    engine.model = FakeModel([say("Hello.")])
    _turn(visitor, "Hello")
    state = visitor.get("/api/demo/shop/state")
    assert state.status_code == 200
    assert state.data["orders"][0]["lines"][0]["name"] == "Old Pack"
    card = visitor.get("/api/demo/shop/call-card")
    assert card.status_code == 200


def test_a_yes_places_only_the_read_back_heard_in_the_turn_just_before(visitor, shop_visit, engine):
    engine.model = FakeModel([call("shop_quote", {"items": [{"sku": "LP-HL", "qty": 1}]})])
    _, conversation = _turn(visitor, "One headlamp please")
    engine.model = FakeModel([say("Want some socks too?")])
    _turn(visitor, "Tell me about socks", conversation)
    # A yes to the socks question: the model places the old quote; code refuses it.
    engine.model = FakeModel([call("shop_place_order"), say("Okay.")])
    events, _ = _turn(visitor, "Yes.", conversation)
    tool = _of(events, "tool")[0]
    assert tool["ok"] is False and "not the last thing they heard" in tool["text"]
    assert ShopOrder.objects.get(visit=shop_visit).status == ShopOrder.QUOTED


def test_yes_and_add_socks_does_not_place(visitor, shop_visit, engine):
    engine.model = FakeModel([call("shop_quote", {"items": [{"sku": "LP-HL", "qty": 1}]})])
    _, conversation = _turn(visitor, "One headlamp please")
    engine.model = FakeModel([call("shop_place_order"), say("Sure.")])
    events, _ = _turn(visitor, "Yes, and add socks too", conversation)
    assert _of(events, "tool")[0]["ok"] is False
    assert ShopOrder.objects.get(visit=shop_visit).status == ShopOrder.QUOTED


def test_an_objection_that_kept_only_a_refusal_gets_the_cheaper_option(visitor, shop_visit, engine):
    engine.model = FakeModel(
        [
            call("shop_find", {"query": "daypack"}),
            say("There are no discounts or coupons here. It can't ship tomorrow."),
        ]
    )
    events, _ = _turn(visitor, "That's too expensive. Any discount, and can it ship tomorrow?")
    assert _said(events) == [
        "There are no discounts or coupons here.",
        "The Ridgeline 28L Daypack is $98.00, and in stock.",
    ]


def test_no_option_is_added_when_a_kept_sentence_names_one(visitor, shop_visit, engine):
    engine.model = FakeModel(
        [
            call("shop_find", {"query": "daypack"}),
            say("No discounts, but the Ridgeline 28L is $98.00. It ships tomorrow."),
        ]
    )
    events, _ = _turn(visitor, "Too expensive.")
    assert _said(events) == ["No discounts, but the Ridgeline 28L is $98.00."]


def test_each_dropped_sentence_is_recorded_by_rule_name_only(visitor, shop_visit, engine):
    from tests.test_assistant_cascade import _record

    engine.model = FakeModel(
        [call("shop_find", {"query": "tent"}), say("It ships tomorrow. Use coupon TRAIL.")]
    )
    _, conversation = _turn(visitor, "A tent please")
    record = _record(conversation)
    assert "shop_dropped shipping,deal" in record
    assert "coupon TRAIL" not in record.split("shop_dropped")[1].splitlines()[0]


@pytest.mark.parametrize(
    "sentence,reason",
    [
        ("The tent is $98.00.", "price_mismatch"),
        ("I can do it for $115.00.", "unknown_amount"),
        ("Use the coupon.", "deal"),
        ("It ships tomorrow.", "shipping"),
        ("Only 2 left!", "urgency"),
        ("Ten bucks off.", "word_figure"),
        ("The headlamp is $36.00.", None),
    ],
)
def test_claim_reason_names_the_rule(sentence, reason):
    assert guards.claim_reason(sentence, []) == reason
