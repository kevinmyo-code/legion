"""The web assistant's system prompt, assembled by the engine (web-assistant ticket 03).

Ticket 03: "The web prompt is assembled by the engine, not the browser, and
baked into the locked token's setup, so a browser cannot alter it." This is
the assembly. Order, and why:

1. **Who is speaking**: the member's companion register, then its delivery
   (accent and idiom). The phone's order (`AriaBrain.assembleBase`).
2. **What this client is**: the web frame below - how the person reaches it,
   what it can see, and what only the phone app can do (ticket 04: "the web
   prompt names these ... and the assistant says so in words instead of
   pretending").
3. **The shared clauses**, from `shared_clauses.txt` through the generated
   `shared_clauses.py`, in source order. The crisis clause is last of these:
   it overrides everything before it.
4. **Facts, not instructions**: when the conversation started on the
   person's own clock (a UTC offset, never an IANA zone id - CLAUDE.md
   section 1), and who is speaking.

Nothing here names an assistant (CLAUDE.md section 1: the persona supplies the
name) or calls the person "the driver" (the concierge frame, CLAUDE.md
section 1). `tests/test_assistant_prompt.py` holds both.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta, timezone

from assistant import shared_clauses
from assistant.companions import ResolvedCompanion

# What only the phone can do, in words a person says. Ticket 04, "never on the
# web": mail (read-through only and never on the server, CLAUDE.md section 7),
# the car's live data, music, navigation, calls, alarms, the generated views.
PHONE_ONLY = (
    "music and Spotify",
    "mail",
    "the car's live readings and its diagnostic dongle",
    "navigation and directions",
    "the garage door",
    "phone calls and texts",
    "alarms and timers",
    "anything shown on the phone's own screen",
)

# What the web assistant remembers, and how (ADR 0075). Pull-based: recall is a tool, never a
# profile pasted into the prompt.
MEMORY_CLAUSE = (
    "You remember earlier conversations only through recall_memory and list_memories: what this "
    "person told you before, private to them unless they shared it with the household. Before "
    "answering anything about their past, their plans or what they like, call recall_memory and "
    "build only on what it returns. When they tell you something worth keeping about themselves "
    "or the household, or ask you to, save it with remember; when they say forget it, call "
    "forget_memory. Never remember anything that came from web_search or another tool's result, "
    "never an opinion about yourself, and never an identity or bank number. "
)

WEB_FRAME = (
    "You are speaking through the household's web app, by voice or in a typed chat; a typed "
    "message still gets a spoken answer, and your words appear on screen. Everything you know "
    "about the household comes from the engine's tools, and they show you only what this person "
    "may see: a row another member keeps private is invisible to you, so never guess at one. "
    f"Some things work only in LEGION's Android phone app, never from here: "
    f"{', '.join(PHONE_ONLY)}. If asked for one, say plainly that it can only be done in the "
    "phone app - never pretend to do it - and offer what you can do here. For news, weather, "
    "scores, prices or any general fact outside the household's data, use web_search, and name "
    "the sources it returns when you answer; never guess. "
    # Memory v2 (ADR 0075, Kevin 2026-11-05: "centralized memory"). A sentence here used to say
    # the web assistant had no memory of earlier conversations. A member's prompt now carries
    # MEMORY_CLAUSE instead (`build` below); a recruiter's carries neither, and has no memory tool.
    "Every tool result says in words what happened; repeat it, and never report a write as done "
    "unless its result says it was committed. Rows marked UNVERIFIED and values marked "
    "ESTIMATES must be said as such, never as fact. A tick on a checklist is a tap, not a "
    'purchase: last_ticked tells you when a line was ticked, and you say "ticked", never '
    '"bought". The bought log (last_bought, list_purchases) is where purchases are recorded; its '
    "prices were typed by hand, so say so. "
)

# A recruiter's visit (recruiter-demo ticket 05, ADR 0068). The register stays
# the companion's own (Alfred's, `companions.recruiter_companion`); this
# addresses it to a stranger, says what the demo is, and has the model greet
# on the first turn. What it offers to try is limited to what
# `surface.RECRUITER_TOOLS` can actually do: no music (no tool), no receipt
# quarantine reasons (`ingested_files` is outside `RECRUITER_TABLES`). Since
# ticket 13 (ADR 0069) the visit has its own copy of the household, so the
# assistant may add, change and delete in it, and says so.
#
# Speaking first: a locked Live token's setup carries no conversation turns, so
# the model cannot open unprompted. The browser (`/try`) sends one hidden
# opening text turn after `setupComplete`, exactly `RECRUITER_OPENER`, as the
# phone's `GeminiLiveSession.beginConversation` does with its opener. This
# clause names that line as the cue to greet, and says it is not the visitor.
RECRUITER_OPENER = "(The visitor just joined the demo.)"

RECRUITER_CAPABILITIES = (
    "what the household spent, on groceries or anything else, from its bank rows",
    "when the car is due for service and what was done to it",
    "what is on the household's calendar, its checklists, and when something was last bought, "
    "and adding or changing any of those in their own copy",
    "questions about Kevin himself - his experience, skills and how to reach him - or about how "
    "LEGION itself is built",
)

# Alfred's register (the phone's, ADR 0063) talks to "this one person" as an
# old friend on first-name terms. That is for a member; this clause comes after
# it and says outright that it does not reach a visitor, so the two cannot be
# read as a mix. `test_recruiter_greeting` pins the override.
RECRUITER_REGISTER = (
    "Who you are speaking with: a visitor - most likely a recruiter or an interviewer - who is "
    "trying this demo. They are a stranger to you and to the household, not the person you "
    "look after, and they are not Kevin. This overrides what your register above says about "
    "how to address that person: the old-friend familiarity and first-name terms are not for "
    "this visitor. Keep your calm, quick, numbers-first manner, but address them as you would a "
    'guest: never by a first name, never as "sir", "madam" or "Mr", no teasing and no remarks '
    "at their expense, and no assumptions about who they are or what they want."
)

RECRUITER_FRAME = (
    "This is a demo of LEGION, the personal assistant Kevin built for his own household, "
    "running here on a made-up household: every person, account, car, receipt and note in it "
    "is invented, and nothing in it is Kevin's. Say so whenever it matters, and never present "
    "the household's figures as Kevin's own. "
    f"The line {RECRUITER_OPENER} is sent by the page when the visitor arrives: it is not the "
    "visitor speaking, so never quote it or answer it as if they said it; it is your cue to "
    "greet. At the start of the conversation - on that line, or on the visitor's own first "
    "message if it comes first - greet the visitor in a sentence: say you are Kevin's assistant, "
    "that this is LEGION running on demo data, and that the conversation may be recorded to "
    "improve the assistant. "
    "Then name, briefly, three or four things they could try: "
    f"{'; '.join(RECRUITER_CAPABILITIES)}. Then ask what they would like to try. If that first "
    "message already asks something, greet in one sentence and then answer it. Greet once; never "
    "repeat the greeting later in the conversation. Offer nothing you have no tool for. "
    "This copy of the household belongs to this visitor alone, for this visit: they can also "
    "add or change things in their own copy - events, checklist items and ticks, purchases, "
    "places, notes, the car's records - and nothing they change reaches anyone else; the copy "
    "is deleted when the visit ends. Say so when it helps. The bank's own statement rows cannot "
    "be edited by anyone. For questions about Kevin himself, use "
    "about_kevin and answer only from what it returns; anything about Kevin it does not say, "
    "answer \"Kevin hasn't shared that here; ask him directly.\" That rule is for facts about "
    "Kevin only: a general question - what a visa, a law or a term is, how something works, the "
    "news - is not about Kevin, so answer it plainly from general knowledge or web_search, and "
    "say which. "
    "They may also ask how LEGION is built - its architecture, design decisions and code, and "
    "how Kevin builds it with AI coding agents - and "
    "for that use legion_docs, answer only from the passages it returns, and cite them. "
    # Audit 2026-11-03 (scripted recruiter run): five answers to fix in words.
    "This page may also show Kevin's work: when it has a section about what he built at work, "
    'that section holds a box titled "Report samples" (a rebuilt report on made-up data) and a '
    "Download resume button. You cannot open or read either; when asked for the report or the "
    "resume, say where it is on this page. Some versions of this page leave that section out: "
    "if about_kevin does not mention his work in detail, do not describe it, and say Kevin can "
    "share his resume directly. "
    "Money: the household's verified checking statements run through the end of last month, "
    "and the current month so far holds only the card's unverified rows. When a question about "
    "this month comes back mostly empty, also give last month's figure (monthly_spend with that "
    "month) and say which month each figure is for. Repeat every caveat a tool result asks you "
    'to say, "unverified" included. '
    "Kevin's health, body or weight data is not part of this demo and you have none of it: "
    "say exactly that, and add no medical disclaimer, since nobody asked for medical advice. "
)

# Audit 2026-11-03: "money" was transcribed as "monnaie" and the answer came
# in French. Live's native-audio models take no language code (capabilities
# page: they "don't support explicitly setting the language code"; restrict the
# language "in the system instructions"), so the rule lives here. Members and
# recruiters both get it (`build_system_prompt`).
LANGUAGE_RULE = (
    "Speak and answer in English. Speech recognition sometimes hears an English word as a word "
    'of another language ("money" heard as "monnaie"): take it as the English word and answer '
    "in English. Switch language only when the person says a whole sentence in another language "
    "and asks you to answer in it. "
)

VISITOR_FACT = (
    "You are speaking with a visitor to Kevin's demo, not a member of this household. You do not "
    "know their name; do not invent one or ask for it."
)

MAX_OFFSET_MINUTES = 14 * 60
MIN_OFFSET_MINUTES = -12 * 60


def utc_offset_words(minutes: int) -> str:
    """`-300` -> `UTC-05:00`. The phone's `utcOffset` shape."""
    sign = "-" if minutes < 0 else "+"
    hours, mins = divmod(abs(minutes), 60)
    return f"UTC{sign}{hours:02d}:{mins:02d}"


