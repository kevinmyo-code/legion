---
map: conversation-records
ticket: "05"
title: "Build: engine page or report that renders a household's conversation batches"
type: build
status: open
status-detail: ""
blockers: ["01"]
blocked-by: ["[[01-all-conversation-logs-on-the-engine]]"]
open-blockers: 0
ready: true
tags: [ticket]
---
# Build: engine conversation view

## What to build

A household-scoped web page or report on the engine that renders `reason=conversation` batches as
the timeline `manage.py device_logs --render` prints (`devicelogs/render.py`), newest first, per
device. Reads go through the request's household (`household_of`), like the upload.

## Open

- A page in the engine's web app, or a scheduled report.
- Whether a session split across two batches is stitched before rendering.
