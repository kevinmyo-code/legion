"""The recruiter demo's daily Gemini budget (recruiter-demo ticket 04, map ruling 4).

One budget across ALL recruiter traffic, $5 a day by default, reset at UTC
midnight. Members are never counted and never blocked: every caller checks
`household.models.is_recruiter` first and touches nothing here otherwise.

## Reserve before acting

The browser talks to Google directly with the token it was minted, so the
engine never sees what a conversation actually cost. It counts in units it
controls instead, and RESERVES the worst case before acting: a Live mint
reserves its full `max_session_minutes()` at `cost_per_voice_minute`, and if
that would cross the cap no token is minted. Nothing is settled afterwards
(there is nothing to settle against), so the cap cannot be overshot; it can
only be under-used. A reservation is released only when Google refused the
mint, so an outage never burns the day.

The reservation is one conditional UPDATE (`... SET reserved = reserved + n
WHERE day = d AND reserved + n <= cap`). Postgres re-checks the WHERE against
the row as committed by whoever held its lock, so two requests at the edge
cannot both fit. `tests/test_demo_budget.py` races them.

## Voice and text

Kevin, 2026-11-02 (ruling 7): text outlives voice. Voice stops at the budget
minus `text_reserve`; text runs to the full budget.

**What that buys today, said plainly.** The web has no text-model route:
typed chat rides the same Live session as voice (`frontend/src/assistant/
live-client.ts` `send()`, and a typed-first session can turn its mic on
later). A mint cannot be told apart by use, and a text-only Live setup
(`responseModalities: ["TEXT"]`) was not adopted: it is untested against the
live model (`reasoned`, not tested: native-audio Live models have refused TEXT output), the browser
client renders audio and its transcription only, and the token would still
accept microphone audio, so "text only" would not be enforced. So every
recruiter Live mint, voice or typed, is checked against the VOICE cap, and
`charge_text_call` - built and tested - is wired to nothing until a text
route exists. Hence `LEGION_DEMO_TEXT_RESERVE_USD` defaults to 0 (Kevin,
2026-11-02): typed chat shares the Live session, so one cap applies to both.
Raise it the day a text-model route exists and `charge_text_call` is wired.

## The defaults, and why (`reasoned`, not a quoted price)

- `LEGION_DEMO_COST_PER_VOICE_MINUTE_USD` = 0.10. Live audio is billed as
  tokens at about 25 per second each way; at list prices of the order of $3
  per million audio tokens in and $12 out, a minute of raw audio is about two
  cents. Live also re-bills the conversation's context on every turn, bounded
  here by the 32,000-token compression trigger (`assistant/live.py`), which
  for a chatty session with tool results can add several cents a minute.
  Ten cents is the top of that range, rounded up.
- `LEGION_DEMO_MAX_SESSION_MINUTES` = 5 (Kevin, 2026-11-02: a recruiter's
  conversation lasts at most five minutes). It is both what a mint reserves
  and how long the recruiter's token lives (`assistant/session.py` sets the
  token's `expireTime` to mint + this). A token is single-use
  (`live.USES = 1`) and the setup is locked, so there is no resumption: a
  longer conversation is a new mint, which reserves again. Five minutes at
  ten cents is $0.50 a mint, ten conversations a day at the defaults.

  **What `expireTime` does and does not do** (API reference
  `AuthToken.expireTime`, quoted in `.scratch/web-assistant/research/
  01-live-from-an-iphone-pwa.md` section 1, `sourced`): after it, "messages
  in BidiGenerateContent sessions will be rejected (Gemini may preemptively
  close the session after this time)". So Google stops serving the session at
  five minutes, which is what bounds the cost; closing the socket is only a
  "may". The browser therefore also ends a recruiter's conversation itself at
  the token's `expires_at` and says why (`frontend/src/assistant/
  live-client.ts`, `sessionLimit`). Not tested against the live service.
- `LEGION_DEMO_COST_PER_TEXT_CALL_USD` = 0.01. A text-model call carrying
  this prompt and tool declarations is of the order of ten thousand tokens in
  and a few hundred out, a fraction of a cent at flash-class prices; one cent
  allows for a tool round trip or two.
- `LEGION_DEMO_COST_PER_SEARCH_USD` = 0.02 (audit 2026-11-03, `web_search`).
  Google Search grounding on Gemini 3 is listed at $14 per 1,000 search
  requests after 5,000 free a month shared across Gemini 3 and newer
  (`brief/news.py` quotes the pricing page), so $0.014 a search once the free
  tier is spent; the flash-lite call around it (a few hundred tokens in and
  out, plus the grounding tool's own prompt tokens) is a fraction of a cent.
  Two cents covers both, rounded up, and counts every search at the paid
  rate. Charged against the FULL budget, like a text call: a search is a
  tool call inside a conversation the voice cap already admitted, and
  refusing it on the voice cap would end a paid-for session's usefulness
  before the budget is spent. A search Google refused (HTTP error) is
  released; one that timed out is kept, since it may have been billed.
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from datetime import UTC, date, datetime
from decimal import Decimal, InvalidOperation

from django.conf import settings
from django.core.exceptions import ImproperlyConfigured
from django.db import IntegrityError, transaction
from django.db.models import F

from demo.models import DemoBudgetDay

BUDGET_USED_UP = "Today's demo budget is used up. Try again tomorrow."


MAX_SESSION_MINUTES_CEILING = 30  # a member's token lifetime (`live.TOKEN_LIFETIME`)


def max_session_minutes() -> int:
    """How long one recruiter conversation may last, in whole minutes: the
    token's lifetime and what each mint reserves."""
    raw = settings.LEGION_DEMO_MAX_SESSION_MINUTES
    try:
        minutes = int(str(raw).strip())
    except ValueError:
        minutes = 0
    if not 1 <= minutes <= MAX_SESSION_MINUTES_CEILING:
        raise ImproperlyConfigured(
            "LEGION_DEMO_MAX_SESSION_MINUTES must be a whole number of minutes from 1 to "
            f"{MAX_SESSION_MINUTES_CEILING}; got {raw!r}."
        )
    return minutes


