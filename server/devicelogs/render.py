"""Render a conversation batch as a timeline, with the same parser the developer tool uses.

`devicelogs/voice_audit.py` is a BYTE-FOR-BYTE copy of the repo's `tools/voice_audit.py`
(phone branch `feat/async-read-tools`, `c4fd370f`, async-lookups ticket 05). It is copied, not
imported, because the engine's image is built from `server/` alone and `tools/` is not in it.
**Do not edit the copy.** Change `tools/voice_audit.py` and copy it over again;
`tests/test_device_logs_render.py` fails while the two differ, whenever `tools/` is present.

The phone writes these lines to logcat and the head unit to its conversation spool, in one format
(`LIVE_AUDIT` and `LIVE_LATENCY`), so one parser reads both devices' batches.
"""

from __future__ import annotations

from devicelogs import voice_audit


def render(text: str, *, verbose: bool = False) -> list[str]:
    """The timeline of one batch's text, one string per line; empty when it has no audit lines."""
    return voice_audit.timeline(text.splitlines(), verbose=verbose)
