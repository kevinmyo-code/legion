"""`connect_session.py bofa`: you log in to Bank of America, this pulls the files.

backend-etl ticket 09. Runs on the LAPTOP, inside Kevin's own login sitting.
The rulings this file exists to keep:

- **No BofA cookie, session or password ever leaves this machine.** Nothing
  here talks to the LEGION server at all; the only network peers are
  bankofamerica.com (in the browser Kevin is driving) and Google Drive (with
  a laptop-only `drive.file` token). `connect_session.put_session` refuses
  `bofa` by name as a second lock.
- **No stored password, no unattended login.** Kevin types it, every time.
- **Nothing partial.** Every download happens first; Drive is touched only if
  every browser step succeeded. A page that no longer looks the way it did on
  the day the locators were captured stops the run in words.

Per account, per run:
- the current-activity CSV, saved as `bofa_<last4>_activity_<YYYY-MM-DD>.csv`
  (a same-day re-run replaces that day's file in Drive);
- the statement PDFs of this year and last that Drive does not already hold,
  saved as `bofa_<last4>_<YYYY-MM>.pdf`.

The server's statements watcher (ticket 06, ticket 13) takes it from there.
Nothing in this file reads a balance or a transaction, and nothing prints one.
"""
from __future__ import annotations

import argparse
import datetime
import json
import re
import uuid
from collections.abc import Callable, Iterable
from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import parse_qs, urljoin, urlparse

# =============================================================================
# BofA's pages. Every locator lives here and nowhere else.
# Last seen working 2026-09-28 (checking 3119 and card 4146, captured live).
# The card's Statements & Documents page was NOT seen; it is assumed to share
# the checking layout, and the run stops loudly if it does not.
# =============================================================================

BOFA_HOME_URL = "https://www.bankofamerica.com/"
OVERVIEW_URL = (
    "https://secure.bankofamerica.com/myaccounts/brain/redirect.go?target=accountsoverview"
)
OVERVIEW_TITLE = "Accounts Overview"
LOGGED_IN_PATH_PREFIX = "/myaccounts/"

ACCOUNT_LINK_CSS = 'a[href*="target=acctDetails"]'
CHECKING_PATH_MARK = "/deposit-details/"

# Checking: /deposit-details/activity/
CHECKING_DOWNLOAD_BUTTON = ("button", "Download")          # role, accessible name
CHECKING_DIALOG = ("dialog", "Download your data")
CHECKING_PERIOD_SELECT = "#select_txnPeriod"
CHECKING_FILETYPE_SELECT = "#select_fileType"
CHECKING_FILETYPE_VALUE = "csv"                            # "Microsoft Excel Format"
CHECKING_SUBMIT = "#btn-download-txn"

# Card
CARD_DOWNLOAD_LINK = "a[name=download_transactions_top]"   # class export-trans-view
CARD_PERIOD_SELECT = "#select_transaction"
CARD_FILETYPE_SELECT = "#select_filetype"
CARD_FILETYPE_TEXT = "Excel"
CARD_SUBMIT = "a.submit-download"

CURRENT_PERIOD_TEXT = "Current transactions"

# Statements & Documents
STATEMENTS_LINK = ("link", "Statements & Documents")
CHECKING_DIALOG_CLOSE = ("button", "close Dialog")
STATEMENTS_PATH_MARK = "/mycomm-acc-stmts-docs/"
STATEMENTS_YEAR_SELECT = ("combobox", "year")
STATEMENTS_EXPAND_BUTTON = ("button", "Statements")
# `#downloadPDFLink` repeats on every row, so a statement is found by its
# accessible name, never by that id alone.
STATEMENT_LINK_NAME = re.compile(
    r"^Download PDF for (?P<month>[A-Za-z]+) Statement for (?P<account>.+) - (?P<last4>\d{4})$"
)

LOCATOR_TIMEOUT_MS = 30_000     # BofA's pages take 3-5 s; this is the ceiling, not a sleep

# =============================================================================

DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
DRIVE_FILES_URL = "https://www.googleapis.com/drive/v3/files"
DRIVE_UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files"

