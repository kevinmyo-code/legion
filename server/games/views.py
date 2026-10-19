"""`/api/games/*`: invite a household member to a Tetris race, and run it
(retro-games ticket 05, ADR 0060).

Token or session authenticated like every other route, and scoped to the
caller's household at `household_of` (ADR 0045). A match is visible only to
its two players: anyone else, in this household or another, gets the same
404 as a match that does not exist.

Every request first runs `matches.sweep` for the caller's household, so an
expired invite reads as expired the moment it is, with or without the cron.

The invite's Web Push is sent HERE, synchronously, not by `push_dispatch`'s
five-minute cron: an invite that arrives four minutes late has expired.
"""

from __future__ import annotations

from django.utils import timezone
from drf_spectacular.utils import OpenApiResponse, extend_schema
from rest_framework import serializers, status
from rest_framework.response import Response
from rest_framework.views import APIView

from api.schema import DetailSerializer
from games import matches
from games.models import CANCELLED, DECLINED, INVITED, PLAYING, GameMatch
from household.models import HouseholdMember
from household.tenancy import household_of, scoped

TAGS = ["games"]

NOT_FOUND = "No such match. It may have closed more than ten minutes ago. Nothing was changed."


# =============================================================================
# Shapes
# =============================================================================


class OpponentSerializer(serializers.Serializer):
    user_id = serializers.UUIDField()
    name = serializers.CharField()


class PlayerSerializer(serializers.Serializer):
    user_id = serializers.UUIDField()
    name = serializers.CharField()
    score = serializers.IntegerField()
    lines = serializers.IntegerField()
    level = serializers.IntegerField()
    topped_out = serializers.BooleanField()
    finished = serializers.BooleanField()
    updated_at = serializers.DateTimeField(allow_null=True)


class MatchSerializer(serializers.Serializer):
    id = serializers.UUIDField()
    game = serializers.CharField(help_text="Always `tetris` today.")
    status = serializers.ChoiceField(
        choices=["invited", "playing", "ended", "declined", "cancelled", "expired"]
    )
    seed = serializers.IntegerField(help_text="A uint32: mulberry32's seed (ticket 04).")
    role = serializers.ChoiceField(choices=[matches.INVITER, matches.INVITEE])
    created_at = serializers.DateTimeField()
    expires_at = serializers.DateTimeField(help_text="The invite lapses here if not accepted.")
    start_at = serializers.DateTimeField(allow_null=True)
    ends_at = serializers.DateTimeField(allow_null=True)
    server_now = serializers.DateTimeField(
        help_text="The engine's clock as it answered: subtract your own to correct the countdown."
    )
    you = PlayerSerializer()
    opponent = PlayerSerializer()
    result = serializers.ChoiceField(
        choices=[matches.WON, matches.LOST, matches.DRAW],
        allow_null=True,
        help_text="For the caller, once the match has ended; null before.",
    )
    detail = serializers.CharField(help_text="What this match is, in words, for the caller.")


class InviteRequestSerializer(serializers.Serializer):
    opponent_user_id = serializers.UUIDField()


class InviteResponseSerializer(serializers.Serializer):
    match = MatchSerializer()
    push = serializers.ChoiceField(
        choices=["sent", "no_device", "turned_off", "failed", "not_set_up"],
        help_text="What happened to the notification. Only `sent` reached a device.",
    )
    detail = serializers.CharField()


class ProgressRequestSerializer(serializers.Serializer):
    score = serializers.IntegerField(min_value=0, max_value=2_000_000_000)
    lines = serializers.IntegerField(min_value=0, max_value=100_000)
    level = serializers.IntegerField(min_value=1, max_value=10_000)
    topped_out = serializers.BooleanField(default=False)
    finished = serializers.BooleanField(
        default=False, help_text="True once this player's game is over: clock out or topped out."
    )


# =============================================================================
# Helpers
# =============================================================================


def _now():
    return timezone.now()


def _body(match: GameMatch, user) -> dict:
    role = matches.role_of(match, user)
    other = matches.INVITEE if role == matches.INVITER else matches.INVITER
    opponent = match.invitee if role == matches.INVITER else match.inviter
    return MatchSerializer(
        {
            "id": match.id,
            "game": "tetris",
            "status": match.status,
            "seed": match.seed,
            "role": role,
            "created_at": match.created_at,
            "expires_at": match.expires_at,
            "start_at": match.start_at,
            "ends_at": match.ends_at,
            "server_now": _now(),
            "you": matches.progress(match, role, user),
            "opponent": matches.progress(match, other, opponent),
            "result": matches.result_for(match, role),
            "detail": matches.sentence(match, role, matches.display_name(opponent)),
        }
    ).data


