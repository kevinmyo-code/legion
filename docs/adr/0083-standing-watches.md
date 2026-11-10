---
status: accepted
decided: 2026-11-10
decided-by: Kevin
supersedes: []
source: "[[decisions#2026-11-10 - Standing watches and engine signals (Kevin)]]"
tags: [adr]
---

# 83. Standing watches: typed rules on the engine, said once on the phone

## Standing

**Kevin can ask to be told when something happens, and the engine's own data reaches the phone
unprompted** (Kevin, 2026-11-10, "1 and 2 yes"; map `.scratch/watches/`). Both are one mechanism:
a **watch**, private to the member who set it, and a **firing**, one fact said once.

**Rules decide; the model only words the line** (proactive-mode decisions 8 and 11). A watch is a
kind from a CLOSED registry (`server/watches/registry.py`) with typed params, evaluated
deterministically. No free-text condition, no model judging a condition per tick. The model's only
jobs are turning a spoken request into a kind and params, and wording the line when one fires. A
request that fits no kind is refused in words that name the kinds there are and the nearest one.

**Kinds exist only over data the engine verifiably holds**, and are evaluated there by
`manage.py evaluate_watches` every 15 minutes. **One kind is evaluated on the phone instead:**
`mail_reply` ("a company I applied to emails me back"), because mail never reaches the engine
(CLAUDE.md section 7). The engine stores only his list of companies; the phone matches Gmail headers
in code and says only a company name from that list.

**Engine signals are built-in watches**, seeded once per member and editable like any other; one
removed is never seeded back.

**Private to the member** (ADR 0052): `owner_user` is never null, `visible()` reads, owner guard,
`TENANT_TABLES`. A recruiter, a demo visit and any Claude worker token are refused (ADR 0068, 0067).

**Creating or resuming a watch is refused in a turn that read mail or the web** (round 3's
per-turn flag, the same one `remember` uses): a watch is a durable write that later causes
unprompted speech, and third-party content never becomes one.

**Delivery rides the phone's existing engine poll** (the Claude result loop): no alarm, no
WorkManager, no second loop. Several firings are one line. The phone acks each firing, so it is said
once. Nothing polls while the app is closed and the assistant is off; firings wait on the engine.

**Its own proactive category, `WATCHES`, on by default** (the `CLAUDE_TASKS` precedent), inside the
master switch and quiet hours. "Stop watching X" removes one; the switch silences all.

## Why

- A typed registry is what makes a watch checkable: the reconciliation gate's posture (section 4)
  applied to speech. A free-text rule judged by a model is shape (b), which proactive-mode retired.
- The engine already holds the bank feed, Canvas and the maintenance schedule (ADR 0044); evaluating
  there means one evaluator for every client and nothing new on the phone's database.
- Mail is the exception that proves the rule: it may be read to answer and then dropped, so its
  check lives where the read happens and leaves nothing behind but a timestamp.

## Consequences

- Latency is the data's cadence plus the evaluator's plus the phone's: a bank charge is said up to
  about 3 h (Plaid) + 15 min + 3 min after it happens; Canvas up to 30 + 15 + 3 minutes.
- Web assistant and `/mcp` watch tools are a follow-up (map ticket 05): `/mcp` has no turn, and the
  Claude worker on it may read mail.
- Weather and river-gauge kinds wait on the conditions work (map ticket 06).
- No Room change.
