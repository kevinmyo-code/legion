"""Which scenario a demo visit's assistant plays: the LEGION guide ("") or the shop's rep.

Kept on the visit's own `DemoVisit` row, never in the browser: the scenario decides which tools
are declared and run, so the page must not be able to choose them. One switch for both ways in,
the page's chip (`views`) and the voice tools (`tools`), so the two cannot disagree (ADR 0035).
Only a recruiter's visit has a `DemoVisit` row, so a member, or a visitor with no visit row, is
never in it. Callers on a member's path skip the read altogether (`assistant.session`, the door).
"""

from __future__ import annotations

from demo.models import DemoVisit

SHOP = "shop"
GUIDE = ""
_CACHE = "_demo_visit_row"


def visit_of(user) -> DemoVisit | None:
    """The caller's visit row, read once per request (cached on the user object), or None."""
    if user is None or not getattr(user, "pk", None):
        return None
    if not hasattr(user, _CACHE):
        setattr(user, _CACHE, DemoVisit.objects.filter(user=user).first())
    return getattr(user, _CACHE)


def active(user) -> bool:
    visit = visit_of(user)
    return visit is not None and visit.scenario == SHOP


def switch(user, on: bool) -> bool:
    """Puts the caller's visit in (or out of) the store-rep scenario. False when there is no visit
    to switch: nothing changed."""
    visit = visit_of(user)
    if visit is None:
        return False
    visit.scenario = SHOP if on else GUIDE
    visit.save(update_fields=["scenario"])
    return True