MONTHS = {
    name: index
    for index, name in enumerate(
        (
            "january", "february", "march", "april", "may", "june", "july",
            "august", "september", "october", "november", "december",
        ),
        start=1,
    )
}

NOTHING_UPLOADED = "nothing was uploaded"


class BofaError(Exception):
    """Stops the run. The message says what did NOT happen."""


class PageChanged(BofaError):
    def __init__(self, step: str, detail: str | None = None):
        self.step = step
        message = f"BofA's page changed at {step}; {NOTHING_UPLOADED}."
        if detail:
            message += f" ({detail})"
        super().__init__(message)


class DriveRefused(BofaError):
    pass


# =============================================================================
# Names (pure)
# =============================================================================

_ACCOUNT_TEXT = re.compile(r"^(?P<name>.+?)\s+-\s+(?P<last4>\d{4})$")


def parse_account_link(text: str) -> tuple[str, str] | None:
    """`Adv SafeBalance Banking - 3119` to (`Adv SafeBalance Banking`, `3119`).
    None for an anchor that is not an account name (BofA puts several links
    to the same account on the overview)."""
    cleaned = " ".join((text or "").split())
    match = _ACCOUNT_TEXT.match(cleaned)
    if not match:
        return None
    return match.group("name"), match.group("last4")


def parse_statement_link(text: str) -> tuple[int, str] | None:
    """`Download PDF for August Statement for Adv SafeBalance Banking - 3119`
    to (8, `3119`). None when it is not a monthly statement link."""
    cleaned = " ".join((text or "").split())
    match = STATEMENT_LINK_NAME.match(cleaned)
    if not match:
        return None
    month = MONTHS.get(match.group("month").lower())
    if month is None:
        return None
    return month, match.group("last4")


def account_kind(url: str) -> str:
    """`checking` for a `/deposit-details/` page, `card` for anything else."""
    return "checking" if CHECKING_PATH_MARK in urlparse(url).path else "card"


def activity_name(last4: str, day: datetime.date) -> str:
    return f"bofa_{last4}_activity_{day.isoformat()}.csv"


def statement_name(last4: str, year: int, month: int) -> str:
    return f"bofa_{last4}_{year:04d}-{month:02d}.pdf"


def statement_years(today: datetime.date) -> tuple[int, int]:
    return today.year, today.year - 1


def bofa_logged_in(context, page) -> bool:
    """Title says Accounts Overview, or the address is under /myaccounts/ and
    is not a sign-in page. Enter is the fallback."""
    try:
        title = page.title() or ""
    except Exception:  # noqa: BLE001 - a navigating page throws; poll again
        title = ""
    if OVERVIEW_TITLE.lower() in title.lower():
        return True
    parsed = urlparse(page.url)
    return (
        parsed.path.startswith(LOGGED_IN_PATH_PREFIX)
        and "signin" not in page.url.lower()
    )


# =============================================================================
# Drive folder ids. A copy of server/ingest/folder_ids.py: this runs on a
# laptop that may not have server/ checked out with its deps.
# =============================================================================

_DRIVE_ID = re.compile(r"^[A-Za-z0-9_-]{10,200}$")


def folder_id_from(value: str) -> str:
    text = (value or "").strip()
    if _DRIVE_ID.match(text):
        return text
    parsed = urlparse(text)
    if parsed.scheme in ("http", "https") and parsed.netloc.endswith("google.com"):
        parts = [part for part in parsed.path.split("/") if part]
        if "folders" in parts:
            index = parts.index("folders")
            if index + 1 < len(parts) and _DRIVE_ID.match(parts[index + 1]):
                return parts[index + 1]
        ids = parse_qs(parsed.query).get("id")
        if ids and _DRIVE_ID.match(ids[0]):
            return ids[0]
    raise ValueError(
        f"{value!r} is not a Drive folder id or a Drive folder URL "
        f"(https://drive.google.com/drive/folders/<id>). Nothing was changed."
    )


# =============================================================================
# What to upload (pure)
# =============================================================================