def _cents(setting: str) -> int:
    """A USD setting as whole cents, rounded UP so a fraction never lets a
    reservation through that the stated price would not."""
    raw = getattr(settings, setting)
    try:
        value = Decimal(str(raw))
    except InvalidOperation:
        raise ImproperlyConfigured(
            f"{setting} must be an amount in USD, like 5 or 0.10; got {raw!r}."
        ) from None
    if value < 0 or not value.is_finite():
        raise ImproperlyConfigured(f"{setting} must be zero or more; got {raw!r}.")
    return math.ceil(value * 100)


@dataclass(frozen=True)
class Limits:
    budget_cents: int
    text_reserve_cents: int
    voice_minute_cents: int
    text_call_cents: int
    session_minutes: int
    search_cents: int = 0

    @property
    def voice_cap_cents(self) -> int:
        return max(self.budget_cents - self.text_reserve_cents, 0)

    @property
    def voice_mint_cents(self) -> int:
        return self.session_minutes * self.voice_minute_cents


def limits() -> Limits:
    return Limits(
        budget_cents=_cents("LEGION_DEMO_DAILY_BUDGET_USD"),
        text_reserve_cents=_cents("LEGION_DEMO_TEXT_RESERVE_USD"),
        voice_minute_cents=_cents("LEGION_DEMO_COST_PER_VOICE_MINUTE_USD"),
        text_call_cents=_cents("LEGION_DEMO_COST_PER_TEXT_CALL_USD"),
        session_minutes=max_session_minutes(),
        search_cents=_cents("LEGION_DEMO_COST_PER_SEARCH_USD"),
    )


def utc_today(now: datetime | None = None) -> date:
    return (now or datetime.now(UTC)).astimezone(UTC).date()


@dataclass(frozen=True)
class Reservation:
    day: date
    cents: int


def _ensure_day(day: date) -> None:
    if DemoBudgetDay.objects.filter(day=day).exists():
        return
    try:
        with transaction.atomic():
            DemoBudgetDay.objects.create(day=day)
    except IntegrityError:
        pass  # another request made it first; the row is what matters


def _reserve(cents: int, cap: int, counter: str, now: datetime | None) -> Reservation | None:
    """Add `cents` to today's reservations if the total stays within `cap`.
    One conditional UPDATE: it either fits and is recorded, or nothing
    changes. None means it did not fit."""
    day = utc_today(now)
    if cents > cap:
        return None
    _ensure_day(day)
    fitted = DemoBudgetDay.objects.filter(day=day, reserved_cents__lte=cap - cents).update(
        reserved_cents=F("reserved_cents") + cents, **{counter: F(counter) + 1}
    )
    return Reservation(day=day, cents=cents) if fitted else None


def reserve_voice_mint(now: datetime | None = None) -> Reservation | None:
    """Reserve one Live mint's worst case against the VOICE cap (budget less
    the text reserve). None: no token may be minted today."""
    lim = limits()
    return _reserve(lim.voice_mint_cents, lim.voice_cap_cents, "voice_mints", now)


def charge_text_call(now: datetime | None = None) -> Reservation | None:
    """Reserve one text-model call against the FULL budget. None: refuse it.
    Not wired: the web has no text-model route yet (see the module doc)."""
    lim = limits()
    return _reserve(lim.text_call_cents, lim.budget_cents, "text_calls", now)


def charge_web_search(now: datetime | None = None) -> Reservation | None:
    """Reserve one recruiter `web_search` against the FULL budget, before
    Google is asked. None: refuse it (`BUDGET_USED_UP`)."""
    lim = limits()
    return _reserve(lim.search_cents, lim.budget_cents, "web_searches", now)


def release(reservation: Reservation) -> None:
    """Give back a reservation whose action never happened (Google refused
    the mint). Never below zero."""
    DemoBudgetDay.objects.filter(
        day=reservation.day, reserved_cents__gte=reservation.cents
    ).update(reserved_cents=F("reserved_cents") - reservation.cents)
