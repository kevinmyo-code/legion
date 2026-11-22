"""Steps checks share: reach LEGION's home, say a line, read what the assistant called since a mark.

Each step records its name (never screen text or what was said back) in the check's result."""
from __future__ import annotations

from . import logs
from .runner import Abort, Blocked, Ctx, Fail

HOME_MARKER = "More"  # docs/screen-map.md: Home's "on screen when open" text


def go_home(ctx: Ctx, tries: int = 4) -> None:
    """LEGION's Typeset home in front: launch, then Back until its marker shows."""
    d = ctx.device
    d.launch()
    for _ in range(tries):
        els = d.screen()
        if any(e.label == HOME_MARKER for e in els):
            ctx.step("home")
            return
        if d.foreground() != "com.kevin.legion":
            d.launch()
        else:
            d.key("back")
        d.sleep(0.5)
    raise Blocked("could not reach LEGION's home (Matrix mode on, or another screen held)")


def say(ctx: Ctx, words: str, tools: tuple[str, ...] = (), timeout: float = 60) -> str:
    """One assistant turn as his words; returns the turn's mark (phone clock) for `calls_since`.

    [tools]: the only tools the turn may write with (the door refuses the rest before they run, on a
    build that has `allow_tools`). After a forbidden-tool stop no further turn is sent, cleanup
    included: the run says LEFT BEHIND instead."""
    if ctx.run.get("forbidden"):
        raise Blocked("not sent: the run stopped on a forbidden tool, no more assistant turns")
    mark = ctx.device.mark()
    why = ctx.device.turn(words, case_id=f"verify-{ctx.result.id}", tools=tools, timeout=timeout)
    ctx.step(f"say ({'write' if tools else 'read'})", why in ("answered", "quiet", "closed"), why)
    if why == "real_conversation":
        raise Blocked("a real conversation took over")
    calls = calls_since(ctx, mark)
    # Refused by the door before it ran (test_mode) is the guard working; anything else ran.
    refused = sorted({c.name for c in calls if c.name in logs.FORBIDDEN_TOOLS and '"test_mode":true' in
                      c.response.replace(" ", "")})
    if refused:
        ctx.note(f"forbidden_refused_{len(ctx.result.steps)}", refused)
    bad = sorted({c.name for c in calls if c.name in logs.FORBIDDEN_TOOLS} - set(refused))
    if bad:
        ctx.run["forbidden"] = bad
        raise Abort(f"FORBIDDEN tool called ({', '.join(bad)}): run stopped, Kevin must look")
    ctx.note(f"tools_{len(ctx.result.steps)}", [c.name for c in calls])
    if why == "timeout":
        raise Fail("the assistant did not finish the turn in time")
    return mark


def calls_since(ctx: Ctx, mark: str) -> list[logs.ToolCall]:
    return logs.tool_calls(ctx.device.logcat(mark))


def end(ctx: Ctx) -> None:
    ctx.device.end_conversation()


def find(els, text: str):
    want = text.lower()
    return [e for e in els if want in e.label.lower()]


def wait_for(ctx: Ctx, text: str, timeout: float) -> bool:
    ok = ctx.device.wait_text(text, timeout)
    ctx.step(f"wait for test item on screen ({'seen' if ok else 'not seen'})", ok)
    return ok


def poll_log(ctx: Ctx, since: str, pattern, timeout: float, every: float = 5, count: int = 1) -> bool:
    """Until [pattern] has matched [count] log lines since [since], or [timeout] seconds."""
    for i in range(int(timeout // every) + 1):
        if sum(1 for ln in ctx.device.logcat(since) if pattern.search(ln.msg)) >= count:
            return True
        if i * every < timeout:
            ctx.device.sleep(every)
    return False
