#!/usr/bin/env python3
"""Hand a login to the LEGION server: you log in, this script carries it over.

backend-etl ticket 02, map ruling 2. You log in by hand, in a real browser
window on your own machine (SSO, captcha and 2FA are all yours to clear), and
this script hands the resulting session to the server's vault. It never asks
for, reads, stores or sends a password.

    canvas     headed Chromium at your Canvas login; once you are in, the
               Canvas cookies (and only those) go to the server
    webassign  the same, for WebAssign
    drive      Google's installed-app OAuth flow in your browser; the
               refresh token goes to the server (Drive scopes: drive.readonly
               and drive.file)

Runs on the LAPTOP, not the server, so its dependencies are deliberately not in
server/requirements.txt. Install once:

    pip install playwright google-auth-oauthlib requests
    playwright install chromium

Examples:

    python tools/connect_session.py canvas --server https://legion.example.com \\
        --token <device token> --base-url https://canvas.school.edu
    python tools/connect_session.py webassign --server ... --token ...
    python tools/connect_session.py drive --server ... --token ... \\
        --client-id <id> --client-secret <secret> \\
        [--statements-folder <folder id or URL>]

Every option can come from the environment instead: LEGION_SERVER,
LEGION_TOKEN, LEGION_CANVAS_URL, LEGION_GOOGLE_CLIENT_ID,
LEGION_GOOGLE_CLIENT_SECRET. The token is a LEGION device token for an OWNER of
the household (`POST /api/auth/login` issues one); only an owner may hand over
a login.

The Google OAuth client is the household's own (bring your own: create a
"Desktop app" client in your Google Cloud project). **Set its consent screen to
"In production"** (unverified is fine for your own accounts): a client left in
"Testing" issues refresh tokens that Google kills after 7 days.

The browser profile lives under ~/.legion/browser-profiles/<source>, on this
machine only and never in the repo, so a site that remembers devices keeps
remembering this one.

**Shaped for ticket 09's `bofa` subcommand, which is NOT here yet.** Each
subcommand is one entry in `build_parser` plus one `run_<source>` function. A
BofA session must never reach the server: `put_session` refuses `bofa` by name,
and so does the server.
"""
from __future__ import annotations

import argparse
import datetime
import os
import sys
import threading
from collections.abc import Callable, Iterable
from pathlib import Path
from urllib.parse import urlparse

DRIVE_SCOPES = (
    "https://www.googleapis.com/auth/drive.readonly",
    "https://www.googleapis.com/auth/drive.file",
)
GOOGLE_AUTH_URI = "https://accounts.google.com/o/oauth2/auth"
GOOGLE_TOKEN_URI = "https://oauth2.googleapis.com/token"

WEBASSIGN_LOGIN_URL = "https://www.webassign.net/wa-auth/login"

# Sources whose session must never be sent to the server (ticket 09).
NEVER_SENT = frozenset({"bofa"})

# The cookie fields the server's vault keeps. Anything else Playwright
# reports is dropped rather than shipped.
COOKIE_FIELDS = ("name", "value", "domain", "path", "expires", "secure", "httpOnly",
                 "sameSite")

PRODUCTION_WARNING = (
    "Before you go on: your Google OAuth client's consent screen must be set to\n"
    "'In production' (Google Cloud console > APIs & Services > OAuth consent\n"
    "screen > Publish app). Unverified is fine for your own accounts. A client\n"
    "left in 'Testing' issues refresh tokens that die after 7 days, and the\n"
    "backup and statement feeds would stop a week from now."
)

LOGIN_TIMEOUT_SECONDS = 600


class ConnectError(Exception):
    """Something went wrong in a way the person can act on; the message says
    how. Printed without a traceback."""


# =============================================================================
# Arguments
# =============================================================================


def _env(name: str) -> str | None:
    value = os.environ.get(name, "").strip()
    return value or None


