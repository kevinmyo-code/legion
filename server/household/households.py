"""`/api/households/me` - the household as a resource an owner administers.

Kept out of `household/views.py` on purpose. That file holds the doors a
CREDENTIAL goes through (log in, sign up, redeem a code, list your own
devices); this one holds the one thing a household IS - a name, a member
roster, and a set of outstanding invites.

**This is the only endpoint on this API that puts a household uuid on the
wire, and it is allowed to.**
`tests/test_tenancy.py::test_no_openapi_component_declares_household_id`
forbids a `household`/`household_id` PROPERTY on any component, because a
tenancy field on a data row is either useless to a client (it already knows
which household it is in - it cannot be in another) or dangerous (a client
that could send one would be choosing its own tenancy). A resource
describing itself with `id` is neither. Read that test before adding a field
here.

**Owner is the only role, and it governs membership only (ADR 0045).** The
owner-only routes below mint invites, revoke them, and remove members.
Nothing an owner can see that a member cannot; `IsHouseholdOwner` guards
exactly these four verbs and must never spread to a data route.
"""
from __future__ import annotations

from django.db.models import F
from django.utils import timezone
from drf_spectacular.utils import OpenApiResponse, extend_schema
from rest_framework import status
from rest_framework.response import Response
from rest_framework.views import APIView

from api.schema import DetailSerializer
from household.models import DeviceToken, Household, HouseholdMember, Invite
from household.permissions import IsHouseholdOwner
from household.serializers import (
    HouseholdPatchRequestSerializer,
    HouseholdSerializer,
    InviteCreateRequestSerializer,
    InviteSerializer,
)
from household.tenancy import household_of
from household.views import join_url_for, refuse

HOUSEHOLD_TAGS = ["households"]

OWNER_ONLY = OpenApiResponse(
    response=DetailSerializer,
    description=(
        "Nothing was changed: this route is owner-only. `owner` is the ONLY role there "
        "is and it governs membership alone - an owner and a member see exactly the same "
        "data (ADR 0045)."
    ),
)


def household_body(household: Household) -> dict:
    """The household and its roster, in the shape `HouseholdSerializer`
    describes. One function so GET and PATCH cannot answer differently."""
    members = (
        HouseholdMember.objects.filter(household=household)
        .select_related("user")
        .order_by("joined_at")
    )
    return HouseholdSerializer(
        {
            "id": household.id,
            "name": household.name,
            "created_at": household.created_at,
            "members": [
                {
                    "user_id": member.user_id,
                    "email": member.user.email,
                    "name": member.user.first_name,
                    "role": member.role,
                    "joined_at": member.joined_at,
                }
                for member in members
            ],
        }
    ).data


def invites_of(household: Household):
    """Every invite minted by a member of this household.

    Keyed on the CREATOR's membership rather than on `Invite.household`,
    because that column cannot answer the question on its own in either
    direction: an unspent `creates_household` code has it null (the household
    does not exist yet), and a spent one has it pointing at the household the
    code FOUNDED, which is somebody else's. Either way the owner who minted
    it is the person who should be able to see and revoke it.

    A member who is removed has their outstanding invites revoked at that
    moment (`MemberDetailView.delete`), so no live invite is ever orphaned
    out of this queryset by the join it depends on disappearing.
    """
    return Invite.objects.filter(created_by__household_member__household=household)


def live_invites_of(household: Household):
    return invites_of(household).filter(
        revoked_at__isnull=True,
        expires_at__gt=timezone.now(),
        used_count__lt=F("max_uses"),
    )


