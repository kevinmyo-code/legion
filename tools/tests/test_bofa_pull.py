"""backend-etl ticket 09: `connect_session.py bofa`, everything testable without
a browser or a network.

The browser half runs against `FakeBofa`, a scripted stand-in for the pages
seen live on 2026-09-28. It proves the flow and the stop-in-words behaviour;
it cannot prove BofA still looks like this. That is the live dry run with
Kevin.

Run from the repo root (no server conftest, no database):

    python -m pytest tools/tests -p no:cacheprovider --junitxml=<file>
"""
from __future__ import annotations

import argparse
import ast
import contextlib
import datetime
import importlib.util
import re
import sys
from pathlib import Path

import pytest

TOOLS = Path(__file__).resolve().parents[1]


def _load(name: str, filename: str):
    spec = importlib.util.spec_from_file_location(name, TOOLS / filename)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


bp = _load("legion_bofa_pull", "bofa_pull.py")
cs = _load("connect_session_under_test", "connect_session.py")

TODAY = datetime.date(2026, 9, 28)
FOLDER = "19tqQabcdefghijKLMNOP_-x"


@pytest.fixture(autouse=True)
def _no_env(monkeypatch):
    for name in (
        "LEGION_SERVER",
        "LEGION_TOKEN",
        "LEGION_GOOGLE_CLIENT_ID",
        "LEGION_GOOGLE_CLIENT_SECRET",
        "LEGION_STATEMENTS_FOLDER",
    ):
        monkeypatch.delenv(name, raising=False)


# =============================================================================
# Names
# =============================================================================


@pytest.mark.parametrize(
    "text, expected",
    [
        ("Adv SafeBalance Banking - 3119", ("Adv SafeBalance Banking", "3119")),
        (
            "Customized Cash Rewards Visa Signature - 4146",
            ("Customized Cash Rewards Visa Signature", "4146"),
        ),
        ("  Adv SafeBalance\n Banking  -  3119 ", ("Adv SafeBalance Banking", "3119")),
        ("View details", None),
        ("Adv SafeBalance Banking - 311", None),
        ("", None),
    ],
)
def test_account_link_text_gives_name_and_last4(text, expected):
    assert bp.parse_account_link(text) == expected


@pytest.mark.parametrize(
    "text, kind, expected",
    [
        ("Period ending 09/04/2026", "checking", datetime.date(2026, 9, 4)),
        ("  Period  ending 12/31/2025 ", "checking", datetime.date(2025, 12, 31)),
        ("Current transactions", "checking", None),
        ("Period ending 13/40/2026", "checking", None),
        ("September 05, 2026", "card", datetime.date(2026, 9, 5)),
        ("january 5, 2026", "card", datetime.date(2026, 1, 5)),
        ("Current transactions", "card", None),
        ("Smarch 05, 2026", "card", None),
        ("February 30, 2026", "card", None),
        # Each kind reads only its own label shape.
        ("September 05, 2026", "checking", None),
        ("Period ending 09/04/2026", "card", None),
    ],
)
def test_a_period_option_gives_its_end_date(text, kind, expected):
    assert bp.parse_period_option(text, kind) == expected


def test_account_kind_comes_from_the_path():
    assert bp.account_kind("https://secure.bankofamerica.com/deposit-details/activity/?a=1") == "checking"
    assert bp.account_kind("https://secure.bankofamerica.com/myaccounts/details/card/") == "card"


def test_file_names():
    assert bp.activity_name("3119", TODAY) == "bofa_3119_activity_2026-09-28.csv"
    assert bp.period_name("4146", datetime.date(2026, 9, 5)) == "bofa_4146_period_2026-09-05.csv"


def test_file_names_match_what_the_server_reads_when_server_is_present():
    """The server takes the account and the window from the NAME; the two
    must agree or every file quarantines."""
    parser = TOOLS.parent / "server" / "ingest" / "parsers" / "bofa_activity.py"
    if not parser.exists():
        pytest.skip("server/ not checked out")
    source = parser.read_text(encoding="utf-8")
    pattern = re.search(r'_FILE_NAME_RE = re\.compile\(\n(.*?)\n    re\.ASCII', source, re.S)
    regex = re.compile(
        "".join(ast.literal_eval(part.strip().rstrip(",")) for part in pattern.group(1).splitlines()),
        re.ASCII | re.IGNORECASE,
    )
    current = regex.search(bp.activity_name("3119", TODAY))
    period = regex.search(bp.period_name("3119", datetime.date(2026, 9, 4)))
    assert (current.group("last4"), current.group("kind")) == ("3119", "activity")
    assert (period.group("last4"), period.group("kind"), period.group("date")) == (
        "3119", "period", "2026-09-04"
    )