def _mine(request, match_id):
    """The match, if the caller is one of its two players; else None."""
    household = household_of(request)
    matches.sweep(_now(), household)
    match = (
        scoped(GameMatch, request).select_related("inviter", "invitee").filter(pk=match_id).first()
    )
    if match is None or matches.role_of(match, request.user) is None:
        return None
    return match


def _not_found() -> Response:
    return Response({"detail": NOT_FOUND}, status=status.HTTP_404_NOT_FOUND)


def _conflict(sentence: str) -> Response:
    return Response({"detail": sentence}, status=status.HTTP_409_CONFLICT)


def _reloaded(match: GameMatch) -> GameMatch:
    return GameMatch.objects.select_related("inviter", "invitee").get(pk=match.pk)


def _why_not(match: GameMatch, request, verb: str) -> str:
    """The refusal when a transition found the match no longer where it was."""
    role = matches.role_of(match, request.user)
    opponent = match.invitee if role == matches.INVITER else match.inviter
    now_is = matches.sentence(match, role, matches.display_name(opponent))
    return f"Nothing was {verb}. {now_is}"


_MATCH_404 = OpenApiResponse(response=DetailSerializer, description=NOT_FOUND)
_MATCH_409 = OpenApiResponse(
    response=DetailSerializer,
    description="The match is not in a state that allows this; nothing changed.",
)


# =============================================================================
# Views
# =============================================================================


class OpponentsView(APIView):
    @extend_schema(
        operation_id="api_games_opponents_list",
        tags=TAGS,
        responses={200: OpponentSerializer(many=True)},
    )
    def get(self, request):
        household = household_of(request)
        members = (
            HouseholdMember.objects.filter(household=household)
            .exclude(user=request.user)
            .select_related("user")
            .order_by("joined_at")
        )
        body = [
            {"user_id": m.user_id, "name": matches.display_name(m.user)}
            for m in members
            if m.user.is_active
        ]
        return Response(OpponentSerializer(body, many=True).data)


class MatchCreateView(APIView):
    @extend_schema(
        operation_id="api_games_matches_create",
        tags=TAGS,
        request=InviteRequestSerializer,
        responses={
            201: OpenApiResponse(
                response=InviteResponseSerializer,
                description="Invited. `push` and `detail` say whether a notification reached them.",
            ),
            400: OpenApiResponse(response=DetailSerializer, description="No invite was made."),
        },
    )
    def post(self, request):
        from push import copy, dispatch
        from push.models import GAME_INVITE
        from push.sender import webpush_sender
        from push.vapid import vapid_config

        body = InviteRequestSerializer(data=request.data)
        if not body.is_valid():
            return Response(
                {"detail": f"No invite was made. {body.errors}"}, status=status.HTTP_400_BAD_REQUEST
            )
        household = household_of(request)
        now = _now()
        matches.sweep(now, household)
        member = (
            HouseholdMember.objects.filter(
                household=household, user_id=body.validated_data["opponent_user_id"]
            )
            .exclude(user=request.user)
            .select_related("user")
            .first()
        )
        if member is None or not member.user.is_active:
            return Response(
                {
                    "detail": "No invite was made. That person is not another member of your "
                    "household."
                },
                status=status.HTTP_400_BAD_REQUEST,
            )
        opponent = member.user
        match = GameMatch.objects.create(
            household=household,
            inviter=request.user,
            invitee=opponent,
            seed=matches.new_seed(),
            status=INVITED,
            created_at=now,
            expires_at=now + matches.INVITE_TTL,
        )
        config = vapid_config()
        if config is None:
            outcome = "not_set_up"
        else:
            outcome = dispatch.send_now(
                household,
                opponent,
                kind=GAME_INVITE,
                key=matches.invite_key(match.id),
                title=copy.TITLE_GAME_INVITE,
                body=copy.game_invite(matches.display_name(request.user)),
                url=matches.match_url(match.id),
                now=now,
                send=webpush_sender(config),
            )
        match = _reloaded(match)
        return Response(
            InviteResponseSerializer(
                {
                    "match": _body(match, request.user),
                    "push": outcome,
                    "detail": matches.push_sentence(outcome, matches.display_name(opponent)),
                }
            ).data,
            status=status.HTTP_201_CREATED,
        )


class PendingView(APIView):
    @extend_schema(
        operation_id="api_games_matches_pending_list",
        tags=TAGS,
        responses={200: MatchSerializer(many=True)},
    )
    def get(self, request):
        household = household_of(request)
        now = _now()
        matches.sweep(now, household)
        rows = (
            scoped(GameMatch, request)
            .filter(invitee=request.user, status=INVITED, expires_at__gt=now)
            .select_related("inviter", "invitee")
            .order_by("created_at")
        )
        return Response([_body(m, request.user) for m in rows])


