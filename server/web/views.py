"""Serving the built web client.

The bundle is produced by `npm run build` in `server/frontend`, which writes it
to `server/static/app/` (Vite's `build.outDir`). Django's job is to hand the
browser `index.html` for any path the SPA owns; every asset it references is a
normal static file that WhiteNoise serves.
"""
from __future__ import annotations

from pathlib import Path

from django.conf import settings
from django.http import HttpRequest, HttpResponse

# Relative to STATIC_ROOT or to a STATICFILES_DIRS entry, whichever has it.
SPA_INDEX_RELATIVE_PATH = Path("app") / "index.html"

NOT_BUILT_MESSAGE = (
    "The web client has not been built; run `npm run build` in server/frontend"
)


def _candidate_paths() -> list[Path]:
    """Where `index.html` might be, in the order it should be looked for.

    STATIC_ROOT first, then the source directories. That order is deliberate
    and it is the production-correct one: after `collectstatic` the file in
    STATIC_ROOT is the one every asset URL on the page was hashed against, so
    reading the source copy instead would serve a shell pointing at asset names
    that the manifest has since renamed.

    Falling back to STATICFILES_DIRS is what makes the dev loop work with no
    `collectstatic` step at all - `npm run build` alone is enough to see the
    real bundle on `runserver`.
    """
    paths: list[Path] = []
    static_root = getattr(settings, "STATIC_ROOT", None)
    if static_root:
        paths.append(Path(static_root) / SPA_INDEX_RELATIVE_PATH)
    for source_dir in getattr(settings, "STATICFILES_DIRS", []):
        paths.append(Path(source_dir) / SPA_INDEX_RELATIVE_PATH)
    return paths


def spa_index(request: HttpRequest) -> HttpResponse:
    """Hand the browser the SPA shell, or say plainly that there isn't one.

    Read from disk per request rather than cached at import. The file is a few
    hundred bytes, it changes on every front-end build, and a process holding a
    stale copy of it during development is a confusing failure to diagnose -
    the page looks right and points at assets that no longer exist.

    `Cache-Control: no-store` for the same reason, aimed at the browser: the
    shell's whole job is to name the current hashed asset bundles, so a cached
    shell is a page asking for files a deploy has already replaced. The assets
    it points at are content-hashed and cache forever; this one file must not.

    When the bundle is absent the answer is a 503 in words - CLAUDE.md section 7
    applies to a server response as much as to a spoken one, and the two ways to
    get this wrong are a stack trace (says nothing to a household running the
    compose stack) and a 404 (says the address is wrong when the address is
    fine and the build simply has not run).
    """
    for candidate in _candidate_paths():
        if candidate.is_file():
            response = HttpResponse(
                candidate.read_bytes(), content_type="text/html; charset=utf-8"
            )
            response["Cache-Control"] = "no-store"
            return response

    response = HttpResponse(
        NOT_BUILT_MESSAGE, content_type="text/plain; charset=utf-8", status=503
    )
    response["Cache-Control"] = "no-store"
    return response
