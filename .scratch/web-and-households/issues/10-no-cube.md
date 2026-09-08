---
map: web-and-households
ticket: "10"
title: "No Cube: the semantic layer is Django, and the escalation trigger is named"
type: decision
status: resolved
status-detail: "Resolved 2026-09-08 at charting (Fable, for Kevin's ack in the map): no Cube. ~30k rows under a 500 MB ceiling; rules live once in Django (ADR 0044); Cube Store needs a volume Cloud Run min-0 cannot give it; tenancy would be enforced twice. Escalation: a report query over 500 ms on real data becomes a Postgres materialized view refreshed by the worker, never a second rules layer."
blockers: []
blocked-by: []
open-blockers: 0
ready: false
no-build-needed: true
tags: [ticket]
---

# No Cube

Kevin: *"also wondering if we really need cube."* No.

The doc argues Cube for hundreds of concurrent dashboard users over millions of rows, with metric
drift across departments and row-level security to enforce in one place. LEGION is a family of six
over ~30,000 rows (20,796 of them OBD samples), on a database whose ceiling is 500 MB, with every
rule already living once in Django by ruling. Every one of Cube's reasons to exist is absent, and
three of its costs are present:

1. **A second rules layer.** Metrics defined in Cube YAML beside serializers that already know
   which row is `UNRECONCILED` and which figure is an `estimate`. The §4 trust posture lives in
   Django; a generic "measure" would happily sum a verified total with an unverified one.
2. **A second stateful container.** Cube Store wants a persistent volume. Cloud Run at
   min-instances 0 does not have one, which is why the doc drifts toward an always-on VM the
   project already rejected.
3. **Tenancy twice.** The doc's own advice is to enforce multi-tenancy in Cube's security context.
   Ticket 02's choke point and 02b's RLS would then need a third copy.

What the doc is right about is the *shape*: a machine-readable model an AI can code against
without hallucinating joins. LEGION has that already - `server/openapi.yaml`, 85 paths, with a
staleness test - and ticket 04 turns it into the typed client. The read-side "semantic layer" is
ticket 11's report endpoints, described in the same contract.

**Escalation trigger, so this is checkable later:** when a ticket-11 report takes more than 500 ms
on the live database, it becomes a Postgres materialized view refreshed by the worker (a
`manage.py` task on the Job schedule). Not Cube, not a cache in Django, and never a second place
where a rule lives.
