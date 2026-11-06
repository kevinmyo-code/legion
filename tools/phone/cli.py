"""`python tools/phone <command>`: drive LEGION on the real phone to verify a change.

Skill: `.claude/skills/verify-on-phone/SKILL.md`. Screen map: `docs/screen-map.md`. Rails:
`rails.py` (read it; they are the point).

Every selector command takes a FRESH dump first (`.claude/agents/device.md`: a dump left on the
phone from an earlier call reads exactly like the current screen). Taps go to the centre of the
matched node's nearest clickable ancestor, the way a finger would.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import sys
import time
from pathlib import Path

from . import rails, uitree
from .adb import (
    PACKAGE, Adb, AdbError, blank_fraction, filter_logcat, input_text_arg, parse_battery,
    parse_devices, parse_focus, parse_screen, parse_screencap_raw, parse_version,
)

SHELL_ACTIVITY = f"{PACKAGE}/.ui.MainActivity"
DUMP_PATH = "/sdcard/legion_phone_dump.xml"
MIN_VERSION_FOR_TAGS = None  # set once a build with testTagsAsResourceId ships; see the skill
TOOLS = Path(__file__).resolve().parents[1]


class CliError(RuntimeError):
    pass


# --------------------------------------------------------------------------------- phone state

class Phone:
    """What the commands need from the phone, over one [Adb]."""

    def __init__(self, adb: Adb):
        self.adb = adb

    def state(self) -> dict:
        """Focus, keyguard and wakefulness in ONE adb round trip."""
        out = self.adb.shell(
            "dumpsys activity activities | grep -E 'topResumedActivity|ResumedActivity'; "
            "echo ---; dumpsys window | grep -E 'isKeyguardShowing|mShowingLockscreen'; "
            "echo ---; dumpsys power | grep -E 'mWakefulness='"
        )
        parts = out.split("---")
        acts = parts[0] if parts else ""
        win = parts[1] if len(parts) > 1 else ""
        pw = parts[2] if len(parts) > 2 else ""
        st = parse_screen(win, pw)
        st["focus"] = parse_focus(acts)
        return st

    def require_usable(self) -> dict:
        st = self.state()
        if not st["awake"]:
            raise CliError("the screen is off. `python tools/phone wake` lights it; unlocking is Kevin's.")
        if st["locked"]:
            raise CliError("the phone is locked. Unlocking is Kevin's (a PIN or fingerprint is never "
                           "entered by an agent). Ask him, then run again.")
        return st

    def dump(self, retries: int = 3) -> list[uitree.Node]:
        last = ""
        for attempt in range(retries):
            out = self.adb.shell(f"rm -f {DUMP_PATH}; uiautomator dump {DUMP_PATH}", timeout=40)
            if "dumped to" in out:
                rc, xml = self.adb.run(["exec-out", "cat", DUMP_PATH], timeout=30)
                self.adb.shell(f"rm -f {DUMP_PATH}")
                try:
                    return uitree.parse(xml)
                except uitree.DumpError as e:
                    last = str(e)
            else:
                last = out.strip()
            time.sleep(0.8 * (attempt + 1))
        raise CliError(f"uiautomator dump failed {retries}x: {last[:200]}. An endless animation (Matrix "
                       "code rain) can keep the UI from ever going idle; screenshot instead.")

    def version(self):
        return parse_version(self.adb.shell(f"dumpsys package {PACKAGE}"))

    def device_time(self) -> str:
        return self.adb.shell("date +'%m-%d %H:%M:%S.000'").strip()

    def pids(self) -> set[str]:
        return set(self.adb.shell(f"pidof {PACKAGE}").split())


def log_action(what: str, detail: dict) -> None:
    """Every action, refused or not, in `.phone-artifacts/actions.log` (gitignored)."""
    try:
        p = rails.artifact_path("actions.log", "actions.log")
        with p.open("a", encoding="utf-8") as f:
            f.write(json.dumps({"at": dt.datetime.now().isoformat(timespec="seconds"), "what": what, **detail})
                    + "\n")
    except OSError:
        pass


def redact_nodes(nodes: list[uitree.Node], focus: str) -> None:
    if focus == rails.VAULT_ACTIVITY:
        for n in nodes:
            n.text = rails.redact_vault_text(n.text)
            n.desc = rails.redact_vault_text(n.desc)


# -------------------------------------------------------------------------------- selectors

def selector(args) -> dict:
    sel = {k: getattr(args, k) for k in ("text", "desc", "tag") if getattr(args, k, None) is not None}
    if not sel:
        raise CliError("give --text, --desc or --tag")
    sel["contains"] = bool(getattr(args, "contains", False))
    return sel


def resolve(nodes, sel: dict, index: int = 0) -> uitree.Node:
    hits = uitree.find(nodes, **sel)
    if not hits:
        raise CliError(f"no node matches {describe(sel)}")
    if index >= len(hits):
        raise CliError(f"{describe(sel)} matched {len(hits)} nodes; --index {index} is out of range")
    return hits[index]


def describe(sel: dict) -> str:
    return " ".join(f"{k}={v!r}" for k, v in sel.items() if k != "contains" and v is not None)


def tap_node(phone: Phone, nodes, node: uitree.Node, focus: str, confirmed: bool, why: str) -> dict:
    target = tap_target_tight(nodes, node)
    words = uitree.subtree_words(nodes, target)
    try:
        rails.check_tap(words, focus, confirmed)
    except rails.Refusal as r:
        log_action("tap", {"refused": str(r), "words": words[:6], "focus": focus})
        raise
    x, y = target.center
    phone.adb.shell(f"input tap {x} {y}")
    detail = {"x": x, "y": y, "words": words[:6], "focus": focus, "why": why, "confirmed": confirmed}
    log_action("tap", detail)
    return detail


def tap_target_tight(nodes, node: uitree.Node) -> uitree.Node:
    """The clickable ancestor, unless it covers a quarter of the screen or more (a whole sheet or a
    scrim): then the node's own centre is where a finger would go. A full-width row is a target."""
    t = uitree.tap_target(nodes, node)
    screen = max((n.area for n in nodes), default=0)
    if t is not node and screen and t.area >= 0.25 * screen:
        return node
    return t


