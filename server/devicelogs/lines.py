"""Reading logcat-shaped lines out of stored batches (2026-11-09, the phone's own logcat).

Pure functions, so `manage.py device_logs` and its tests share one reading:

- `parse` splits a `logcat -v threadtime` line (`MM-dd HH:mm:ss.SSS pid tid L Tag: message`).
- `utc_offset` works out a batch's clock offset. A threadtime stamp is the PHONE's local time with
  no year and no zone; the batch's `window_start` is epoch milliseconds from the same phone. Their
  difference, rounded to 15 minutes, is the phone's UTC offset at the time, so a line can be put on
  UTC without the phone ever sending its timezone.
- `merged` puts several batches' lines in one time order, for a window that spans batches.
- `blob_lines` / `read_blobs` carry text through Cloud Logging intact: gzip, base64, numbered
  `BLOB i/n` lines. A Cloud Run Job's stdout reaches `gcloud logging read` one entry per line with
  no ordering promise inside a millisecond, so plain lines could come back shuffled; numbered
  chunks reassemble in order whatever order they arrive in.
"""

from __future__ import annotations

import base64
import datetime
import gzip
import hashlib
import re
from collections.abc import Iterable

# "11-09 14:03:22.123  1234  1250 I View    : message"
_LINE = re.compile(
    r"^(?P<date>\d\d-\d\d) (?P<time>\d\d:\d\d:\d\d\.\d{3})\s+(?P<pid>\d+)\s+(?P<tid>\d+)\s+"
    r"(?P<level>[VDIWEFA])\s+(?P<tag>.*?)\s*: (?P<msg>.*)$"
)
_QUARTER_HOUR_S = 15 * 60
BLOB_CHUNK_CHARS = 60_000


def parse(line: str) -> dict | None:
    """The fields of one threadtime line, or None for anything else (a header, a blank)."""
    m = _LINE.match(line.rstrip("\r\n"))
    return m.groupdict() if m else None


def tag_of(line: str) -> str | None:
    fields = parse(line)
    return fields["tag"] if fields else None


def _naive(fields: dict, year: int) -> datetime.datetime | None:
    try:
        return datetime.datetime.strptime(
            f"{year}-{fields['date']} {fields['time']}", "%Y-%m-%d %H:%M:%S.%f"
        )
    except ValueError:
        return None


def utc_offset(lines: list[str], window_start: datetime.datetime | None) -> datetime.timedelta:
    """The phone's local-minus-UTC offset for this batch, rounded to 15 minutes; zero when unknown."""
    if window_start is None:
        return datetime.timedelta(0)
    start = window_start.astimezone(datetime.UTC).replace(tzinfo=None)
    for line in lines:
        fields = parse(line)
        if fields is None:
            continue
        local = _naive(fields, start.year)
        if local is None:
            continue
        seconds = (local - start).total_seconds()
        # A batch that starts on Dec 31 local and Jan 1 UTC: the stamp's year was a guess.
        if seconds > 180 * 86400:
            local = local.replace(year=start.year - 1)
        elif seconds < -180 * 86400:
            local = local.replace(year=start.year + 1)
        seconds = (local - start).total_seconds()
        return datetime.timedelta(seconds=round(seconds / _QUARTER_HOUR_S) * _QUARTER_HOUR_S)
    return datetime.timedelta(0)


def line_utc(
    line: str, window_start: datetime.datetime | None, offset: datetime.timedelta
) -> datetime.datetime | None:
    """When the line was written, in UTC, or None when it carries no threadtime stamp."""
    fields = parse(line)
    if fields is None:
        return None
    year = (window_start or datetime.datetime.now(datetime.UTC)).astimezone(datetime.UTC).year
    local = _naive(fields, year)
    if local is None:
        return None
    return (local - offset).replace(tzinfo=datetime.UTC)


def keep(
    line: str,
    *,
    when: datetime.datetime | None,
    since: datetime.datetime | None,
    until: datetime.datetime | None,
    tags: set[str] | None,
    needle: str,
) -> bool:
    """The command's line filters. A line with no stamp passes the time filters (it is a
    continuation the logcat reader split, or a header) but never a tag filter."""
    if tags is not None:
        tag = tag_of(line)
        if tag is None or tag.lower() not in tags:
            return False
    if needle and needle not in line.lower():
        return False
    if when is not None:
        if since is not None and when < since:
            return False
        if until is not None and when > until:
            return False
    return True


def merged(batches: Iterable[list[tuple[datetime.datetime | None, str]]]) -> list[str]:
    """Every batch's (utc, line) pairs in one time order. Stable: a line with no stamp keeps its
    place after the stamped line before it, and same-millisecond lines keep batch order."""
    keyed = []
    for b_index, pairs in enumerate(batches):
        last = None
        for l_index, (when, line) in enumerate(pairs):
            if when is not None:
                last = when
            stamp = when or last or datetime.datetime.min.replace(tzinfo=datetime.UTC)
            keyed.append((stamp, b_index, l_index, line))
    keyed.sort(key=lambda k: (k[0], k[1], k[2]))
    return [k[3] for k in keyed]


def blob_lines(text: str) -> list[str]:
    """`text` gzipped, base64'd and cut into numbered `BLOB i/n <chunk>` lines, then one
    `BLOB_END n <sha256 of text>` line so the reader can tell a whole transfer from a cut one."""
    encoded = base64.b64encode(gzip.compress(text.encode("utf-8"))).decode("ascii")
    chunks = [
        encoded[i : i + BLOB_CHUNK_CHARS] for i in range(0, len(encoded), BLOB_CHUNK_CHARS)
    ] or [""]
    total = len(chunks)
    out = [f"BLOB {i + 1}/{total} {chunk}" for i, chunk in enumerate(chunks)]
    out.append(f"BLOB_END {total} {hashlib.sha256(text.encode('utf-8')).hexdigest()}")
    return out


_BLOB = re.compile(r"BLOB (\d+)/(\d+) (\S*)")
_BLOB_END = re.compile(r"BLOB_END (\d+) ([0-9a-f]{64})")


def read_blobs(lines: Iterable[str]) -> str:
    """The text `blob_lines` carried, from its lines in any order (duplicates are fine).
    Raises ValueError in words when a chunk is missing or the checksum does not match."""
    chunks: dict[int, str] = {}
    total = None
    digest = None
    for line in lines:
        m = _BLOB.search(line)
        if m:
            chunks[int(m.group(1))] = m.group(3)
            total = int(m.group(2))
            continue
        m = _BLOB_END.search(line)
        if m:
            total, digest = int(m.group(1)), m.group(2)
    if total is None:
        raise ValueError("No BLOB lines found.")
    missing = [i for i in range(1, total + 1) if i not in chunks]
    if missing:
        raise ValueError(f"{len(missing)} of {total} chunks are missing (first: {missing[0]}).")
    text = gzip.decompress(
        base64.b64decode("".join(chunks[i] for i in range(1, total + 1)))
    ).decode("utf-8")
    if digest is not None and hashlib.sha256(text.encode("utf-8")).hexdigest() != digest:
        raise ValueError("The reassembled text does not match its checksum.")
    return text