@pytest.mark.parametrize(
    "today, cutoff",
    [
        (datetime.date(2026, 9, 28), datetime.date(2025, 8, 28)),
        (datetime.date(2026, 1, 15), datetime.date(2024, 12, 15)),
        (datetime.date(2026, 3, 31), datetime.date(2025, 2, 28)),
    ],
)
def test_the_cutoff_is_thirteen_months_back(today, cutoff):
    assert bp.period_cutoff(today) == cutoff


def test_logged_in_signal():
    class P:
        def __init__(self, title, url):
            self._title, self.url = title, url

        def title(self):
            return self._title

    assert bp.bofa_logged_in(None, P("Bank of America | Accounts Overview", "https://x/"))
    assert bp.bofa_logged_in(None, P("", "https://secure.bankofamerica.com/myaccounts/brain/x"))
    assert not bp.bofa_logged_in(None, P("", "https://secure.bankofamerica.com/myaccounts/signin/x"))
    assert not bp.bofa_logged_in(None, P("Bank of America", "https://www.bankofamerica.com/"))


def test_no_statement_pdf_code_is_left():
    """Kevin, 2026-09-28: transaction history only. Nothing in the script may
    reach for Statements & Documents or a PDF again."""
    source = (TOOLS / "bofa_pull.py").read_text(encoding="utf-8")
    names = _called_names(TOOLS / "bofa_pull.py")
    for gone in ("pull_statements", "_open_statements", "statement_name", "STATEMENTS_LINK",
                 "STATEMENT_LINK_NAME", "statement_years", "parse_statement_link"):
        assert gone not in names
    assert "mycomm-acc-stmts-docs" not in source
    assert ".pdf" not in source and "application/pdf" not in source


# =============================================================================
# Folder ids (a copy of server/ingest/folder_ids.py)
# =============================================================================


@pytest.mark.parametrize(
    "value",
    [
        FOLDER,
        f"https://drive.google.com/drive/folders/{FOLDER}",
        f"https://drive.google.com/drive/folders/{FOLDER}?usp=sharing",
        f"https://drive.google.com/drive/u/0/folders/{FOLDER}",
        f"https://drive.google.com/open?id={FOLDER}",
        f"  {FOLDER}  ",
    ],
)
def test_folder_id_from_accepts_an_id_or_a_url(value):
    assert bp.folder_id_from(value) == FOLDER


@pytest.mark.parametrize("value", ["", "short", "https://example.com/folders/" + FOLDER])
def test_folder_id_from_refuses_anything_else(value):
    with pytest.raises(ValueError, match="Nothing was changed"):
        bp.folder_id_from(value)


def test_folder_id_copy_matches_the_server_rule_when_server_is_present():
    server_copy = TOOLS.parent / "server" / "ingest" / "folder_ids.py"
    if not server_copy.exists():
        pytest.skip("server/ not checked out")
    server = _load("server_folder_ids", "../server/ingest/folder_ids.py")
    for value in (FOLDER, f"https://drive.google.com/drive/u/0/folders/{FOLDER}"):
        assert bp.folder_id_from(value) == server.folder_id_from(value)
    assert bp._DRIVE_ID.pattern == server._ID.pattern


# =============================================================================
# The dedupe decision
# =============================================================================


def test_plan_skips_periods_in_drive_and_replaces_same_day_csv(tmp_path):
    files = [
        tmp_path / "bofa_3119_period_2026-09-04.csv",
        tmp_path / "bofa_3119_period_2026-08-04.csv",
        tmp_path / "bofa_3119_activity_2026-09-28.csv",
        tmp_path / "bofa_4146_activity_2026-09-28.csv",
    ]
    existing = {
        "bofa_3119_period_2026-08-04.csv": "id-period",
        "bofa_3119_activity_2026-09-28.csv": "id-csv",
    }
    uploads, skipped = bp.plan_uploads(files, existing)
    assert skipped == ["bofa_3119_period_2026-08-04.csv"]
    assert [(u.name, u.action, u.file_id) for u in uploads] == [
        ("bofa_3119_period_2026-09-04.csv", "create", None),
        ("bofa_4146_activity_2026-09-28.csv", "create", None),
        ("bofa_3119_activity_2026-09-28.csv", "update", "id-csv"),
    ]


def test_yesterdays_csv_does_not_stop_todays(tmp_path):
    uploads, skipped = bp.plan_uploads(
        [tmp_path / "bofa_3119_activity_2026-09-28.csv"],
        {"bofa_3119_activity_2026-09-27.csv": "old"},
    )
    assert skipped == []
    assert [(u.name, u.action) for u in uploads] == [("bofa_3119_activity_2026-09-28.csv", "create")]


