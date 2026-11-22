""" "Let me get someone": a visitor leaves a name and a contact, and Kevin is told
(Kevin's 2026-11-22 ruling: a hand-off to Kevin; no real phone number).

**Behind `LEGION_DEMO_HANDOFF`, unset by default.** Off, the rep says no person can be reached
from the demo and the form route refuses; nothing is stored or sent. Read at call time, never at
import. Deliberately NOT in `deploy/cloudrun/flags.yaml` or the deploy pass-through list (the
`LEGION_JEEP_ASSISTANT` precedent): turning it on is a reviewed commit, on Kevin's word.

**The channel is the engine's existing Web Push** (`push.dispatch.send_now`, the path laptop-health
alerts take), to every owner of the engine's own household, kind `demo_callback`, which they can
turn off like any other kind. It reaches a browser that subscribed to the household app's
notifications; the LEGION phone app has no engine-to-phone notification path yet. What happened
is stored (`CallbackRequest.delivery`) and said in words: "Kevin has been told" only on `sent`.

**Strict caps.** One request per visit; one per address per UTC day (a keyed hash of the address,
never the address); `DAILY_CAP` across every visitor per UTC day, held by one conditional UPDATE
(`take_slot`), all counted before anything is stored or sent. A tester visit counts too. The form
route also has its own per-address throttle and a honeypot field, and every field is refused with
a link or a hidden control character in it.

**Nothing is dialled.** The notification carries the visitor's name, contact, their own note and a
summary code builds from the visit's orders. No model words reach it, and nothing of it ever
enters Kevin's assistant's context.
"""

from __future__ import annotations

import os
import re
from dataclasses import dataclass

from django.db import IntegrityError, transaction
from django.db.models import F
from django.utils import timezone
from django.utils.crypto import salted_hmac

from demo.models import CallbackRequest, DemoCallbackDay, ShopOrder
from demo.shop import catalogue, scenario

ENV = "LEGION_DEMO_HANDOFF"
DAILY_CAP = 3

OFF = "off"
DAY_FULL = "day_full"
ALREADY = "already"
AVAILABLE = "available"
NO_VISIT = "no_visit"

SENT = "sent"
SAVED = "saved"

_NAME = re.compile(r"^[^\W\d_][\w .'-]{0,59}$", re.UNICODE)
_EMAIL = re.compile(
    r"^[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9-]{1,63}(\.[A-Za-z0-9-]{1,63})*\.[A-Za-z]{2,24}$"
)
_PHONE = re.compile(r"^\+?[0-9][0-9 ()\-.]{6,21}$")
# Anything link-like, in any field: a scheme, "www.", "//", a path after a dot-name, or a bare
# domain. An email address is checked by its own pattern instead.
_LINK = re.compile(
    r"([a-z][a-z0-9+.-]*:/|www\.|//|\.[a-z]{2,24}/|\b[a-z0-9-]+\.(com|net|org|io|ru|xyz|co|"
    r"info|biz|app|dev|link|click|top|me)\b)",
    re.I,
)
# Control and direction-changing characters: they can make a notification read differently.
_HIDDEN = re.compile("[\u0000-\u001f\u007f\u200b-\u200f\u202a-\u202e\u2066-\u2069\ufeff]")
ALREADY_WORDS = "Nothing more was sent: this visit has already asked for a callback."
ADDRESS_WORDS = (
    "Nothing more was sent: a callback was already asked for from this network today. The "
    "contact links on this page still reach Kevin."
)


def enabled() -> bool:
    return os.environ.get(ENV, "").strip().lower() == "on"


def _today():
    return timezone.now().date()


def used_today() -> int:
    row = DemoCallbackDay.objects.filter(day=_today()).first()
    return row.count if row else 0


def state_for(user) -> str:
    """What asking for a person would do now, for this visitor."""
    if not enabled():
        return OFF
    visit = scenario.visit_of(user)
    if visit is None:
        return NO_VISIT
    if CallbackRequest.objects.filter(visit=visit).exists():
        return ALREADY
    if used_today() >= DAILY_CAP:
        return DAY_FULL
    return AVAILABLE


def _ensure_day() -> None:
    try:
        with transaction.atomic():
            DemoCallbackDay.objects.get_or_create(day=_today())
    except IntegrityError:
        pass  # Made by a concurrent request.


def take_slot() -> bool:
    """Counts one callback against today's cap, or False when it is used up. One conditional
    UPDATE: two visitors at once can never both take the last slot."""
    day = _today()
    _ensure_day()
    return bool(
        DemoCallbackDay.objects.filter(day=day, count__lt=DAILY_CAP).update(count=F("count") + 1)
    )


def contact_problem(name: str, contact: str, note: str) -> str | None:
    """Why the form's fields are refused, in words, or None."""
    if _HIDDEN.search(name + contact + note):
        return "Nothing was sent. The form holds characters that cannot be sent."
    if _LINK.search(name) or _LINK.search(note) or (_LINK.search(contact) and "@" not in contact):
        return "Nothing was sent. No links, please: a name, a phone number or an email, a note."
    if not _NAME.match(name):
        return "Nothing was sent. Give a name of up to 60 letters."
    phone = _PHONE.match(contact) and sum(c.isdigit() for c in contact) >= 7
    if not (_EMAIL.match(contact) or phone):
        return "Nothing was sent. Give a phone number or an email address to be reached at."
    if len(note) > 280:
        return "Nothing was sent. The note may hold up to 280 characters."
    return None