class MatchDetailView(APIView):
    @extend_schema(
        operation_id="api_games_matches_retrieve",
        tags=TAGS,
        responses={200: MatchSerializer, 404: _MATCH_404},
    )
    def get(self, request, match_id):
        match = _mine(request, match_id)
        if match is None:
            return _not_found()
        return Response(_body(match, request.user))


class _Transition(APIView):
    """Accept, decline or cancel: one conditional UPDATE, then the match."""

    role: str = ""
    verb: str = ""

    def changes(self, now) -> dict:
        raise NotImplementedError

    def post(self, request, match_id):
        match = _mine(request, match_id)
        if match is None:
            return _not_found()
        if matches.role_of(match, request.user) != self.role:
            who = "the person invited" if self.role == matches.INVITEE else "the person who invited"
            return Response(
                {"detail": f"Nothing was {self.verb}. Only {who} can do that."},
                status=status.HTTP_403_FORBIDDEN,
            )
        now = _now()
        moved = GameMatch.objects.filter(pk=match.pk, status=INVITED, expires_at__gt=now).update(
            **self.changes(now)
        )
        match = _reloaded(match)
        if not moved:
            return _conflict(_why_not(match, request, self.verb))
        return Response(_body(match, request.user))


def _transition_schema(operation_id: str, description: str):
    return extend_schema(
        operation_id=operation_id,
        tags=TAGS,
        request=None,
        responses={
            200: OpenApiResponse(response=MatchSerializer, description=description),
            403: OpenApiResponse(response=DetailSerializer, description="Not your move to make."),
            404: _MATCH_404,
            409: _MATCH_409,
        },
    )


class AcceptView(_Transition):
    role = matches.INVITEE
    verb = "accepted"

    def changes(self, now):
        start = now + matches.COUNTDOWN
        return {"status": PLAYING, "start_at": start, "ends_at": start + matches.RACE}

    @_transition_schema(
        "api_games_matches_accept_create",
        "Accepted: `start_at` is 5 s from now and `ends_at` 180 s after that.",
    )
    def post(self, request, match_id):
        return super().post(request, match_id)


class DeclineView(_Transition):
    role = matches.INVITEE
    verb = "declined"

    def changes(self, now):
        return {"status": DECLINED, "closed_at": now}

    @_transition_schema("api_games_matches_decline_create", "Declined.")
    def post(self, request, match_id):
        return super().post(request, match_id)


class CancelView(_Transition):
    role = matches.INVITER
    verb = "cancelled"

    def changes(self, now):
        return {"status": CANCELLED, "closed_at": now}

    @_transition_schema("api_games_matches_cancel_create", "Cancelled.")
    def post(self, request, match_id):
        return super().post(request, match_id)


class ProgressView(APIView):
    @extend_schema(
        operation_id="api_games_matches_progress_create",
        tags=TAGS,
        request=ProgressRequestSerializer,
        responses={
            200: OpenApiResponse(
                response=MatchSerializer, description="Saved; the match as it now is."
            ),
            400: OpenApiResponse(response=DetailSerializer, description="Nothing was saved."),
            404: _MATCH_404,
            409: OpenApiResponse(
                response=DetailSerializer,
                description="Nothing was saved: the match is not being played, or it ended more "
                "than 15 s ago.",
            ),
        },
    )
    def post(self, request, match_id):
        body = ProgressRequestSerializer(data=request.data)
        if not body.is_valid():
            return Response(
                {"detail": f"Nothing was saved. {body.errors}"}, status=status.HTTP_400_BAD_REQUEST
            )
        match = _mine(request, match_id)
        if match is None:
            return _not_found()
        now = _now()
        role = matches.role_of(match, request.user)
        if match.ends_at is not None and now > match.ends_at + matches.GRACE:
            return _conflict(
                "Nothing was saved. The race ended more than 15 seconds ago, so this score "
                "arrived too late to count."
            )
        if match.status != PLAYING:
            return _conflict(_why_not(match, request, "saved"))
        data = body.validated_data
        # A finished or topped-out player stays so: a late, out-of-order post
        # cannot bring a game back.
        update = {
            f"{role}_score": data["score"],
            f"{role}_lines": data["lines"],
            f"{role}_level": data["level"],
            f"{role}_topped_out": data["topped_out"] or getattr(match, f"{role}_topped_out"),
            f"{role}_finished": data["finished"] or getattr(match, f"{role}_finished"),
            f"{role}_updated_at": now,
        }
        saved = GameMatch.objects.filter(pk=match.pk, status=PLAYING).update(**update)
        if not saved:
            return _conflict(_why_not(_reloaded(match), request, "saved"))
        matches.sweep(now, household_of(request))
        return Response(_body(_reloaded(match), request.user))
