"""Which REST routes a recruiter's demo visit may use (recruiter-demo ticket 13,
Kevin's ruling 1, 2026-11-02; ADR 0069 amending ADR 0068).

Since ticket 13 a visitor is the only member of its own throwaway copy of the
demo household, so it uses the NORMAL web app on that copy: Money, Fleet,
Lists, Calendar, Places, Notes, and the rest, writes included. What stays
closed is everything that reaches outside the household's own rows (a bank
link, a feed's login, a device, a push endpoint, a laptop, the engine's MCP
door), the body aspect (ruling 3), and the household's own settings.

Every routed view that `IsHouseholdMember` guards is named in exactly one of
the three tables below, keyed by its dotted class path.
`tests/test_recruiter.py` walks the router and fails on a view that is in
none of them, so a route added next month is classified before it ships.
At run time a view in none of them is REFUSED (`unclassified_refusal`): the
walk is the reminder, the default is still deny.

- `RECRUITER_ALLOWED`: reads and writes, on the visit's own household, which
  tenancy (`household.tenancy`) already scopes every query to.
- `RECRUITER_READ_ONLY`: reads only; a write refuses with the reason given.
- `RECRUITER_DENIED`: refused outright, with the reason given, in words.

`allows_recruiter = True` on a view (the assistant doors, the demo's own
pages) still opens it, as before. One of those doors is
`POST /api/assistant/conversation` (2026-11-03): a visitor uploads its OWN
conversation record there, matched to the caller and the visit, and reads
nothing. `/api/device-logs/` stays denied below.
"""
from __future__ import annotations

RECRUITER_ALLOWED: frozenset[str] = frozenset(
    {
        # who am I / sign out
        "household.views.LogoutView",
        "household.views.SessionLogoutView",
        # dates and the changes feed (body and memory aspects refused inside
        # `api.changes.ChangesView`, see `RECRUITER_DENIED_ASPECTS`)
        "api.events.EventListCreateView",
        "api.events.EventDetailView",
        "api.event_skips.EventSkipListCreateView",
        "api.event_skips.EventSkipDetailView",
        "api.suggestion_pin_views.SuggestionPinCreateView",
        "api.suggestion_pin_views.SuggestionPinMineView",
        "api.changes.ChangesView",
        # places, notes (voice notes: ruling 4)
        "api.places.PlaceRenameView",
        "api.places.PlaceViewSet",
        "api.voice_notes.VoiceNoteViewSet",
        # ledger. The gated tables' own routes refuse writes for everyone.
        "api.ledger_spend.SpendView",
        "api.ledger.CategoryViewSet",
        "api.ledger.CategoryRuleViewSet",
        "api.ledger.MerchantAliasViewSet",
        "api.ledger.BudgetTargetViewSet",
        "api.ledger.LedgerTransactionCategoryViewSet",
        "api.ledger.StatementViewSet",
        "api.ledger.LedgerTransactionViewSet",
        # pantry
        "api.pantry.GroceryStapleViewSet",
        "api.pantry.ReceiptViewSet",
        "api.pantry.ReceiptLineItemViewSet",
        # fleet
        "api.fleet.VehicleViewSet",
        "api.fleet.ServiceHistoryViewSet",
        "api.fleet.DriveViewSet",
        "api.fleet.CodeEventViewSet",
        "api.fleet.CodeClearEventViewSet",
        "api.fleet.OilAnalysisViewSet",
        "api.fleet.BuildEntryViewSet",
        "api.fleet.DriveReassignmentViewSet",
        "api.fleet.ChassisQuirkViewSet",
        "api.fleet.VehicleSpecViewSet",
        "api.fleet.MaintenanceScheduleViewSet",
        "api.fleet.ObdSampleListView",
        "api.fleet.ObdSampleBatchView",
        "api.fleet.ObdSampleCountView",
        # lists and the bought log
        "checklists.views.ChecklistListCreateView",
        "checklists.views.ChecklistDetailView",
        "checklists.views.ChecklistItemListCreateView",
        "checklists.views.ChecklistItemDetailView",
        "checklists.views.ChecklistItemTickView",
        "checklists.views.ChecklistItemUntickView",
        "purchases.views.PurchaseListCreateView",
        "purchases.views.LastBoughtView",
        "purchases.views.PurchaseDetailView",
    }
)

