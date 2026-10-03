"""`manage.py issue_device_token <email> --name <device> [--scope read|write]`.

engine-mcp ticket 05 (Kevin, 2026-10-02): Claude Code authenticates to `/mcp`
with a device token, and a token for a TOOL rather than a person is minted
`read` by default - "a leaked read token is bad, a leaked write token is
worse". `--scope write` is the explicit opt-in.

The raw key is printed once, on stdout, and never stored: only its hash is
(`DeviceToken.issue`). Put it in `.claude/mcp.env` (gitignored), never in a
committed file. Revoke it from the web app's device list or the admin.
"""
from __future__ import annotations

from django.core.management.base import BaseCommand, CommandError

from household.models import DeviceToken, HouseholdMember, User


class Command(BaseCommand):
    help = (
        "Issues a device token for an existing household member and prints the raw key "
        "once. Defaults to a read-only token; pass --scope write for one that may change data."
    )

    def add_arguments(self, parser):
        parser.add_argument("email", type=str, help="Email of an existing household member.")
        parser.add_argument(
            "--name", required=True, help="What this token is for, e.g. 'Claude Code'."
        )
        parser.add_argument(
            "--scope",
            choices=[DeviceToken.SCOPE_READ, DeviceToken.SCOPE_WRITE],
            default=DeviceToken.SCOPE_READ,
            help="read (default) may read and change nothing; write may change data.",
        )

    def handle(self, *args, **options):
        email = options["email"]
        user = User.objects.filter(email__iexact=email).first()
        if user is None:
            raise CommandError(f"No user has the email {email!r}. No token was issued.")
        if not HouseholdMember.objects.filter(user=user).exists():
            raise CommandError(
                f"{email} belongs to no household, so a token for it could read nothing. "
                f"No token was issued. Add it to one with `manage.py add_household_member`."
            )
        _token, raw_key = DeviceToken.issue(user, options["name"], scope=options["scope"])
        self.stdout.write(
            f"Issued a {options['scope']}-scoped token named {options['name']!r} for {email}. "
            f"It is shown once and not stored:\n{raw_key}"
        )
