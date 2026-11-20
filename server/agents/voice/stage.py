"""The speech stage's per-turn record, for every voice door: speech-to-text with its timings, the
speech made, its spend on the member's cap, and the timing line's numbers.

Moved from the web door (`assistant/cascade.py`, ADR 0102). Names and numbers only: nothing here
keeps a word of what was said or heard.
"""

from __future__ import annotations

import logging

from django.db import DatabaseError, transaction

from household.tenancy import household_of
from llm import cap as member_cap
from llm import client as llm_client

log = logging.getLogger(__name__)

# The timing line's keys, in order (the web door's `cascade_turn` line, the agents door's
# `agents_voice_turn` line). A key a turn did not reach is written -1.
TIMING_KEYS = (
    "audio_ms",
    "stt_ms",
    "stt_prompt_tokens",
    "stt_output_tokens",
    "rounds",
    "tools",
    "model_ms",
    "prompt_tokens",
    "cached_tokens",
    "output_tokens",
    "first_sentence_ms",
    "sentences",
    "verbatim",
    "money_dropped",
    "unbacked",
    "filler",
    "tts_first_audio_ms",
    "tts_ms",
    "tts_audio_ms",
    "tts_prompt_tokens",
    "tts_output_tokens",
    "tts_failed",
    "turn_ms",
    "ack",
    "filler_ms",
    "first_sound_ms",
)


def timing_values(timings: dict) -> str:
    """`key=value` for every `TIMING_KEYS` entry, -1 for one the turn did not reach."""
    return " ".join(f"{k}={-1 if timings.get(k) is None else timings[k]}" for k in TIMING_KEYS)


def transcribe(speech, pcm: bytes, timings: dict):
    """`speech.transcribe(pcm)`, noting how many requests it sent beyond the first (a retry),
    which the turn's reservation holds one call for (round 3, finding 8 leftovers), and the
    transcription's own numbers once it answered."""
    try:
        heard = speech.transcribe(pcm)
    finally:
        timings["stt_retries"] = max(getattr(speech, "stt_sent", 1) - 1, 0)
    timings.update(
        stt_ms=heard.ms,
        audio_ms=heard.audio_ms,
        stt_prompt_tokens=heard.prompt_tokens,
        stt_output_tokens=heard.output_tokens,
    )
    return heard


def record_speech(timings: dict, speaker) -> None:
    """What the turn's `Speaker` made: its first audio, time, audio length, tokens, failures."""
    timings.update(
        tts_first_audio_ms=speaker.first_audio_ms,
        first_sound_ms=speaker.first_sound_ms,
        tts_ms=speaker.total_ms,
        tts_audio_ms=sum(s.audio_ms for s in speaker.done),
        tts_prompt_tokens=sum(s.prompt_tokens for s in speaker.done),
        tts_output_tokens=sum(s.output_tokens for s in speaker.done),
        tts_failed=sum(1 for s in speaker.done if s.failed),
    )


def meter_speech(request, timings: dict, feature: str) -> None:
    """A member's speech-to-text and text-to-speech as `llm_usage` rows too (finding 12), so their
    daily cap sees all of a spoken turn. Estimates, at `tools/voice_prices.json`'s prices."""
    if not member_cap.applies_to(request.user):
        return

    def g(key: str) -> int:
        value = timings.get(key)
        return value if isinstance(value, int) and value > 0 else 0

    parts = (
        ("stt", g("stt_prompt_tokens"), g("stt_output_tokens"), PRICE_TEXT_IN, PRICE_TEXT_OUT),
        ("tts", g("tts_prompt_tokens"), g("tts_output_tokens"), PRICE_TTS_IN, PRICE_TTS_OUT),
    )
    try:
        with transaction.atomic():
            for model, prompt, out, price_in, price_out in parts:
                if not (prompt or out):
                    continue
                llm_client.record(
                    household=household_of(request),
                    feature=feature,
                    model=model,
                    user=request.user,
                    usage={
                        "prompt_tokens": prompt,
                        "output_tokens": out,
                        "total_tokens": prompt + out,
                    },
                    cost_estimate_micro_usd=int(prompt * price_in + out * price_out) + 1,
                )
    except DatabaseError:
        log.warning("assistant cascade: speech meter rows failed", exc_info=True)


# USD per million tokens, from `tools/voice_prices.json` (read 2026-11-15). ESTIMATES: what the
# live strip shows and what the reports quote, never a bill.
PRICE_TEXT_IN = 0.30
PRICE_TEXT_CACHED = 0.03
PRICE_TEXT_OUT = 2.50
PRICE_TTS_IN = 0.50
PRICE_TTS_OUT = 9.00


def turn_cost_usd(timings: dict, spoken: list | None = None) -> float:
    """A turn's estimated cost: speech-to-text, the text rounds (cached input at the cached
    rate) and the speech made. `spoken` is the speaker's per-sentence stats, when the turn has not
    yet folded them into `timings`."""

    def g(key: str) -> int:
        value = timings.get(key)
        return value if isinstance(value, int) and value > 0 else 0

    tts_in = sum(s.prompt_tokens for s in spoken) if spoken is not None else g("tts_prompt_tokens")
    tts_out = sum(s.output_tokens for s in spoken) if spoken is not None else g("tts_output_tokens")
    cached = min(g("cached_tokens"), g("prompt_tokens"))
    usd = (
        (g("prompt_tokens") - cached + g("stt_prompt_tokens")) * PRICE_TEXT_IN
        + cached * PRICE_TEXT_CACHED
        + (g("output_tokens") + g("stt_output_tokens")) * PRICE_TEXT_OUT
        + tts_in * PRICE_TTS_IN
        + tts_out * PRICE_TTS_OUT
    )
    return usd / 1_000_000
