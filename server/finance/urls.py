from django.urls import path

from finance import views

urlpatterns = [
    path("accounts", views.FinanceAccountsView.as_view(), name="finance-accounts"),
    path("snapshots", views.SnapshotsView.as_view(), name="finance-snapshots"),
]
