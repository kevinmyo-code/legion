---
map: watches
title: "Standing watches and engine signals"
charted: 2026-11-10
charted-by: "Kevin + Opus"
effort: "`.scratch/watches/`"
tickets: 5
open: 5
status: open
tags: [map]
---

# Standing watches and engine signals

**Kevin, 2026-11-10 ("1 and 2 yes"):**

1. **Standing watches.** "Alfred, tell me when my paycheck lands / a charge goes over $X / a grade
   posts." He sets a rule by voice (or on a screen), the engine checks it, Alfred speaks when it fires.
2. **Engine signals.** Things the engine already knows (bank feed, Canvas, car service due) reach
   the phone's proactive bus on their own.
3. Later the same day: **"notify me whenever a company i applied to emails me back"**, the first
   PHONE-evaluated kind (mail never reaches the engine).

Binding decision: **ADR 0083** (`docs/adr/0083-standing-watches.md`).

**A fresh effort, by Kevin's ruling.** `proactive-mode`'s map put "push from anywhere but the device
itself" out of scope, returning only as a fresh effort. That line predates the engine (ADR 0044).
This map is that effort. Its settled decisions 7, 8, 11 and 12 bind here unchanged.

## Contract

**One mechanism.** A `Watch` row (owner, kind, typed params, repeat or one-shot, expiry, paused) and a
`WatchFiring` row (the engine's own sentence, the source row ids, a per-member `fact_key` so one fact
fires once). "Engine signals" are built-in watches, seeded per member on their first phone read.

**Kinds (closed registry, `server/watches/registry.py`):**

| Kind | Reads | Params | Evaluated |
|---|---|---|---|
| `charge_over` | `ledger_transactions`, money out | amount_cents, currency, merchant? | engine |
| `deposit_over` | `ledger_transactions`, money in, not on a credit account | amount_cents, currency, payer? | engine |
| `balance_under` | `plaid_items.accounts` balance + as-of | amount_cents, currency, account? | engine, edge-triggered |
| `canvas_due` | `events` with Canvas metadata (private) | hours (24) | engine |
| `grade_posted` | `events.structured_meta.grade/score` | course? | engine, baselined at arm |
| `service_due` | `maintenance_schedules` + `service_history` | days (14), miles (500, estimate) | engine |
| `mail_reply` | Gmail headers, on the phone | companies | **phone** |

Dropped: "event or task due" (the phone's reminder alarms and `push_dispatch` already say those; a
watch would double-speak). Weather and river gauges wait for `feat/conditions` (`CONDITIONS_SEAM`).

**Endpoints (`/api/watches/`):** `GET` list (+ kinds, seeds built-ins), `POST` set, `PATCH/DELETE
<id>`, `GET firings?pending=1`, `POST firings/ack {ids, outcome}`. Recruiters, demo visits and any
Claude worker token are refused.

**Firing payload:** `id, watch_id, watch_label, kind, facts, detail, sources, fired_at, expires_at`.

**Evaluation:** `manage.py evaluate_watches`, crontab `7,22,37,52 * * * *` (seven minutes after
`plaid_sync` and `canvas_poll`). No backfill: money rows must be stored after arming and dated
within three days of it; grades on file at arming are baseline (never said).

**Delivery:** the phone's existing Claude result loop (`ClaudeResultWatcher.run`) calls
`WatchFiringPoller.maybePoll` each 30 s tick; it asks the engine every 3 min, turns every pending
firing into ONE `ProactiveRaise` in the new `WATCHES` category, and acks each (spoken, notified or
silenced). "Not now" acks nothing. **Nothing polls while the app is closed and the assistant is
off.**

**Voice:** one `watch` tool (list, set, pause, resume, remove). A turn that read mail or the web
cannot set or resume one (round 3's flag). Hands path: Settings, Proactive speech, Watches.

## Tickets

- [01 Engine: registry, evaluator, endpoints](issues/01-engine.md)
- [02 Phone: delivery, voice tool, Watches screen](issues/02-phone.md)
- [03 Phone-evaluated mail_reply](issues/03-mail-reply.md)
- [04 Run it on the phone](issues/04-phone-run.md)
- [05 Web assistant and /mcp watch tools](issues/05-web-and-mcp.md)

## Not yet specified

- **Weather and river-gauge kinds.** They join at `CONDITIONS_SEAM` in `server/watches/registry.py`
  once `feat/conditions` merges and its stored data is known. Ticketed then, not now.
- **A one-shot from voice.** The engine stores `repeat`; neither the tool nor the screen sets it
  yet (payload). Every watch repeats until "stop watching X".

## Out of scope

- A free-text condition, or a model judging any condition per tick. Never (decisions 8 and 11).
- Any kind over data the engine does not verifiably hold.
- Storing anything read from mail, or sending it to the engine (CLAUDE.md section 7).
