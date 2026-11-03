from django.urls import path

from procedures.views import BranchReportView, ProcedureListView, RunDetailView, RunListView

# procedures ticket 14: the run inspector and the branch report, read-only.
urlpatterns = [
    path("", ProcedureListView.as_view(), name="procedures-list"),
    path("runs", RunListView.as_view(), name="procedures-runs"),
    path("runs/<uuid:run_id>", RunDetailView.as_view(), name="procedures-run"),
    path("report", BranchReportView.as_view(), name="procedures-report"),
]