class HouseholdMeView(APIView):
    """`GET`/`PATCH /api/households/me` - the caller's household.

    GET is open to any member: the roster is not owner-only information, and
    a member who could not see who else is in their household would have no
    way to check they joined the right one. PATCH (rename) is owner-only,
    which is why `get_permissions` splits by method rather than the class
    carrying one permission for both.
    """

    def get_permissions(self):
        if self.request.method == "PATCH":
            return [IsHouseholdOwner()]
        return super().get_permissions()

    @extend_schema(
        operation_id="api_households_me_retrieve",
        tags=HOUSEHOLD_TAGS,
        responses={
            200: OpenApiResponse(
                response=HouseholdSerializer,
                description=(
                    "The caller's household and everyone in it, oldest member first. "
                    "`id` is the household's own id - this is the household resource, and "
                    "the one place on this API a household uuid appears."
                ),
            ),
            403: OpenApiResponse(
                response=DetailSerializer,
                description="The credential is live but its user is in no household.",
            ),
        },
    )
    def get(self, request):
        return Response(household_body(household_of(request)), status=status.HTTP_200_OK)

    @extend_schema(
        operation_id="api_households_me_partial_update",
        tags=HOUSEHOLD_TAGS,
        request=HouseholdPatchRequestSerializer,
        responses={
            200: OpenApiResponse(
                response=HouseholdSerializer,
                description="Renamed. The body is the household as stored.",
            ),
            403: OWNER_ONLY,
        },
    )
    def patch(self, request):
        household = household_of(request)
        serializer = HouseholdPatchRequestSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        household.name = serializer.validated_data["name"].strip()
        household.save(update_fields=["name"])
        return Response(household_body(household), status=status.HTTP_200_OK)


class InviteListCreateView(APIView):
    """`GET`/`POST /api/households/me/invites`. Owner-only, both.

    **The code comes back in full exactly once, from POST.** GET lists it
    again for LIVE invites only, which is a deliberate and narrow
    re-exposure: an owner who minted a code and lost the message has to be
    able to read it back, and a live code is one that is going to be typed
    into a signup form anyway. A spent, expired or revoked code is not listed
    at all, so its value never appears again.
    """

    permission_classes = [IsHouseholdOwner]

    @extend_schema(
        operation_id="api_households_me_invites_list",
        tags=HOUSEHOLD_TAGS,
        responses={
            200: OpenApiResponse(
                response=InviteSerializer(many=True),
                description=(
                    "Live invites minted by this household, newest first, code included. "
                    "Revoked, expired and fully-used invites are not listed."
                ),
            ),
            403: OWNER_ONLY,
        },
    )
    def get(self, request):
        household = household_of(request)
        invites = live_invites_of(household).order_by("-created_at")
        body = InviteSerializer(
            [
                {
                    "id": invite.id,
                    "code": invite.code,
                    "join_url": join_url_for(request, invite.code),
                    "creates_household": invite.creates_household,
                    "max_uses": invite.max_uses,
                    "used_count": invite.used_count,
                    "expires_at": invite.expires_at,
                    "created_at": invite.created_at,
                }
                for invite in invites
            ],
            many=True,
        ).data
        return Response(body, status=status.HTTP_200_OK)

    @extend_schema(
        operation_id="api_households_me_invites_create",
        tags=HOUSEHOLD_TAGS,
        request=InviteCreateRequestSerializer,
        responses={
            201: OpenApiResponse(
                response=InviteSerializer,
                description=(
                    "A new invite. `join_url` is the whole thing to send someone - it "
                    "carries the code and points at the SPA's /join screen on this same "
                    "host."
                ),
            ),
            403: OWNER_ONLY,
        },
    )
    def post(self, request):
        household = household_of(request)
        serializer = InviteCreateRequestSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        data = serializer.validated_data

        creates = data["creates_household"]
        invite, code = Invite.mint(
            created_by=request.user,
            # Null for a code that FOUNDS a household: the household it will
            # belong to does not exist until the first person signs up with it
            # and names it. `Invite.household` is filled in at that moment, so
            # a second use of the same code joins what the first one founded.
            household=None if creates else household,
            creates_household=creates,
            max_uses=data["max_uses"],
            expires_in_days=data["expires_in_days"],
        )
        body = InviteSerializer(
            {
                "id": invite.id,
                "code": code,
                "join_url": join_url_for(request, code),
                "creates_household": invite.creates_household,
                "max_uses": invite.max_uses,
                "used_count": invite.used_count,
                "expires_at": invite.expires_at,
                "created_at": invite.created_at,
            }
        ).data
        return Response(body, status=status.HTTP_201_CREATED)


