"""`manage.py set_companion <email> <persona> [--name N] [--voice V]`

Sets which built-in companion a member talks to on the web (web-assistant
ticket 03). The seeding migration already gave every member who existed then
their companion, and a member who joins later gets the seed rule computed on
read (`assistant/companions.py`); this is for changing either, until the phone
pushes its roster up. Idempotent: running it twice leaves one row.

    manage.py set_companion mia@example.com dorothy
    manage.py set_companion kevin@example.com alfred --voice Charon
"""

from __future__ import annotations

from django.core.management.base import BaseCommand, CommandError

from assistant.companions import set_companion
from assistant.personas import BY_KEY


class Command(BaseCommand):
    help = "Sets a member's web-assistant companion to a built-in persona."

    def add_arguments(self, parser):
        parser.add_argument("email")
        parser.add_argument("persona", choices=sorted(BY_KEY))
        parser.add_argument("--name", default="", help="What it is called; default the persona's.")
        parser.add_argument(
            "--voice", default="", help="A Gemini prebuilt voice; default the persona's."
        )

    def handle(self, *args, email, persona, name, voice, **options):
        from household.models import User

        user = User.objects.filter(email__iexact=email.strip()).first()
        if user is None:
            raise CommandError(f"Nothing was changed. There is no account for {email}.")
        try:
            row = set_companion(user, persona, name=name, voice_name=voice)
        except ValueError as exc:
            raise CommandError(f"Nothing was changed. {exc}") from exc
        self.stdout.write(
            f"{user.email} now talks to {row.name} ({row.persona_key}"
            f"{', voice ' + row.voice_name if row.voice_name else ''}) on the web."
        )