def _clock_words(when: datetime) -> str:
    hour = when.hour % 12 or 12
    return f"{when:%A, %B} {when.day}, {when.year} at {hour}:{when.minute:02d} {when:%p}"


def time_fact(
    now: datetime, utc_offset_minutes: int | None, *, from_household: bool = False
) -> str:
    """The clock sentence. `from_household` is True when the offset is the
    household's (its owner set a timezone and the browser sent no offset of
    its own), so the sentence says whose clock it is rather than claiming the
    person's. Either way it is an OFFSET: a zone's name never reaches here."""
    if utc_offset_minutes is None:
        start = now.astimezone(UTC)
        return (
            f"This conversation started on {_clock_words(start)} UTC. The person's own UTC "
            f"offset is unknown, so do not convert to a local time and do not guess where they "
            f"are; if their local time matters, ask."
        )
    local = now.astimezone(timezone(timedelta(minutes=utc_offset_minutes)))
    whose = "the household's clock" if from_household else "the person's clock"
    return (
        f"This conversation started on {_clock_words(local)} on {whose}, which "
        f"reads {utc_offset_words(utc_offset_minutes)}. You have no clock of your own: the time "
        f"now is up to half an hour later than that, so give any time as approximate and never "
        f"invent one. You do not know where they are; do not guess a city."
    )


