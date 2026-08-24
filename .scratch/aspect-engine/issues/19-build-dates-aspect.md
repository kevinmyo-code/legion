---
map: aspect-engine
ticket: "19"
title: "Build the Dates aspect"
type: task
status: open
status-detail: ""
blockers: ["16"]
blocked-by: ["[[16-build-engine-core]]"]
open-blockers: 0
ready: true
tags: [ticket]
---
# Build the Dates aspect

## Question

Build what ticket 05 locked:

1. The built-in Dates aspect on the engine: events as records, voice CRUD via meta-tools,
   agenda as a query across the Dates aspect plus every record's dueAt.
2. Google Calendar import as a capability plugin: store everything imported (invites included),
   tagged source=google plus event id; re-import updates in place; Google-side deletions mirror;
   one-way in v1.
3. Reminder firing: exact alarms with the permission flow; every reminder passes the compulsion
   test (anchored, actionable, silenceable, never absence-referencing); fired-reminder copy
   human-reviewed.
4. OpenerCalendarBriefing and the proactive layer read the central date store once it exists,
   not Google directly (fog item graduated here).