def summary(visit) -> str:
    """What the call came to, from the visit's own orders: code's words, never the model's."""
    placed = ShopOrder.objects.filter(visit=visit, status=ShopOrder.PLACED).order_by("created_at")
    quoted = ShopOrder.objects.filter(visit=visit, status=ShopOrder.QUOTED).first()
    if placed:
        orders = "; ".join(f"{o.ref} {catalogue.money(o.total_cents)}" for o in placed)
        return f"Store-rep demo call. Orders placed: {orders}."
    if quoted is not None:
        return (
            f"Store-rep demo call. Order read back but not placed: "
            f"{catalogue.money(quoted.total_cents)}."
        )
    return "Store-rep demo call. No order."


@dataclass(frozen=True)
class Outcome:
    status: str
    words: str
    http: int


def _owners():
    from household.models import HouseholdMember
    from household.tenancy import resolve_default_household

    household = resolve_default_household()
    if household is None:
        return None, []
    users = [
        m.user
        for m in HouseholdMember.objects.filter(
            household=household, role=HouseholdMember.OWNER
        ).select_related("user")
    ]
    return household, users


def default_sender():
    from push.sender import webpush_sender
    from push.vapid import vapid_config

    config = vapid_config()
    return webpush_sender(config) if config is not None else None


def notify(row: CallbackRequest, send=None) -> str:
    """Tells the engine's owners by Web Push. Returns what happened, never raises."""
    from push.dispatch import SENT as PUSH_SENT
    from push.dispatch import send_now
    from push.models import DEMO_CALLBACK

    send = send or default_sender()
    if send is None:
        return "push_off"
    household, owners = _owners()
    if household is None or not owners:
        return "no_owner"
    body = f"{row.name}, {row.contact}. {row.summary}"
    if row.note:
        body += f' They wrote: "{row.note}"'
    results = []
    for user in owners:
        try:
            results.append(
                send_now(
                    household,
                    user,
                    kind=DEMO_CALLBACK,
                    key=f"demo_callback:{row.pk}",
                    title="Demo visitor asked for a callback",
                    body=body,
                    url="/",
                    now=timezone.now(),
                    send=send,
                )
            )
        except Exception:  # noqa: BLE001 - the request is stored; the words say what happened
            results.append("failed")
    return PUSH_SENT if PUSH_SENT in results else (results[0] if results else "no_owner")


def address_key(ip: str) -> str:
    """The caller's address as a keyed hash: enough to hold one callback per network per day,
    never the address itself."""
    return salted_hmac("demo-callback-address", ip).hexdigest() if ip else ""


def request_callback(
    user, *, name: str, contact: str, note: str, ip: str = "", send=None
) -> Outcome:
    """The form's whole path: switch, visit, caps, fields, store, notify. Each refusal in words.
    Caps: one per visit, one per address per UTC day, `DAILY_CAP` across everyone."""
    name, contact, note = name.strip(), contact.strip(), (note or "").strip()
    state = state_for(user)
    if state == OFF:
        return Outcome(OFF, "Nothing was sent. Callbacks are switched off on this demo.", 409)
    if state == NO_VISIT:
        return Outcome(NO_VISIT, "Nothing was sent. This is not a demo visit.", 403)
    if state == ALREADY:
        return Outcome(ALREADY, ALREADY_WORDS, 429)
    problem = contact_problem(name, contact, note)
    if problem:
        return Outcome("invalid", problem, 400)
    visit = scenario.visit_of(user)
    key = address_key(ip)
    with transaction.atomic():
        # Today's counter row locked: every callback of the day passes these checks one at a time,
        # so two forms at once cannot both pass "one per visit" or "one per address".
        _ensure_day()
        DemoCallbackDay.objects.select_for_update().filter(day=_today()).first()
        if CallbackRequest.objects.filter(visit=visit).exists():
            return Outcome(ALREADY, ALREADY_WORDS, 429)
        if (
            key
            and CallbackRequest.objects.filter(address_key=key, created_at__date=_today()).exists()
        ):
            return Outcome("address_used", ADDRESS_WORDS, 429)
        if not take_slot():
            return Outcome(
                DAY_FULL,
                "Nothing was sent: today's callback requests are used up. The contact links on "
                "this page still reach Kevin.",
                429,
            )
        row = CallbackRequest.objects.create(
            visit=visit,
            ref=visit.ref,
            address_key=key,
            name=name,
            contact=contact,
            note=note,
            summary=summary(visit),
        )
    row.delivery = notify(row, send=send)
    row.save(update_fields=["delivery"])
    if row.delivery == SENT:
        return Outcome(
            SENT,
            "Sent. Kevin has been notified with your name, contact and a summary of this call.",
            201,
        )
    return Outcome(
        SAVED,
        "Saved, but Kevin has not been notified yet, so it may take him a while to see it.",
        201,
    )