@dataclass(frozen=True)
class Upload:
    name: str
    path: Path
    action: str                 # "create" or "update"
    file_id: str | None = None


def plan_uploads(files: Iterable[Path], existing: dict[str, str]) -> tuple[list[Upload], list[str]]:
    """(uploads, skipped names). `existing` maps a name already in the folder
    to its file id. A statement already there is skipped; an activity CSV
    already there (a same-day re-run) replaces that file rather than adding a
    second one with the same name."""
    uploads: list[Upload] = []
    skipped: list[str] = []
    for path in files:
        name = path.name
        if name in existing:
            if name.endswith(".csv"):
                uploads.append(Upload(name, path, "update", existing[name]))
            else:
                skipped.append(name)
        else:
            uploads.append(Upload(name, path, "create"))
    # Creates first: if Drive refuses the folder itself, it refuses before any
    # existing file has been touched.
    uploads.sort(key=lambda u: (u.action != "create", u.name))
    return uploads, skipped


def multipart_body(metadata: dict, content: bytes, mime: str) -> tuple[bytes, str]:
    """A Drive `uploadType=multipart` body and its Content-Type."""
    boundary = "legion-" + uuid.uuid4().hex
    head = (
        f"--{boundary}\r\n"
        "Content-Type: application/json; charset=UTF-8\r\n\r\n"
        f"{json.dumps(metadata)}\r\n"
        f"--{boundary}\r\n"
        f"Content-Type: {mime}\r\n\r\n"
    ).encode()
    tail = f"\r\n--{boundary}--\r\n".encode()
    return head + content + tail, f"multipart/related; boundary={boundary}"


def mime_for(name: str) -> str:
    return "application/pdf" if name.endswith(".pdf") else "text/csv"


def foreign_folder_sentence(status: int) -> str:
    return (
        f"Google refused to put a file in the statements folder (HTTP {status}). "
        "This laptop's token has only the drive.file scope, which may not reach a folder "
        "this app did not create. Fix, Kevin's call: let the script create and own its own "
        "statements folder and point the server's watcher at that, or widen this laptop's "
        "scope to full drive."
    )


# =============================================================================
# Drive (installed-app OAuth on the laptop, token cached under ~/.legion/)
# =============================================================================


def token_path() -> Path:
    return Path.home() / ".legion" / "bofa-drive-token.json"


def drive_session(client_id: str, client_secret: str, timeout: int):
    """An authorised requests session for `drive.file` only. The token is
    cached under ~/.legion/, never in the repo and never sent to the server."""
    try:
        from google.auth.transport.requests import AuthorizedSession, Request
        from google.oauth2.credentials import Credentials
        from google_auth_oauthlib.flow import InstalledAppFlow
    except ImportError as exc:
        raise BofaError(
            "google-auth-oauthlib is not installed here. Run:\n"
            "    pip install playwright google-auth-oauthlib requests\n"
            f"Nothing was downloaded and {NOTHING_UPLOADED}."
        ) from exc

    path = token_path()
    credentials = None
    if path.exists():
        try:
            credentials = Credentials.from_authorized_user_file(str(path), [DRIVE_FILE_SCOPE])
        except (ValueError, OSError):
            credentials = None
    if credentials and not credentials.valid and credentials.refresh_token:
        try:
            credentials.refresh(Request())
        except Exception:  # noqa: BLE001 - a revoked token: ask again
            credentials = None
    if not credentials or not credentials.valid:
        flow = InstalledAppFlow.from_client_config(
            {
                "installed": {
                    "client_id": client_id,
                    "client_secret": client_secret,
                    "auth_uri": "https://accounts.google.com/o/oauth2/auth",
                    "token_uri": "https://oauth2.googleapis.com/token",
                    "redirect_uris": ["http://localhost"],
                }
            },
            scopes=[DRIVE_FILE_SCOPE],
        )
        print("A browser tab is opening for Google: allow LEGION to add files to your Drive.")
        credentials = flow.run_local_server(
            port=0, access_type="offline", prompt="consent", timeout_seconds=timeout
        )
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(credentials.to_json(), encoding="utf-8")
    return AuthorizedSession(credentials)


