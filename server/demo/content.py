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
import re
import struct
from dataclasses import dataclass
from pathlib import Path

from django.db import transaction
from jsonschema import Draft202012Validator

from demo import media_store
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

# Media FILE NAMES (Kevin, 2026-11-19). A model, its poster, and (instead of a public link) a
# section's or a phone clip's video, poster and captions may name a file in the private content
# folder: the loader stores it beside the content and `GET /api/demo/media/<name>` serves it to a
# visit or an owner only. A name is one plain segment, so it can never leave the folder.
# `\Z`, not `$`: jsonschema searches with `re.search`, where `$` also matches before a final
# newline, and a name ending in a newline would pass the schema yet never be served.
_STEM = r"^[A-Za-z0-9][A-Za-z0-9_-]{0,63}"
MODEL_NAME = _STEM + r"\.glb\Z"
POSTER_NAME = _STEM + r"\.(png|jpg|jpeg|webp)\Z"
VIDEO_NAME = _STEM + r"\.(mp4|webm)\Z"
CAPTIONS_NAME = _STEM + r"\.vtt\Z"
# A link the page fetches as written: off-site (https://) or on this site (/...). Never a name.
_LINK = r"^(https?://|/)\S*\Z"
_FOLDER = "a file name in the content folder (letters, digits, - and _, then "
_NAME_HINTS = {
    MODEL_NAME: _FOLDER + ".glb)",
    POSTER_NAME: _FOLDER + ".png, .jpg or .webp)",
    VIDEO_NAME: _FOLDER + ".mp4 or .webm)",
    CAPTIONS_NAME: _FOLDER + ".vtt)",
}


def _link_or_file(name_pattern: str) -> dict:
    """A clip's video, poster or captions: a link, as before, or a file name served privately."""
    return {
        "type": "string",
        "anyOf": [{"pattern": _LINK}, {"pattern": name_pattern}],
        "x-hint": f"a link (https://... or /...) or {_NAME_HINTS[name_pattern]}",
    }


_CLIP_FILES = {
    "video": _link_or_file(VIDEO_NAME),
    "poster": _link_or_file(POSTER_NAME),
    "captions": _link_or_file(CAPTIONS_NAME),
}


def is_file_name(value: str) -> bool:
    """A media value that names a file in the folder (not a link)."""
    return re.match(_LINK, value) is None


