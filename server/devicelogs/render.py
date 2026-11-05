"""Render a conversation batch as a timeline, with the same parser the developer tool uses.

`devicelogs/voice_audit.py` is a BYTE-FOR-BYTE copy of the repo's `tools/voice_audit.py`
(phone branch `feat/async-read-tools`, `c4fd370f`, async-lookups ticket 05). It is copied, not
imported, because the engine's image is built from `server/` alone and `tools/` is not in it.
**Do not edit the copy.** Change `tools/voice_audit.py` and copy it over again;
`tests/test_device_logs_render.py` fails while the two differ, whenever `tools/` is present.

The phone writes these lines to logcat and the head unit to its conversation spool, in one format
(`LIVE_AUDIT` and `LIVE_LATENCY`), so one parser reads both devices' batches.

**One rendering, two doors** (alfred-builds ticket 02): `manage.py device_logs --render` and
`GET /api/device-logs/<id>/render` both call `render_batch`, so the worker reads exactly what the
operator command prints. Mail is redacted on the device before upload
(`[redacted: third-party content]`, CLAUDE.md section 7); nothing here un-redacts or re-reads it.
"""

from __future__ import annotations

from devicelogs import voice_audit

NO_AUDIT_LINES = "(no LIVE_AUDIT or LIVE_LATENCY lines in this batch)"


def render(text: str, *, verbose: bool = False) -> list[str]:
    """The timeline of one batch's text, one string per line; empty when it has no audit lines."""
    return voice_audit.timeline(text.splitlines(), verbose=verbose)


def batch_text(row) -> str:
    """The batch's text, decompressed. Raises ValueError in words when it cannot be read."""
    from devicelogs.views import inflate_capped

    return inflate_capped(bytes(row.body_gz)).decode("utf-8", errors="replace")


def batch_header(row, line_count: int) -> str:
    """The one-line header `manage.py device_logs` prints above each batch."""
    return (
        f"=== batch {row.id} | received {row.received_at:%Y-%m-%d %H:%M:%S}Z | "
        f"{row.device_label} v{row.app_version_name or '?'}"
        f"({row.app_version_code if row.app_version_code is not None else '?'}) | "
        f"{row.reason} | device window {row.window_start} .. {row.window_end} | "
        f"{line_count} lines"
        + (f" | conversation {row.batch_id}" if row.device_label == "web" else "")
        + (f" | visit ref={row.visit_ref or '-'}" if row.demo_visitor else "")
    )


def render_batch(row, *, verbose: bool = False) -> tuple[str, list[str]]:
    """(header, timeline lines) for one stored batch, exactly as `device_logs --render` prints it.
    Raises ValueError when the stored body cannot be read."""
    lines = render(batch_text(row), verbose=verbose) or [NO_AUDIT_LINES]
    return batch_header(row, len(lines)), lines
