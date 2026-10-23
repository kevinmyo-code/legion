---
map: books
ticket: "04"
title: "Build: server PDF to EPUB conversion, pass-through, nothing stored"
type: build
status: built
status-detail: "Built 2026-10-23 on PyMuPDF + ebooklib, the ticket's fallback (Calibre measured at 1105 MB of apt). Server suite green locally (1854, 0 failed, 46 skipped). Owed at deploy, with Kevin's OK: image build, then one real PDF converted on the deployed container; Cloud Run timeout/memory raise is now optional, measure first."
blockers: ["03"]
blocked-by: ["[[03-pdf-refit-decision]]"]
open-blockers: 0
ready: false
tags: [ticket]
---

# Build: server PDF to EPUB conversion

Rulings in ticket 03. Server only; `app/` is ticket 05.

## Endpoint

`POST /api/books/convert`, authenticated like every other device endpoint (device token or session),
household membership required. Body: the PDF as `application/pdf` (raw body, not multipart). Response:

| Case | Status | Body |
|---|---|---|
| Converted | 200 | `application/epub+zip`, the EPUB bytes. Header `X-Legion-Pages` (page count). |
| No text layer | 422 | JSON `{"code": "scanned", "message": "This PDF is scanned pages with no text, so its text cannot be refit."}` |
| Not a PDF / unreadable / encrypted | 422 | JSON `{"code": "unreadable", "message": "<in words, which>"}` |
| Over the size cap | 413 | JSON `{"code": "too_big", "message": "This PDF is <n> MB; the most the server takes is 30 MB."}` |
| Converter failed or timed out | 502 | JSON `{"code": "failed", "message": "<in words>"}` |

- **Size cap 30 MB**, checked from `Content-Length` before reading the body (stays under Cloud Run's
  32 MiB front-end limit with headroom).
- **Nothing persists.** Work in a `tempfile.TemporaryDirectory()`, delete on every path including
  exceptions. No model, no migration, no tenancy table.
- **Logging: metadata only** - bytes in, pages, chars detected, seconds, outcome code. Never a title,
  a filename, or text.
- Add to `openapi.yaml` and regenerate `server/frontend/src/api/schema.d.ts` the way the brief fix did
  (`write_openapi`, then `openapi-typescript`).

## Scan detection (before conversion)

Count extractable text characters per page with `pdftotext` (poppler-utils) or the converter's own
text pass. **Scanned if the median page has fewer than 200 non-whitespace characters.** Sheet music
and image-only PDFs land here. Test with fixtures.

## Converter

- **Primary: Calibre `ebook-convert in.pdf out.epub --enable-heuristics`**, plus
  `--pdf-engine` default. Install in `server/Dockerfile`'s runtime stage via apt (`calibre` pulls Qt;
  use `--no-install-recommends` and set `QT_QPA_PLATFORM=offscreen`). Subprocess with a **50 s timeout**
  (the service timeout is 60 s today), killed on overrun -> 502.
- **Fallback if the Calibre image is unworkable** (build fails, image grows past ~1.5 GB, or it will
  not run headless): PyMuPDF text blocks into a minimal EPUB (`ebooklib`), one chapter per ~20 pages,
  paragraphs joined across line breaks, hyphenated line ends rejoined, repeated header/footer lines and
  bare page numbers dropped. Record which path shipped and why in the commit.
- **Dockerfile change is its own commit.** It cannot be built on the charting machine (no Docker).
  Verification that the image builds and `ebook-convert` runs on the deployed container is a
  **deploy-time step**, not a done step.

## Deploy-time config (needs Kevin's OK, like every production deploy)

Cloud Run `legion`: raise `timeoutSeconds` 60 -> 120 and memory 512 MiB -> 1 GiB. A 12 MB textbook
under Calibre will not fit 512 MiB / 60 s. Say this in the deploy request; do not change it without OK.

## Tests

- Subprocess mocked (no Calibre on Windows): each status row above, the size cap from
  `Content-Length`, temp dir removed on success and on exception, log line carries no filename/text.
- Scan detection on two fixtures: a tiny text PDF and an image-only PDF (generate both in the test).
- One integration test that really converts, `skipif` `ebook-convert` is not on PATH. Say plainly that
  real conversion is first exercised on the deployed container.
- Server rules: `uv run --no-sync`, never `--reuse-db`, one pytest run at a time.

## As built (2026-10-23)

- **Converter: the fallback, PyMuPDF 1.28.2 + ebooklib 0.20**, run as a child process
  (`server/books/convert.py`) under the 50 s timeout. Calibre was ruled out on Debian's own
  package data, not memory: `python:3.13-slim` is trixie, and trixie's `calibre`
  8.5.0+ds-1+deb13u3 with `--no-install-recommends` pulls 370 packages, 1105 MB installed (amd64;
  1064 MB arm64), Chromium's QtWebEngine and a second Python among them, onto an image of roughly
  400 MB. That is the ~1.5 GB line above, and it could not be run headless before production.
  The numbers are in commit `077bfc6e`.
- **No Dockerfile text change.** Both libraries install from wheels (abi3, x86_64 and aarch64) in
  the existing `pip install` layer, and PyMuPDF's own text pass replaces `pdftotext`, so
  poppler-utils is not added. The image still changes, through `requirements.txt`.
- **The real conversion runs in the test suite**, not behind a `skipif`: PyMuPDF is a pinned
  requirement with a Windows wheel. What has not run is the converter inside the built image on
  Cloud Run.
- The deploy-time config above was sized for Calibre. With MuPDF the current 60 s / 512 MiB may
  be enough; time one real conversion of the 12.4 MB textbook on the deployed service before
  asking for the raise. **If the raise is asked for, it is a pair:** gunicorn's `--timeout 60`
  (`server/entrypoint.sh`) counts the whole request, upload included, so Cloud Run at 120 s with
  gunicorn left at 60 s changes nothing. The 50 s child timeout fits under 60 s only while the
  upload is quick.