class DriveFolder:
    """The household's statements folder, seen through `drive.file`: only the
    files this app created are visible, which is exactly the set to dedupe
    against."""

    def __init__(self, session, folder_id: str):
        self.session = session
        self.folder_id = folder_id

    def existing(self) -> dict[str, str]:
        names: dict[str, str] = {}
        page_token = None
        while True:
            params = {
                "q": f"'{self.folder_id}' in parents and trashed = false",
                "fields": "nextPageToken, files(id, name)",
                "pageSize": 1000,
                "supportsAllDrives": "true",
                "includeItemsFromAllDrives": "true",
            }
            if page_token:
                params["pageToken"] = page_token
            response = self.session.get(DRIVE_FILES_URL, params=params, timeout=60)
            if response.status_code != 200:
                raise DriveRefused(
                    f"Drive would not list the statements folder (HTTP {response.status_code}). "
                    f"Nothing was downloaded and {NOTHING_UPLOADED}."
                )
            payload = response.json()
            for item in payload.get("files", []):
                names.setdefault(item["name"], item["id"])
            page_token = payload.get("nextPageToken")
            if not page_token:
                return names

    def upload(self, upload: Upload) -> None:
        content = upload.path.read_bytes()
        mime = mime_for(upload.name)
        if upload.action == "create":
            metadata = {"name": upload.name, "parents": [self.folder_id]}
            body, content_type = multipart_body(metadata, content, mime)
            response = self.session.post(
                DRIVE_UPLOAD_URL,
                params={"uploadType": "multipart", "supportsAllDrives": "true"},
                data=body,
                headers={"Content-Type": content_type},
                timeout=120,
            )
        else:
            body, content_type = multipart_body({}, content, mime)
            response = self.session.patch(
                f"{DRIVE_UPLOAD_URL}/{upload.file_id}",
                params={"uploadType": "multipart", "supportsAllDrives": "true"},
                data=body,
                headers={"Content-Type": content_type},
                timeout=120,
            )
        if response.status_code in (403, 404) and upload.action == "create":
            raise DriveRefused(foreign_folder_sentence(response.status_code))
        if response.status_code not in (200, 201):
            raise DriveRefused(
                f"Drive refused {upload.name} (HTTP {response.status_code})."
            )


# =============================================================================
# The browser half. `page` is a Playwright Page; tests pass a fake.
# =============================================================================


@dataclass
class Account:
    label: str
    last4: str
    kind: str = "unknown"
    activity: Path | None = None
    statements_found: int = 0
    statements_in_drive: int = 0
    statements: list[Path] = field(default_factory=list)
    notes: list[str] = field(default_factory=list)


class _Step:
    """`with _Step("..."):` turns anything a locator throws into PageChanged
    naming the step, so a missing element can never be skipped silently."""

    def __init__(self, name: str):
        self.name = name

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, tb):
        if exc is None or isinstance(exc, BofaError):
            return False
        if not isinstance(exc, Exception):
            return False
        raise PageChanged(self.name, f"{exc_type.__name__}") from exc


def _open_statements(page) -> None:
    """Go to the account's Statements & Documents page: by the link's own
    address when it has one, else by clicking the one that is visible. The page
    can carry two such links, one hidden, and `.first` picked the hidden one."""
    links = _role(page, STATEMENTS_LINK)
    links.first.wait_for(state="attached")
    visible = None
    for i in range(links.count()):
        link = links.nth(i)
        href = link.get_attribute("href") or ""
        if href and not href.startswith(("#", "javascript:")):
            page.goto(urljoin(page.url, href))
            return
        if visible is None and link.is_visible():
            visible = link
    (visible or links.first).click()


def _role(page, pair: tuple[str, str], exact: bool = True):
    role, name = pair
    return page.get_by_role(role, name=name, exact=exact)


def _select_containing(select, text: str) -> None:
    """Choose the option whose visible text contains `text`; raise if none."""
    labels = [" ".join(t.split()) for t in select.locator("option").all_inner_texts()]
    for label in labels:
        if text.lower() in label.lower():
            select.select_option(label=label)
            return
    raise LookupError(f"no option containing {text!r}")


