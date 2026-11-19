"""The recruiter page's content: its schema, its check, and loading it.

recruiter-demo tickets 07 and 10. Kevin writes `content.json` (and optionally
`report.html`, `resume.pdf` and its page as `resume.png`) in a private folder outside the repo;
`manage.py load_recruiter_content <dir>` checks it against `CONTENT_SCHEMA`
and upserts `demo.models.RecruiterContent`, with the model and poster files a
project section names as `demo.models.RecruiterMediaFile` rows. Nothing here
holds real content.

`profile` is the one part no page is served: it is the text `about_kevin`
answers from (ticket 10), one string per section ("Experience", "Location",
"Work authorisation", ...), and `GET /api/demo/content` drops it.
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from pathlib import Path

from django.db import transaction
from jsonschema import Draft202012Validator

from demo.models import RecruiterContent, RecruiterMediaFile
from demo.profile import PROFILE_ORDER

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

# A clip that may not exist yet (Kevin, 2026-11-05: "Media: coming soon"). With `video` the page
# plays it; without, it shows `note` (say, "Video coming soon, recorded on demo data"); with
# neither, nothing. All optional, so a slot can be declared before anything is recorded.
_MEDIA = _obj(
    {"video": _STR, "poster": _STR, "captions": _STR, "note": _STR},
    optional=("video", "poster", "captions", "note"),
)

# A 3D model a visitor can turn (Kevin, 2026-11-19). `model` (a GLB) and `poster` (its still,
# shown until the 3D has loaded and kept if it cannot) are FILE NAMES in the private content
# folder: the loader stores them beside the content and `GET /api/demo/media/<name>` serves them
# to a visit or an owner only. A name is one plain segment, so it can never leave the folder.
MODEL_NAME = r"^[A-Za-z0-9][A-Za-z0-9_-]{0,63}\.glb$"
POSTER_NAME = r"^[A-Za-z0-9][A-Za-z0-9_-]{0,63}\.(png|jpg|jpeg|webp)$"
_NAME_HINTS = {
    MODEL_NAME: "a file name in the content folder (letters, digits, - and _, then .glb)",
    POSTER_NAME: (
        "a file name in the content folder (letters, digits, - and _, then .png, .jpg or .webp)"
    ),
}
_MODEL_3D = _obj(
    {
        "title": _STR,
        "model": {"type": "string", "pattern": MODEL_NAME},
        "poster": {"type": "string", "pattern": POSTER_NAME},
        "note": _STR,
    },
    optional=("note",),
)
MAX_MODELS = 4

# A project section's media: the clip slot plus up to `MAX_MODELS` models. The strip's line keeps
# the plain clip slot: a model belongs in a section with room for it.
_SECTION_MEDIA = _obj(
    {
        **_MEDIA["properties"],
        "models": {"type": "array", "items": _MODEL_3D, "maxItems": MAX_MODELS},
    },
    optional=("video", "poster", "captions", "note", "models"),
)

# The "Projects" strip near the top (Kevin, 2026-11-05): one line each. `href` is an anchor on
# this page ("#legion") or a link out; without it the line stands on its own (a private repo).
_PROJECT = _obj(
    {"title": _STR, "line": _STR, "href": _STR, "media": _MEDIA},
    optional=("href", "media"),
)

# Ids the page already uses for its own sections; a project section may not take one.
RESERVED_SECTION_IDS = (
    "projects", "look-for", "legion", "phone", "work", "experience", "hero-title",
)  # fmt: skip

# A project section below LEGION's (Kevin, 2026-11-05: the second project). The same shape as
# `legion`, plus the `id` the strip's anchor points at and an optional media slot.
_PROJECT_SECTION = _obj(
    {
        "id": {
            "type": "string",
            "pattern": "^[a-z][a-z0-9-]{0,39}$",
            "not": {"enum": list(RESERVED_SECTION_IDS)},
        },
        "title": _STR,
        "intro": _STR,
        "diagram_svg": _STR,
        "diagram_caption": _STR,
        "parts": {"type": "array", "items": _TITLE_BODY},
        "media": _SECTION_MEDIA,
    },
    optional=("diagram_svg", "diagram_caption", "media"),
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
        # What LEGION is, for a visitor who has not met it (Kevin, 2026-11-04: "a short section
        # about legion's architecture ... so people know what it is"). Optional: without it
        # nothing is drawn.
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
        # Optional, so content written before them still loads: without `projects` no strip is
        # drawn, without `project_sections` nothing below LEGION's section.
        "projects": {"type": "array", "items": _PROJECT},
        "project_sections": {"type": "array", "items": _PROJECT_SECTION},
    },
    # `work` is optional too: the LinkedIn version leaves it out (Kevin, 2026-11-04).
    optional=("name", "phone", "legion", "work", "projects", "project_sections"),
)

_VALIDATOR = Draft202012Validator(CONTENT_SCHEMA)

ASSET_KINDS = ("report", "resume", "resume_image")
# (file in the folder, content type it is served with)
ASSET_FILES = {
    "report": ("report.html", "text/html; charset=utf-8"),
    "resume": ("resume.pdf", "application/pdf"),
    # The resume's page as an image, for the resume modal (demo-modals): rendered from
    # resume.pdf in the private folder by Kevin's generator, never committed.
    "resume_image": ("resume.png", "image/png"),
}

PNG_MAGIC = bytes([0x89]) + b"PNG" + bytes([0x0D, 0x0A, 0x1A, 0x0A])

# A model or poster file: (content type, what it is called in a refusal, its first bytes test).
MEDIA_TYPES = {
    "glb": ("model/gltf-binary", "a GLB", lambda b: b.startswith(b"glTF")),
    "png": ("image/png", "a PNG", lambda b: b.startswith(PNG_MAGIC)),
    "jpg": ("image/jpeg", "a JPEG", lambda b: b.startswith(b"\xff\xd8\xff")),
    "jpeg": ("image/jpeg", "a JPEG", lambda b: b.startswith(b"\xff\xd8\xff")),
    "webp": ("image/webp", "a WebP", lambda b: b[:4] == b"RIFF" and b[8:12] == b"WEBP"),
}
# Per file. The page's own target is under 3 MB a model (a phone on a cell link); this is the
# ceiling past which a row in Postgres and one response stop being sensible.
MEDIA_MAX_BYTES = 8 * 1024 * 1024
MEDIA_URL = "/api/demo/media/"


class ContentInvalid(ValueError):
    """content.json is missing, unreadable or does not fit. Said in words."""


def problems(content) -> list[str]:
    """Every way `content` does not fit the schema, each naming its path."""
    found = []
    for error in sorted(_VALIDATOR.iter_errors(content), key=lambda e: list(e.absolute_path)):
        where = "/".join(str(p) for p in error.absolute_path) or "(top level)"
        message = error.message
        if error.validator == "pattern" and error.validator_value in _NAME_HINTS:
            message = f"{error.instance!r} is not {_NAME_HINTS[error.validator_value]}"
        found.append(f"{where}: {message}")
    if not found and isinstance(content, dict):
        found.extend(_anchor_problems(content))
    return found


def _anchor_problems(content: dict) -> list[str]:
    """What the schema cannot say: two project sections with one id, or a strip line whose
    `#anchor` names a section this content does not draw (a dead link on the page)."""
    found = []
    ids = [section["id"] for section in content.get("project_sections", [])]
    for index, section_id in enumerate(ids):
        if section_id in ids[:index]:
            found.append(f"project_sections/{index}/id: {section_id!r} is used twice")
    drawn = set(ids) | {"experience"}
    drawn |= {key for key in ("legion", "phone", "work") if key in content}
    for index, project in enumerate(content.get("projects", [])):
        href = project.get("href", "")
        if href.startswith("#") and href[1:] not in drawn:
            found.append(
                f"projects/{index}/href: {href!r} names no section on this page "
                f"(there are: {', '.join(sorted(drawn))})"
            )
    return found


def public_content(content: dict) -> dict:
    """What the page is served: everything but `profile` (and its order), with each model's
    `model` and `poster` file names turned into the private route that serves them."""
    public = {
        key: value for key, value in content.items() if key not in ("profile", PROFILE_ORDER)
    }
    sections_in = public.get("project_sections", [])
    if not any(section.get("media", {}).get("models") for section in sections_in):
        return public
    sections = []
    for section in public["project_sections"]:
        media = section.get("media")
        if media and media.get("models"):
            models = [
                {**m, "model": MEDIA_URL + m["model"], "poster": MEDIA_URL + m["poster"]}
                for m in media["models"]
            ]
            section = {**section, "media": {**media, "models": models}}
        sections.append(section)
    return {**public, "project_sections": sections}


def media_names(content: dict) -> list[tuple[str, str]]:
    """(schema path, file name) of every model and poster the content names, in order."""
    named = []
    for s, section in enumerate(content.get("project_sections", [])):
        for m, model in enumerate(section.get("media", {}).get("models", [])):
            for key in ("model", "poster"):
                named.append((f"project_sections/{s}/media/models/{m}/{key}", model[key]))
    return named


def _read_media(folder: Path, content: dict) -> dict[str, tuple[bytes, str]]:
    """name -> (bytes, content type) for every file the content names. Refuses a file that is
    missing, too big or not what its name says. Names were checked by the schema already."""
    media: dict[str, tuple[bytes, str]] = {}
    for where, name in media_names(content):
        if name in media:
            continue
        path = folder / name
        if not path.is_file():
            raise ContentInvalid(f"Nothing was loaded. {where}: {name!r} is not in the folder.")
        size = path.stat().st_size
        if size > MEDIA_MAX_BYTES:
            raise ContentInvalid(
                f"Nothing was loaded. {where}: {name!r} is {size} bytes, over the "
                f"{MEDIA_MAX_BYTES} byte limit."
            )
        data = path.read_bytes()
        content_type, called, looks_right = MEDIA_TYPES[name.rsplit(".", 1)[1].lower()]
        if not looks_right(data):
            raise ContentInvalid(
                f"Nothing was loaded. {where}: {name!r} does not start like {called}."
            )
        media[name] = (data, content_type)
    return media


@dataclass(frozen=True)
class Loaded:
    row: RecruiterContent
    created: bool
    files: dict[str, int]  # kind -> bytes loaded
    profile_sections: tuple[str, ...]
    media_files: int = 0


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
    if "resume_image" in files:
        if not files["resume_image"].startswith(PNG_MAGIC):
            raise ContentInvalid("Nothing was loaded. resume.png does not start like a PNG.")
        if "resume" not in files:
            # The image is a picture OF resume.pdf; alone it would be a resume nobody can
            # download, and possibly an older one.
            raise ContentInvalid(
                "Nothing was loaded. resume.png is in the folder without resume.pdf; render it "
                "from the PDF beside it, or remove it."
            )
    return content, files


def load(folder: Path, key: str = RecruiterContent.CURRENT_KEY) -> Loaded:
    """Check the folder, then replace the row keyed `key` with it: content, and
    each file present. A file absent from the folder is cleared, so the page
    never offers a file the folder no longer holds. `key` is a code's label
    for that code's own version (`RecruiterContent.for_user`)."""
    content, files = read_folder(folder)
    media = _read_media(Path(folder), content)
    # Postgres keeps a JSON object's keys in its own order, and the cascade speaks the profile's
    # sections in the order he wrote them (`demo.profile.sections_of`): the order is kept beside it.
    content = {**content, PROFILE_ORDER: list(content["profile"])}
    defaults: dict = {"content": content}
    for kind, (name, content_type) in ASSET_FILES.items():
        data = files.get(kind)
        defaults[kind] = data
        defaults[f"{kind}_content_type"] = content_type if data is not None else ""
        defaults[f"{kind}_filename"] = name if data is not None else ""
    with transaction.atomic():
        row, created = RecruiterContent.objects.update_or_create(key=key, defaults=defaults)
        # The row's files are exactly the ones this content names: a file a reload no longer
        # names is no longer served.
        RecruiterMediaFile.objects.filter(content=row).delete()
        RecruiterMediaFile.objects.bulk_create(
            RecruiterMediaFile(
                content=row,
                name=name,
                data=data,
                content_type=content_type,
                sha256=hashlib.sha256(data).hexdigest(),
            )
            for name, (data, content_type) in media.items()
        )
    return Loaded(
        row=row,
        created=created,
        files={kind: len(data) for kind, data in files.items()},
        profile_sections=tuple(content["profile"]),
        media_files=len(media),
    )
