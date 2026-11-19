---
map: engine-brain
ticket: "04"
title: "The switch: limb contract, relay, SSE turns, per-member flag"
type: build
status: open
size: L
blockers: ["02", "03"]
blocked-by: ["[[02-runtime-and-evals]]", "[[03-engine-homes-for-room-only-data]]"]
open-blockers: 2
ready: false
tags: [ticket]
---
# The switch: limb contract, relay, SSE turns, per-member flag

Stage 3. The engine answers turns. The phone keeps its own loop for mail turns until ticket 05.

## Build
- `/api/agents/turn` (SSE), `tool_result`, `cancel` and `warm`.
- A `Limb` table with a capability manifest; contract versions N and N-1 served (ADR 0098).
- The phone as the first limb:
  - an engine-brain client in the cascade;
  - a relay executor over the existing tool dispatch, with confirm gates and call-id dedupe;
  - a per-member flag in Settings plus a server default (ADR 0035 hands path).
- Every turn goes through the generalist and the relay.

## Verification (gates)
- [ ] Every suite runs against BOTH brains on the same cases, and the engine is at or above the phone on each.
- [ ] On-phone smoke: 10 fixed invented turns through the phone's test-turn path over adb. All answer, the relay works, and first sentence comes within today's median + 0.5 s.
- [ ] The flag flips back from Settings and from the server default.
- [ ] A locked phone: each screen-needing relay tool fails in words. This was not determined from code, so check it on the device.

## E1 debts for E2 (2026-11-19, from the blind review of E1 at 6f4ce58f)
E1 shipped the `Limb` table, `AgentTurn.pending_relay` and the manifest upload DARK. Nothing reads a manifest or writes `pending_relay` yet. Each item below must be closed in E2 (or the slice named) BEFORE the thing it guards is used.

- **(a) `pending_relay` has no blanking path.** `server/agents/legion/retention.py` blanks only `transcript`, `final_text` and `history` at 30 days, so a paused turn's relay payload would live until the 90-day row delete. E2 adds `pending_relay` to the 30-day blanking set (set it to NULL) and redacts it like a trace row, in the same commit that first writes it. It also clears it when the turn resumes, times out or is cancelled.
- **(b) A `Limb` row has no retention or removal rule.** What the foreign keys do today:
  - token revoked (`revoked_at` set; a token row is never deleted): the limb row stays;
  - member removed from a household (`household/households.py` revokes their tokens, tombstones private rows and deletes the membership): the limb row stays, owned by someone who is no longer a member. Invisible to the household (`visible()` keys on owner), never removed;
  - user deleted: Django's `on_delete=CASCADE` (in Python, at the ORM; the database key is plain and deferred) deletes the rows;
  - household deleted: `PROTECT` on `household`, but `demo/visit_cleanup.py` deletes every `TENANT_TABLES` row first, so a demo visit still goes whole.
  
  The rule E2 implements: a limb row goes when its token is revoked (in the same transaction as the revoke, including the member-removal path) and when it is unseen for 90 days (`last_seen_at`, pruned by `retention.py` with the turn tables). A member who asks to be forgotten is a user delete, so the cascade covers it; a test proves it.
- **(c) The manifest's `writes`, `read_through`, `actuates` and `confirm` are the device's own claim.** Before the taint guard or the write guard reads any of them (E2/E3), the engine checks each relayed tool against its own table of known limb tools (name -> flags), and the engine's flags win. Unknown tools are denied by default: not offered to the model, never relayed. ADR 0098 also says anything that actuates always needs a confirm, so `actuates: true` with no confirm is refused there.
- **(d) Limb tool names are not checked against engine tool names.** A limb declaring an engine tool's name (`add_event`) would give the model two tools with one name. E2 refuses a collision at upload (400, in words) or namespaces relayed tools; that is decided with (c)'s table.
- **(e) `parameters` is not validated as a JSON schema.** It is any object up to a depth limit. E2 hands it to Gemini as a function declaration, where a malformed one fails the whole model call. E2 validates the shape (`type: object`, `properties`, the subset Gemini accepts), or takes the declaration from (c)'s table instead of the device.
- **(f) The limb route has no throttle.** Only the turn has `AgentTurnThrottle`. Add a scope (one upload per conversation open, so a low rate) with the switch-on stage (E6) at the latest.
- **(g) `brain_choice` is stored per device; the plan's override is per member.** Per member is right: the plan's flag precedence names "the server's per-member override", and the flip back from the server must not depend on which device he picks up. E1 stores it on each `Limb` row, and the code comments now say so. E2/E5 moves it to one per-member place (or sets and reads it across all the member's rows), with a test that a new device inherits it. Nothing reads it for a turn until then.