def test_multipart_body_carries_metadata_and_content():
    body, content_type = bp.multipart_body({"name": "a.csv", "parents": ["p"]}, b"x,y", "text/csv")
    boundary = content_type.split("boundary=")[1]
    assert content_type.startswith("multipart/related; ")
    assert body.startswith(f"--{boundary}\r\n".encode())
    assert b'{"name": "a.csv", "parents": ["p"]}' in body
    assert b"Content-Type: text/csv\r\n\r\nx,y\r\n" in body
    assert body.endswith(f"--{boundary}--\r\n".encode())


# =============================================================================
# A scripted BofA
# =============================================================================


class FakeTimeout(Exception):
    """What Playwright raises when a locator finds nothing in time."""


class El:
    def __init__(self, text="", click=None, options=(), name=None, visible=True):
        self.text = text
        self.visible = visible
        self._click = click
        self.options = list(options)          # [(label, value)]
        self.name = name or text
        self.selected = None

    def click(self):
        if self._click:
            self._click()


class Download:
    def __init__(self, suggested: str, content: bytes):
        self.suggested_filename = suggested
        self.content = content

    def save_as(self, path):
        Path(path).write_bytes(self.content)


class FakeLocator:
    def __init__(self, page, key, *, index=None, has_text=None, alt=None, options_of=None):
        self.page, self.key = page, key
        self.index, self.has_text, self.alt, self.options_of = index, has_text, alt, options_of

    def _els(self):
        if self.options_of is not None:
            return self.options_of._need()
        els = list(self.page.find(self.key))
        if self.has_text:
            els = [e for e in els if self.has_text in e.text]
        if self.alt is not None:
            els += self.alt._els()
        if self.index is not None:
            els = els[self.index:self.index + 1]
        return els

    def _need(self):
        els = self._els()
        if not els:
            raise FakeTimeout(f"nothing matches {self.key}")
        return els[0]

    @property
    def first(self):
        return FakeLocator(self.page, self.key, index=0, has_text=self.has_text, alt=self.alt)

    def nth(self, i):
        return FakeLocator(self.page, self.key, index=i, has_text=self.has_text, alt=self.alt)

    def filter(self, has_text):
        return FakeLocator(self.page, self.key, has_text=has_text)

    def or_(self, other):
        return FakeLocator(self.page, self.key, alt=other)

    def count(self):
        return len(self._els())

    def all_inner_texts(self):
        if self.options_of is not None:
            return [label for label, _ in self.options_of._need().options]
        return [e.text for e in self._els()]

    def wait_for(self, state=None):
        self._need()

    def is_visible(self):
        els = self._els()
        return bool(els) and els[0].visible

    def click(self):
        self._need().click()

    def locator(self, css):
        assert css == "option"
        return FakeLocator(self.page, None, options_of=self)

    def select_option(self, label=None, value=None):
        el = self._need()
        for opt_label, opt_value in el.options:
            if (label is not None and opt_label == label) or (value is not None and opt_value == value):
                el.selected = opt_label
                self.page.on_select(el, opt_label)
                return
        raise FakeTimeout(f"no option {label or value}")


MONTH_NAMES = [
    "January", "February", "March", "April", "May", "June", "July",
    "August", "September", "October", "November", "December",
]

CHECKING = {"label": "Adv SafeBalance Banking", "last4": "3119", "kind": "checking"}
CARD = {"label": "Customized Cash Rewards Visa Signature", "last4": "4146", "kind": "card"}


def period_label(kind: str, end: datetime.date) -> str:
    if kind == "checking":
        return f"Period ending {end:%m/%d/%Y}"
    return f"{MONTH_NAMES[end.month - 1]} {end.day:02d}, {end.year}"


class FakeKeyboard:
    def __init__(self, page):
        self.page = page
        self.pressed = []

    def press(self, key):
        self.pressed.append(key)
        if key == "Escape":
            self.page.dialog_open = False


