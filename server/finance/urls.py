from django.urls import path

from finance import figure_views, views

urlpatterns = [
    path("accounts", views.FinanceAccountsView.as_view(), name="finance-accounts"),
    path("snapshots", views.SnapshotsView.as_view(), name="finance-snapshots"),
    path("networth", figure_views.NetWorthView.as_view(), name="finance-networth"),
    path("cashflow", figure_views.CashFlowView.as_view(), name="finance-cashflow"),
    path("runway", figure_views.RunwayView.as_view(), name="finance-runway"),
    path(
        "safe-to-spend", figure_views.SafeToSpendView.as_view(), name="finance-safe-to-spend"
    ),
    path("budgets", figure_views.BudgetsView.as_view(), name="finance-budgets"),
]
