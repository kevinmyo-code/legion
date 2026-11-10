from django.urls import path

from claude_session import views

urlpatterns = [
    path("tasks/", views.TaskListCreateView.as_view(), name="claude-tasks"),
    path("tasks/claim/", views.ClaimView.as_view(), name="claude-tasks-claim"),
    path("tasks/<int:task_id>/", views.TaskDetailView.as_view(), name="claude-task"),
    path("tasks/<int:task_id>/cancel/", views.TaskCancelView.as_view(), name="claude-task-cancel"),
    path("tasks/<int:task_id>/assign/", views.TaskAssignView.as_view(), name="claude-task-assign"),
    path(
        "tasks/<int:task_id>/send-file/",
        views.TaskSendFileView.as_view(),
        name="claude-task-send-file",
    ),
    path("tasks/<int:task_id>/finish/", views.FinishView.as_view(), name="claude-task-finish"),
    path("tasks/<int:task_id>/files/", views.FileUploadView.as_view(), name="claude-task-files"),
    path(
        "tasks/<int:task_id>/files/<int:file_id>/",
        views.FileDownloadView.as_view(),
        name="claude-task-file",
    ),
    path(
        "tasks/<int:task_id>/files/<int:file_id>/received/",
        views.FileReceivedView.as_view(),
        name="claude-task-file-received",
    ),
    path("pages/<int:file_id>/", views.PageView.as_view(), name="claude-page"),
    path("status/", views.StatusView.as_view(), name="claude-status"),
    path("machines/heartbeat/", views.HeartbeatView.as_view(), name="claude-heartbeat"),
    path("sessions/", views.SessionReportView.as_view(), name="claude-sessions"),
]
