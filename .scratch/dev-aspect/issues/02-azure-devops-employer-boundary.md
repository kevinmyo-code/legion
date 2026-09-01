---
map: dev-aspect
ticket: "02"
title: "Azure DevOps and the employer-data boundary"
type: grilling
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# Azure DevOps and the employer-data boundary

## Question

The org work lives in the employer's Azure DevOps. Pulling it into a personal Supabase project is
technically easy and is the part of this map most likely to be a mistake.

Two rules collide with it:

**CLAUDE.md section 7, third-party content is read-through only.** *"Anything other people wrote to
Kevin rather than anything Kevin created or chose to import ... may be read to answer a question and
must then be dropped. Never persisted to Room, never synced, never remembered."* A work item's
description and comment thread, written by a colleague, is squarely that shape. A work item's
title and status, on an item assigned to Kevin, is arguably not - it is metadata about his own
assignments.

**Employer data policy**, which nothing in this repo can answer and which is not the same question
as whether the API allows it. A PAT that works is not permission.

## The middle option, and why it is the recommendation

Sync **titles, state, assignee, project, URL and dates only. No descriptions, no comment threads,
no attachments.** That answers the question actually asked - what is pending on which project - and
it never persists another person's prose. The assistant says *"four open items on Project X, oldest
is 'Fix the ingest retry'"* and, if Kevin wants the body, it opens the URL rather than reading a
stored copy.

## Decide

1. Titles-and-status only, full sync, or no Azure sync at all?
2. If full sync: Kevin rules it an explicit, written exception to section 7, with the reasoning in
   `decisions.md`. It is not enough for it to be convenient.
3. Employer policy - has this been checked, and against what? Record the answer, not an assumption.
4. Does the sync cover only items assigned to Kevin, or every item in projects he can see? The
   second is far more third-party content for very little extra answer.
5. Where does the PAT live? It is an employer credential, so the BYO-key posture that covers the
   Gemini key needs restating for a secret with a different owner.
6. Revocation: what happens to already-synced rows when the PAT is revoked or Kevin leaves? A
   deletion path is part of the ruling, not a later cleanup.

## Verification

- The ruling is in `decisions.md` with its reasoning, and if it is an exception to section 7 that
  word appears in it.
- If titles-only, the exclusion is enforced at the write site the way
  `LiveToolbox.EPISODIC_EXCLUDED_TOOLS` is - a column that is never populated, not a habit each
  future change has to remember.
- Ticket 06 does not start until this is resolved.
