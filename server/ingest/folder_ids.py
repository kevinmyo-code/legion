"""A Drive folder id from whatever a person pastes: the bare id, or the folder's
URL from the browser (backend-etl ticket 06). Standard library only, because
`tools/connect_session.py` runs on a laptop without Django and imports the same
rule by path rather than keeping a second copy."""
from __future__ import annotations

import re
from urllib.parse import parse_qs, urlparse

# Drive ids are url-safe base64-ish: letters, digits, `-` and `_`.
_ID = re.compile(r"^[A-Za-z0-9_-]{10,200}$")


class FolderIdError(ValueError):
    pass


def folder_id_from(value: str) -> str:
    """`19tqQ...` or `https://drive.google.com/drive/folders/19tqQ...?usp=sharing`
    (also `/drive/u/0/folders/<id>` and `open?id=<id>`) to the id."""
    text = (value or "").strip()
    if _ID.match(text):
        return text
    parsed = urlparse(text)
    if parsed.scheme in ("http", "https") and parsed.netloc.endswith("google.com"):
        parts = [part for part in parsed.path.split("/") if part]
        if "folders" in parts:
            index = parts.index("folders")
            if index + 1 < len(parts) and _ID.match(parts[index + 1]):
                return parts[index + 1]
        ids = parse_qs(parsed.query).get("id")
        if ids and _ID.match(ids[0]):
            return ids[0]
    raise FolderIdError(
        f"{value!r} is not a Drive folder id or a Drive folder URL "
        f"(https://drive.google.com/drive/folders/<id>). Nothing was changed."
    )