def build_parser() -> argparse.ArgumentParser:
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument(
        "--server",
        default=_env("LEGION_SERVER"),
        help="The LEGION server's base URL (env LEGION_SERVER).",
    )
    common.add_argument(
        "--token",
        default=_env("LEGION_TOKEN"),
        help="A device token for an owner of the household (env LEGION_TOKEN).",
    )
    common.add_argument(
        "--timeout",
        type=int,
        default=LOGIN_TIMEOUT_SECONDS,
        help="Seconds to wait for you to finish logging in (default 600).",
    )

    parser = argparse.ArgumentParser(
        prog="connect_session.py",
        description="Log in by hand; this hands the session to the LEGION server.",
    )
    sub = parser.add_subparsers(dest="source", required=True, metavar="SOURCE")

    canvas = sub.add_parser(
        "canvas", parents=[common], help="Log in to Canvas and hand over its cookies."
    )
    canvas.add_argument(
        "--base-url",
        default=_env("LEGION_CANVAS_URL"),
        help="Your Canvas address, e.g. https://canvas.school.edu (env LEGION_CANVAS_URL).",
    )

    webassign = sub.add_parser(
        "webassign",
        parents=[common],
        help="Log in to WebAssign and hand over its cookies.",
    )
    webassign.add_argument(
        "--login-url",
        default=WEBASSIGN_LOGIN_URL,
        help=f"Where the login starts (default {WEBASSIGN_LOGIN_URL}).",
    )

    drive = sub.add_parser(
        "drive",
        parents=[common],
        help="Authorise Google Drive and hand over the refresh token.",
    )
    drive.add_argument(
        "--client-id",
        default=_env("LEGION_GOOGLE_CLIENT_ID"),
        help="The household's own OAuth client id (env LEGION_GOOGLE_CLIENT_ID).",
    )
    drive.add_argument(
        "--client-secret",
        default=_env("LEGION_GOOGLE_CLIENT_SECRET"),
        help="Its client secret (env LEGION_GOOGLE_CLIENT_SECRET).",
    )
    drive.add_argument(
        "--backup",
        action=argparse.BooleanOptionalAction,
        default=None,
        help="Switch the nightly whole-database backup to this Drive on (--backup) or "
        "off (--no-backup). Only the household that runs the engine can hold it "
        "(backend-etl ticket 03). Left out, the current setting is kept.",
    )
    drive.add_argument(
        "--statements-folder",
        default=None,
        help="The Drive folder the statements job watches, as its id or its URL "
        "(backend-etl ticket 06). Left out, the current folder is kept.",
    )
    return parser


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    """Parsed arguments, refusing in words when something required is missing
    (argparse cannot mark an option required when it may come from env)."""
    parser = build_parser()
    args = parser.parse_args(argv)
    missing = []
    if not args.server:
        missing.append("--server (or LEGION_SERVER)")
    if not args.token:
        missing.append("--token (or LEGION_TOKEN)")
    if args.source == "canvas" and not args.base_url:
        missing.append("--base-url (or LEGION_CANVAS_URL)")
    if args.source == "drive":
        if not args.client_id:
            missing.append("--client-id (or LEGION_GOOGLE_CLIENT_ID)")
        if not args.client_secret:
            missing.append("--client-secret (or LEGION_GOOGLE_CLIENT_SECRET)")
    if missing:
        parser.error("missing " + ", ".join(missing))
    if args.source == "drive" and args.statements_folder is not None:
        try:
            args.statements_folder = _folder_ids().folder_id_from(args.statements_folder)
        except ValueError as exc:
            parser.error(str(exc))
    args.server = args.server.rstrip("/")
    if args.source == "canvas":
        try:
            args.base_url = normalise_base_url(args.base_url)
        except ConnectError as exc:
            parser.error(str(exc))
    return args


def normalise_base_url(url: str) -> str:
    """`canvas.school.edu/` and `https://canvas.school.edu` are one address."""
    url = url.strip()
    if "://" not in url:
        url = "https://" + url
    parsed = urlparse(url)
    if not parsed.netloc:
        raise ConnectError(f"{url!r} is not a web address.")
    return f"{parsed.scheme}://{parsed.netloc}"


# =============================================================================
# Cookie jars
# =============================================================================


def _cookie_domain_matches(cookie_domain: str, hosts: Iterable[str]) -> bool:
    """A cookie belongs to a site when its domain is the site's host or a
    parent of it (`.school.edu` is sent to `canvas.school.edu`). A cookie for
    a CHILD or an unrelated host (the university's SSO provider) is not."""
    domain = cookie_domain.lstrip(".").lower()
    for host in hosts:
        host = host.lower()
        if host == domain or host.endswith("." + domain):
            return True
    return False


def shape_cookie_jar(cookies: Iterable[dict], hosts: Iterable[str]) -> dict:
    """The `{"cookies": [...]}` the vault stores, from Playwright's
    `context.cookies()`: only the site's own cookies, only the fields the
    vault keeps. The identity provider's cookies stay on this machine - they
    are a login to everything the SSO covers, not just this site."""
    hosts = list(hosts)
    kept = []
    for cookie in cookies:
        if not _cookie_domain_matches(cookie.get("domain", ""), hosts):
            continue
        kept.append({field: cookie[field] for field in COOKIE_FIELDS if field in cookie})
    return {"cookies": kept}


def expires_hint(jar: dict) -> str | None:
    """The earliest expiry any kept cookie declares, ISO 8601 in UTC, or None
    when they are all session cookies (Playwright reports those as -1)."""
    times = [
        c["expires"]
        for c in jar.get("cookies", [])
        if isinstance(c.get("expires"), (int, float)) and c["expires"] > 0
    ]
    if not times:
        return None
    return datetime.datetime.fromtimestamp(min(times), datetime.UTC).isoformat()


