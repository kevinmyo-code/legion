"""DOT's admin pages for OAuth clients and tokens, for superusers only
(ai-upgrade ticket 03).

An OAuth client is not a household's row: Claude's CIMD client is one row for
every member on the engine, and its tokens belong to members of different
households. So these pages are the operator's, never a household owner's, and
a staff user who is not a superuser sees no OAuth section at all. Wired through
DOT's `*_ADMIN_CLASS` settings. `manage.py mcp_clients` is the shell version.
"""

from oauth2_provider import admin as dot_admin


class _SuperuserOnly:
    def has_module_permission(self, request):
        return bool(getattr(request.user, "is_superuser", False))

    def has_view_permission(self, request, obj=None):
        return self.has_module_permission(request)

    def has_change_permission(self, request, obj=None):
        return self.has_module_permission(request)

    def has_delete_permission(self, request, obj=None):
        return self.has_module_permission(request)

    def has_add_permission(self, request):
        return False


class ApplicationAdmin(_SuperuserOnly, dot_admin.ApplicationAdmin):
    pass


class AccessTokenAdmin(_SuperuserOnly, dot_admin.AccessTokenAdmin):
    pass


class GrantAdmin(_SuperuserOnly, dot_admin.GrantAdmin):
    pass


class IDTokenAdmin(_SuperuserOnly, dot_admin.IDTokenAdmin):
    pass


class RefreshTokenAdmin(_SuperuserOnly, dot_admin.RefreshTokenAdmin):
    pass