class FakeBofa:
    """Pages as seen 2026-09-28. `periods` is {last4: [period end dates]},
    listed newest first after "Current transactions". `missing` drops a
    locator key. The checking dialog's period select exists only while the
    dialog is open, and the card's only while its panel is open; the card's
    download link TOGGLES the panel, so clicking it twice folds it away."""

    def __init__(self, accounts, periods, *, missing=(), close_button=True):
        self.accounts = accounts
        self.periods = periods
        self.missing = set(missing)
        self.close_button = close_button
        self.url = "https://www.bankofamerica.com/"
        self.at = ("home", None)
        self.dialog_open = False
        self.panel_open = False
        self.period = None
        self.last_download = None
        self.downloads = []                    # (last4, period label)
        self.closed_by_button = 0
        self.keyboard = FakeKeyboard(self)

    # --- Playwright surface ---------------------------------------------------
    def set_default_timeout(self, ms):
        self.timeout = ms

    def goto(self, url):
        assert url == bp.OVERVIEW_URL, "the script visits no page but the overview"
        self.url = url
        self.at = ("overview", None)

    def title(self):
        return "Accounts Overview" if self.at[0] == "overview" else "Bank of America"

    def wait_for_load_state(self, state=None):
        pass

    def locator(self, css):
        return FakeLocator(self, ("css", css))

    def get_by_role(self, role, name=None, exact=False):
        return FakeLocator(self, ("role", role, name, exact))

    @contextlib.contextmanager
    def expect_download(self):
        page = self

        class Info:
            @property
            def value(self):
                if page.last_download is None:
                    raise FakeTimeout("no download")
                return page.last_download

        self.last_download = None
        yield Info()

    # --- the site -------------------------------------------------------------
    def _download(self, suggested, account):
        if self.period is None:
            raise FakeTimeout("no period chosen")
        self.last_download = Download(suggested, f"{account['last4']}|{self.period}".encode())
        self.downloads.append((account["last4"], self.period))

    def _open_account(self, account):
        self.at = ("account", account)
        self.dialog_open = self.panel_open = False
        self.period = None
        if account["kind"] == "checking":
            self.url = "https://secure.bankofamerica.com/deposit-details/activity/?adx=1"
        else:
            self.url = "https://secure.bankofamerica.com/myaccounts/details/card/account-details.go?adx=2"

    def on_select(self, el, label):
        if el.name == "period":
            self.period = label

    def find(self, key):
        if key is None:
            return []
        if key[0] == "css" and key[1] in self.missing:
            return []
        if key[0] == "role" and key[2] in self.missing:
            return []
        where, account = self.at
        els = self._elements(where, account)
        if key[0] == "css":
            return els.get(key[1], [])
        _, role, name, exact = key
        found = []
        for el_key, items in els.items():
            if not isinstance(el_key, tuple) or el_key[0] != role:
                continue
            for el in items:
                if exact:
                    ok = el.name == name
                else:
                    ok = name.lower() in el.name.lower()
                if ok:
                    found.append(el)
        return found

    def _period_options(self, account):
        options = [("Current transactions", "c")]
        for index, end in enumerate(self.periods.get(account["last4"], [])):
            options.append((period_label(account["kind"], end), f"p{index}"))
        return options

    def _elements(self, where, account):
        if where == "overview":
            links = []
            for acc in self.accounts:
                links.append(El(f"{acc['label']} - {acc['last4']}", click=lambda a=acc: self._open_account(a)))
                links.append(El("View details", click=lambda a=acc: self._open_account(a)))
            return {bp.ACCOUNT_LINK_CSS: links}
        if where == "account" and account["kind"] == "checking":
            buttons = [El("Download", click=self._open_dialog)]
            if self.dialog_open and self.close_button:
                buttons.append(El("close Dialog", click=self._close_dialog))
            els = {
                ("button",): buttons,
                ("dialog",): [El("Download your data")] if self.dialog_open else [],
            }
            if self.dialog_open:
                els.update({
                    "#select_txnPeriod": [El(name="period", options=self._period_options(account))],
                    "#select_fileType": [El(options=[("Microsoft Excel Format", "csv"), ("Quicken", "qfx")])],
                    "#btn-download-txn": [El(click=lambda: self._download("stmt.csv", account))],
                })
            return els
        if where == "account":
            last4 = account["last4"]
            els = {bp.CARD_DOWNLOAD_LINK: [El("Download", click=self._toggle_panel)]}
            if self.panel_open:
                els.update({
                    "#select_transaction": [El(name="period", options=self._period_options(account))],
                    "#select_filetype": [El(options=[("Microsoft Excel (.csv)", "x"), ("Quicken", "q")])],
                    "a.submit-download": [
                        El(click=lambda: self._download(f"currentTransaction_{last4}.csv", account))
                    ],
                })
            return els
        return {}

    def _open_dialog(self):
        self.dialog_open = True

    def _close_dialog(self):
        self.dialog_open = False
        self.closed_by_button += 1

    def _toggle_panel(self):
        self.panel_open = not self.panel_open


def _ends(*pairs):
    return [datetime.date(y, m, d) for y, m, d in pairs]


