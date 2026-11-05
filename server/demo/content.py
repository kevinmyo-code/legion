"""The recruiter page's content: its schema, its check, and loading it.

recruiter-demo tickets 07 and 10. Kevin writes `content.json` (and optionally
`report.html` and `resume.pdf`) in a private folder outside the repo;
`manage.py load_recruiter_content <dir>` checks it against `CONTENT_SCHEMA`
and upserts `demo.models.RecruiterContent`. Nothing here holds real content.

`profile` is the one part no page is served: it is the text `about_kevin`
answers from (ticket 10), one string per section ("Experience", "Location",
"Work authorisation", ...), and `GET /api/demo/content` drops it.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

from django.db import transaction
from jsonschema import Draft202012Validator

from demo.models import RecruiterContent

_STR = {"type": "string"}
_STRS = {"type": "array", "items": _STR}


def _obj(properties: dict, optional: tuple[str, ...] = ()) -> dict:
    """A closed object: every key required unless named in `optional`."""
    return {
        "type": "object",
        "properties": properties,
        "required": [key for key in properties if key not in optional],
        "additionalProperties": False,
    }


_TITLE_BODY = _obj({"title": _STR, "body": _STR})

# "LEGION on the phone" (prototype D, 2026-11-03): one clip per capability, cut
# from one recording. A clip with no `video` yet is drawn as a placeholder;
# `poster` and `captions` (a WebVTT file) go with the video. `full_take` is the
# whole recording, linked under the section.
_PHONE_CLIP = _obj(
    {"title": _STR, "body": _STR, "video": _STR, "poster": _STR, "captions": _STR},
    optional=("video", "poster", "captions"),
)
_PHONE = _obj(
    {
        "title": _STR,
        "intro": _STR,
        "clips": {"type": "array", "items": _PHONE_CLIP},
        "full_take": _STR,
    },
    optional=("full_take",),
)

CONTENT_SCHEMA: dict = _obj(
    {
        "hero": _obj({"title": _STR, "lead": _STR, "facts": _STRS}),
        "look_for": {"type": "array", "items": _TITLE_BODY},
        "work": _obj(
            {
                "title": _STR,
                "intro": _STR,
                "stats": {"type": "array", "items": _obj({"value": _STR, "label": _STR})},
                "diagram_svg": _STR,
                "diagram_caption": _STR,
                "cards": {"type": "array", "items": _TITLE_BODY},
            }
        ),
        "experience": {
            "type": "array",
            "items": _obj({"when": _STR, "title": _STR, "bullets": _STRS}),
        },
        "links": _obj({"linkedin": _STR, "github": _STR, "email": _STR}),
        "profile": {"type": "object", "additionalProperties": _STR},
        # Optional, so content written before them still loads. `name` is the
        # page's header line; without it the header says "LEGION". Without
        # `phone` the phone section is not drawn.
        "name": _STR,
        "phone": _PHONE,
        # What LEGION is, for a visitor who has not met it (Kevin, 2026-11-04: "a short section about
        # legion's architecture ... so people know what it is"). Optional: without it nothing is drawn.
        "legion": _obj(
            {
                "title": _STR,
                "intro": _STR,
                "diagram_svg": _STR,
                "diagram_caption": _STR,
                "parts": {"type": "array", "items": _TITLE_BODY},
            },
            optional=("diagram_svg", "diagram_caption"),
        ),
    },
    optional=("name", "phone", "legion"),
)

_VALIDATOR = Draft202012Validator(CONTENT_SCHEMA)

ASSET_KINDS = ("report", "resume")
# (file in the folder, content type it is served with)
ASSET_FILES = {
    "report": ("report.html", "text/html; charset=utf-8"),
    "resume": ("resume.pdf", "application/pdf"),
}


class ContentInvalid(ValueError):
    """content.json is missing, unreadable or does not fit. Said in words."""


def problems(content) -> list[str]:
    """Every way `content` does not fit the schema, each naming its path."""
    found = []
    for error in sorted(_VALIDATOR.iter_errors(content), key=lambda e: list(e.absolute_path)):
        where = "/".join(str(p) for p in error.absolute_path) or "(top level)"
        found.append(f"{where}: {error.message}")
    return found


def public_content(content: dict) -> dict:
    """What the page is served: everything but `profile`."""
    return {key: value for key, value in content.items() if key != "profile"}


@dataclass(frozen=True)
class Loaded:
    row: RecruiterContent
    created: bool
    files: dict[str, int]  # kind -> bytes loaded
    profile_sections: tuple[str, ...]


def read_folder(folder: Path) -> tuple[dict, dict[str, bytes]]:
    folder = Path(folder)
    if not folder.is_dir():
        raise ContentInvalid(f"Nothing was loaded. {folder} is not a folder.")
    source = folder / "content.json"
    if not source.is_file():
        raise ContentInvalid(f"Nothing was loaded. {source} does not exist; it is required.")
    try:
        content = json.loads(source.read_text(encoding="utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ContentInvalid(f"Nothing was loaded. {source} is not valid JSON: {exc}") from None
    found = problems(content)
    if found:
        raise ContentInvalid(
            "Nothing was loaded. content.json does not fit the schema:\n  " + "\n  ".join(found)
        )
    files = {}
    for kind, (name, _type) in ASSET_FILES.items():
        path = folder / name
        if path.is_file():
            files[kind] = path.read_bytes()
    if "resume" in files and not files["resume"].startswith(b"%PDF-"):
        raise ContentInvalid("Nothing was loaded. resume.pdf does not start like a PDF.")
    return content, files


def load(folder: Path) -> Loaded:
    """Check the folder, then replace the live row with it: content, and each
    file present. A file absent from the folder is cleared, so the page never
    offers a file the folder no longer holds."""
    content, files = read_folder(folder)
    defaults: dict = {"content": content}
    for kind, (name, content_type) in ASSET_FILES.items():
        data = files.get(kind)
        defaults[kind] = data
        defaults[f"{kind}_content_type"] = content_type if data is not None else ""
        defaults[f"{kind}_filename"] = name if data is not None else ""
    with transaction.atomic():
        row, created = RecruiterContent.objects.update_or_create(
            key=RecruiterContent.CURRENT_KEY, defaults=defaults
        )
    return Loaded(
        row=row,
        created=created,
        files={kind: len(data) for kind, data in files.items()},
        profile_sections=tuple(content["profile"]),
    )
