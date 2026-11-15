"""`tools/voice_cost.py`: the log shapes the phone writes, and the Decimal pricing arithmetic.
Invented counts only. Run from the repo root:

    python -m pytest tools/tests/test_voice_cost.py -p no:cacheprovider
"""

from __future__ import annotations

import datetime
import importlib.util
import sys
from decimal import Decimal
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("voice_cost_under_test", TOOLS / "voice_cost.py")
vc = importlib.util.module_from_spec(spec)
sys.modules["voice_cost_under_test"] = vc
spec.loader.exec_module(vc)

PRICES = vc.load_prices()
DAY = datetime.date(2026, 11, 15)
M = "gemini-3.5-flash-lite"
TTS = "gemini-3.8-flash-tts"


def line(time: str, tag: str, msg: str) -> str:
    return f"11-15 {time}.000  4242  4300 I {tag}: {msg}"


CLOUD = [
    line("10:00:00", "LIVE_AUDIT", f"session_start model=cloud+{M} async_set=none pack=1/remote"),
    line("10:00:01", "LIVE_LATENCY", f"gemini_usage kind=cache_create model={M} usage=present prompt=12000 prompt_text=12000 prompt_audio=0 cached=0 out=0 out_text=0 out_audio=0 thoughts=0 audio_s=0.00 chars=40000 discarded=false ttl_s=3600"),
    line("10:00:03", "LIVE_LATENCY", f"gemini_usage kind=stt model={M} usage=present prompt=420 prompt_text=100 prompt_audio=320 cached=0 out=12 out_text=12 out_audio=0 thoughts=0 audio_s=10.00 chars=0 discarded=false"),
    line("10:00:04", "LIVE_LATENCY", f"gemini_usage kind=llm model={M} usage=present prompt=13000 prompt_text=13000 prompt_audio=0 cached=12000 out=40 out_text=40 out_audio=0 thoughts=0 audio_s=0.00 chars=0 discarded=false"),
    line("10:00:05", "LIVE_LATENCY", f"gemini_usage kind=tts model={TTS} usage=present prompt=10 prompt_text=10 prompt_audio=0 cached=0 out=250 out_text=0 out_audio=250 thoughts=0 audio_s=10.00 chars=40 discarded=false"),
    line("10:00:06", "LIVE_LATENCY", f"gemini_usage kind=tts model={TTS} usage=absent prompt=0 prompt_text=0 prompt_audio=0 cached=0 out=0 out_text=0 out_audio=0 thoughts=0 audio_s=2.00 chars=30 discarded=true"),
    line("10:00:07", "LIVE_LATENCY", f"gemini_usage kind=stt model={M} usage=absent prompt=0 prompt_text=0 prompt_audio=0 cached=0 out=0 out_text=0 out_audio=0 thoughts=0 audio_s=3.00 chars=0 discarded=true failed=true"),
    line("10:00:08", "LIVE_LATENCY", f"llm_usage model={M} prompt=13000 cached=12000 out=40 thoughts=0 rounds=1 cache_rounds=1 inline_rounds=0"),
    line("10:00:09", "LIVE_LATENCY", "cascade_usage_summary engine=laptop>cloud turns=1 cache_create=x cache_tokens=12000 cache_ttl_s=3600 | llm/x calls=1"),
    line("10:00:10", "LIVE_AUDIT", 'session_end reason="stopped"'),
]


def test_prices_are_decimal_with_date_and_source():
    assert all(isinstance(p.usd_per_mtok, Decimal) for p in PRICES)
    assert all(p.read_on and p.source.startswith("https://") for p in PRICES)


def test_rate_follows_dates_and_falls_back_to_the_general_kind():
    assert vc.rate(PRICES, TTS, "output_audio", datetime.date(2026, 12, 31)) == Decimal("9.00")
    assert vc.rate(PRICES, TTS, "output_audio", datetime.date(2027, 1, 1)) == Decimal("18.00")
    # flash-lite has one input rate for text and audio
    assert vc.rate(PRICES, M, "input_audio", DAY) == Decimal("0.30")
    assert vc.rate(PRICES, "gemini-9-unknown", "input", DAY) is None