def _save_download(page, click: Callable[[], None], dest: Path, suffix: str) -> Path:
    with page.expect_download() as info:
        click()
    download = info.value
    suggested = download.suggested_filename or ""
    if not suggested.lower().endswith(suffix):
        raise LookupError(f"download was {suggested!r}, not a {suffix} file")
    dest.parent.mkdir(parents=True, exist_ok=True)
    download.save_as(str(dest))
    return dest


def find_accounts(page) -> list[Account]:
    with _Step("the Accounts Overview"):
        page.goto(OVERVIEW_URL)
        links = page.locator(ACCOUNT_LINK_CSS)
        links.first.wait_for(state="visible")
        texts = links.all_inner_texts()
    seen: dict[str, Account] = {}
    for text in texts:
        parsed = parse_account_link(text)
        if parsed and parsed[1] not in seen:
            seen[parsed[1]] = Account(label=parsed[0], last4=parsed[1])
    if not seen:
        raise PageChanged("the Accounts Overview", "no account link reads '<name> - <last4>'")
    return list(seen.values())


def open_account(page, account: Account) -> None:
    step = f"opening account {account.last4}"
    with _Step(step):
        page.goto(OVERVIEW_URL)
        link = page.locator(ACCOUNT_LINK_CSS).filter(has_text=f"- {account.last4}").first
        link.click()
        checking = _role(page, CHECKING_DOWNLOAD_BUTTON)
        card = page.locator(CARD_DOWNLOAD_LINK)
        checking.or_(card).first.wait_for(state="visible")
    account.kind = account_kind(page.url)


def pull_activity(page, account: Account, out_dir: Path, today: datetime.date) -> None:
    dest = out_dir / activity_name(account.last4, today)
    if account.kind == "checking":
        with _Step(f"checking {account.last4}: the Download dialog"):
            _role(page, CHECKING_DOWNLOAD_BUTTON).first.click()
            _role(page, CHECKING_DIALOG).first.wait_for(state="visible")
            _select_containing(page.locator(CHECKING_PERIOD_SELECT), CURRENT_PERIOD_TEXT)
            page.locator(CHECKING_FILETYPE_SELECT).select_option(value=CHECKING_FILETYPE_VALUE)
            account.activity = _save_download(
                page, lambda: page.locator(CHECKING_SUBMIT).click(), dest, ".csv"
            )
        # The dialog stays open after the download and covers the page; the
        # statements step's click never landed behind it (dry run 2026-09-28).
        close = _role(page, CHECKING_DIALOG_CLOSE)
        if close.count() and close.first.is_visible():
            close.first.click()
        else:
            page.keyboard.press("Escape")
    else:
        with _Step(f"card {account.last4}: the download panel"):
            page.locator(CARD_DOWNLOAD_LINK).first.click()
            _select_containing(page.locator(CARD_PERIOD_SELECT), CURRENT_PERIOD_TEXT)
            _select_containing(page.locator(CARD_FILETYPE_SELECT), CARD_FILETYPE_TEXT)
            account.activity = _save_download(
                page, lambda: page.locator(CARD_SUBMIT).first.click(), dest, ".csv"
            )


def _statement_links(page, account: Account, step: str) -> list[tuple[int, object]]:
    """(month, locator) for every monthly statement link now listed. A link
    that looks like a statement but does not parse, or names another account,
    stops the run rather than being skipped."""
    generic = page.get_by_role("link", name=re.compile(r"^Download PDF for .+ Statement for "))
    found: list[tuple[int, object]] = []
    for index in range(generic.count()):
        link = generic.nth(index)
        snapshot = link.aria_snapshot()
        match = re.search(r'link "(?P<name>[^"]+)"', snapshot)
        parsed = parse_statement_link(match.group("name")) if match else None
        if parsed is None:
            raise PageChanged(step, "a statement link did not read 'Download PDF for <Month> Statement for <account> - <last4>'")
        month, last4 = parsed
        if last4 != account.last4:
            raise PageChanged(step, f"a statement link names account {last4}, not {account.last4}")
        found.append((month, link))
    return found


