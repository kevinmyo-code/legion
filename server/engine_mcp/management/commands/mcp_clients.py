"""`manage.py mcp_clients` - every OAuth client registered with this engine's
`/mcp` authorization server, and who holds a live token for it
(ai-upgrade ticket 03, ruling 3: "every registered client is listed and
revocable").

    manage.py mcp_clients                          # list
    manage.py mcp_clients --revoke <client_id>     # delete the client and every token it holds
    manage.py mcp_clients --revoke-member <email>  # delete one member's OAuth tokens, all clients

A client is shared by everyone who connects it: Claude's CIMD client is one
row for every member on the engine, so `--revoke` on it signs every member's
Claude out (Claude re-registers on the next sign-in). `--revoke-member` is the
one-person version. Device tokens (`issue_device_token`) are not touched here.

The same rows are in the admin (`/admin/oauth2_provider/`), superusers only.
"""

from __future__ import annotations

from django.core.management.base import BaseCommand, CommandError
from django.utils import timezone
from oauth2_provider.models import (
    get_access_token_model,
    get_application_model,
    get_grant_model,
    get_refresh_token_model,
)


class Command(BaseCommand):
    help = "List, or revoke, the OAuth clients connected to /mcp."

    def add_arguments(self, parser):
        parser.add_argument("--revoke", metavar="CLIENT_ID", default=None)
        parser.add_argument("--revoke-member", metavar="EMAIL", default=None)

    def handle(self, *args, **options):
        if options["revoke"]:
            return self._revoke_client(options["revoke"])
        if options["revoke_member"]:
            return self._revoke_member(options["revoke_member"])
        return self._list()

    def _list(self):
        Application = get_application_model()
        AccessToken = get_access_token_model()
        now = timezone.now()
        apps = list(Application.objects.order_by("created"))
        if not apps:
            self.stdout.write("No OAuth client is registered with this engine.")
            return
        for app in apps:
            live = AccessToken.objects.filter(
                application=app, user__isnull=False, expires__gt=now
            ).select_related("user")
            members = sorted({token.user.email for token in live})
            self.stdout.write(
                f"{app.client_id}\n"
                f"  name: {app.name or '(none)'}  source: {app.registration_source}  "
                f"registered: {app.created:%Y-%m-%d %H:%M} UTC\n"
                f"  redirect: {app.redirect_uris or '(none)'}\n"
                f"  members with a live token: {', '.join(members) if members else 'none'}"
            )

    def _revoke_client(self, client_id: str):
        Application = get_application_model()
        app = Application.objects.filter(client_id=client_id).first()
        if app is None:
            raise CommandError(f"Nothing was revoked. No client has the id {client_id!r}.")
        name = app.name or client_id
        app.delete()
        self.stdout.write(
            f"Revoked {name}: the client and every grant and token it held are deleted."
        )

    def _revoke_member(self, email: str):
        from household.models import User

        user = User.objects.filter(email__iexact=email).first()
        if user is None:
            raise CommandError(f"Nothing was revoked. No member has the email {email!r}.")
        counts = {}
        for label, model in (
            ("refresh tokens", get_refresh_token_model()),
            ("access tokens", get_access_token_model()),
            ("grants", get_grant_model()),
        ):
            counts[label] = model.objects.filter(user=user).delete()[0]
        summary = ", ".join(f"{n} {label}" for label, n in counts.items())
        self.stdout.write(f"Revoked every OAuth token {user.email} held: deleted {summary}.")