# =============================================================================
# Talking to the server
# =============================================================================


def build_put(
    server: str,
    token: str,
    source: str,
    secret: dict,
    *,
    config: dict | None = None,
    expires: str | None = None,
) -> tuple[str, dict, dict]:
    """(url, headers, json body) for `PUT /api/ingest/sessions/<source>`.

    Refuses a source in `NEVER_SENT` before anything is built, so no code path
    can hand a BofA session to the server by accident.
    """
    if source in NEVER_SENT:
        raise ConnectError(
            f"A {source} session is never sent to the server (backend-etl ticket 09). "
            f"Nothing was sent."
        )
    body: dict = {"secret": secret, "config": config or {}}
    if expires is not None:
        body["expires_hint"] = expires
    headers = {"Authorization": f"Token {token}", "Content-Type": "application/json"}
    return f"{server.rstrip('/')}/api/ingest/sessions/{source}", headers, body


def describe_stored(metadata: dict) -> str:
    """One line about what the server now holds. Built from the response's
    metadata, which never carries the secret."""
    line = f"Stored: {metadata.get('source')} ({metadata.get('kind')}), captured "
    line += str(metadata.get("captured_at"))
    if metadata.get("expires_hint"):
        line += f", may expire {metadata['expires_hint']}"
    return line + "."


def put_session(
    server: str,
    token: str,
    source: str,
    secret: dict,
    *,
    config: dict | None = None,
    expires: str | None = None,
    http_put: Callable | None = None,
) -> dict:
    url, headers, body = build_put(
        server, token, source, secret, config=config, expires=expires
    )
    if http_put is None:
        import requests

        http_put = requests.put
    response = http_put(url, json=body, headers=headers, timeout=30)
    try:
        payload = response.json()
    except ValueError:
        payload = {}
    if response.status_code != 200:
        detail = payload.get("detail") if isinstance(payload, dict) else None
        raise ConnectError(
            f"The server refused the session (HTTP {response.status_code}): "
            f"{detail or 'no reason given'}"
        )
    return payload


# =============================================================================
# Browser logins (canvas, webassign; ticket 09's bofa will reuse these)
# =============================================================================


def profile_dir(source: str) -> Path:
    """Per-source browser profile, under the home directory, never the repo."""
    path = Path.home() / ".legion" / "browser-profiles" / source
    path.mkdir(parents=True, exist_ok=True)
    return path


def _enter_pressed() -> threading.Event:
    """Set when the person presses Enter: the fallback post-login signal for
    a site whose landing page this script does not recognise."""
    event = threading.Event()

    def wait():
        try:
            input()
        except EOFError:
            return
        event.set()

    threading.Thread(target=wait, daemon=True).start()
    return event


def browser_login(
    source: str,
    login_url: str,
    logged_in: Callable,
    timeout: int,
) -> list[dict]:
    """Open a headed Chromium at `login_url`, wait until `logged_in(context,
    page)` is true (or the person presses Enter), return the context's cookies.

    Raises `ConnectError` on timeout: nothing is sent for a login that never
    finished.
    """
    try:
        from playwright.sync_api import sync_playwright
    except ImportError as exc:
        raise ConnectError(
            "Playwright is not installed here. Run:\n"
            "    pip install playwright google-auth-oauthlib requests\n"
            "    playwright install chromium"
        ) from exc

    print(f"A browser window is opening at {login_url}.")
    print("Log in there as you normally would. This script never sees your password.")
    print("It carries on by itself once you are in; if it does not, press Enter here.")
    enter = _enter_pressed()
    deadline = datetime.datetime.now() + datetime.timedelta(seconds=timeout)
    with sync_playwright() as playwright:
        context = playwright.chromium.launch_persistent_context(
            str(profile_dir(source)), headless=False
        )
        try:
            page = context.pages[0] if context.pages else context.new_page()
            page.goto(login_url)
            while True:
                if enter.is_set():
                    break
                try:
                    if logged_in(context, page):
                        break
                except Exception:  # noqa: BLE001 - a navigating page throws; poll again
                    pass
                if datetime.datetime.now() > deadline:
                    raise ConnectError(
                        f"No login seen within {timeout} seconds. Nothing was sent."
                    )
                page.wait_for_timeout(1000)
            return context.cookies()
        finally:
            context.close()


def canvas_logged_in(base_url: str) -> Callable:
    """Canvas's own answer: `/api/v1/users/self` returns 200 only to a
    logged-in session. Asked with the browser context's cookies."""

    def check(context, page) -> bool:
        response = context.request.get(f"{base_url}/api/v1/users/self")
        return response.status == 200

    return check


