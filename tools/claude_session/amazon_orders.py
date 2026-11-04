"""Read Kevin's recent Amazon orders and their live tracking, from his own signed-in profile.

Claude session's live fallback for "where's my Amazon order" (Kevin, 2026-11-04: "gmail first and
amazon as a real time fallback"). Amazon's shipping emails come first (main_session_prompt.md rule
10); this reads the orders page itself when mail has no status or an old one.

    python amazon_orders.py              recent orders, each with its status, plus live tracking
                                         for up to three that are not delivered yet
    python amazon_orders.py --json       the same, as JSON
    python amazon_orders.py --sign-in    a visible window on the same profile for Kevin to sign
                                         in himself; close it when done

**Read only, by construction.** It goes to two kinds of page, the order history and a package's
tracking page, both checked against `ALLOWED_PAGES` before it navigates, and it never clicks,
types or submits anything. Nothing here can buy, cancel, return or change an order.

**It never signs in.** The profile (`~/.legion/browser-profiles/amazon`, beside the BofA and Canvas
ones) holds the login Kevin made with `--sign-in`. When Amazon wants a sign-in, a code or a
captcha, this says so and stops (exit 3 or 4); nothing tries to get past the check.

Exit codes: 0 read, 3 sign-in needed, 4 Amazon showed a check (captcha), 5 the page was not
understood (Amazon changed its layout), 2 Playwright is missing.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import asdict, dataclass, field
from pathlib import Path

PROFILE = Path.home() / ".legion" / "browser-profiles" / "amazon"
ORDERS_URL = "https://www.amazon.com/gp/css/order-history"
ALLOWED_PAGES = (
    re.compile(r"^https://www\.amazon\.com/(gp/css/order-history|your-orders/orders)"),
    re.compile(r"^https://www\.amazon\.com/(gp/your-account/ship-track|progress-tracker/package)"),
)
TRACK_LIMIT = 3
TIMEOUT_MS = 30_000

SIGN_IN_NEEDED = ("Amazon wants Kevin to sign in again. Nothing was read. Kevin runs "
                  "amazon_orders.py --sign-in on the Alienware and signs in himself.")
CHECK_SHOWN = ("Amazon showed a security check. Nothing was read, and nothing tries to get past "
               "it. Kevin opens amazon_orders.py --sign-in and clears it himself.")
NOT_UNDERSTOOD = "The Amazon orders page did not look as expected, so nothing could be read from it."

# A delivery status line, as Amazon words it on the order card and the tracking page.
STATUS = re.compile(
    r"^(Arriving|Now arriving|Delivered|Out for delivery|Shipped|Expected|Now expected|"
    r"Running late|Delayed|Not yet shipped|Preparing for shipment|Package was handed|"
    r"Undeliverable|Delivery attempted|Return|Refund|Cancelled|Your package)\b.*",
    re.IGNORECASE,
)


@dataclass
class Order:
    placed: str = ""
    status: str = ""
    items: list[str] = field(default_factory=list)
    track_url: str = ""
    tracking: str = ""


def allowed(url: str) -> bool:
    return any(p.match(url) for p in ALLOWED_PAGES)


def lines_of(text: str) -> list[str]:
    return [line.strip() for line in text.splitlines() if line.strip()]


def parse_card(text: str) -> Order:
    """One order card's visible text: when it was placed and its delivery status."""
    lines = lines_of(text)
    order = Order()
    for i, line in enumerate(lines):
        if line.upper() == "ORDER PLACED" and i + 1 < len(lines):
            order.placed = lines[i + 1]
        if not order.status and STATUS.match(line):
            order.status = line
    return order


def parse_tracking(text: str) -> str:
    """A tracking page's visible text, cut to its headline status and latest update.

    The page also draws a progress bar whose step labels ("Ordered", "Shipped", "Out for
    delivery") look like statuses; only the first status line is the package's own.
    """
    headline = ""
    latest = ""
    for line in lines_of(text):
        if not headline and STATUS.match(line):
            headline = line
        elif not latest and line.lower().startswith("latest update"):
            latest = line
    return "; ".join(part for part in (headline, latest) if part)


