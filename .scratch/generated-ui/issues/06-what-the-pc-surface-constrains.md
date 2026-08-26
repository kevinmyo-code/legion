---
map: generated-ui
ticket: "06"
title: "What the PC surface constrains, and what it does not"
type: grilling
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# What the PC surface constrains, and what it does not

## Question

The PC app is coming and will have a fixed rich UI. It is not being built here. This ticket exists
only to settle the parts that constrain the phone, so the phone is not built into a corner.

1. **Does the PC share the response schema?** If the schema is phone-only, it can be shaped purely
   for small screens and spoken questions. If shared, it has to carry layout intent the phone will
   never use. Recommend phone-only, with the PC reading Supabase directly through its own fixed UI:
   the schema then stays small, which is what keeps tokens and validation cheap.
2. **Does the PC share components, or only data?** Recommend only data. Two renderers with one
   component vocabulary drift, and the PC's whole premise is that it does NOT need generated views.
3. **Discoverability lives on the PC** (ticket 04, question 4). If so, that is a real dependency to
   record: until the PC exists, the phone is the only surface, and the discoverability gap is open
   rather than solved. Say so plainly rather than treating a future app as present mitigation.
4. **Auth and household are already settled** for any surface by
   [[0038-byo-supabase-is-the-system-of-record]] and backend-erp ticket 02: email and password,
   household RLS, dashboard-created accounts. The PC inherits that; nothing here reopens it.

## Note

Anything beyond these four questions belongs to a PC map that does not exist yet. Resist scoping the
PC app here.