def test_each_call_kind_is_priced_exactly():
    convs = vc.parse(CLOUD, 2026)
    assert len(convs) == 1
    c = convs[0]
    assert c.engine == "laptop>cloud" and c.model == M and c.turns == 1
    priced = {(x.kind, x.discarded): vc.price_call(x, PRICES, DAY) for x in c.calls}
    # llm: 1000 uncached x 0.30 + 12000 cached x 0.03 + 40 out x 2.50, per million
    assert priced[("llm", False)].usd == Decimal("0.000300") + Decimal("0.000360") + Decimal("0.000100")
    # stt: 100 text + 320 audio at 0.30, 12 out at 2.50
    assert priced[("stt", False)].usd == Decimal(420) * Decimal("0.30") / vc.MILLION + Decimal(12) * Decimal("2.50") / vc.MILLION
    # tts: 10 in x 0.50 + 250 audio out x 9.00
    assert priced[("tts", False)].usd == Decimal(10) * Decimal("0.50") / vc.MILLION + Decimal(250) * Decimal("9.00") / vc.MILLION
    # discarded tts with no counts: derived from 2 s x 25 tokens/s at the audio rate
    d = priced[("tts", True)]
    assert d.derived and d.usd == Decimal(50) * Decimal("9.00") / vc.MILLION
    # a failed call is not billed
    assert priced[("stt", True)].usd == 0
    # cache: creation at the input rate + one hour of storage
    cache = priced[("cache_create", False)]
    assert cache.usd == Decimal(12000) * Decimal("0.30") / vc.MILLION + Decimal(12000) * Decimal("1.00") / vc.MILLION


def test_old_per_turn_lines_are_not_counted_twice():
    bill = vc.price_conversation(vc.parse(CLOUD, 2026)[0], PRICES)
    llm_once = Decimal("0.000760")
    assert bill.by_kind["llm"] == llm_once
    old_build = [l for l in CLOUD if "gemini_usage kind=llm" not in l]
    old_bill = vc.price_conversation(vc.parse(old_build, 2026)[0], PRICES)
    assert old_bill.by_kind["llm"] == llm_once  # priced from llm_usage instead


def test_live_usage_lines_are_priced_like_for_like():
    live = [
        line("09:00:00", "LIVE_AUDIT", "session_start model=gemini-3.8-live async_set=none pack=1/remote"),
        line("09:00:05", "LIVE_AUDIT", "usage prompt=14000 prompt_text=11000 prompt_audio=200 reply=60 reply_audio=50 thoughts=100 total=14060"),
        line("09:00:09", "LIVE_AUDIT", 'session_end reason="stopped"'),
    ]
    bill = vc.price_conversation(vc.parse(live, 2026)[0], PRICES)
    expected = (
        Decimal(11000 + 2800) * Decimal("0.75")
        + Decimal(200) * Decimal("3.00")
        + Decimal(10 + 100) * Decimal("4.50")
        + Decimal(50) * Decimal("12.00")
    ) / vc.MILLION
    assert bill.by_kind["live"] == expected
    assert bill.conv.engine == "live"


def test_live_repeat_reports_in_one_generation_are_folded_not_added():
    live = [
        line("09:00:00", "LIVE_AUDIT", "session_start model=gemini-3.8-live"),
        line("09:00:05", "LIVE_AUDIT", "usage prompt=1000 prompt_text=1000 reply=10 reply_audio=10"),
        line("09:00:05", "LIVE_AUDIT", "usage prompt=1000 prompt_text=1000 reply=20 reply_audio=20"),
        line("09:00:06", "LIVE_AUDIT", "turn_complete"),
        line("09:00:09", "LIVE_AUDIT", "usage prompt=1000 prompt_text=1000 reply=5 reply_audio=5"),
        line("09:00:10", "LIVE_AUDIT", 'session_end reason="stopped"'),
    ]
    calls = vc.parse(live, 2026)[0].calls
    assert len(calls) == 2  # one folded generation, then a new one after turn_complete
    assert calls[0].counts["reply_audio"] == 20


def test_an_unknown_model_is_said_not_guessed():
    lines = [
        line("11:00:00", "LIVE_AUDIT", "session_start model=cloud+gemini-9-unknown"),
        line("11:00:01", "LIVE_LATENCY", "gemini_usage kind=llm model=gemini-9-unknown usage=present prompt=5 cached=0 out=1 thoughts=0 discarded=false"),
        line("11:00:02", "LIVE_AUDIT", 'session_end reason="stopped"'),
    ]
    out = vc.report(lines, 2026, PRICES)
    assert any("no price on file" in o for o in out)
    assert any("total=$0.00000" in o for o in out)


def test_the_report_says_it_is_an_estimate_and_totals_per_day():
    out = vc.report(CLOUD, 2026, PRICES)
    assert out[0].startswith("ESTIMATE")
    assert any(o.startswith("day 2026-11-15: 1 conversations") for o in out)
    assert any(o.startswith("window: 1 conversations") for o in out)
    assert any("discarded tts=" in o for o in out)