# ------------------------------------------------------------------------------------ commands

def cmd_devices(phone: Phone, args) -> int:
    rc, out = phone.adb.run(["devices", "-l"], device=False)
    devs = parse_devices(out)
    if not devs:
        print("no devices. On the phone: Developer options > Wireless debugging on (same Wi-Fi), then "
              "`python tools/phone connect`.")
        return 1
    for d in devs:
        print(f"{d.serial}\t{d.state}\t{d.model}")
    return 0


def cmd_connect(phone: Phone, args) -> int:
    target = args.hostport
    if not target:
        rc, out = phone.adb.run(["mdns", "services"], device=False)
        for line in out.splitlines():
            if "_adb-tls-connect" in line:
                target = line.split()[-1]
                break
    if not target:
        print("no wireless-debug service found on the network. Pairing a new laptop needs the code on "
              "the phone (`adb pair host:port code`): that is Kevin's.")
        return 1
    rc, out = phone.adb.run(["connect", target], device=False)
    print(out.strip())
    return 0 if "connected" in out else 1


def cmd_version(phone: Phone, args) -> int:
    v = phone.version()
    if v is None:
        print(f"{PACKAGE} is not installed")
        return 1
    print(json.dumps({"versionCode": v.version_code, "versionName": v.version_name, "build": v.build,
                      "lastUpdateTime": v.last_update}))
    if args.min is not None and (v.version_code or 0) < args.min:
        print(f"OLDER than {args.min}: report this, do not install over it without being asked.")
        return 2
    return 0


def cmd_preflight(phone: Phone, args) -> int:
    rc, out = phone.adb.run(["devices", "-l"], device=False)
    devs = [d for d in parse_devices(out) if d.state == "device"]
    report: dict = {"devices": [f"{d.serial} {d.model}" for d in devs]}
    if not devs:
        report["ready"] = False
        report["why"] = "no phone attached (wireless debugging off, or the laptop is on another network)"
        print(json.dumps(report, indent=1))
        return 1
    if len(devs) > 1 and not phone.adb.serial:
        report["ready"] = False
        report["why"] = "more than one device: pass --serial (pick by model from `devices`)"
        print(json.dumps(report, indent=1))
        return 1
    v = phone.version()
    st = phone.state()
    bat = parse_battery(phone.adb.shell("dumpsys battery"))
    report.update({
        "version": v and {"code": v.version_code, "name": v.version_name, "build": v.build},
        "battery": bat, "awake": st["awake"], "locked": st["locked"], "focus": st["focus"],
    })
    why = []
    if v is None:
        why.append("LEGION is not installed")
    if not st["awake"]:
        why.append("screen off (`wake` lights it)")
    if st["locked"]:
        why.append("locked: Kevin unlocks")
    if st["focus"] and not st["focus"].startswith(PACKAGE + "/"):
        why.append(f"{st['focus'].split('/')[0]} is in front: Kevin may be using the phone; ask first")
    if bat["level"] is not None and bat["level"] < 15 and not bat["charging"]:
        why.append(f"battery {bat['level']}% and not charging")
    report["ready"] = not why
    if why:
        report["why"] = "; ".join(why)
    print(json.dumps(report, indent=1))
    return 0 if not why else 1


