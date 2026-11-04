"""Read-only admin. Lists show ids, states and times only: `__str__` and
`list_display` never carry a request, summary or file name, because admin's
`LogEntry.object_repr` stores `str(obj)`."""

from django.contrib import admin

from claude_session.models import ClaudeCodeSession, ClaudeMachine, ClaudeTask, ClaudeTaskFile


class _ReadOnly(admin.ModelAdmin):
    def has_add_permission(self, request):
        return False

    def has_change_permission(self, request, obj=None):
        return False


@admin.register(ClaudeMachine)
class ClaudeMachineAdmin(_ReadOnly):
    list_display = ("id", "household", "scope", "reported_state", "last_seen_at")


@admin.register(ClaudeTask)
class ClaudeTaskAdmin(_ReadOnly):
    list_display = ("id", "household", "machine", "status", "created_at", "finished_at")
    list_filter = ("status",)
    exclude = ("request", "summary", "question", "progress", "steps")


@admin.register(ClaudeTaskFile)
class ClaudeTaskFileAdmin(_ReadOnly):
    list_display = ("id", "task", "size", "requested_at", "uploaded_at")
    exclude = ("path", "name", "content")


@admin.register(ClaudeCodeSession)
class ClaudeCodeSessionAdmin(_ReadOnly):
    list_display = ("id", "household", "machine", "state", "last_seen_at")
    exclude = ("last_prompt_summary",)