# "LEGION on the phone" (prototype D, 2026-11-03): one clip per capability, cut
# from one recording. A clip with no `video` yet is drawn as a placeholder;
# `poster` and `captions` (a WebVTT file) go with the video, each a link or a file
# name (`_link_or_file`). `full_take` is the whole recording, linked under the section.
# The phone section's clips: the page shows one per capability; a dozen is already a long scroll.
MAX_PHONE_CLIPS = 12
_PHONE_CLIP = _obj(
    {"title": _STR, "body": _STR, **_CLIP_FILES},
    optional=("video", "poster", "captions"),
)
_PHONE = _obj(
    {
        "title": _STR,
        "intro": _STR,
        "clips": {"type": "array", "items": _PHONE_CLIP, "maxItems": MAX_PHONE_CLIPS},
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

# A project section's media: the clip slot (its video, poster and captions each a link or a file
# name) plus up to `MAX_MODELS` models. The strip's line keeps the plain slot of links: a model
# belongs in a section with room for it.
_SECTION_MEDIA = _obj(
    {
        **_CLIP_FILES,
        "note": _STR,
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

# The page's section palettes (Kevin, 2026-11-22: a different feel per section): the
# page blends to a section's palette as it scrolls in. Named in `index.css` (`[data-theme]`).
SECTION_THEMES = ("legion", "ledger", "midcon", "aw4", "shop")
# A mark the orb takes on beside a section (Kevin, 2026-11-22: the orb becomes the finance voice
# demo's Q at its section). Only a letter drawn from a system font: no logo file is shipped.
# "larkspur": the made-up shop's own mark (a pine and a larkspur spike, drawn by the page).
ORB_MARKS = ("q", "larkspur")
# A live demo the page itself draws inside a section (Kevin, 2026-11-22: the store rep as a section
# of its own): its controls and its after-call card. The page knows each by name; content only
# says where it goes.
SECTION_WIDGETS = ("store_rep",)

# A project section below LEGION's (Kevin, 2026-11-05: the second project). The same shape as
# `legion`, plus the `id` the strip's anchor points at, an optional media slot, an optional link
# out to a live demo (https only: the page opens it in a new tab), a palette and an orb mark.
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
        "link": _obj({"href": {"type": "string", "pattern": r"^https://\S+\Z"}, "label": _STR}),
        "theme": {"enum": list(SECTION_THEMES)},
        "orb_mark": {"enum": list(ORB_MARKS)},
        "widget": {"enum": list(SECTION_WIDGETS)},
    },
    optional=("diagram_svg", "diagram_caption", "media", "link", "theme", "orb_mark", "widget"),
)

# A part of the LEGION section: its words, and (optionally) the headings of the diagram boxes it is
# about, which the page lights as the part's step comes up (Kevin, 2026-11-22: the words move with
# the diagram's highlights). A heading the diagram does not have lights nothing; the page then
# falls back to the layer at the part's share of the way through.
MAX_LIGHTS = 12
_LIGHTS = {
    "type": "array",
    # Not blank: a name of spaces would normalise to "" and light every box with no heading.
    "items": {"type": "string", "minLength": 1, "maxLength": 80, "pattern": r"\S"},
    "maxItems": MAX_LIGHTS,
}
# `short`: the same words, fewer of them, for a phone, where the stage leaves the card little room
# (blind review D2, 2026-11-22).
MAX_SHORT = 160
_SHORT = {"type": "string", "minLength": 1, "maxLength": MAX_SHORT, "pattern": r"\S"}
_LEGION_PART = _obj(
    {"title": _STR, "body": _STR, "lights": _LIGHTS, "short": _SHORT},
    optional=("lights", "short"),
)

# "Quality for voice agents" inside the LEGION section (Kevin, 2026-11-22): a few figures and the
# checks behind them, short and visual. Lists, so later checks (a scenario, a card) add rows only.
MAX_QUALITY_STATS = 6
_QUALITY = _obj(
    {
        "title": _STR,
        "intro": _STR,
        "stats": {
            "type": "array",
            "items": _obj({"value": _STR, "label": _STR}),
            "maxItems": MAX_QUALITY_STATS,
        },
        "checks": {"type": "array", "items": _TITLE_BODY},
    },
    optional=("intro",),
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
                "parts": {"type": "array", "items": _LEGION_PART},
                "quality": _QUALITY,
            },
            optional=("diagram_svg", "diagram_caption", "quality"),
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

ASSET_KINDS = RecruiterContent.ASSET_KINDS
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
    "mp4": ("video/mp4", "an MP4", lambda b: b[4:8] == b"ftyp"),
    "webm": ("video/webm", "a WebM", lambda b: b.startswith(bytes([0x1A, 0x45, 0xDF, 0xA3]))),
    "vtt": (
        "text/vtt; charset=utf-8",
        "a WebVTT file",
        lambda b: b.removeprefix(bytes([0xEF, 0xBB, 0xBF])).startswith(b"WEBVTT"),
    ),
}
# Size discipline (review R2-1, 2026-11-19). The files live in the engine's Postgres, a small
# hosted plan that turns the WHOLE engine read-only when it fills, so the caps are about the
# database, not the page. Per file, by kind: a model 4 MB (the page's own target is under 3 MB);
# a still 1 MB (shown at most about 700 px wide); a video 16 MB (about 30 s of a 1080p screen
# recording at 4 Mbit/s: cut a longer take into clips); captions 256 KB. Per content version: all
# its files together at most `MEDIA_TOTAL_MAX_BYTES`, and at most `MAX_VIDEO_FILES` videos. Serving
# is bounded separately: the route reads at most `content_views.RANGE_CHUNK` of a row at a time.
MEDIA_MAX_BYTES = {
    "model/gltf-binary": 4 * 1024 * 1024,
    "video/mp4": 16 * 1024 * 1024,
    "video/webm": 16 * 1024 * 1024,
    "text/vtt; charset=utf-8": 256 * 1024,
}
IMAGE_MAX_BYTES = 1024 * 1024
MEDIA_TOTAL_MAX_BYTES = 40 * 1024 * 1024
MAX_VIDEO_FILES = 12

MEDIA_URL = "/api/demo/media/"


def media_cap(content_type: str) -> int:
    return MEDIA_MAX_BYTES.get(content_type, IMAGE_MAX_BYTES)


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
        elif error.validator == "anyOf" and "x-hint" in error.schema:
            message = f"{error.instance!r} is not {error.schema['x-hint']}"
        found.append(f"{where}: {message}")
    if not found and isinstance(content, dict):
        found.extend(_anchor_problems(content))
    return found


def _anchor_problems(content: dict) -> list[str]:
    """What the schema cannot say: two project sections with one id, one model shown twice in a
    section, or a strip line whose `#anchor` names a section this content does not draw (a dead
    link on the page)."""
    found = []
    ids = [section["id"] for section in content.get("project_sections", [])]
    for index, section_id in enumerate(ids):
        if section_id in ids[:index]:
            found.append(f"project_sections/{index}/id: {section_id!r} is used twice")
    for s, section in enumerate(content.get("project_sections", [])):
        seen: list[str] = []
        for m, model in enumerate(section.get("media", {}).get("models", [])):
            if model["model"] in seen:
                found.append(
                    f"project_sections/{s}/media/models/{m}/model: {model['model']!r} is shown "
                    "twice in this section"
                )
            seen.append(model["model"])
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
    """What the page is served: everything but `profile` (and its order), with every media file
    name (`media_slots`) turned into the private route that serves it. Links stay as written."""
    public = {
        key: value for key, value in content.items() if key not in ("profile", PROFILE_ORDER)
    }
    if not media_names(public):
        return public
    public = json.loads(json.dumps(public))  # a deep copy: the stored content is never touched
    for _where, holder, key in media_slots(public):
        if is_file_name(holder[key]):
            holder[key] = MEDIA_URL + holder[key]
    return public


def media_slots(content: dict) -> list[tuple[str, dict, str]]:
    """(schema path, the dict holding it, its key) of every value that may name a media file: a
    project section's clip files and models, and the phone clips' files."""
    slots = []
    for s, section in enumerate(content.get("project_sections", [])):
        media = section.get("media", {})
        for key in ("video", "poster", "captions"):
            if key in media:
                slots.append((f"project_sections/{s}/media/{key}", media, key))
        for m, model in enumerate(media.get("models", [])):
            for key in ("model", "poster"):
                slots.append((f"project_sections/{s}/media/models/{m}/{key}", model, key))
    for c, clip in enumerate(content.get("phone", {}).get("clips", [])):
        for key in ("video", "poster", "captions"):
            if key in clip:
                slots.append((f"phone/clips/{c}/{key}", clip, key))
    return slots


def media_names(content: dict) -> list[tuple[str, str]]:
    """(schema path, file name) of every media file the content names, in order (links skipped)."""
    return [
        (where, holder[key])
        for where, holder, key in media_slots(content)
        if is_file_name(holder[key])
    ]


def _read_media(folder: Path, content: dict) -> dict[str, tuple[bytes, str]]:
    """name -> (bytes, content type) for every file the content names. Refuses, before reading
    any file and before anything is written, a file that is missing or over its kind's cap, more
    than `MAX_VIDEO_FILES` videos, or a total over `MEDIA_TOTAL_MAX_BYTES`; then a file that is not
    what its name says. Names were checked by the schema already."""
    named: dict[str, tuple[str, Path, str, str, int]] = {}
    for where, name in media_names(content):
        if name in named:
            continue
        path = folder / name
        if not path.is_file():
            raise ContentInvalid(f"Nothing was loaded. {where}: {name!r} is not in the folder.")
        content_type, called, _looks_right = MEDIA_TYPES[name.rsplit(".", 1)[1].lower()]
        size, cap = path.stat().st_size, media_cap(content_type)
        if size > cap:
            raise ContentInvalid(
                f"Nothing was loaded. {where}: {name!r} is {size} bytes, over the "
                f"{cap} byte limit for {called}."
            )
        named[name] = (where, path, content_type, called, size)
    videos = [name for name, item in named.items() if item[2].startswith("video/")]
    if len(videos) > MAX_VIDEO_FILES:
        raise ContentInvalid(
            f"Nothing was loaded. The content names {len(videos)} video files; at most "
            f"{MAX_VIDEO_FILES} are stored (the first one over is {videos[MAX_VIDEO_FILES]!r})."
        )
    total = 0
    for name, (where, _path, _type, _called, size) in named.items():
        total += size
        if total > MEDIA_TOTAL_MAX_BYTES:
            raise ContentInvalid(
                f"Nothing was loaded. {where}: {name!r} takes the media files to {total} bytes, "
                f"over the {MEDIA_TOTAL_MAX_BYTES} byte limit for one content version (the "
                "engine's database is small; shorten or drop a video)."
            )
    media: dict[str, tuple[bytes, str]] = {}
    for name, (where, path, content_type, called, _size) in named.items():
        data = path.read_bytes()
        if not MEDIA_TYPES[name.rsplit(".", 1)[1].lower()][2](data):
            raise ContentInvalid(
                f"Nothing was loaded. {where}: {name!r} does not start like {called}."
            )
        problem = _glb_problem(data) if content_type == "model/gltf-binary" else None
        if problem:
            raise ContentInvalid(f"Nothing was loaded. {where}: {name!r} {problem}.")
        media[name] = (data, content_type)
    return media


def _store_media(
    row: RecruiterContent, media: dict[str, tuple[bytes, str]]
) -> tuple[int, int, int]:
    """Make the row's files exactly `media`: drop the ones no longer named or changed, insert the
    new and changed ones ONE ROW PER INSERT, and leave a file whose sha256 is unchanged alone (a
    text-only reload moves no megabytes). With a media bucket set (`demo.media_store`) the bytes
    go there as well, and the row names the object; a file already stored whose bytes never
    reached the bucket is uploaded too, so the first load after the bucket is set moves everything
    out of the read path. Returns (files written, bytes written, files put in the bucket).

    An upload that a later failure rolls back leaves an object nothing names, which costs a few
    megabytes of bucket and is overwritten by the next load of the same bytes: the object's name
    is its own hash."""
    wanted = {name: hashlib.sha256(data).hexdigest() for name, (data, _type) in media.items()}
    store = media_store.get_store()
    have = {
        name: (sha, key)
        for name, sha, key in RecruiterMediaFile.objects.filter(content=row).values_list(
            "name", "sha256", "object_name"
        )
    }
    stale = [name for name, (sha, _key) in have.items() if wanted.get(name) != sha]
    RecruiterMediaFile.objects.filter(content=row, name__in=stale).delete()
    fresh = [name for name in media if have.get(name, ("", ""))[0] != wanted[name]]
    moving = [
        name
        for name in media
        if name not in fresh and store is not None and not have[name][1]
    ]
    keys = {name: media_store.object_key(wanted[name], name) for name in (*fresh, *moving)}
    if store is not None:
        for name in (*fresh, *moving):
            store.put(keys[name], media[name][0], media[name][1])
    RecruiterMediaFile.objects.bulk_create(
        (
            RecruiterMediaFile(
                content=row,
                name=name,
                data=media[name][0],
                object_name=keys[name] if store is not None else "",
                content_type=media[name][1],
                sha256=wanted[name],
            )
            for name in fresh
        ),
        batch_size=1,
    )
    for name in moving:
        RecruiterMediaFile.objects.filter(content=row, name=name).update(object_name=keys[name])
    to_bucket = len(keys) if store is not None else 0
    return len(fresh), sum(len(media[name][0]) for name in fresh), to_bucket


def file_named_videos(content: dict) -> list[str]:
    """Schema paths of clip videos named by FILE (not by link). The engine before demo 0010 serves
    such a value as a raw relative URL, so after a traffic rollback these clips break at once."""
    return [where for where, _name in media_names(content) if where.endswith("/video")]


_GLB_JSON_CHUNK = 0x4E4F534A


def _glb_problem(data: bytes) -> str | None:
    """Why a GLB may not be served to the page, or None. A model with embedded texture images is
    refused: three.js decodes them through `blob:` URLs, which the engine's CSP (`legion/csp.py`,
    connect-src) does not allow, so it would load untextured or fail once CSP is enforced."""
    if len(data) < 20:
        return "is too short to be a GLB"
    length, kind = struct.unpack_from("<II", data, 12)
    if kind != _GLB_JSON_CHUNK:
        return "has no JSON chunk where a GLB keeps it"
    try:
        gltf = json.loads(data[20 : 20 + length])
    except (UnicodeDecodeError, ValueError):
        return "has a JSON chunk that does not parse"
    if gltf.get("images"):
        return (
            f"embeds {len(gltf['images'])} texture image(s); the page's CSP has no blob: in "
            "connect-src, so textures are not allowed (bake colours into materials instead)"
        )
    return None


@dataclass(frozen=True)
class Loaded:
    row: RecruiterContent
    created: bool
    files: dict[str, int]  # kind -> bytes loaded
    profile_sections: tuple[str, ...]
    media_files: int = 0
    media_bytes: int = 0  # all the version's media files, stored
    media_written: int = 0  # files this load inserted (new or changed)
    media_bytes_written: int = 0
    media_to_bucket: int = 0  # files whose bytes this load put in the media bucket
    file_named_videos: tuple[str, ...] = ()


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
        written, bytes_written, to_bucket = _store_media(row, media)
    return Loaded(
        row=row,
        created=created,
        files={kind: len(data) for kind, data in files.items()},
        profile_sections=tuple(content["profile"]),
        media_files=len(media),
        media_bytes=sum(len(data) for data, _type in media.values()),
        media_written=written,
        media_bytes_written=bytes_written,
        media_to_bucket=to_bucket,
        file_named_videos=tuple(file_named_videos(content)),
    )