_SETTINGS = "the household's own settings are not part of the demo"
_INSPECTOR = "the run inspector only reads the visit's own runs"

RECRUITER_READ_ONLY: dict[str, str] = {
    # The app shell reads both; neither may be changed on a visit.
    "household.views.MeView": "the visitor's account is made by the demo and cannot be renamed",
    "household.households.HouseholdMeView": _SETTINGS,
    # procedures ticket 14 (ruling 07): the run inspector and branch report, on the visit's
    # own runs. Tenancy scopes them to the visit household, and the views narrow a recruiter to
    # their own runs on top. GET only today; a write added later refuses here.
    "procedures.views.ProcedureListView": _INSPECTOR,
    "procedures.views.RunListView": _INSPECTOR,
    "procedures.views.RunDetailView": _INSPECTOR,
    "procedures.views.BranchReportView": _INSPECTOR,
}

_BANK = "linking or syncing a bank (Plaid) reaches an outside account"
_BODY = "the body aspect (health, food, sleep, workouts) is not part of the demo"
_MEMORY = "the assistant's memory of a household member is not part of the demo"
_UPLOAD = "uploading documents (Drive, ingestion) is not part of the demo"
_DEVICE = "it reaches a device or an account outside the household's own rows"
_CLAUDE = "the Claude session runs on a household member's own laptop"

