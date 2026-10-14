# `legion-schema.yaml` — VENDORED COPY

This file is **generated**, not hand-written. It is produced by

```
python manage.py spectacular
```

run inside the LEGION repo's `server/` (the Django engine, `com.kevin.legion` /
`github.com/kevinmyo-code/legion`). It is the OpenAPI 3 contract between the engine and this limb
(AiApp / MidnightAI, the head-unit client) — the same document the phone app and the planned PWA
client also build against.

## This is a vendored copy, not a live link

AiApp has no build-time dependency on the LEGION repo or its running server. The file here is a
snapshot, checked into this repo, and `openApiGenerate` (see `app/build.gradle.kts` / root
`build.gradle.kts`) reads it from disk at build time. **Re-syncing it is a manual, two-step act:**

1. Re-run `python manage.py spectacular` in the LEGION repo's `server/` to produce a fresh
   `legion-schema.yaml`.
2. Copy that file over `openapi/legion-schema.yaml` here, re-run codegen
   (`./gradlew openApiGenerate`), and fix whatever the generated model diff breaks in
   `backend/engine/`.

Nothing here fetches the schema automatically. If the engine's contract changes and nobody performs
those two steps, this limb silently keeps building against a stale contract — the checksum below
exists so that staleness is at least detectable by a human, not caught only when a field goes
missing at runtime.

## Provenance

| Field | Value |
|---|---|
| Source | LEGION repo, `server/`, `python manage.py spectacular` |
| `info.title` | LEGION |
| `info.version` | `0.1.0` |
| `info.description` | "The engine. One Django server, every limb a client of it." |
| Vendored on | 2026-09-07 |
| SHA-256 | `d6d4757d4ac5518d62ba8ab7cf5ce85617807c1b1859055dad2d0017bc8c3428` |

To check for drift against a freshly generated copy:

```
sha256sum openapi/legion-schema.yaml
```

If the hash does not match the one above, this README is stale relative to the file next to it —
re-copy from LEGION, re-run codegen, and update both the hash and the `info.version` row above in
the same commit.
