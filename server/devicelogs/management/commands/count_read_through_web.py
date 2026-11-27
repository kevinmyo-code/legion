"""Count stored web conversation records that still hold third-party text (ticket #475).

Read only. Records written before the engine redacted at write time
(`devicelogs.read_through_lines`) may keep a read-through tool's reply, or the assistant's words
after one, in clear. Whether to rewrite or delete them is the owner's call; this prints counts so
he can make it, and never a word of any record.

    manage.py count_read_through_web
"""

from __future__ import annotations

from django.core.management.base import BaseCommand

from devicelogs.models import DEVICE_LABEL_WEB, REASON_CONVERSATION, DeviceLog
from devicelogs.read_through_lines import redact
from devicelogs.views import inflate_capped


class Command(BaseCommand):
    help = "Count web conversation records holding un-redacted third-party text. Read only."

    def handle(self, *args, **options):
        scanned = affected = lines = 0
        # Members' records and recruiter visits' (no household) alike.
        rows = DeviceLog.objects.filter(
            device_label=DEVICE_LABEL_WEB, reason=REASON_CONVERSATION
        ).only("body_gz")
        for row in rows.iterator():
            scanned += 1
            body = inflate_capped(bytes(row.body_gz)).decode("utf-8", errors="replace")
            stored = body.splitlines()
            kept = redact([], [(None, text) for text in stored])
            changed = sum(1 for text, (_, after) in zip(stored, kept, strict=True) if text != after)
            if changed:
                affected += 1
                lines += changed
        self.stdout.write(
            f"web conversation records: {scanned} scanned, {affected} hold third-party text "
            f"in clear, {lines} lines in all. Nothing was changed."
        )
