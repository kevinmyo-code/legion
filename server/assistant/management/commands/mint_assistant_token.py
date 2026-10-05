"""`manage.py mint_assistant_token --email <member> [--utc-offset-minutes N] [--dry-run]`

The manual real mint web-assistant ticket 07 owes: builds exactly the token
request `POST /api/assistant/session` would for that member, and either prints
it (`--dry-run`, no network, no key needed) or sends it to Google with
`LEGION_GEMINI_KEY` and prints the token, so a person can open the WebSocket
by hand:

    manage.py mint_assistant_token --email kevin@example.com --utc-offset-minutes -300
    # then, within 60 seconds, connect to
    #   <ws_url>?access_token=<token, URL-encoded>
    # and send {"setup": {"model": "models/gemini-3.8-live"}} - the token's own
    # setup wins over anything else in it - then a turn:
    #   {"clientContent": {"turns": [{"role": "user", "parts": [{"text": "hello"}]}],
    #                      "turnComplete": true}}

The key is read from the environment and sent in one header; it is never
printed. The printed token is a Live-only credential for this one locked
setup, valid about 30 minutes: treat it like any short-lived secret. Not
audited - this is the operator at the engine's own console, not a member.
"""

from __future__ import annotations

import json
from datetime import UTC, datetime

from django.core.management.base import BaseCommand, CommandError

from assistant import live
from assistant.prompt import MAX_OFFSET_MINUTES, MIN_OFFSET_MINUTES
from assistant.session import token_request_for
from assistant.views import KEY_ABSENT
from ingest.statements import gemini_key


class Command(BaseCommand):
    help = "Mints (or, with --dry-run, prints) one member's locked Gemini Live token request."

    def add_arguments(self, parser):
        parser.add_argument("--email", required=True)
        parser.add_argument("--utc-offset-minutes", type=int, default=None)
        parser.add_argument("--dry-run", action="store_true", help="Print the body; send nothing.")

    def handle(self, *args, email, utc_offset_minutes, dry_run, **options):
        from household.models import User

        if utc_offset_minutes is not None and not (
            MIN_OFFSET_MINUTES <= utc_offset_minutes <= MAX_OFFSET_MINUTES
        ):
            raise CommandError("Nothing was minted. --utc-offset-minutes is out of range.")
        user = User.objects.filter(email__iexact=email.strip()).first()
        if user is None or user.household is None:
            raise CommandError(f"Nothing was minted. {email} is not a member of a household.")
        body, companion = token_request_for(
            user, now=datetime.now(UTC), utc_offset_minutes=utc_offset_minutes
        )
        if dry_run:
            self.stdout.write(f"POST {live.AUTH_TOKENS_URL}")
            self.stdout.write("x-goog-api-key: <LEGION_GEMINI_KEY, never printed>")
            self.stdout.write(json.dumps(body, indent=2, ensure_ascii=False))
            return
        key = gemini_key()
        if key is None:
            raise CommandError(KEY_ABSENT)
        try:
            minted = live.mint(key, body)
        except live.MintFailed as exc:
            raise CommandError(exc.message) from exc
        self.stdout.write(f"companion: {companion.name} (voice {companion.voice_name})")
        self.stdout.write(f"token:     {minted.token}")
        self.stdout.write(f"ws_url:    {live.WS_URL}")
        self.stdout.write(f"connect by {minted.connect_by}; expires {minted.expires_at}")