# Checking closes on the 4th, the card on the 5th. Newest first, as listed.
CHECKING_PERIODS = _ends(
    (2026, 9, 4), (2026, 8, 4), (2026, 7, 4), (2026, 6, 4), (2026, 5, 4), (2026, 4, 4),
    (2026, 3, 4), (2026, 2, 4), (2026, 1, 4), (2025, 12, 4), (2025, 11, 4), (2025, 10, 4),
    (2025, 9, 4), (2025, 8, 4),
    # Older than 13 months before TODAY (cutoff 2025-08-28): never pulled.
    (2025, 7, 4), (2024, 12, 4),
)
CARD_PERIODS = _ends((2026, 9, 5), (2026, 8, 5), (2025, 9, 5), (2025, 8, 5))


def _site(**kwargs):
    return FakeBofa(
        [CHECKING, CARD], {"3119": CHECKING_PERIODS, "4146": CARD_PERIODS}, **kwargs
    )


def _in_window(periods):
    cutoff = bp.period_cutoff(TODAY)
    return [end for end in periods if end >= cutoff]


# =============================================================================
# The browser half
# =============================================================================


def test_a_full_pull_names_every_file_and_skips_what_drive_holds(tmp_path):
    page = _site()
    held = _in_window(CHECKING_PERIODS)[1:]          # all but the newest checking period
    in_drive = {bp.period_name("3119", end) for end in held}
    accounts = bp.pull_all(page, tmp_path, TODAY, in_drive)

    by_last4 = {a.last4: a for a in accounts}
    assert list(by_last4) == ["3119", "4146"]
    checking, card = by_last4["3119"], by_last4["4146"]
    assert (checking.kind, card.kind) == ("checking", "card")
    assert checking.activity.name == "bofa_3119_activity_2026-09-28.csv"
    assert card.activity.name == "bofa_4146_activity_2026-09-28.csv"

    assert checking.periods_listed == 13 and checking.periods_in_drive == 12
    assert [p.name for p in checking.periods] == ["bofa_3119_period_2026-09-04.csv"]
    assert [p.name for p in card.periods] == [
        "bofa_4146_period_2026-09-05.csv",
        "bofa_4146_period_2026-08-05.csv",
        "bofa_4146_period_2025-09-05.csv",
    ]
    # A period already in Drive, or older than the cutoff, is never clicked.
    assert page.downloads == [
        ("3119", "Current transactions"),
        ("3119", "Period ending 09/04/2026"),
        ("4146", "Current transactions"),
        ("4146", "September 05, 2026"),
        ("4146", "August 05, 2026"),
        ("4146", "September 05, 2025"),
    ]
    # Each file holds what was chosen for it (the fake writes last4|option).
    assert (tmp_path / "bofa_4146_period_2026-08-05.csv").read_bytes() == b"4146|August 05, 2026"
    assert (tmp_path / "bofa_3119_activity_2026-09-28.csv").read_bytes() == b"3119|Current transactions"
    assert sorted(p.name for p in tmp_path.iterdir()) == sorted(
        [checking.activity.name, card.activity.name]
        + [p.name for p in checking.periods + card.periods]
    )


def test_the_checking_dialog_is_closed_after_every_download(tmp_path):
    page = FakeBofa([CHECKING], {"3119": _ends((2026, 9, 4), (2026, 8, 4))})
    bp.pull_all(page, tmp_path, TODAY, set())
    assert page.closed_by_button == 3
    assert not page.dialog_open


def test_escape_closes_the_dialog_when_it_has_no_close_button(tmp_path):
    page = FakeBofa([CHECKING], {"3119": _ends((2026, 9, 4))}, close_button=False)
    bp.pull_all(page, tmp_path, TODAY, set())
    assert page.keyboard.pressed == ["Escape", "Escape"]


def test_the_card_panel_is_not_folded_away_between_downloads(tmp_path):
    """The card's link toggles its panel. It is clicked only when the panel
    is not showing, so the second download does not fold it shut."""
    page = FakeBofa([CARD], {"4146": _ends((2026, 9, 5), (2026, 8, 5))})
    [account] = bp.pull_all(page, tmp_path, TODAY, set())
    assert len(account.periods) == 2
    assert page.panel_open


@pytest.mark.parametrize(
    "missing, step",
    [
        ({"#btn-download-txn"}, "checking 3119: the Download dialog, Current transactions"),
        ({"Download your data"}, "checking 3119: the Download dialog, the period list"),
        ({"#select_txnPeriod"}, "checking 3119: the Download dialog, the period list"),
        ({"a.submit-download"}, "card 4146: the download panel, Current transactions"),
        ({"#select_transaction"}, "card 4146: the download panel, the period list"),
        ({bp.CARD_DOWNLOAD_LINK}, "opening account 4146"),
        ({bp.ACCOUNT_LINK_CSS}, "the Accounts Overview"),
    ],
)
def test_a_missing_element_stops_the_run_naming_the_step(tmp_path, missing, step):
    with pytest.raises(bp.PageChanged) as raised:
        bp.pull_all(_site(missing=missing), tmp_path, TODAY, set())
    assert str(raised.value).startswith(f"BofA's page changed at {step}; nothing was uploaded.")


