"""Speech wording (2026-11-18, the demo's stage 2): no backticks, file paths or URLs are read aloud,
and "ADR 0057" is said "ADR 57". Speech only: the caption keeps the words as written. Invented
examples only."""

from __future__ import annotations

import pytest

from assistant import cascade
from assistant.spoken_words import FILE, LINK, speakable_words


@pytest.mark.parametrize(
    ("written", "spoken"),
    [
        ("See `server/assistant/cascade.py` for the loop.", f"See {FILE} for the loop."),
        ("It lives in docs/adr/0057-plaid.md now.", f"It lives in {FILE} now."),
        ("The loop is in cascade.py.", f"The loop is in {FILE}."),
        ("Read https://example.com/docs/page?x=1 for more.", f"Read {LINK} for more."),
        ("Go to www.example.com.", f"Go to {LINK}."),
        ("Call `run_loop` once per turn.", "Call run_loop once per turn."),
        ("ADR 0057 says a bank feed is fact.", "ADR 57 says a bank feed is fact."),
        ("ADR 0102 and ADR 0103 cover it.", "ADR 102 and ADR 103 cover it."),
        ("The rules are in server/assistant/rules now.", f"The rules are in {FILE} now."),
        ("ADR-0090 guards writes.", "ADR 90 guards writes."),
        # Ordinary words, figures and an email stay as they are.
        ("It spent $1,234.50 on 3/4 of the trips.", "It spent $1,234.50 on 3/4 of the trips."),
        ("Write to pat@example.invalid today.", "Write to pat@example.invalid today."),
        ("Version 1.2 shipped and/or slipped.", "Version 1.2 shipped and/or slipped."),
        ("", ""),
    ],
)
def test_speech_says_neither_paths_nor_links_nor_backticks(written, spoken):
    assert speakable_words(written) == spoken


def test_the_speaker_speaks_the_wording_and_the_caption_keeps_the_text():
    speaker = cascade.Speaker(None, "Puck", enabled=False)
    written = "ADR 0057 is in `docs/adr/0057-plaid.md`."
    assert speaker.spoken_text(written) == f"ADR 57 is in {FILE}."
