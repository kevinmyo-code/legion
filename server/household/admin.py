"""Users are made here or by `manage.py createsuperuser` - there is no
in-app signup or invite flow yet (ADR 0044, backend-erp ticket 02's ruling 3,
carried over from the Supabase dashboard to this one). web-and-households
ticket 03 is what adds signup, invite codes and join; until it lands this and
`manage.py add_household_member` are the two doors.

**Every ModelAdmin here is household-scoped for a non-superuser** (ADR 0045).
A staff account that is a member of one household must not be able to read or
edit another household's users, device tokens or membership rows through the
admin - the API's choke point does not run here, so the same rule is spelled
out per model below. A superuser sees everything: it is the operator of the
engine, not a member of a family, and there is no honest way to run one
without that.
"""
from django.contrib import admin
from django.contrib.auth.admin import UserAdmin as DjangoUserAdmin

from household.models import DeviceToken, Household, HouseholdMember, User


class HouseholdScopedAdmin(admin.ModelAdmin):
    """`get_queryset` narrowed to the request user's household.

    `household_lookup` is the ORM path from THIS model to a household - it
    differs per model (a `User` reaches one through its membership row, a
    `Household` IS one), which is why it is an attribute rather than a
    hardcoded filter.

    A staff user who belongs to no household sees NOTHING rather than
    everything - `none()`, never the unfiltered queryset. That is the same
    fail-closed posture `household.tenancy.household_of` takes, and it is the
    direction that matters: the failure mode of guessing wrong here is
    showing one family another family's rows.
    """

    # An ID lookup, not an object one, so `Household` itself can use `pk`:
    # `Household.objects.filter(pk=<Household instance>)` is a ValidationError
    # ("... is not a valid UUID"), while `filter(pk=<uuid>)` is what every one
    # of these means.
    household_lookup: str = "household_id"

    def get_queryset(self, request):
        queryset = super().get_queryset(request)
        if request.user.is_superuser:
            return queryset
        household = getattr(request.user, "household", None)
        if household is None:
            return queryset.none()
        return queryset.filter(**{self.household_lookup: household.pk})


@admin.register(Household)
class HouseholdAdmin(HouseholdScopedAdmin):
    # A household reaches itself by its own primary key - see
    # `HouseholdScopedAdmin.household_lookup`.
    household_lookup = "pk"
    list_display = ["name", "id", "created_at"]
    readonly_fields = ["id", "created_at"]


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

    def get_queryset(self, request):
        """Scoped like the rest, but it cannot inherit `HouseholdScopedAdmin`
        - `DjangoUserAdmin` brings the whole password-change machinery this
        page needs, and Python's MRO would put one of the two first
        arbitrarily. Six lines repeated is cheaper than a mixin whose ordering
        has to be reasoned about every time either base changes.

        A user reaches a household through their one membership row
        (`household_member` is that `OneToOneField`'s `related_name`).
        """
        queryset = super().get_queryset(request)
        if request.user.is_superuser:
            return queryset
        household = getattr(request.user, "household", None)
        if household is None:
            return queryset.none()
        return queryset.filter(household_member__household_id=household.pk)


@admin.register(DeviceToken)
class DeviceTokenAdmin(HouseholdScopedAdmin):
    # A token reaches a household through the user it was issued to.
    household_lookup = "user__household_member__household_id"
    list_display = ["name", "user", "created_at", "last_seen_at", "revoked_at"]
    readonly_fields = ["key_hash", "created_at", "last_seen_at"]


@admin.register(HouseholdMember)
class HouseholdMemberAdmin(HouseholdScopedAdmin):
    list_display = ["user", "household", "role", "joined_at"]
    list_filter = ["role"]