def test_an_option_that_is_gone_is_a_page_change(tmp_path):
    page = _site()
    original = page._elements

    def no_csv(where, account):
        els = original(where, account)
        if "#select_fileType" in els:
            els["#select_fileType"] = [El(options=[("Quicken", "qfx")])]
        return els

    page._elements = no_csv
    with pytest.raises(bp.PageChanged, match="checking 3119: the Download dialog"):
        bp.pull_all(page, tmp_path, TODAY, set())


def test_a_dropdown_without_current_transactions_is_a_page_change(tmp_path):
    page = FakeBofa([CARD], {"4146": CARD_PERIODS})
    page._period_options = lambda account: [("September 05, 2026", "p0")]
    with pytest.raises(bp.PageChanged, match="no 'Current transactions' option"):
        bp.pull_all(page, tmp_path, TODAY, set())


def test_a_dropdown_with_no_closed_period_is_a_page_change(tmp_path):
    """If BofA renames its period labels, the script would otherwise pull
    only the current window forever and nothing would ever be verified."""
    page = FakeBofa([CHECKING], {"3119": []})
    with pytest.raises(bp.PageChanged, match="no closed period is listed"):
        bp.pull_all(page, tmp_path, TODAY, set())


def test_a_period_label_that_is_not_a_date_is_a_page_change(tmp_path):
    page = FakeBofa([CHECKING], {"3119": []})
    page._period_options = lambda account: [
        ("Current transactions", "c"), ("Period ending 31/31/2026", "p0"),
    ]
    with pytest.raises(bp.PageChanged, match="did not read as a date"):
        bp.pull_all(page, tmp_path, TODAY, set())


def test_an_option_that_is_neither_is_passed_over(tmp_path):
    page = FakeBofa([CHECKING], {"3119": _ends((2026, 9, 4))})
    original = page._period_options
    page._period_options = lambda account: original(account) + [("Since last statement", "s")]
    [account] = bp.pull_all(page, tmp_path, TODAY, set())
    assert [p.name for p in account.periods] == ["bofa_3119_period_2026-09-04.csv"]


def test_no_account_link_that_parses_is_a_page_change(tmp_path):
    page = FakeBofa([{"label": "Weird", "last4": "12", "kind": "checking"}], {})
    with pytest.raises(bp.PageChanged, match="the Accounts Overview"):
        bp.pull_all(page, tmp_path, TODAY, set())


def test_the_summary_names_files_and_counts_only(tmp_path):
    accounts = bp.pull_all(_site(), tmp_path, TODAY, set())
    lines = bp.summary_lines(accounts, set(), dry_run=True)
    assert lines[0].startswith(
        "Checking 3119 (Adv SafeBalance Banking): current transactions "
        "bofa_3119_activity_2026-09-28.csv (would upload)"
    )
    assert "closed periods listed 13, already in Drive 0, would upload 13." in lines[0]
    assert all("$" not in line for line in lines)


# =============================================================================
# The run: dry run, Drive, nothing partial
# =============================================================================


def _browser(page, record=None):
    @contextlib.contextmanager
    def browser(source, url, logged_in, timeout, nothing_done):
        if record is not None:
            record.append((source, url, nothing_done))
        yield object(), page

    return browser


def _args(tmp_path, **overrides):
    values = dict(
        dry_run=False, out=str(tmp_path / "out"), timeout=5,
        client_id="cid", client_secret="csec", statements_folder=FOLDER,
    )
    values.update(overrides)
    return argparse.Namespace(**values)


class FakeDrive:
    def __init__(self, existing=None, refuse_create=None):
        self._existing = existing or {}
        self.refuse_create = refuse_create
        self.uploaded = []

    def existing(self):
        return dict(self._existing)

    def upload(self, upload):
        if self.refuse_create and upload.action == "create":
            raise bp.DriveRefused(bp.foreign_folder_sentence(self.refuse_create))
        self.uploaded.append((upload.name, upload.action))


