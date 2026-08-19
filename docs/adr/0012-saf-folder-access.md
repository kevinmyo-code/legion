---
status: accepted
decided: 2026-08-01
decided-by: Kevin
source: "decisions.md 2026-08-01"
tags: [adr]
---

# 12. Statement folder access is SAF, not a Drive scope

## Standing

ACCEPTED, with a specified device probe that was never run.

## Context

Kevin wants to point LEGION at a folder of bank statements that the Drive app syncs locally. SAF, `drive.file` and `drive.readonly` were all candidates. The latter two are restricted scopes with a verification burden.

## Decision

Use SAF `ACTION_OPEN_DOCUMENT_TREE`, gated at SDK 30+, with a per-file `ACTION_OPEN_DOCUMENT` fallback below that. No new OAuth scope: `drive.appdata` is unchanged.

## Consequences

- SAF exposes no content hash, so file identity is `driveFileId` plus `sizeBytes` plus `lastModified`, and establishing content identity requires reading the whole file.
- The central claim, that files added later appear in the listing, was traced through four layers and **never tested on hardware**. A 15-minute probe was specified and not run.
- Stale-empty listings caused by Drive sync latency are normal and must never be treated as an error.
