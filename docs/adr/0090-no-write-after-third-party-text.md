---
status: accepted
decided: 2026-11-13
decided-by: Kevin
supersedes: []
source: "[[decisions#2026-11-13 - Release and deploy from GitHub, no laptop; injection guard (Kevin)]]"
tags: [adr]
---

# 90. No write tool runs in a turn that read third-party text

## Standing

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