def delivered(status: str) -> bool:
    return status.lower().startswith(("delivered", "return", "refund", "cancelled"))


def gate(page) -> int:
    """0 when the page is the one asked for, else the exit code that says why not."""
    url = page.url
    if "/ap/signin" in url or "/ap/mfa" in url or page.locator("#ap_email, #ap_password").count():
        return 3
    if "validateCaptcha" in url or "Enter the characters you see" in page.content():
        return 4
    return 0


def read_orders(page, track_limit: int) -> tuple[int, list[Order]]:
    page.goto(ORDERS_URL, wait_until="domcontentloaded", timeout=TIMEOUT_MS)
    code = gate(page)
    if code:
        return code, []
    cards = page.locator(".order-card, .js-order-card")
    if cards.count() == 0:
        return 5, []
    orders: list[Order] = []
    for i in range(min(cards.count(), 10)):
        card = cards.nth(i)
        order = parse_card(card.inner_text())
        titles = card.locator(".yohtmlc-product-title, a.a-link-normal[href*='/dp/']")
        for j in range(min(titles.count(), 4)):
            title = " ".join(titles.nth(j).inner_text().split())
            if title and title not in order.items:
                order.items.append(title)
        track = card.locator("a[href*='ship-track'], a[href*='progress-tracker']")
        if track.count():
            href = track.first.get_attribute("href") or ""
            order.track_url = href if href.startswith("http") else "https://www.amazon.com" + href
        orders.append(order)
    for order in [o for o in orders if o.track_url and not delivered(o.status)][:track_limit]:
        if not allowed(order.track_url):
            continue
        page.goto(order.track_url, wait_until="domcontentloaded", timeout=TIMEOUT_MS)
        if gate(page):
            break
        order.tracking = parse_tracking(page.locator("body").inner_text())
    return 0, orders


def spoken(orders: list[Order]) -> str:
    """Plain lines for the helper to turn into its one or two spoken sentences."""
    out = []
    for o in orders:
        what = o.items[0] if o.items else "an order"
        more = f" and {len(o.items) - 1} more" if len(o.items) > 1 else ""
        line = f"Placed {o.placed or 'date unknown'}: {what}{more}. Status: {o.status or 'not shown'}."
        if o.tracking:
            line += f" Live tracking: {o.tracking}."
        out.append(line)
    return "\n".join(out) or "No recent orders on the page."


def sign_in(sync_playwright) -> int:
    PROFILE.mkdir(parents=True, exist_ok=True)
    with sync_playwright() as p:
        context = p.chromium.launch_persistent_context(str(PROFILE), headless=False)
        page = context.pages[0] if context.pages else context.new_page()
        page.goto(ORDERS_URL, timeout=TIMEOUT_MS)
        print("Sign in to Amazon in the window (tick 'Keep me signed in'), then close the window.")
        context.wait_for_event("close", timeout=0)
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--sign-in", action="store_true")
    parser.add_argument("--json", action="store_true")
    parser.add_argument("--track", type=int, default=TRACK_LIMIT)
    args = parser.parse_args(argv)
    # Item titles carry any character (full-width commas, emoji); a Windows console is cp1252.
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        print("Playwright is not installed for this Python, so Amazon could not be read.")
        return 2
    if args.sign_in:
        return sign_in(sync_playwright)
    if not PROFILE.exists():
        print(SIGN_IN_NEEDED)
        return 3
    with sync_playwright() as p:
        context = p.chromium.launch_persistent_context(str(PROFILE), headless=True)
        try:
            page = context.pages[0] if context.pages else context.new_page()
            code, orders = read_orders(page, max(0, args.track))
        finally:
            context.close()
    if code:
        print({3: SIGN_IN_NEEDED, 4: CHECK_SHOWN, 5: NOT_UNDERSTOOD}[code])
        return code
    print(json.dumps([asdict(o) for o in orders], indent=1) if args.json else spoken(orders))
    return 0


if __name__ == "__main__":
    sys.exit(main())