def pull_statements(
    page,
    account: Account,
    out_dir: Path,
    today: datetime.date,
    in_drive: set[str],
) -> None:
    open_step = f"{account.kind} {account.last4}: Statements & Documents"
    with _Step(open_step):
        _open_statements(page)
        year_select = _role(page, STATEMENTS_YEAR_SELECT)
        year_select.first.wait_for(state="visible")
    if STATEMENTS_PATH_MARK not in urlparse(page.url).path:
        detail = f"expected {STATEMENTS_PATH_MARK} in the address"
        if account.kind == "card":
            detail += "; the card's statements page was never seen live, so this is the first look"
        raise PageChanged(open_step, detail)

    with _Step(open_step):
        years_listed = {t.strip() for t in year_select.first.locator("option").all_inner_texts()}
    if not years_listed:
        raise PageChanged(open_step, "the year list is empty")

    for year in statement_years(today):
        if str(year) not in years_listed:
            account.notes.append(f"{year} is not offered")
            continue
        step = f"{account.kind} {account.last4}: statements for {year}"
        with _Step(step):
            year_select.first.select_option(label=str(year))
            page.wait_for_load_state("networkidle")
            expand = _role(page, STATEMENTS_EXPAND_BUTTON)
            if expand.count():
                expand.first.click()
            links = _statement_links(page, account, step)
        if not links:
            if year == today.year and today.month <= 2:
                account.notes.append(f"no {year} statement yet")
                continue
            raise PageChanged(step, "no statement links listed")
        for month, link in links:
            name = statement_name(account.last4, year, month)
            account.statements_found += 1
            if name in in_drive:
                account.statements_in_drive += 1
                continue
            if any(p.name == name for p in account.statements):
                continue
            with _Step(f"{step}: downloading {name}"):
                account.statements.append(
                    _save_download(page, link.click, out_dir / name, ".pdf")
                )


def pull_all(page, out_dir: Path, today: datetime.date, in_drive: set[str]) -> list[Account]:
    """Every account's files, into `out_dir`. Raises on the first step that
    does not look the way it did on the capture date."""
    page.set_default_timeout(LOCATOR_TIMEOUT_MS)
    accounts = find_accounts(page)
    for account in accounts:
        open_account(page, account)
        pull_activity(page, account, out_dir, today)
        pull_statements(page, account, out_dir, today, in_drive)
    return accounts


# =============================================================================
# Summary (names and counts only; never a balance or a transaction)
# =============================================================================


def summary_lines(accounts: list[Account], uploaded: set[str], dry_run: bool) -> list[str]:
    verb = "would upload" if dry_run else "uploaded"
    lines = []
    for account in accounts:
        csv = account.activity.name if account.activity else "none"
        pdf_names = {p.name for p in account.statements}
        sent = len(pdf_names & uploaded) if not dry_run else len(pdf_names)
        lines.append(
            f"{account.kind.capitalize()} {account.last4} ({account.label}): "
            f"activity CSV {csv}"
            + (f" ({verb})" if account.activity and (dry_run or csv in uploaded) else "")
            + f"; statements found {account.statements_found}, "
            f"already in Drive {account.statements_in_drive}, {verb} {sent}."
        )
        for note in account.notes:
            lines.append(f"    note: {note}")
    return lines


# =============================================================================
# The run
# =============================================================================


def default_out_dir(today: datetime.date) -> Path:
    return Path.home() / ".legion" / "bofa-pull" / today.isoformat()