def cmd_wake(phone: Phone, args) -> int:
    phone.adb.shell("input keyevent KEYCODE_WAKEUP")
    log_action("wake", {})
    print("woke the screen (unlocking is Kevin's)")
    return 0


def cmd_screenshot(phone: Phone, args) -> int:
    out = rails.artifact_path(args.out, f"shot-{stamp()}.png")
    st = phone.state()
    rc, png = phone.adb.run(["exec-out", "screencap", "-p"], binary=True, timeout=60)
    if rc != 0 or not png.startswith(b"\x89PNG"):
        raise CliError("screencap returned no PNG")
    out.write_bytes(png)
    result = {"png": str(out), "focus": st["focus"], "locked": st["locked"]}
    if args.secure_check:
        rc, raw = phone.adb.run(["exec-out", "screencap"], binary=True, timeout=90)
        w, h, rgba = parse_screencap_raw(raw)
        frac = blank_fraction(w, h, rgba)
        result["near_black_fraction"] = round(frac, 4)
        result["blank"] = frac >= 0.9
    log_action("screenshot", result)
    print(json.dumps(result))
    return 0


def cmd_dump(phone: Phone, args) -> int:
    st = phone.state()
    nodes = phone.dump()
    redact_nodes(nodes, st["focus"])
    rows = uitree.compact(nodes, all_nodes=args.all)
    payload = {"focus": st["focus"], "nodes": rows}
    if args.out:
        p = rails.artifact_path(args.out, f"dump-{stamp()}.json")
        p.write_text(json.dumps(payload, indent=1, ensure_ascii=False), encoding="utf-8")
        print(json.dumps({"written": str(p), "nodes": len(rows), "focus": st["focus"]}))
    else:
        print(f"focus {st['focus']}")
        for r in rows:
            bits = [f"#{r['i']}"]
            for k in ("text", "desc", "tag"):
                if k in r:
                    bits.append(f"{k}={r[k]!r}")
            bits.append("@" + ",".join(map(str, r["bounds"])))
            if "flags" in r:
                bits.append(r["flags"])
            print(" ".join(bits))
    return 0


def cmd_tap(phone: Phone, args) -> int:
    st = phone.require_usable()
    nodes = phone.dump()
    if args.xy:
        x, y = args.xy
        node = uitree.node_at(nodes, x, y)
        if node is None:
            raise CliError(f"nothing on screen at {x},{y}")
        words = uitree.subtree_words(nodes, tap_target_tight(nodes, node))
        try:
            rails.check_tap(words, st["focus"], args.confirmed_by_kevin)
        except rails.Refusal as r:
            log_action("tap", {"refused": str(r), "xy": [x, y], "focus": st["focus"]})
            raise
        phone.adb.shell(f"input tap {x} {y}")
        log_action("tap", {"x": x, "y": y, "words": words[:6], "focus": st["focus"],
                           "confirmed": args.confirmed_by_kevin})
        print(json.dumps({"tapped": [x, y], "words": words[:6]}))
        return 0
    sel = selector(args)
    node = resolve(nodes, sel, args.index)
    detail = tap_node(phone, nodes, node, st["focus"], args.confirmed_by_kevin, describe(sel))
    print(json.dumps({"tapped": [detail["x"], detail["y"]], "words": detail["words"]}))
    return 0


def cmd_type(phone: Phone, args) -> int:
    st = phone.require_usable()
    rails.check_type(st["focus"], args.confirmed_by_kevin)
    phone.adb.shell(f"input text {input_text_arg(args.value)}")
    log_action("type", {"chars": len(args.value), "focus": st["focus"]})
    print(json.dumps({"typed_chars": len(args.value)}))
    return 0


