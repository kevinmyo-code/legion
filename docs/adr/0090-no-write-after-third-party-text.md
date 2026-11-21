---
status: accepted
decided: 2026-11-13
amended: 2026-11-21
decided-by: Kevin
supersedes: []
source: "[[decisions#2026-11-13 - Release and deploy from GitHub, no laptop; injection guard (Kevin)]]"
tags: [adr]
---

# 90. No write tool runs in a turn that read third-party text

## Standing

**AMENDED 2026-11-17 by [[0098-limbs-device-contract-and-relay]] and [[0099-engine-mail-read-draft-send]]:** on the engine, taint is carried in the turn's state; mail reads and relayed read-through tools taint the turn, and relayed writes are refused on the engine and checked again on the limb.

**A turn that read outside text may keep reading, but every write tool refuses in words, and the
change waits for the person's next message.** Outside text is what a third-party tool returns:
`web_search`, `brief_latest`'s web news, `github_status`'s issue and PR titles, and any tool later
added to that list. The refusal says what did not happen ("Nothing was deleted: delete_event did
not run ... they can simply ask again").

- **Engine:** `EngineTool.writes` is a required field, so every tool declares read or write; a
  tool registered without it fails at import, and a test fails if a default comes back.
  `run_engine_tool` asks `assistant.read_through.write_refusal` before any write handler, on the web
  assistant door, in procedures, and within one `/mcp` request. A mark that cannot be read refuses.
- **Phone:** `/mcp` carries no turn, so `AskEngine` refuses a write once an engine third-party tool
  answered within the same ask; a Live turn already marked read-through sends nothing at all.

## Amendment 2026-11-21: the carry, the turn after too (Kevin)

**A deliberate ruling by the owner on 2026-11-21, made after a live check showed the web assistant
writing an item in the turn right after a web search: the web door is to be as strict as the
engine brain after outside text, on every door.** He chose that scope, and kept it strict: no
early clearing. The same day he ruled how it meets a same-turn refusal (option A2, below).

- **The rule.** A turn that read outside text taints the person's NEXT turn too.
  - "Read outside text" means the turn's own read-through mark: a third-party tool, a relayed
    read-through call, or a late answer built on web pages.
  - Every write in that next turn refuses in words, including a change he names fully himself.
    It still reads and answers what it can.
  - The carry clears after that one turn. A turn tainted only by the carry does not carry again.
  - **Except after a same-turn refusal of a change HE asked for (A2):** a turn whose own read
    refused a write, when his own words in that turn asked for a change, does not carry. It told
    him to ask again, and that promise is true.
    - His words decide, never the model's text, a tool's output or the page: one shared check over
      the turn's transcript (`assistant/write_intent.py`).
    - It fails closed: no words, words it cannot read, or no request for a change all mean no
      exception, so the next turn is carried.
    - Code keeps a marker for it: on the web, a `ReadThroughTurn` row at
      `read_through.refused_mark(turn)`; on the engine, the `write_refused` taint source.
    - A model's claim of a change with no write call never sets it.
    - When his words did not ask, the refusal promises nothing (`WRITE_REFUSAL_UNASKED` for the
      model; the spoken line is "I haven't changed anything: this turn read text from the web."),
      and the next turn is carried.
    - A plain read with no refusal still carries.
- **The words.** The refusal and the spoken line say which turn read it:
  - the tool's words are `read_through.CARRY_WRITE_REFUSAL` / `CARRY_REFUSAL`;
  - the closing line is `lines.READ_THROUGH_WAIT_CARRIED` ("I haven't changed anything: I read
    text from the web just before this, so changes wait for your next message. Ask me again and
    I'll do it.").
  - The same-turn words are unchanged where he asked for the change, and true again under A2.
- **Web assistant (the cascade door), members only.** The server decides from its own record:
  `ReadThroughTurn` holds the previous turn's mark, read in the same single query as this turn's.
  - The recruiter demo (/try) keeps the same-turn rule: it is frozen. `read_through.CARRY_ON_DEMO`
    is the one switch, and applying the rule there is an open question for the owner.
  - The Live fallback door counts model turns, not his turns, so it keeps the same-turn rule.
- **Engine brain.** `run_turn` reads the previous turn's `taint_sources` and adds `previous_turn`
  to this turn's taint (`agents/legion/carry.py`).
- **A turn never answered does not spend the carry.** On the engine that is a cancelled turn that
  read nothing itself; on the web, a nothing-heard request, or a carried turn cut off before its
  answer (it marks itself).
- **The record.** A carried web turn writes `read_through source=carry` to the conversation's
  record, so memory consolidation keeps none of it. A carried engine turn ends with
  `done.tainted`, from which the phone writes its own line.
- **Phone cascade.** `ReadThroughCarry` today carries only after a read-through opener. It is to
  carry after any turn that read outside text, his own search included. That is a separate phone
  slice with its own app update.

## Amendment 2026-11-17: the phone guard, "Block lasting + outbound" (Kevin)

Kevin's ruling, by question dialog: **"Block lasting + outbound": after an outside read refuse only
things that last or leave the phone; still allow navigation, timers, music, volume, screens and
reading more of a web page.**

- **Phone:** one guard before every resolved tool call on every path (Live, the cascade on cloud
  or laptop voice, typed chat), `ReadThroughWriteGuard` in `LiveSessionController.runTool`. In a
  marked turn a call runs if it is a read (`ToolReads`) or on the allow-after-read list
  (navigation, clock, music, volume, books, screens, the camera look, answering a ringing call, and
  the browser's `read`, `scroll`, `back` and `close`). Everything else is refused in words:
  memories, watches, records and `aspect_clerk`, to-dos, logs, goals and proposals, mail drafts and
  sends, calls, the garage, Claude work, `ask_engine`, app installs, lasting settings, and a browser
  `open`, `click` or `type` (an address can carry text out in its query string). Deny by default:
  a test fails on any tool the model can reach that is not classified.
- Phone taint sources: mail (including the folded `look_up`/`day` calls), the brief, parcel and
  flight lookups, web pages and search grounding, `ask_engine`'s third-party tools, a camera look,
  and the turn after a read-through raise (wake brief, `mail_reply` line). Calendar invites from
  others, the agenda block and Claude task results are not sources; that waits for a ruling.
- **Known difference:** the engine's `guards.py` stays stricter for now and refuses every write
  after third-party text, the allowed ones included. Not changed on this ruling.

## Context

A security review of `server/` (2026-11-12) traced a path from a public GitHub issue title, through
`github_status`, to `delete_event` or `forget_memory` on the model's call. `read_through` marked
such turns, but only `remember` checked the mark. Whether a model would obey an injected instruction
cannot be settled from source, and the cost of being wrong is deleted or altered household data.
Kevin approved closing it ("yes to all4").

## Decision

Refuse writes, not reads, after third-party text, at the door the write passes through, with one
classification for every tool. Not chosen: a confirmation prompt (the voice path has no reliable
second channel), or stripping third-party text (it is the answer the person asked for).

## Consequences

- "Read me the open issues and close the stale ones" takes two turns. That is the intended cost.
- Not covered: a client that reads outside text through its own tools and then calls `/mcp` in a
  later request (Claude Code, the head unit). Closing that needs the caller to send its own
  read-through flag.
- A new third-party tool must join `ENGINE_THIRD_PARTY_TOOLS` (phone) and the engine's
  third-party list; a new write tool is guarded by declaring `writes=True`, which it must.