def test_dry_run_touches_no_drive_and_says_what_it_would_upload(tmp_path):
    lines, record = [], []

    def no_drive(_args):
        raise AssertionError("a dry run must not reach Drive")

    code = bp.run(
        _args(tmp_path, dry_run=True, client_id=None, client_secret=None, statements_folder=None),
        browser=_browser(_site(), record), drive_factory=no_drive, today=TODAY, out=lines.append,
    )
    assert code == 0
    assert record == [("bofa", bp.BOFA_HOME_URL, "Nothing was downloaded and nothing was uploaded.")]
    assert "Dry run: nothing was uploaded. Would upload:" in lines
    assert "    bofa_3119_activity_2026-09-28.csv" in lines
    assert "    bofa_4146_period_2025-09-05.csv" in lines
    # A dry run keeps its files to be looked at.
    assert len(list((tmp_path / "out").iterdir())) == 2 + 13 + 3


def test_default_out_dir_is_under_home_not_the_repo(monkeypatch, tmp_path):
    monkeypatch.setattr(bp.Path, "home", classmethod(lambda cls: tmp_path))
    assert bp.default_out_dir(TODAY) == tmp_path / ".legion" / "bofa-pull" / "2026-09-28"
    assert bp.token_path() == tmp_path / ".legion" / "bofa-drive-token.json"


def test_a_real_run_creates_new_files_and_replaces_todays_csv(tmp_path):
    held = _in_window(CHECKING_PERIODS)[1:]
    drive = FakeDrive(existing={
        "bofa_3119_activity_2026-09-28.csv": "csv-id",
        **{bp.period_name("3119", end): f"p{i}" for i, end in enumerate(held)},
    })
    lines = []
    code = bp.run(_args(tmp_path), browser=_browser(_site()), drive_factory=lambda a: drive,
                  today=TODAY, out=lines.append)
    assert code == 0
    assert ("bofa_3119_activity_2026-09-28.csv", "update") in drive.uploaded
    assert ("bofa_4146_activity_2026-09-28.csv", "create") in drive.uploaded
    assert ("bofa_3119_period_2026-09-04.csv", "create") in drive.uploaded
    assert not any(name in {bp.period_name("3119", e) for e in held} for name, _ in drive.uploaded)
    assert len(drive.uploaded) == 2 + 1 + 3
    assert any("already in Drive 12, uploaded 1." in line for line in lines)
    # Kevin, 2026-09-28: Drive is the one copy, so the laptop keeps none.
    assert not (tmp_path / "out").exists()
    assert any(line.startswith("Removed 6 ") for line in lines)


def test_a_page_change_uploads_nothing(tmp_path):
    drive = FakeDrive()
    with pytest.raises(bp.PageChanged, match="nothing was uploaded"):
        bp.run(_args(tmp_path), browser=_browser(_site(missing={"a.submit-download"})),
               drive_factory=lambda a: drive, today=TODAY, out=lambda s: None)
    assert drive.uploaded == []


def test_drive_refusing_the_foreign_folder_says_so_and_uploads_nothing(tmp_path):
    drive = FakeDrive(refuse_create=404)
    with pytest.raises(bp.BofaError) as raised:
        bp.run(_args(tmp_path), browser=_browser(_site()), drive_factory=lambda a: drive,
               today=TODAY, out=lambda s: None)
    message = str(raised.value)
    assert "HTTP 404" in message and "drive.file" in message
    assert "Nothing was uploaded." in message
    assert drive.uploaded == []
    # A failed run keeps the downloads, and says where.
    assert any((tmp_path / "out").iterdir())
    assert str(tmp_path / "out") in message


# =============================================================================
# DriveFolder against a fake HTTP session
# =============================================================================


class Resp:
    def __init__(self, status, payload=None):
        self.status_code, self._payload = status, payload or {}

    def json(self):
        return self._payload


class Session:
    def __init__(self, gets=(), post_status=200, patch_status=200):
        self.gets = list(gets)
        self.post_status, self.patch_status = post_status, patch_status
        self.calls = []

    def get(self, url, params=None, timeout=None):
        self.calls.append(("GET", url, params))
        return self.gets.pop(0)

    def post(self, url, params=None, data=None, headers=None, timeout=None):
        self.calls.append(("POST", url, params))
        return Resp(self.post_status)

    def patch(self, url, params=None, data=None, headers=None, timeout=None):
        self.calls.append(("PATCH", url, params))
        return Resp(self.patch_status)


def test_existing_follows_pages_and_scopes_to_the_folder():
    session = Session(gets=[
        Resp(200, {"files": [{"id": "1", "name": "a.csv"}], "nextPageToken": "t"}),
        Resp(200, {"files": [{"id": "2", "name": "b.csv"}, {"id": "3", "name": "a.csv"}]}),
    ])
    folder = bp.DriveFolder(session, FOLDER)
    assert folder.existing() == {"a.csv": "1", "b.csv": "2"}
    assert session.calls[0][2]["q"] == f"'{FOLDER}' in parents and trashed = false"
    assert session.calls[1][2]["pageToken"] == "t"


