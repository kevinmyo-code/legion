# RETIRED 2026-10-09 (ADR 0057, docs/adr/0057-the-bank-feed-is-the-ledgers-truth.md): Bank of America
# now comes from the bank connection (Plaid). Kept for history and as the way back; the server's
# Drive watcher skips the bofa_* files this writes.
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

**Transaction downloads only, never statement PDFs.** Kevin, 2026-09-28: *"lets
not use statements. only the transaction history. i just need to know what im
spending on."* This file does not visit Statements & Documents at all.

Per account, per run, from the account's own transaction-download control:
- "Current transactions", saved as `bofa_<last4>_activity_<YYYY-MM-DD>.csv`
  (today's date; a same-day re-run replaces that day's file in Drive);
- every CLOSED period the dropdown lists, ending within the last 13 months,
  that Drive does not already hold, saved as
  `bofa_<last4>_period_<YYYY-MM-DD>.csv`, dated by the period's END as BofA
  lists it.

The two names are how the server tells an open window from a closed one (the
content cannot): a checking `_period_` file is gated and verified, and it
supersedes the provisional rows the daily `_activity_` files left in its
window (`server/ingest/parsers/bofa_activity.py`). Nothing in this file reads
a balance or a transaction, and nothing prints one.
"""
from __future__ import annotations

import argparse
import calendar
import datetime
import json
import re
import uuid
from collections.abc import Callable, Iterable
from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import parse_qs, urlparse

# =============================================================================
# BofA's pages. Every locator lives here and nowhere else.
# Last seen working 2026-09-28 (checking 3119 and card 4146, captured live).
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
CHECKING_DIALOG_CLOSE = ("button", "close Dialog")
# A closed period in `#select_txnPeriod` reads "Period ending 09/04/2026".
CHECKING_PERIOD_OPTION = re.compile(r"^Period ending (?P<date>\d{2}/\d{2}/\d{4})$")

# Card
CARD_DOWNLOAD_LINK = "a[name=download_transactions_top]"   # class export-trans-view
CARD_PERIOD_SELECT = "#select_transaction"
CARD_FILETYPE_SELECT = "#select_filetype"
CARD_FILETYPE_TEXT = "Excel"
CARD_SUBMIT = "a.submit-download"
# A closed period in `#select_transaction` reads "September 05, 2026".
CARD_PERIOD_OPTION = re.compile(r"^(?P<month>[A-Za-z]+) (?P<day>\d{1,2}), (?P<year>\d{4})$")

CURRENT_PERIOD_TEXT = "Current transactions"

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


def parse_period_option(text: str, kind: str) -> datetime.date | None:
    """A closed period's END date from its dropdown label, None when the label
    is not a closed period (e.g. "Current transactions").

    Checking: `Period ending 09/04/2026`. Card: `September 05, 2026` (month
    names read here, not by `strptime`, so the laptop's locale cannot change
    the answer). A label that matches the shape but is not a real date is
    None too, and the caller treats that as a page change."""
    cleaned = " ".join((text or "").split())
    try:
        if kind == "checking":
            match = CHECKING_PERIOD_OPTION.match(cleaned)
            if not match:
                return None
            return datetime.datetime.strptime(match.group("date"), "%m/%d/%Y").date()
        match = CARD_PERIOD_OPTION.match(cleaned)
        if not match:
            return None
        month = MONTHS.get(match.group("month").lower())
        if month is None:
            return None
        return datetime.date(int(match.group("year")), month, int(match.group("day")))
    except ValueError:
        return None


def looks_like_period(text: str, kind: str) -> bool:
    """The label has a closed period's SHAPE (whether or not it parses). Used
    to tell "not a period" (skipped) from "a period we cannot read" (stop)."""
    cleaned = " ".join((text or "").split())
    if kind == "checking":
        return cleaned.lower().startswith("period ending")
    return bool(re.match(r"^[A-Za-z]+ \d{1,2}, \d{4}$", cleaned))


def account_kind(url: str) -> str:
    """`checking` for a `/deposit-details/` page, `card` for anything else."""
    return "checking" if CHECKING_PATH_MARK in urlparse(url).path else "card"


def activity_name(last4: str, day: datetime.date) -> str:
    """"Current transactions", pulled on `day`: the still-open window."""
    return f"bofa_{last4}_activity_{day.isoformat()}.csv"


def period_name(last4: str, period_end: datetime.date) -> str:
    """One closed period, dated by its end as BofA lists it."""
    return f"bofa_{last4}_period_{period_end.isoformat()}.csv"


PERIOD_MONTHS_BACK = 13


def period_cutoff(today: datetime.date) -> datetime.date:
    """The oldest period END this pulls: the same day `PERIOD_MONTHS_BACK`
    months ago (clamped to that month's last day)."""
    index = today.year * 12 + (today.month - 1) - PERIOD_MONTHS_BACK
    year, month = divmod(index, 12)
    month += 1
    return datetime.date(year, month, min(today.day, calendar.monthrange(year, month)[1]))


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


def is_activity(name: str) -> bool:
    return "_activity_" in name


def plan_uploads(files: Iterable[Path], existing: dict[str, str]) -> tuple[list[Upload], list[str]]:
    """(uploads, skipped names). `existing` maps a name already in the folder
    to its file id. A closed period already there is skipped (it never
    changes); today's current-transactions CSV already there (a same-day
    re-run) replaces that file rather than adding a second one with the same
    name."""
    uploads: list[Upload] = []
    skipped: list[str] = []
    for path in files:
        name = path.name
        if name in existing:
            if is_activity(name):
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
    # Transaction CSVs only (no statement PDFs, Kevin 2026-09-28).
    return "text/csv"


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
    periods_listed: int = 0
    periods_in_drive: int = 0
    periods: list[Path] = field(default_factory=list)
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


def _role(page, pair: tuple[str, str], exact: bool = True):
    role, name = pair
    return page.get_by_role(role, name=name, exact=exact)


def _option_labels(select) -> list[str]:
    return [" ".join(t.split()) for t in select.locator("option").all_inner_texts()]


def _select_containing(select, text: str) -> None:
    """Choose the option whose visible text contains `text`; raise if none."""
    for label in _option_labels(select):
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


# --- one download, per kind ------------------------------------------------------


def _checking_download(page, option_label: str, dest: Path) -> Path:
    """Open the "Download your data" dialog, choose `option_label` and CSV,
    download, then close the dialog: it stays open after a download and
    covers the page (dry run 2026-09-28), so the next click would miss."""
    dialog = _role(page, CHECKING_DIALOG)
    if not (dialog.count() and dialog.first.is_visible()):
        _role(page, CHECKING_DOWNLOAD_BUTTON).first.click()
        dialog.first.wait_for(state="visible")
    page.locator(CHECKING_PERIOD_SELECT).select_option(label=option_label)
    page.locator(CHECKING_FILETYPE_SELECT).select_option(value=CHECKING_FILETYPE_VALUE)
    saved = _save_download(page, lambda: page.locator(CHECKING_SUBMIT).click(), dest, ".csv")
    close = _role(page, CHECKING_DIALOG_CLOSE)
    if close.count() and close.first.is_visible():
        close.first.click()
    else:
        page.keyboard.press("Escape")
    return saved


def _card_download(page, option_label: str, dest: Path) -> Path:
    """The card's download panel: opened by its link only when its period
    select is not already showing (a second click could fold it away)."""
    select = page.locator(CARD_PERIOD_SELECT)
    if not (select.count() and select.first.is_visible()):
        page.locator(CARD_DOWNLOAD_LINK).first.click()
        select.first.wait_for(state="visible")
    select.select_option(label=option_label)
    _select_containing(page.locator(CARD_FILETYPE_SELECT), CARD_FILETYPE_TEXT)
    return _save_download(page, lambda: page.locator(CARD_SUBMIT).first.click(), dest, ".csv")


def _period_select(page, kind: str):
    return page.locator(CHECKING_PERIOD_SELECT if kind == "checking" else CARD_PERIOD_SELECT)


def _download(page, kind: str, option_label: str, dest: Path) -> Path:
    if kind == "checking":
        return _checking_download(page, option_label, dest)
    return _card_download(page, option_label, dest)


def _step_name(account: Account, what: str) -> str:
    where = "the Download dialog" if account.kind == "checking" else "the download panel"
    return f"{account.kind} {account.last4}: {where}, {what}"


def _listed_periods(page, account: Account) -> list[tuple[str, datetime.date]]:
    """(option label, period end) for every closed period the dropdown lists,
    newest first as BofA orders them. A label shaped like a period that does
    not read as a date stops the run; so does a dropdown with no period and
    no "Current transactions" at all."""
    step = _step_name(account, "the period list")
    with _Step(step):
        if account.kind == "checking":
            dialog = _role(page, CHECKING_DIALOG)
            if not (dialog.count() and dialog.first.is_visible()):
                _role(page, CHECKING_DOWNLOAD_BUTTON).first.click()
                dialog.first.wait_for(state="visible")
        else:
            select = page.locator(CARD_PERIOD_SELECT)
            if not (select.count() and select.first.is_visible()):
                page.locator(CARD_DOWNLOAD_LINK).first.click()
                select.first.wait_for(state="visible")
        labels = _option_labels(_period_select(page, account.kind))
    if not any(CURRENT_PERIOD_TEXT.lower() in label.lower() for label in labels):
        raise PageChanged(step, f"no '{CURRENT_PERIOD_TEXT}' option")
    periods = []
    for label in labels:
        end = parse_period_option(label, account.kind)
        if end is None:
            if looks_like_period(label, account.kind):
                raise PageChanged(step, "a closed period's label did not read as a date")
            continue
        periods.append((label, end))
    if not periods:
        raise PageChanged(step, "no closed period is listed")
    return periods


def pull_account(
    page, account: Account, out_dir: Path, today: datetime.date, in_drive: set[str]
) -> None:
    """Current transactions, then every closed period since the cutoff that
    Drive does not hold. A period already in Drive is never clicked."""
    periods = _listed_periods(page, account)

    with _Step(_step_name(account, "Current transactions")):
        current = next(
            label
            for label in _option_labels(_period_select(page, account.kind))
            if CURRENT_PERIOD_TEXT.lower() in label.lower()
        )
        account.activity = _download(
            page, account.kind, current, out_dir / activity_name(account.last4, today)
        )

    cutoff = period_cutoff(today)
    for label, end in periods:
        if end < cutoff or end > today:
            continue
        name = period_name(account.last4, end)
        account.periods_listed += 1
        if name in in_drive:
            account.periods_in_drive += 1
            continue
        if any(p.name == name for p in account.periods):
            continue
        with _Step(_step_name(account, f"downloading {name}")):
            account.periods.append(_download(page, account.kind, label, out_dir / name))


def pull_all(page, out_dir: Path, today: datetime.date, in_drive: set[str]) -> list[Account]:
    """Every account's files, into `out_dir`. Raises on the first step that
    does not look the way it did on the capture date."""
    page.set_default_timeout(LOCATOR_TIMEOUT_MS)
    accounts = find_accounts(page)
    for account in accounts:
        open_account(page, account)
        pull_account(page, account, out_dir, today, in_drive)
    return accounts


# =============================================================================
# Summary (names and counts only; never a balance or a transaction)
# =============================================================================


def summary_lines(accounts: list[Account], uploaded: set[str], dry_run: bool) -> list[str]:
    verb = "would upload" if dry_run else "uploaded"
    lines = []
    for account in accounts:
        csv = account.activity.name if account.activity else "none"
        period_names = {p.name for p in account.periods}
        sent = len(period_names & uploaded) if not dry_run else len(period_names)
        lines.append(
            f"{account.kind.capitalize()} {account.last4} ({account.label}): "
            f"current transactions {csv}"
            + (f" ({verb})" if account.activity and (dry_run or csv in uploaded) else "")
            + f"; closed periods listed {account.periods_listed}, "
            f"already in Drive {account.periods_in_drive}, {verb} {sent}."
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
        "Drive is not consulted, so every closed period counts as new.",
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
        p for a in accounts for p in a.periods
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
