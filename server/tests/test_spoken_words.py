"""Speech wording (2026-11-18, the demo's stage 2; cut to two rules by blind review round 7):
a model sentence's backticked file span and its http(s) links are not read aloud, and "ADR 0057"
is said "ADR 57". Nothing else is replaced, and a line code wrote (his profile, a figure) is never
touched. Speech only: the caption keeps the words as written. Invented examples only."""

from __future__ import annotations

import pytest

from assistant import cascade
from assistant.spoken_words import FILE, LINK, speakable_words


@pytest.mark.parametrize(
    ("written", "spoken"),
    [
        # (a) backticks: a slash and an extension, or a path of three or more segments.
        ("See `server/assistant/cascade.py` for the loop.", f"See {FILE} for the loop."),
        ("Its rules are in `server/assistant/rules`.", f"Its rules are in {FILE}."),
        ("Call `run_loop` once per turn.", "Call run_loop once per turn."),
        ("Use `cascade.py` for it.", "Use cascade.py for it."),
        # (b) a link that starts with http:// or https://.
        ("Read https://example.com/docs/page?x=1 for more.", f"Read {LINK} for more."),
        ("Read `https://example.com/a/b/c` now.", f"Read {LINK} now."),
        # ADR numbers lose their filing zeros.
        ("ADR 0057 says a bank feed is fact.", "ADR 57 says a bank feed is fact."),
        ("ADR 0102 and ADR 0103 cover it.", "ADR 102 and ADR 103 cover it."),
        ("ADR-0090 guards writes.", "ADR 90 guards writes."),
        # Nothing outside backticks is replaced (blind review round 7's examples).
        (
            "Skills: React, Node.js, Next.js and Vue.js.",
            "Skills: React, Node.js, Next.js and Vue.js.",
        ),
        ("Python/SQL/Django daily.", "Python/SQL/Django daily."),
        ("Open to AI/ML/data roles.", "Open to AI/ML/data roles."),
        ("Portfolio: github.com/pat/legion", "Portfolio: github.com/pat/legion"),
        ("Paid on 11/18/2026 for groceries.", "Paid on 11/18/2026 for groceries."),
        ("It lives in docs/adr/0057-plaid.md now.", "It lives in docs/adr/0057-plaid.md now."),
        ("Go to www.example.com.", "Go to www.example.com."),
        ("It spent $1,234.50 on 3/4 of the trips.", "It spent $1,234.50 on 3/4 of the trips."),
        ("Write to pat@example.invalid today.", "Write to pat@example.invalid today."),
        ("Version 1.2 shipped and/or slipped.", "Version 1.2 shipped and/or slipped."),
        ("", ""),
    ],
)
def test_speech_rewords_only_backticked_files_and_links(written, spoken):
    assert speakable_words(written) == spoken


def test_the_speaker_rewords_a_model_sentence_and_never_a_code_line():
    speaker = cascade.Speaker(None, "Puck", enabled=False)
    written = "ADR 0057 is in `docs/adr/0057-plaid.md`."
    assert speaker.spoken_text(written, model=True) == f"ADR 57 is in {FILE}."
    # A line code wrote is spoken exactly as written, links, slashes, backticks and all.
    profile = "Portfolio: https://example.invalid/pat, Python/SQL/Django, ADR 0057 `x/y/z`."
    assert speaker.spoken_text(profile) == profile