@pytest.mark.parametrize("status", [403, 404])
def test_a_refused_create_names_the_scope_problem(tmp_path, status):
    path = tmp_path / "bofa_3119_period_2026-09-04.csv"
    path.write_bytes(b"x")
    folder = bp.DriveFolder(Session(post_status=status), FOLDER)
    with pytest.raises(bp.DriveRefused, match="drive.file"):
        folder.upload(bp.Upload(path.name, path, "create"))


def test_update_patches_the_existing_file(tmp_path):
    path = tmp_path / "bofa_3119_activity_2026-09-28.csv"
    path.write_bytes(b"x")
    session = Session()
    bp.DriveFolder(session, FOLDER).upload(bp.Upload(path.name, path, "update", "fid"))
    assert session.calls == [("PATCH", f"{bp.DRIVE_UPLOAD_URL}/fid",
                              {"uploadType": "multipart", "supportsAllDrives": "true"})]


# =============================================================================
# connect_session: arguments, and bofa never reaches the server
# =============================================================================


def test_bofa_takes_no_server_or_token():
    with pytest.raises(SystemExit):
        cs.parse_args(["bofa", "--dry-run", "--server", "https://s", "--token", "t"])


def test_bofa_dry_run_needs_no_drive_settings():
    args = cs.parse_args(["bofa", "--dry-run"])
    assert args.source == "bofa" and args.dry_run
    assert not hasattr(args, "server") and not hasattr(args, "token")


def test_bofa_real_run_names_what_is_missing(capsys):
    with pytest.raises(SystemExit):
        cs.parse_args(["bofa"])
    err = capsys.readouterr().err
    assert "--client-id" in err and "--statements-folder (or LEGION_STATEMENTS_FOLDER)" in err


def test_bofa_folder_url_is_turned_into_an_id(monkeypatch):
    monkeypatch.setenv("LEGION_GOOGLE_CLIENT_ID", "cid")
    monkeypatch.setenv("LEGION_GOOGLE_CLIENT_SECRET", "sec")
    monkeypatch.setenv("LEGION_STATEMENTS_FOLDER", f"https://drive.google.com/drive/folders/{FOLDER}")
    args = cs.parse_args(["bofa"])
    assert args.statements_folder == FOLDER


def test_put_session_still_refuses_bofa():
    sent = []
    with pytest.raises(cs.ConnectError, match="never sent"):
        cs.put_session("https://s", "tok", "bofa", {"cookies": []},
                       http_put=lambda *a, **k: sent.append(a))
    assert sent == []


def test_the_bofa_path_never_calls_put_session(monkeypatch, tmp_path, capsys):
    def boom(*a, **k):
        raise AssertionError("bofa reached the server path")

    monkeypatch.setattr(cs, "put_session", boom)
    monkeypatch.setattr(cs, "build_put", boom)
    monkeypatch.setattr(cs, "logged_in_browser", _browser(_site()))
    assert cs.main(["bofa", "--dry-run", "--out", str(tmp_path)]) == 0
    assert "Dry run: nothing was uploaded." in capsys.readouterr().out


def test_a_page_change_through_main_exits_1_in_words(monkeypatch, tmp_path, capsys):
    monkeypatch.setattr(cs, "logged_in_browser", _browser(_site(missing={"a.submit-download"})))
    assert cs.main(["bofa", "--dry-run", "--out", str(tmp_path)]) == 1
    assert "BofA's page changed at card 4146: the download panel, Current transactions; nothing was uploaded." in capsys.readouterr().err


def _called_names(path: Path) -> set[str]:
    tree = ast.parse(path.read_text(encoding="utf-8"))
    names = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Name):
            names.add(node.id)
        elif isinstance(node, ast.Attribute):
            names.add(node.attr)
    return names


def test_no_code_in_the_bofa_path_names_the_server_route():
    """Static: bofa_pull.py has no identifier for the vault route, and
    run_bofa's body in connect_session.py does not either."""
    forbidden = {"put_session", "build_put", "cookies", "LEGION_SERVER", "LEGION_TOKEN"}
    assert not (_called_names(TOOLS / "bofa_pull.py") & forbidden)
    source = (TOOLS / "bofa_pull.py").read_text(encoding="utf-8")
    assert "/api/" not in source

    tree = ast.parse((TOOLS / "connect_session.py").read_text(encoding="utf-8"))
    [run_bofa] = [n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == "run_bofa"]
    used = {n.id for n in ast.walk(run_bofa) if isinstance(n, ast.Name)}
    used |= {n.attr for n in ast.walk(run_bofa) if isinstance(n, ast.Attribute)}
    assert not (used & {"put_session", "build_put", "server", "token", "cookies"})
