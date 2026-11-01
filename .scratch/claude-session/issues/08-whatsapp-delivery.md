---
map: claude-session
ticket: "08"
title: "Build: WhatsApp delivery of task files to Kevin's own number"
type: build
status: dropped
status-detail: "Kevin 2026-11-01: \"whatsapp is abit hard then, gmail is much easier yeah, lets keep gmail then\". Delivery is `deliver: email_self` via send_mail.py (tickets 03/04). Kept for the record."
blockers: ["03", "04"]
blocked-by: ["[[03-engine-app]]", "[[04-worker]]"]
open-blockers: 2
ready: false
tags: [ticket]
---

# Build: WhatsApp delivery

Destination: "use Claude on my Alienware to find the best-rated 4x4 shops near Austin, put it in an
Excel sheet and send it to my WhatsApp." A helper searches the web, writes the .xlsx, reports
`--deliver whatsapp_self --file PATH`; the worker uploads the file; the engine sends it to
Kevin's WhatsApp from Alfred's number with a one-line caption; Alfred says "sent to your WhatsApp".

## Flow

- Task field `deliver`: null or `whatsapp_self` (set by the phone only when Kevin named his
  WhatsApp in the request, ruling 17). Without it, a helper that wants to send asks
  (`needs_confirmation`), same as mail.
- `report.py --deliver whatsapp_self --file PATH` (repeatable file): the worker treats the file as
  requested and uploads it on the next heartbeat. The work laptop (`listed`) refuses: summaries only.
- Engine on upload of a `deliver` file: send via WhatsApp Cloud API
  `POST /{phone-number-id}/messages`, type `document` (or `image`), media uploaded first, caption =
  task summary first line. Result (`sent`, `failed: <reason in words>`) on the task.
- 24 h customer-service window: free-form messages only reach Kevin if he messaged Alfred's number
  in the last 24 h. Outside it, send an approved utility template ("Your file from <machine> is
  ready") with the document header. Template name in settings.
- Secrets: `WHATSAPP_TOKEN`, `WHATSAPP_PHONE_NUMBER_ID`, `WHATSAPP_KEVIN_NUMBER` in engine env
  (Secret Manager), never in the repo or logs. Media limit: document 100 MB (task files cap at 25).

## Kevin by hand

1. Meta Business account + WhatsApp Business app in developers.facebook.com.
2. A number for Alfred that is not on personal WhatsApp (a cheap SIM or virtual number).
3. Permanent system-user token; one utility template submitted for approval.
4. Message Alfred's number once from his phone to open the chat.

## Tests

Fake Cloud API: document send, template fallback outside 24 h, failure surfaced in words, refusal
without `deliver`, refusal from a `listed` machine, nothing logged but ids and status.