def cmd_key(phone: Phone, name: str) -> int:
    phone.adb.shell(f"input keyevent KEYCODE_{name}")
    log_action("key", {"key": name})
    print(json.dumps({"key": name}))
    return 0


def swipe_coords(direction: str, w: int = 1080, h: int = 2340) -> tuple[int, int, int, int]:
    """A finger moving [direction]: `up` scrolls the content down the page (reveals what is below)."""
    cx, cy = w // 2, h // 2
    dy, dx = int(h * 0.3), int(w * 0.35)
    return {
        "up": (cx, cy + dy, cx, cy - dy),
        "down": (cx, cy - dy, cx, cy + dy),
        "left": (cx + dx, cy, cx - dx, cy),
        "right": (cx - dx, cy, cx + dx, cy),
    }[direction]


def screen_size(phone: Phone) -> tuple[int, int]:
    out = phone.adb.shell("wm size")
    import re
    m = re.findall(r"(\d+)x(\d+)", out)
    return (int(m[-1][0]), int(m[-1][1])) if m else (1080, 2340)


def cmd_swipe(phone: Phone, args) -> int:
    phone.require_usable()
    if args.xy:
        x1, y1, x2, y2 = args.xy
    else:
        x1, y1, x2, y2 = swipe_coords(args.direction, *screen_size(phone))
    phone.adb.shell(f"input swipe {x1} {y1} {x2} {y2} 350")
    log_action("swipe", {"from": [x1, y1], "to": [x2, y2]})
    print(json.dumps({"swiped": [x1, y1, x2, y2]}))
    return 0


def cmd_scroll_to(phone: Phone, args) -> int:
    phone.require_usable()
    sel = selector(args)
    w, h = screen_size(phone)
    for i in range(args.max + 1):
        hits = uitree.find(phone.dump(), **sel)
        if hits:
            print(json.dumps({"found": describe(sel), "after_swipes": i, "bounds": list(hits[0].bounds)}))
            return 0
        if i < args.max:
            x1, y1, x2, y2 = swipe_coords(args.direction, w, h)
            phone.adb.shell(f"input swipe {x1} {y1} {x2} {y2} 350")
    print(json.dumps({"found": None, "selector": describe(sel), "swipes": args.max}))
    return 1


def wait_for(phone: Phone, sel: dict, timeout: float, gone: bool = False):
    """(first match or None, the dump it came from), polling fresh dumps until [sel] shows (or,
    with [gone], until it does not)."""
    deadline = time.monotonic() + timeout
    while True:
        nodes = phone.dump()
        hits = uitree.find(nodes, **sel)
        if (hits and not gone) or (gone and not hits):
            return (hits[0] if hits else None), nodes
        if time.monotonic() >= deadline:
            raise CliError(f"timed out after {timeout:.0f}s waiting for {describe(sel)}"
                           + (" to go" if gone else ""))
        time.sleep(0.5)


def cmd_wait_for(phone: Phone, args) -> int:
    sel = selector(args)
    n, _ = wait_for(phone, sel, args.timeout, args.gone)
    print(json.dumps({"ok": True, "selector": describe(sel), "bounds": list(n.bounds) if n else None}))
    return 0


def load_map():
    sys.path.insert(0, str(TOOLS))
    import screen_map
    screens, problems = screen_map.build()
    return screen_map, screens, problems


def cmd_screens(phone: Phone, args) -> int:
    sm, screens, problems = load_map()
    for s in sorted(screens.values(), key=lambda s: s.id):
        print(f"{s.id:34} {s.kind:9} {s.name}")
    if problems:
        print(f"\n{len(problems)} drift problems: run python tools/screen_map.py --check", file=sys.stderr)
    return 0


def step_selector(step) -> dict:
    kind, value = step
    return {kind: value, "contains": False}