RECRUITER_DENIED: dict[str, str] = {
    # household settings, members, invites, tokens, passwords
    "household.views.PasswordChangeView": "a visitor has no password to change",
    "household.views.DeviceListView": "device tokens are household settings",
    "household.views.DeviceDetailView": "device tokens are household settings",
    "household.households.InviteListCreateView": _SETTINGS,
    "household.households.InviteDetailView": _SETTINGS,
    "household.households.MemberDetailView": _SETTINGS,
    # body (ruling 3) and memory
    "api.body.BodyweightLogViewSet": _BODY,
    "api.body.MealLogViewSet": _BODY,
    "api.body.MealTargetViewSet": _BODY,
    "api.body.SleepLogViewSet": _BODY,
    "api.body.SleepTargetViewSet": _BODY,
    "api.body.WorkoutPlanViewSet": _BODY,
    "api.body.WorkoutPlanItemViewSet": _BODY,
    "api.body.WorkoutSetLogViewSet": _BODY,
    "api.memory.MemoryViewSet": _MEMORY,
    "api.memory.CompanionMemoryViewSet": _MEMORY,
    "api.memory.MemoryAuditViewSet": _MEMORY,
    # ingestion: uploads, feed logins, the bank link, the files ledger
    "api.ingest_files.IngestedFileViewSet": (
        "the document-ingestion ledger is not part of the demo"
    ),
    "ingest.views.StatementIngestView": _UPLOAD,
    "ingest.views.ReceiptIngestView": _UPLOAD,
    "ingest.sessions.SessionListView": "a feed's login reaches an outside account",
    "ingest.sessions.SessionDetailView": "a feed's login reaches an outside account",
    "ingest.plaid_views.BankStatusView": _BANK,
    "ingest.plaid_views.BankLinkTokenView": _BANK,
    "ingest.plaid_views.BankUpdateLinkTokenView": _BANK,
    "ingest.plaid_views.BankExchangeView": _BANK,
    "ingest.plaid_views.BankSyncView": _BANK,
    "ingest.freshness.FreshnessView": "the feeds' freshness reports outside accounts",
    # Claude session (ADR 0067): tasks for Claude Code on a member's laptops
    "claude_session.views.TaskListCreateView": _CLAUDE,
    "claude_session.views.TaskDetailView": _CLAUDE,
    "claude_session.views.TaskCancelView": _CLAUDE,
    "claude_session.views.TaskAssignView": _CLAUDE,
    "claude_session.views.TaskSendFileView": _CLAUDE,
    "claude_session.views.StatusView": _CLAUDE,
    "claude_session.views.HeartbeatView": _CLAUDE,
    "claude_session.views.ClaimView": _CLAUDE,
    "claude_session.views.FinishView": _CLAUDE,
    "claude_session.views.FileUploadView": _CLAUDE,
    "claude_session.views.FileDownloadView": _CLAUDE,
    "claude_session.views.SessionReportView": _CLAUDE,
    # devices, notifications, laptops
    "push.views.VapidKeyView": "notifications are sent to a member's own devices",
    "push.views.SubscriptionListCreateView": "notifications are sent to a member's own devices",
    "push.views.SubscriptionDetailView": "notifications are sent to a member's own devices",
    "push.views.PreferenceView": "notifications are sent to a member's own devices",
    "push.views.PreferenceOffView": "notifications are sent to a member's own devices",
    "devicelogs.views.DeviceLogUploadView": "device logs come from a member's phone",
    # alfred-builds ticket 02: a member's conversation records, read by the Claude worker.
    "devicelogs.records.DeviceLogRenderView": "device logs come from a member's phone",
    "games.views.OpponentsView": "games are played between household members",
    "games.views.MatchCreateView": "games are played between household members",
    "games.views.PendingView": "games are played between household members",
    "games.views.MatchDetailView": "games are played between household members",
    "games.views.AcceptView": "games are played between household members",
    "games.views.DeclineView": "games are played between household members",
    "games.views.CancelView": "games are played between household members",
    "games.views.ProgressView": "games are played between household members",
    "brief.views.BriefLatestView": (
        "the daily brief is built from outside feeds on the household's own key"
    ),
    "brief.views.BriefRefreshView": (
        "the daily brief is built from outside feeds on the household's own key"
    ),
    "books.views.BookConvertView": "books live in a folder on a member's laptop",
    "books.reading.BookPositionListView": "books live in a folder on a member's laptop",
    "books.reading.BookPositionDetailView": "books live in a folder on a member's laptop",
    "books.reading.BookBookmarkListView": "books live in a folder on a member's laptop",
    "books.reading.BookBookmarkDetailView": "books live in a folder on a member's laptop",
    # alfred-builds ticket 06: the household's legal book library.
    "books.library.LibraryListView": "the household's book library is not part of the demo",
    "books.library.LibraryDetailView": "the household's book library is not part of the demo",
    "books.library.LibraryDownloadView": "the household's book library is not part of the demo",
    # The engine's MCP door is for Claude Code on Kevin's machines.
    "engine_mcp.views.McpView": _DEVICE,
}

# `GET /api/changes?aspects=` is one route over every aspect; these are the
# ones a recruiter's visit may not pull through it.
RECRUITER_DENIED_ASPECTS: dict[str, str] = {
    "body": _BODY,
    "memory": _MEMORY,
    "ingest": "the document-ingestion ledger is not part of the demo",
}

REFUSAL_HEAD = "Nothing was read or written. This is a recruiter's demo visit, and"
WRITE_REFUSAL_HEAD = "Nothing was written. This is a recruiter's demo visit, and"


def view_key(view) -> str:
    cls = view if isinstance(view, type) else type(view)
    return f"{cls.__module__}.{cls.__qualname__}"


def denied_refusal(reason: str) -> str:
    return f"{REFUSAL_HEAD} {reason}."


def unclassified_refusal() -> str:
    return denied_refusal("this part of the engine has not been opened to demo visits")


def route_refusal(view, method: str, safe_methods) -> str | None:
    """None when a recruiter on its own visit household may use `view` with
    `method`; otherwise why not, in words."""
    if getattr(view, "allows_recruiter", False):
        return None
    key = view_key(view)
    if key in RECRUITER_ALLOWED:
        return None
    if key in RECRUITER_READ_ONLY:
        if method in safe_methods:
            return None
        return f"{WRITE_REFUSAL_HEAD} {RECRUITER_READ_ONLY[key]}."
    if key in RECRUITER_DENIED:
        return denied_refusal(RECRUITER_DENIED[key])
    return unclassified_refusal()
