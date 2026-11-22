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


def say(ctx: Ctx, words: str, writes: bool = False, timeout: float = 60) -> str:
    """One assistant turn as his words; returns the turn's mark (phone clock) for `calls_since`."""
    mark = ctx.device.mark()
    why = ctx.device.turn(words, case_id=f"verify-{ctx.result.id}", writes=writes, timeout=timeout)
    ctx.step(f"say ({'write' if writes else 'read'})", why in ("answered", "quiet", "closed"), why)
    if why == "real_conversation":
        raise Blocked("a real conversation took over")
    calls = calls_since(ctx, mark)
    bad = sorted({c.name for c in calls} & logs.FORBIDDEN_TOOLS)
    if bad:
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


def poll_log(ctx: Ctx, since: str, pattern, timeout: float, every: float = 5) -> bool:
    for i in range(int(timeout // every) + 1):
        if any(pattern.search(ln.msg) for ln in ctx.device.logcat(since)):
            return True
        if i * every < timeout:
            ctx.device.sleep(every)
    return False