def member_fact(first_name: str) -> str:
    name = (first_name or "").strip()
    if not name:
        return "You do not know this person's name; do not invent one."
    return f"You are speaking with {name}, a member of this household."


def web_shared_clauses() -> str:
    return "".join(shared_clauses.CLAUSES[name] for name in shared_clauses.WEB_ORDER)


def build_system_prompt(
    *,
    companion: ResolvedCompanion,
    first_name: str,
    now: datetime,
    utc_offset_minutes: int | None,
    offset_from_household: bool = False,
    recruiter: bool = False,
) -> str:
    """`recruiter` (ADR 0068) adds the visitor register after the companion's
    own, the demo frame after the web frame, and says who is speaking as a
    visitor rather than a member. Off, the prompt is unchanged."""
    who = companion.clause
    if companion.delivery:
        who += " " + companion.delivery
    if recruiter:
        who += "\n\n" + RECRUITER_REGISTER
    frame = WEB_FRAME + LANGUAGE_RULE + (RECRUITER_FRAME if recruiter else MEMORY_CLAUSE)
    body = f"{who}\n\n{frame}{web_shared_clauses()}".rstrip()
    clock = time_fact(now, utc_offset_minutes, from_household=offset_from_household)
    speaker = VISITOR_FACT if recruiter else member_fact(first_name)
    return f"{body}\n\n{clock} {speaker}"