def webassign_logged_in(context, page) -> bool:
    """Reasoned, not yet seen on a real login (2026-09-27): WebAssign's
    login pages live under /wa-auth/ or a login path, and a signed-in person
    lands anywhere else on webassign.net. Enter is the fallback."""
    parsed = urlparse(page.url)
    host = parsed.netloc.lower()
    path = parsed.path.lower()
    on_webassign = host == "webassign.net" or host.endswith(".webassign.net")
    return on_webassign and "wa-auth" not in path and "login" not in path


def run_canvas(args: argparse.Namespace) -> dict:
    cookies = browser_login(
        "canvas", f"{args.base_url}/login", canvas_logged_in(args.base_url), args.timeout
    )
    jar = shape_cookie_jar(cookies, [urlparse(args.base_url).netloc])
    if not jar["cookies"]:
        raise ConnectError("No Canvas cookies were found after the login. Nothing was sent.")
    return put_session(
        args.server,
        args.token,
        "canvas",
        jar,
        config={"base_url": args.base_url},
        expires=expires_hint(jar),
    )


def run_webassign(args: argparse.Namespace) -> dict:
    cookies = browser_login("webassign", args.login_url, webassign_logged_in, args.timeout)
    jar = shape_cookie_jar(cookies, ["www.webassign.net", "webassign.net"])
    if not jar["cookies"]:
        raise ConnectError(
            "No WebAssign cookies were found after the login. Nothing was sent."
        )
    return put_session(
        args.server, args.token, "webassign", jar, expires=expires_hint(jar)
    )


# =============================================================================
# Drive (installed-app OAuth)
# =============================================================================


def drive_client_config(client_id: str, client_secret: str) -> dict:
    return {
        "installed": {
            "client_id": client_id,
            "client_secret": client_secret,
            "auth_uri": GOOGLE_AUTH_URI,
            "token_uri": GOOGLE_TOKEN_URI,
            "redirect_uris": ["http://localhost"],
        }
    }


def drive_secret(credentials, client_id: str, client_secret: str) -> dict:
    """The vault's `oauth_refresh` shape, from google-auth `Credentials`."""
    if not getattr(credentials, "refresh_token", None):
        raise ConnectError(
            "Google returned no refresh token. Remove LEGION's access at "
            "https://myaccount.google.com/permissions and run this again, so the "
            "consent screen is shown. Nothing was sent."
        )
    return {
        "refresh_token": credentials.refresh_token,
        "client_id": client_id,
        "client_secret": client_secret,
        "token_uri": getattr(credentials, "token_uri", None) or GOOGLE_TOKEN_URI,
        "scopes": list(getattr(credentials, "scopes", None) or DRIVE_SCOPES),
    }


def run_drive(args: argparse.Namespace) -> dict:
    print(PRODUCTION_WARNING)
    print()
    try:
        from google_auth_oauthlib.flow import InstalledAppFlow
    except ImportError as exc:
        raise ConnectError(
            "google-auth-oauthlib is not installed here. Run:\n"
            "    pip install playwright google-auth-oauthlib requests"
        ) from exc
    flow = InstalledAppFlow.from_client_config(
        drive_client_config(args.client_id, args.client_secret), scopes=list(DRIVE_SCOPES)
    )
    # `prompt=consent` makes Google issue a refresh token even when this
    # account has authorised this client before.
    credentials = flow.run_local_server(
        port=0, access_type="offline", prompt="consent", timeout_seconds=args.timeout
    )
    secret = drive_secret(credentials, args.client_id, args.client_secret)
    return put_session(
        args.server,
        args.token,
        "drive",
        secret,
        config=drive_config(
            secret["scopes"], args.backup, getattr(args, "statements_folder", None)
        ),
    )


def drive_config(
    scopes: list[str], backup: bool | None, statements_folder: str | None = None
) -> dict:
    """The non-secret config sent with a Drive login. `backup` and the
    statements folder are sent only when given: the server MERGES config, so
    leaving one out keeps whatever the last login set."""
    config: dict = {"scopes": scopes}
    if backup is not None:
        config["backup"] = backup
    if statements_folder is not None:
        config["statements_folder_id"] = statements_folder
    return config


def _folder_ids():
    """`server/ingest/folder_ids.py`, loaded by path: one rule for turning a
    pasted folder URL into an id, shared with the server's
    `set_statements_folder` command, without needing Django here."""
    import importlib.util

    path = Path(__file__).resolve().parents[1] / "server" / "ingest" / "folder_ids.py"
    spec = importlib.util.spec_from_file_location("legion_folder_ids", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


RUNNERS: dict[str, Callable[[argparse.Namespace], dict]] = {
    "canvas": run_canvas,
    "webassign": run_webassign,
    "drive": run_drive,
}


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        metadata = RUNNERS[args.source](args)
    except ConnectError as exc:
        print(f"\n{exc}", file=sys.stderr)
        return 1
    print(describe_stored(metadata))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