def cmd_open(phone: Phone, args) -> int:
    sm, screens, problems = load_map()
    s = screens.get(args.screen)
    if s is None:
        close = [k for k in screens if args.screen in k]
        raise CliError(f"no screen '{args.screen}' in the map" + (f"; did you mean {close[:5]}" if close else "")
                       + ". `python tools/phone screens` lists them.")
    phone.require_usable()
    chain = sm.path_from_launch(screens, s.id)
    # Without --by-taps: deep link to the nearest screen on the way that has one (the screen itself
    # for a route), then tap only what is left - Data and privacy by link, then "ID vault" by tap.
    # With --by-taps: every tap from HOME, which is what checks a hands path.
    start_at = 0
    if not args.by_taps:
        for i in range(len(chain) - 1, -1, -1):
            if screens[chain[i][0]].deeplink:
                start_at = i
                break
    first = screens[chain[start_at][0]]
    rest = chain[start_at + 1:] if first.deeplink and not args.by_taps else chain
    steps = []
    for cid, reach in rest:
        if screens[cid].parent is not None and not reach:
            raise CliError(f"'{s.id}' has no fixed tap path into '{cid}' ({screens[cid].needs or 'see docs/screen-map.md'})"
                           + ("; drop --by-taps to use the deep link" if s.deeplink else ""))
        steps += reach
    root = screens[chain[0][0]]
    if first.deeplink and not args.by_taps:
        deep_link(phone, first.deeplink)
        how = f"deep link route={first.deeplink}" + (f", then {len(steps)} tap(s)" if steps else "")
    else:
        deep_link(phone, "home")
        how = f"{len(steps)} tap(s) from HOME"
    if steps:
        time.sleep(1.0)
        nodes = phone.dump()
        matrix_on = bool(uitree.find(nodes, text="All apps")) and bool(uitree.find(nodes, text="LEGION"))
        if root.kind == "matrix" and not matrix_on:
            raise CliError(f"'{s.id}' needs Matrix mode, and Matrix HOME is not up. The CLI never toggles "
                           "Matrix mode (it changes Do Not Disturb); Kevin turns it on.")
        if root.kind != "matrix" and matrix_on and (args.by_taps or not first.deeplink):
            raise CliError(f"Matrix mode is on, so the Soft HOME's taps to '{s.id}' are not on screen. "
                           + ("Drop --by-taps to use the deep link." if s.deeplink else "Kevin turns Matrix mode off."))
    done = []
    for step in steps:
        if step[0] == "key":
            phone.adb.shell(f"input keyevent KEYCODE_{step[1]}")
        else:
            sel = step_selector(step)
            node, nodes = wait_for(phone, sel, args.timeout)
            focus = phone.state()["focus"]
            tap_node(phone, nodes, node, focus, False, f"open {s.id}: {describe(sel)}")
        done.append(sm.fmt_step(step))
        time.sleep(0.7)
    result = {"opened": s.id, "how": how}
    if done:
        result["steps"] = done
    if s.activity:
        # The marker text can also be on the screen that opens it (Data and privacy has an "ID
        # vault" row), so an activity is proven by what is in front, not by its words.
        want = f"{PACKAGE}/{s.activity}"
        deadline = time.monotonic() + args.timeout
        while phone.state()["focus"] != want:
            if time.monotonic() >= deadline:
                result["activity"] = f"{s.activity} NOT in front within {args.timeout:.0f}s"
                print(json.dumps(result))
                return 1
            time.sleep(0.5)
        result["activity"] = f"{s.activity} is in front"
    if s.marker:
        try:
            wait_for(phone, {"text": s.marker, "contains": False}, args.timeout)
            result["marker"] = f"text {s.marker!r} is on screen"
        except CliError:
            result["marker"] = f"text {s.marker!r} NOT seen within {args.timeout:.0f}s - screenshot and look"
            print(json.dumps(result))
            return 1
    if not s.marker and not s.activity:
        result["marker"] = "none in the map: arrival NOT proven - dump or screenshot and look"
    result["focus"] = phone.state()["focus"]
    log_action("open", result)
    print(json.dumps(result))
    return 0


def deep_link(phone: Phone, route: str) -> None:
    """`EXTRA_ROUTE` on the exported, singleTask MainActivity: what a notification does. Only routes
    from the map are ever sent; Navigation throws on a route the graph does not have."""
    out = phone.adb.shell(f"am start -n {SHELL_ACTIVITY} --es route '{route}'")
    if "Error" in out or "Exception" in out:
        raise CliError(f"am start failed: {out.strip()[:200]}")


