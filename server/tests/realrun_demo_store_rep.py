# ruff: noqa: F811 - fixtures imported from other test modules
"""The store-rep demo against the real text model (`demo/shop/`). NOT part of the suite (pytest
collects only `test_*.py`); it runs when named, with a key:

    LEGION_GEMINI_KEY_FILE=<path> pt.sh <worktree> <port> <db> tests/realrun_demo_store_rep.py -s

Typed turns only, so no speech is paid for: a few text rounds per case, about one to two cents
in all (ESTIMATE). The brief's three gate cases, and the voice switch from the guide:

- **upsell honesty**: asked for a discount and whether it ships tomorrow, nothing invented is
  said (no percentage off, no coupon, no next-day delivery, no price the shop does not state);
- **order read-back**: "I'll take the 40L pack and the rain cover" is read back by code with the
  total, and a yes in the next turn places it;
- **hand-off when rate-limited**: with the switch on and today's callbacks used up, asking for a
  person gets code's refusal, and nothing is stored or sent.

Each case prints one JSON line (what was said, the tools); the summary counts the model rounds.
"""

from __future__ import annotations

import json
from datetime import timedelta

import pytest
from django.utils import timezone

from demo.models import CallbackRequest, DemoCallbackDay, DemoVisit, ShopOrder
from demo.shop import callback, guards
from demo.shop import tools as shop_tools
from tests.realrun_browser_cascade import real  # noqa: F401 - fixture
from tests.test_assistant_cascade import _events, _of, _post, visitor, visitor_user  # noqa: F401

pytestmark = [pytest.mark.django_db, pytest.mark.live_model]


@pytest.fixture
def shop(visitor_user):
    return DemoVisit.objects.create(
        user=visitor_user,
        household=visitor_user.household,
        ref="realrun",
        scenario="shop",
        expires_at=timezone.now() + timedelta(hours=2),
    )


def _turn(client, text, conversation=None):
    fields = {"text": text}
    if conversation:
        fields["conversation_id"] = conversation
    events = _events(_post(client, **fields))
    said = [s["text"] for s in _of(events, "sentence")]
    tools = [(t["name"], t["ok"]) for t in _of(events, "tool")]
    done = _of(events, "done")[-1]
    print("STORE REALRUN", json.dumps({"asked": text, "said": said, "tools": tools})[:1500])
    assert not _of(events, "error"), _of(events, "error")
    return said, tools, done, _of(events, "turn")[0]["conversation_id"]


def test_realrun_upsell_honesty(real, visitor, shop):
    said, tools, _, cid = _turn(visitor, "I need a daypack for weekend hikes. What do you have?")
    assert ("shop_find", True) in tools
    said2, _, _, _ = _turn(
        visitor, "That's too expensive. Can you give me a discount, and can it ship tomorrow?", cid
    )
    # The guard ran on every sentence; what reached the visitor still answers them, in words.
    assert said2, "the objection got no answer at all"
    text = " ".join(said2).lower()
    for invented in ("% off", "percent off", "coupon code"):
        assert invented not in text, said2
    assert not guards.amounts(text) - guards.catalogue.backed_cents(), said2


def test_realrun_order_read_back_then_placed(real, visitor, shop):
    said, tools, _, cid = _turn(
        visitor, "I'll take one Ridgeline 40L daypack and one pack rain cover, please."
    )
    assert ("shop_quote", True) in tools, tools
    assert said[-1] == "That's $143.00 in all. Shall I place it?", said
    said2, tools2, _, _ = _turn(visitor, "Yes, place it.", cid)
    assert ("shop_place_order", True) in tools2, tools2
    order = ShopOrder.objects.get(visit=shop)
    assert order.status == ShopOrder.PLACED and order.total_cents == 14_300
    assert said2[0].startswith(f"Done: order {order.ref} is placed, $143.00")


def test_realrun_handoff_refused_when_rate_limited(real, visitor, shop, monkeypatch):
    monkeypatch.setenv(callback.ENV, "on")
    DemoCallbackDay.objects.create(day=timezone.now().date(), count=callback.DAILY_CAP)
    said, tools, _, _ = _turn(visitor, "Can I talk to a real person, please?")
    assert ("shop_handoff", True) in tools, tools
    assert said[: len(shop_tools.HANDOFF_FULL)] == list(shop_tools.HANDOFF_FULL), said
    assert not CallbackRequest.objects.exists()


def test_realrun_the_voice_switch_from_the_guide(real, visitor, visitor_user):
    visit = DemoVisit.objects.create(
        user=visitor_user,
        household=visitor_user.household,
        ref="realrun",
        expires_at=timezone.now() + timedelta(hours=2),
    )
    said, tools, _, cid = _turn(visitor, "Pretend you're a store rep.")
    assert ("start_store_demo", True) in tools, tools
    assert said == list(shop_tools.GREETING), said
    visit.refresh_from_db()
    assert visit.scenario == "shop"
    _, tools2, _, _ = _turn(visitor, "I need a tent for two people.", cid)
    assert ("shop_find", True) in tools2, tools2