def add_arguments(parser: argparse.ArgumentParser, env: Callable[[str], str | None]) -> None:
    parser.add_argument(
        "--client-id",
        default=env("LEGION_GOOGLE_CLIENT_ID"),
        help="The household's own OAuth client id (env LEGION_GOOGLE_CLIENT_ID).",
    )
    parser.add_argument(
        "--client-secret",
        default=env("LEGION_GOOGLE_CLIENT_SECRET"),
        help="Its client secret (env LEGION_GOOGLE_CLIENT_SECRET).",
    )
    parser.add_argument(
        "--statements-folder",
        default=env("LEGION_STATEMENTS_FOLDER"),
        help="The household's statements Drive folder, id or URL (env LEGION_STATEMENTS_FOLDER).",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Download everything, upload nothing, and print what would be uploaded. "
        "Drive is not consulted, so every statement counts as new.",
    )
    parser.add_argument(
        "--out",
        default=None,
        help="Where downloads land on this laptop (default ~/.legion/bofa-pull/<date>/).",
    )


def missing_arguments(args: argparse.Namespace) -> list[str]:
    if args.dry_run:
        return []
    missing = []
    if not args.client_id:
        missing.append("--client-id (or LEGION_GOOGLE_CLIENT_ID)")
    if not args.client_secret:
        missing.append("--client-secret (or LEGION_GOOGLE_CLIENT_SECRET)")
    if not args.statements_folder:
        missing.append("--statements-folder (or LEGION_STATEMENTS_FOLDER)")
    return missing


def run(
    args: argparse.Namespace,
    *,
    browser: Callable,
    drive_factory: Callable[[argparse.Namespace], DriveFolder] | None = None,
    today: datetime.date | None = None,
    out: Callable[[str], None] = print,
) -> int:
    """`browser(source, url, logged_in, timeout, nothing_done)` is a context
    manager yielding (context, page) once Kevin is logged in."""
    today = today or datetime.date.today()
    out_dir = Path(args.out).expanduser() if args.out else default_out_dir(today)

    folder: DriveFolder | None = None
    existing: dict[str, str] = {}
    if not args.dry_run:
        # Drive first: a refused token is found before Kevin logs in to the bank.
        factory = drive_factory or (
            lambda a: DriveFolder(
                drive_session(a.client_id, a.client_secret, a.timeout), a.statements_folder
            )
        )
        folder = factory(args)
        existing = folder.existing()

    with browser(
        "bofa",
        BOFA_HOME_URL,
        bofa_logged_in,
        args.timeout,
        f"Nothing was downloaded and {NOTHING_UPLOADED}.",
    ) as (_context, page):
        accounts = pull_all(page, out_dir, today, set(existing))

    files = [a.activity for a in accounts if a.activity] + [
        p for a in accounts for p in a.statements
    ]
    uploads, _skipped = plan_uploads(files, existing)

    out(f"Downloaded to {out_dir}")
    if args.dry_run:
        out("Dry run: nothing was uploaded. Would upload:")
        for upload in uploads:
            out(f"    {upload.name}")
        if not uploads:
            out("    (nothing)")
        for line in summary_lines(accounts, set(), dry_run=True):
            out(line)
        return 0

    uploaded: set[str] = set()
    try:
        for upload in uploads:
            folder.upload(upload)
            uploaded.add(upload.name)
    except DriveRefused as exc:
        if uploaded:
            tail = f"Uploaded before it stopped: {', '.join(sorted(uploaded))}. The rest was not."
        else:
            tail = f"{NOTHING_UPLOADED.capitalize()}."
        raise BofaError(f"{exc} {tail} The downloads are still in {out_dir}.") from exc
    for line in summary_lines(accounts, uploaded, dry_run=False):
        out(line)
    # Kevin, 2026-09-28: Drive is the one copy. After every upload succeeded, the
    # laptop's copies go, so no bank file is left lying on this machine. A failed
    # run above keeps them (its message says where) so nothing is lost.
    removed = remove_downloads(files, out_dir)
    out(f"Removed {removed} downloaded file(s) from this laptop; Drive holds them now.")
    return 0


def remove_downloads(files: Iterable[Path], out_dir: Path) -> int:
    """Delete this run's downloads, then the dated folder if it is now empty."""
    removed = 0
    for path in files:
        if path.exists():
            path.unlink()
            removed += 1
    try:
        out_dir.rmdir()
    except OSError:
        pass  # not empty (something else lives there) or already gone
    return removed