def cmd_logcat(phone: Phone, args) -> int:
    mark_file = rails.artifact_path("logcat.mark", "logcat.mark")
    if args.mark:
        t = phone.device_time()
        mark_file.write_text(t, encoding="utf-8")
        print(json.dumps({"mark": t, "clock": "the phone's"}))
        return 0
    if args.minutes is not None:
        since = phone.adb.shell(f"date -d @$(( $(date +%s) - {int(args.minutes * 60)} )) +'%m-%d %H:%M:%S.000'").strip()
    elif mark_file.exists():
        since = mark_file.read_text(encoding="utf-8").strip()
    else:
        raise CliError("no mark: run `logcat --mark` before acting, or pass --minutes N")
    rc, text = phone.adb.run(["logcat", "-d", "-v", "threadtime", "-T", since], timeout=60)
    pids = phone.pids()
    lines = text.splitlines() if args.all else filter_logcat(text, pids, grep=args.grep)
    if args.out:
        p = rails.artifact_path(args.out, f"logcat-{stamp()}.txt")
        p.write_text("\n".join(lines) + "\n", encoding="utf-8")
        print(json.dumps({"written": str(p), "lines": len(lines), "since": since}))
    else:
        tail = lines[-args.tail:] if args.tail else lines
        print(f"# since {since} (phone clock), LEGION pids {sorted(pids)}, {len(lines)} lines"
              + (f", last {len(tail)}" if len(tail) < len(lines) else ""))
        for ln in tail:
            print(ln)
    return 0


def cmd_say(phone: Phone, args) -> int:
    """A typed turn to the assistant. It can call real tools: the skill says read-only questions."""
    st = phone.require_usable()
    rails.check_say(st["focus"])
    print("NOTE: a typed turn runs the assistant with its tools. Ask read-only questions unless the "
          "task needs a write, and say so in the report.", file=sys.stderr)
    v = phone.version()
    if v and v.debuggable and not args.typed:
        text = args.value.replace("'", "'\\''")
        phone.adb.shell(f"am broadcast -a com.kevin.legion.DEBUG_SAY --es text '{text}'")
        log_action("say", {"how": "DEBUG_SAY", "chars": len(args.value)})
        print(json.dumps({"said": True, "how": "DEBUG_SAY broadcast (debug build); the reply is spoken and "
                          "logged under LIVE_AUDIT - read `logcat`"}))
        return 0
    # `fast` (or --typed): the typed chat panel, the same path a thumb takes.
    nodes = phone.dump()
    field_sel = {"desc": "Type to ^", "contains": False}
    if not uitree.find(nodes, **field_sel):
        opener = next((s for s in ({"desc": "Chat with ^", "contains": False},
                                   {"text": "Assistant", "contains": False}) if uitree.find(nodes, **s)), None)
        if opener is None:
            raise CliError("no chat button (`Chat with ...`) or Matrix `Assistant` entry on screen; "
                           "`open home` first")
        tap_node(phone, nodes, resolve(nodes, opener), st["focus"], False, "say: open chat")
        wait_for(phone, field_sel, args.timeout)
        nodes = phone.dump()
    field = resolve(nodes, field_sel)
    x, y = field.center
    phone.adb.shell(f"input tap {x} {y}")
    time.sleep(0.4)
    phone.adb.shell(f"input text {input_text_arg(args.value)}")
    time.sleep(0.4)
    nodes = phone.dump()
    send = resolve(nodes, {"desc": "Send", "contains": False})
    sx, sy = tap_target_tight(nodes, send).center
    # The one place this CLI taps a control named Send: `say` IS the request to send this turn.
    phone.adb.shell(f"input tap {sx} {sy}")
    log_action("say", {"how": "typed panel", "chars": len(args.value), "focus": st["focus"]})
    print(json.dumps({"said": True, "how": "typed chat panel; the reply appears in the panel - dump or "
                      "screenshot to read it, `logcat` for the tool calls"}))
    return 0


def stamp() -> str:
    return dt.datetime.now().strftime("%Y%m%d-%H%M%S")


# --------------------------------------------------------------------------------------- main