class InviteDetailView(APIView):
    """`DELETE /api/households/me/invites/<code>`. Revokes a code this
    household minted, so nobody else can sign up with it.

    Revoke rather than delete: `revoked_at` is why
    `Invite.unavailable_reason` can tell someone holding the link "that was
    revoked by the person who made it" instead of "no such code", which is
    the difference between a person who knows to ask again and a person who
    thinks they mistyped it.
    """

    permission_classes = [IsHouseholdOwner]

    @extend_schema(
        operation_id="api_households_me_invites_destroy",
        tags=HOUSEHOLD_TAGS,
        responses={
            204: OpenApiResponse(
                description=(
                    "That code is revoked and can no longer be signed up with. Revoking an "
                    "already-revoked code is the same answer: the outcome asked for holds."
                )
            ),
            403: OWNER_ONLY,
            404: OpenApiResponse(
                response=DetailSerializer,
                description="This household minted no invite with that code. Nothing was "
                "revoked.",
            ),
        },
    )
    def delete(self, request, code: str):
        household = household_of(request)
        invite = invites_of(household).filter(code=code).first()
        if invite is None:
            return refuse(
                "Nothing was revoked: this household minted no invite with that code.",
                status.HTTP_404_NOT_FOUND,
            )
        if invite.revoked_at is None:
            invite.revoked_at = timezone.now()
            invite.save(update_fields=["revoked_at"])
        return Response(status=status.HTTP_204_NO_CONTENT)


class MemberDetailView(APIView):
    """`DELETE /api/households/me/members/<user_id>`. Removes a person from
    this household.

    **Three things happen together, and the second and third are the point.**
    The membership row goes, every live device token that person holds is
    revoked, and every invite they minted is revoked. Without the token
    revocation a removed person keeps a working phone; without the invite
    revocation they keep a working back door, which is the same failure one
    step removed. A Django session they hold survives as a cookie, but it now
    authenticates a user who is in no household, so `IsHouseholdMember`
    refuses every request it makes.

    **The `User` row itself is NOT deleted**, deliberately: their rows in the
    household's data carry `household_id`, not a user id that would dangle,
    and deleting an account is a different decision from removing it from a
    family. They keep an account that belongs to no household until someone
    invites them into one.

    **The last owner cannot be removed, including by themself.** A household
    with no owner has nobody who can invite or remove anyone ever again, and
    there is no admin above it to fix that - ADR 0045 has no roles above
    `owner` and no approval workflow to appeal to.
    """

    permission_classes = [IsHouseholdOwner]

    @extend_schema(
        operation_id="api_households_me_members_destroy",
        tags=HOUSEHOLD_TAGS,
        responses={
            204: OpenApiResponse(
                description=(
                    "Removed. Their device tokens are revoked and the invites they minted "
                    "are revoked. Their account still exists and belongs to no household."
                )
            ),
            400: OpenApiResponse(
                response=DetailSerializer,
                description="Nobody was removed: they are the last owner of this household.",
            ),
            403: OWNER_ONLY,
            404: OpenApiResponse(
                response=DetailSerializer,
                description="Nobody with that user id is in this household. Nobody was "
                "removed.",
            ),
        },
    )
    def delete(self, request, user_id):
        household = household_of(request)
        membership = (
            HouseholdMember.objects.filter(household=household, user_id=user_id)
            .select_related("user")
            .first()
        )
        if membership is None:
            return refuse(
                "Nobody was removed: no member of this household has that user id.",
                status.HTTP_404_NOT_FOUND,
            )

        owners = HouseholdMember.objects.filter(
            household=household, role=HouseholdMember.OWNER
        ).count()
        if membership.role == HouseholdMember.OWNER and owners <= 1:
            themself = membership.user_id == request.user.id
            who = "You are" if themself else f"{membership.user.email} is"
            return refuse(
                f"Nobody was removed. {who} the last owner of this household, and a "
                f"household with no owner has nobody who can ever invite or remove anyone "
                f"again. Make someone else an owner first."
            )

        now = timezone.now()
        DeviceToken.objects.filter(user=membership.user, revoked_at__isnull=True).update(
            revoked_at=now
        )
        # By creator, not through `invites_of` - the same set for this user,
        # and an `update()` needs no join to reason about.
        Invite.objects.filter(created_by=membership.user, revoked_at__isnull=True).update(
            revoked_at=now
        )
        membership.delete()
        return Response(status=status.HTTP_204_NO_CONTENT)
