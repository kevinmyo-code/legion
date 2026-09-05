"""Users are made here or by `manage.py createsuperuser` - there is no
in-app signup or invite flow (ADR 0044, backend-erp ticket 02's ruling 3,
carried over from the Supabase dashboard to this one)."""
from django.contrib import admin
from django.contrib.auth.admin import UserAdmin as DjangoUserAdmin

from household.models import DeviceToken, HouseholdMember, User


@admin.register(User)
class UserAdmin(DjangoUserAdmin):
    ordering = ["email"]
    list_display = ["email", "is_staff", "is_active"]
    search_fields = ["email"]
    fieldsets = (
        (None, {"fields": ("email", "password")}),
        ("Personal info", {"fields": ("first_name", "last_name")}),
        (
            "Permissions",
            {"fields": ("is_active", "is_staff", "is_superuser", "groups", "user_permissions")},
        ),
        ("Important dates", {"fields": ("last_login", "date_joined")}),
    )
    add_fieldsets = (
        (
            None,
            {
                "classes": ("wide",),
                "fields": ("email", "password1", "password2"),
            },
        ),
    )


@admin.register(DeviceToken)
class DeviceTokenAdmin(admin.ModelAdmin):
    list_display = ["name", "user", "created_at", "last_seen_at", "revoked_at"]
    readonly_fields = ["key_hash", "created_at", "last_seen_at"]


@admin.register(HouseholdMember)
class HouseholdMemberAdmin(admin.ModelAdmin):
    list_display = ["user", "joined_at"]