def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="python tools/phone", description=__doc__.split("\n\n")[0])
    p.add_argument("--serial", help="adb serial (default $ANDROID_SERIAL, or the only device)")
    sub = p.add_subparsers(dest="cmd", required=True)

    def sel_args(sp, with_tap=False):
        sp.add_argument("--text")
        sp.add_argument("--desc")
        sp.add_argument("--tag")
        sp.add_argument("--contains", action="store_true", help="substring match for text/desc")
        if with_tap:
            sp.add_argument("--index", type=int, default=0, help="which match, when several")
            sp.add_argument("--xy", type=int, nargs=2, metavar=("X", "Y"))
            sp.add_argument("--confirmed-by-kevin", action="store_true",
                            help="lifts the send/pay/buy/delete/... refusal. ONLY with Kevin's yes in chat")

    sub.add_parser("devices", help="attached devices with model")
    c = sub.add_parser("connect", help="wireless debug: connect to the phone (mDNS if no host:port)")
    c.add_argument("hostport", nargs="?")
    sub.add_parser("preflight", aliases=["status"], help="device, build, battery, screen, lock, focus")
    v = sub.add_parser("version", help="installed versionName/versionCode and build")
    v.add_argument("--min", type=int, help="exit 2 if the installed versionCode is older")
    sub.add_parser("wake", help="light the screen (never unlocks)")
    s = sub.add_parser("screenshot", help="PNG under .phone-artifacts/ (or an outside path)")
    s.add_argument("out", nargs="?")
    s.add_argument("--secure-check", action="store_true", help="also report whether the frame is blank (FLAG_SECURE)")
    d = sub.add_parser("dump", help="uiautomator dump, compact: text, desc, tag, bounds")
    d.add_argument("--out")
    d.add_argument("--all", action="store_true", help="include layout-only nodes")
    t = sub.add_parser("tap", help="tap by --text/--desc/--tag/--xy, with the rails")
    sel_args(t, with_tap=True)
    ty = sub.add_parser("type", help="type into the focused field")
    ty.add_argument("value")
    ty.add_argument("--confirmed-by-kevin", action="store_true")
    sub.add_parser("back")
    sub.add_parser("home")
    sw = sub.add_parser("swipe", help="up|down|left|right (finger direction), or --xy x1 y1 x2 y2")
    sw.add_argument("direction", nargs="?", choices=["up", "down", "left", "right"], default="up")
    sw.add_argument("--xy", type=int, nargs=4)
    sc = sub.add_parser("scroll-to", help="swipe until a selector is on screen")
    sel_args(sc)
    sc.add_argument("--direction", choices=["up", "down"], default="up")
    sc.add_argument("--max", type=int, default=8)
    w = sub.add_parser("wait-for", help="poll fresh dumps until a selector shows (or --gone)")
    sel_args(w)
    w.add_argument("--timeout", type=float, default=10)
    w.add_argument("--gone", action="store_true")
    sub.add_parser("screens", help="every screen id in the map")
    o = sub.add_parser("open", help="go to a screen from docs/screen-map.md")
    o.add_argument("screen")
    o.add_argument("--by-taps", action="store_true", help="walk the tap path even when a deep link exists")
    o.add_argument("--timeout", type=float, default=10)
    lg = sub.add_parser("logcat", help="LEGION's lines since a mark (the phone's clock)")
    lg.add_argument("--mark", action="store_true", help="set the mark now")
    lg.add_argument("--minutes", type=float, help="instead of the mark: the last N minutes")
    lg.add_argument("--grep")
    lg.add_argument("--all", action="store_true", help="every line, not just LEGION's")
    lg.add_argument("--tail", type=int, default=200)
    lg.add_argument("--out")
    sy = sub.add_parser("say", help="a typed turn to the assistant (read-only questions)")
    sy.add_argument("value")
    sy.add_argument("--typed", action="store_true", help="use the chat panel even on a debug build")
    sy.add_argument("--timeout", type=float, default=10)
    return p


def main(argv=None, adb: Adb | None = None) -> int:
    args = build_parser().parse_args(argv)
    phone = Phone(adb or Adb(serial=args.serial))
    handlers = {
        "devices": cmd_devices, "connect": cmd_connect, "version": cmd_version,
        "preflight": cmd_preflight, "status": cmd_preflight, "wake": cmd_wake,
        "screenshot": cmd_screenshot, "dump": cmd_dump, "tap": cmd_tap, "type": cmd_type,
        "back": lambda ph, a: cmd_key(ph, "BACK"), "home": lambda ph, a: cmd_key(ph, "HOME"),
        "swipe": cmd_swipe, "scroll-to": cmd_scroll_to, "wait-for": cmd_wait_for,
        "screens": cmd_screens, "open": cmd_open, "logcat": cmd_logcat, "say": cmd_say,
    }
    try:
        return handlers[args.cmd](phone, args)
    except rails.Refusal as r:
        print(str(r), file=sys.stderr)
        return 3
    except (CliError, AdbError, uitree.DumpError) as e:
        print(f"phone: {e}", file=sys.stderr)
        return 1
