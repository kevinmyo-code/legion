"""The engine's gate on a proposed open loop (ADR 0085). **The helper proposes, the engine decides**
(CLAUDE.md section 4's posture, the same as `member_memory.review.judge`): every check below is
code, none depends on the prompt.

A proposal from the helper is `{"text", "evidence": [W labels], "reason"}`. It passes when:

1. `text` is short, plain and **made only of words he said** in the cited records (his own words
   from clean turns, recomputed here from the stored records, never trusted from the laptop). This
   is the anchor (compulsion test clause a): the proposal is his words, not the model's reading of
   them. It is also what makes the dedupe key hold across nights (rule 4).
2. It carries **no identity or bank number** (`member_memory.sensitive.looks_sensitive`, the
   vault's filter) and **nothing about money** (`names_money`: an amount, a currency, or a word
   that moves money). Deliberately broad: a money task he wants tracked he can still say as
   "remind me".
3. Its **date is only one his words print**: exactly one calendar date in `text` itself (ISO, or
   a month and day), not before today. A relative day ("Friday", "tomorrow") or a date that has
   passed leaves the proposal undated. The helper never sends a date.
4. **Answered once is answered for good.** `dedupe_key` is the content words, lower-cased, minus a
   short list of function words, sorted and hashed. A row with that key that he tracked or
   dismissed refuses it; a pending one makes it a duplicate; an expired one is offered again only
   from a new conversation (the ledger keeps an old one from being read twice).
5. It is **not already on his list**: no open reminder or task (`public.events`) and no unticked
   checklist line he can see carries the same key.

The `reason` is kept only as a paraphrase: one that repeats six or more of his words in a row is
dropped (the proposal stays). Nothing here logs; refusals are words about the proposal, never a
quote.
"""

from __future__ import annotations

import datetime
import hashlib
import re

from member_memory.review import printed_dates
from member_memory.sensitive import looks_sensitive
from open_loops.models import REASON_MAX_CHARS, TITLE_MAX_CHARS

_WORD = re.compile(r"[a-z0-9]+")

# Function words left out of the key, so "Call the dentist" and "call dentist" are one loop. Short
# and closed on purpose: a content word is never dropped.
STOPWORDS = frozenset(
    {
        "a", "an", "the", "to", "my", "our", "his", "her", "their", "your", "for", "of", "on",
        "in", "at", "and", "i", "me", "need", "needs", "should", "gotta", "have", "has", "got",
        "must", "will", "ill", "im", "be", "go", "going", "want", "wanna", "really", "just",
        "please", "some", "this", "that", "it", "by", "up",
    }
)

_MONEY = re.compile(
    r"[$£€¥₹]"
    r"|\b\d+[.,]\d{2}\b"
    r"|\b(usd|sgd|dollars?|bucks|cents?|euros?)\b"
    r"|\b(pay|pays|paid|paying|payment|payments|transfer|wire|deposit|withdraw|invest|investing"
    r"|loan|loans|mortgage|rent|invoice|bill|bills|venmo|zelle|paypal|bank|refund|reimburse"
    r"|reimbursement|tax|taxes|budget|savings|credit\s+card|debit\s+card|401k|ira|stocks?)\b",
    re.IGNORECASE,
)

VERBATIM_RUN = 6


def tokens(text: str) -> list[str]:
    return _WORD.findall((text or "").lower())


def content_words(text: str) -> list[str]:
    return [w for w in tokens(text) if w not in STOPWORDS]


def dedupe_key(text: str) -> str:
    words = sorted(set(content_words(text)))
    return hashlib.sha256(" ".join(words).encode()).hexdigest()[:64]


def names_money(text: str) -> bool:
    return bool(_MONEY.search(text or ""))


def stated_date(text: str, today: datetime.date) -> datetime.date | None:
    """The one calendar date `text` prints, if it is today or later; otherwise None."""
    found = sorted(set(printed_dates(text or "", today)))
    if len(found) != 1 or found[0] < today:
        return None
    return found[0]


def quotes(reason: str, heard: str, run: int = VERBATIM_RUN) -> bool:
    """True when `reason` repeats `run` or more of his words in a row."""
    said = tokens(heard)
    if len(said) < run:
        return False
    grams = {tuple(said[i : i + run]) for i in range(len(said) - run + 1)}
    words = tokens(reason)
    return any(tuple(words[i : i + run]) in grams for i in range(len(words) - run + 1))


def clean(value) -> str | None:
    if not isinstance(value, str):
        return None
    text = " ".join(value.split()).strip(" .")
    return text or None


def check_text(text: str | None, heard: str) -> str:
    """Why `text` may not be proposed against `heard` (his cited words), or "" when it may."""
    if not text:
        return "text is missing."
    if len(text) > TITLE_MAX_CHARS:
        return f"text is over {TITLE_MAX_CHARS} characters."
    if not content_words(text):
        return "text names no task."
    if looks_sensitive(text):
        return "text looked like an identity or bank number."
    if names_money(text):
        return "text is about money; money is never proposed."
    if not heard:
        return "it cites none of his own words from this context."
    missing = set(tokens(text)) - set(tokens(heard))
    if missing:
        return f"text adds {len(missing)} word(s) he did not say in the cited conversation."
    return ""


def keep_reason(reason, heard: str) -> tuple[str, str]:
    """(reason to store, note). A reason that quotes him or looks sensitive is dropped."""
    text = clean(reason) or ""
    if not text:
        return "", ""
    if len(text) > REASON_MAX_CHARS:
        text = text[: REASON_MAX_CHARS - 1] + "…"
    if looks_sensitive(text) or names_money(text):
        return "", "reason dropped: it named a number or money."
    if quotes(text, heard):
        return "", "reason dropped: it quoted him."
    return text, ""
