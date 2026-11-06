#!/usr/bin/env python3
"""Generate `docs/screen-map.md`: every LEGION phone screen, how to reach it from launch by stable
selectors, its key controls, and which voice tools open it (phone-verify map, ticket 03).

**Why this exists.** An agent verifying phone work needs to know how to get to the screen before it
can check anything on it. Without a map it either guesses taps (and a wrong tap on a real phone is
not free) or reports "owed on device" and hands the checking back to Kevin. `tools/phone open
<screen>` reads the same model this script builds, so the doc and the driver cannot disagree.

**Generated from the code, with hand-written copy, and drift is a hard failure** - the same posture
as `tools/voice_guide.py`:

- Routes come from `ui/LegionRoute.kt` and the `composable(...)` registrations in
  `ui/MainActivity.kt`. Edges (which screen opens which) come from the `navController.navigate`
  callbacks wired into each registration, and the tap label for an edge is read from the parent
  screen's own file (`label = "Memory"` beside `onClick = onOpenMemory`).
- Screens that are not nav routes are checked against the code too: every `<activity>` in the
  manifest, every `MatrixDest`, and any `FleetDrilldown` the copy names.
- Human descriptions, overrides and voice tools live in `tools/screen_map_copy.py`.

**A registered route, activity or Matrix screen with no entry in the copy exits non-zero and names
it.** So does copy for something that no longer exists, a voice tool the toolboxes do not declare,
a selector text that no longer appears anywhere in `app/src/main`, and a screen with no way to reach
it.

**The repo is public**, so nothing in the copy or the output may name Kevin's data: no vault labels,
memories, amounts or people. Selectors are the app's own fixed strings.

    python tools/screen_map.py          # write docs/screen-map.md
    python tools/screen_map.py --check  # report drift only, write nothing
"""
from __future__ import annotations

import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "app/src/main/java/com/kevin/legion"
ROUTES_KT = SRC / "ui/LegionRoute.kt"
MAIN_KT = SRC / "ui/MainActivity.kt"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
MATRIX_DEST_KT = SRC / "ui/dumb/MatrixDest.kt"
FLEET_KT = SRC / "ui/FleetScreen.kt"
OUT = ROOT / "docs/screen-map.md"

sys.path.insert(0, str(Path(__file__).resolve().parent))


# ------------------------------------------------------------------------------- code readers

_TEXT: dict[Path, str] = {}


def read(p: Path) -> str:
    """Cached; `replace` because a few Kotlin files carry a stray Latin-1 byte in a comment."""
    if p not in _TEXT:
        _TEXT[p] = p.read_text(encoding="utf-8", errors="replace")
    return _TEXT[p]


def strip_comments(text: str) -> str:
    """Kotlin minus its comments, so a tombstone like `// composable(LegionRoute.NOTES) {...}
    DELETED` is not read as a live registration. `//` inside `https://` is kept (it follows `:`)."""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r'(?<![:\w"])//[^\n]*', "", text)


def route_constants(text: str | None = None) -> dict[str, str]:
    """CONST_NAME -> route string, from `const val X = "..."` in LegionRoute. `*_ARG` are argument
    names, not routes. A `$`-interpolated pattern is resolved against the args."""
    text = text if text is not None else read(ROUTES_KT)
    raw = dict(re.findall(r'const val (\w+) = "([^"]*)"', text))
    args = {k: v for k, v in raw.items() if k.endswith("_ARG")}
    out = {}
    for k, v in raw.items():
        if k.endswith("_ARG"):
            continue
        out[k] = re.sub(r"\$(\w+)", lambda m: args.get(m.group(1), m.group(0)), v)
    return out


@dataclass
class Registration:
    const: str
    screen_fn: str | None
    edges: list[tuple[str, str]]  # (callback name, child CONST)
    body: str


def registrations(text: str | None = None) -> list[Registration]:
    """Every `composable(LegionRoute.X)` in the NavHost, the screen function it renders, and the
    `onOpenFoo = { navController.navigate(LegionRoute.Y) }` edges wired into it."""
    text = strip_comments(text if text is not None else read(MAIN_KT))
    starts = []
    for m in re.finditer(r"composable\(\s*LegionRoute\.(\w+)", text):
        starts.append((m.start(), [m.group(1)]))
    for m in re.finditer(r"for \(\w+ in listOf\(([^)]*)\)\)\s*composable\(", text):
        consts = re.findall(r"LegionRoute\.(\w+)", m.group(1))
        starts.append((m.start(), consts))
    starts.sort()
    regs = []
    for i, (pos, consts) in enumerate(starts):
        end = starts[i + 1][0] if i + 1 < len(starts) else len(text)
        body = text[pos:end]
        # A `...Screen(` call wins over `...Root(`/`...Route(`/`...Home(`: HOME renders DumbHome
        # only in Matrix mode, and HomeScreen is the screen the route is named for.
        fn = None
        for suffix in ("Screen", "Root|Route|Home"):
            fm = re.search(rf"(?:\w+\.)*([A-Z]\w*(?:{suffix}))\(", body)
            if fm:
                fn = fm.group(1)
                break
        edges = re.findall(
            r"(on\w+)\s*=\s*\{\s*(?:\w+\s*->\s*)?navController\.navigate\(\s*LegionRoute\.(\w+)", body)
        for c in consts:
            regs.append(Registration(c, fn, edges, body))
    return regs


_FN_CACHE: dict[str, Path | None] = {}
_ALL_KT: list[Path] | None = None


def all_kotlin() -> list[Path]:
    global _ALL_KT
    if _ALL_KT is None:
        _ALL_KT = sorted(SRC.rglob("*.kt"))
    return _ALL_KT


def file_of(fn: str) -> Path | None:
    """The file declaring `fun fn(`; one pass over the tree builds the index."""
    if not _FN_CACHE:
        for p in all_kotlin():
            for name in re.findall(r"\bfun (?:<[^>]*>\s*)?([A-Z]\w*)\(", read(p)):
                _FN_CACHE.setdefault(name, p)
    return _FN_CACHE.get(fn)


LABEL_RE = re.compile(r'(?:label|title|text)\s*=\s*"([^"$]+)"|\bText\(\s*"([^"$]+)"')


def label_for(callback: str, screen_file: Path) -> str | None:
    """The fixed text on the control that fires [callback] in [screen_file]: the nearest
    `label = "..."`/`title = "..."`/`Text("...")` within six lines above `onClick = callback`."""
    lines = read(screen_file).splitlines()
    rx = re.compile(rf"onClick\s*=\s*(?:\{{\s*)?(?:\w+\.)?{re.escape(callback)}\b")
    for i, line in enumerate(lines):
        if rx.search(line):
            for j in range(i, max(-1, i - 7), -1):
                ms = LABEL_RE.findall(lines[j])
                if ms:
                    a, b = ms[-1]
                    return a or b
    return None


def header_title(screen_file: Path | None) -> str | None:
    """The screen's own header, `DeckScreenHeader(title = "...")`: what `open` waits for."""
    if screen_file is None:
        return None
    m = re.search(r'DeckScreenHeader\(\s*title\s*=\s*"([^"$]+)"', read(screen_file))
    return m.group(1) if m else None


TAG_LIT_RE = re.compile(r'testTag\(\s*"([^"]+)"\s*\)')
TAG_CONST_RE = re.compile(r'(?:const )?val (\w*TAG\w*) = "([^"]+)"')
TAG_REF_RE = re.compile(r"testTag\(\s*(\w+)\s*\)")


def test_tags(screen_file: Path | None) -> list[str]:
    """Test tags used in [screen_file]: literals, and constants declared in the same file.
    `${item.id}` templates print as `<id>`."""
    if screen_file is None:
        return []
    t = read(screen_file)
    consts = dict(TAG_CONST_RE.findall(t))
    tags = [re.sub(r"\$\{[^}]*\}|\$\w+", "<id>", s) for s in TAG_LIT_RE.findall(t)]
    tags += [consts[r] for r in TAG_REF_RE.findall(t) if r in consts]
    return sorted(set(tags))


def manifest_activities(text: str | None = None) -> list[str]:
    text = text if text is not None else read(MANIFEST)
    return re.findall(r'<activity\s+android:name="([^"]+)"', text)


def matrix_dests(text: str | None = None) -> dict[str, str]:
    text = text if text is not None else read(MATRIX_DEST_KT)
    body = text.split("enum class MatrixDest", 1)[1].split(";", 1)[0]
    return dict(re.findall(r'(\w+)\("([^"]+)"\)', body))


def fleet_drilldowns(text: str | None = None) -> list[str]:
    text = text if text is not None else read(FLEET_KT)
    m = re.search(r"enum class FleetDrilldown \{([^}]*)\}", text)
    return [s.strip() for s in m.group(1).split(",") if s.strip()] if m else []


def rel(p: Path) -> str:
    """Package-relative (`ui/vault/VaultScreen.kt`), the form `docs_check.py` resolves under
    `app/src/main/java/com/kevin/legion/`."""
    return p.relative_to(SRC).as_posix()


_LITERALS: set[str] | None = None


def string_literals() -> set[str]:
    """Every string literal in app/src/main Kotlin, for checking that a selector still exists."""
    global _LITERALS
    if _LITERALS is None:
        lits = set()
        for p in all_kotlin():
            lits.update(re.findall(r'"((?:[^"\\\n]|\\.)*)"', read(p)))
        _LITERALS = lits
    return _LITERALS


def selector_exists(value: str) -> bool:
    """A selector text exists if some literal equals it, or (for a `^` prefix selector) some literal
    starts with it - `"Chat with $companionName"` starts with `Chat with `."""
    lits = string_literals()
    if value.endswith("^"):
        pre = value[:-1]
        return any(l.startswith(pre) for l in lits)
    return value in lits


# ------------------------------------------------------------------------------------- model

@dataclass
class Screen:
    id: str                 # route string, or an extra's key
    name: str
    kind: str               # route | activity | matrix | drilldown
    what: str = ""
    const: str | None = None
    parent: str | None = None
    reach: list = field(default_factory=list)   # steps from parent: (kind, value)
    reach_auto: bool = False
    deeplink: str | None = None                 # route string `open` sends, if any
    marker: str | None = None                   # text that proves the screen is up
    tags: list[str] = field(default_factory=list)
    controls: list = field(default_factory=list)
    voice: list[str] = field(default_factory=list)
    needs: str = ""
    file: str | None = None
    activity: str | None = None                 # manifest name, for an activity screen


def build(copy=None) -> tuple[dict[str, Screen], list[str]]:
    """(screens by id, drift problems). Empty problems means the copy and the code agree."""
    if copy is None:
        import screen_map_copy as copy  # noqa: PLW0127
    problems: list[str] = []
    consts = route_constants()
    regs = registrations()
    by_const = {r.const: r for r in regs}
    registered = {r.const for r in regs}
    const_of = {consts[c]: c for c in registered if c in consts}

    # Parent edges: child CONST -> (parent CONST, label or None). First labelled edge wins.
    parent_of: dict[str, tuple[str, str | None]] = {}
    for r in regs:
        pfile = file_of(r.screen_fn) if r.screen_fn else None
        for cb, child in r.edges:
            label = label_for(cb, pfile) if pfile else None
            if child not in parent_of or (parent_of[child][1] is None and label):
                parent_of[child] = (r.const, label)

    screens: dict[str, Screen] = {}
    copy_routes = dict(copy.ROUTES)
    for c in sorted(registered, key=lambda c: consts.get(c, c)):
        route = consts.get(c)
        if route is None:
            problems.append(f"composable(LegionRoute.{c}) is registered but LegionRoute has no such const")
            continue
        entry = copy_routes.pop(route, None)
        if entry is None:
            problems.append(
                f'route "{route}" (LegionRoute.{c}) is registered in MainActivity but has no entry in '
                "tools/screen_map_copy.py ROUTES")
            continue
        reg = by_const[c]
        f = file_of(reg.screen_fn) if reg.screen_fn else None
        s = Screen(id=route, name=entry["name"], kind="route", what=entry.get("what", ""), const=c,
                   file=rel(f) if f else None)
        s.marker = entry.get("marker") or header_title(f)
        s.tags = test_tags(f)
        s.controls = list(entry.get("controls", []))
        s.voice = list(entry.get("voice", []))
        s.needs = entry.get("needs", "")
        s.deeplink = entry.get("deeplink", route if "{" not in route else None)
        if "reach" in entry:
            s.parent = entry.get("parent")
            s.reach = list(entry["reach"])
        elif c in parent_of and parent_of[c][1]:
            pc, label = parent_of[c]
            s.parent = consts[pc]
            s.reach = [("text", label)]
            s.reach_auto = True
        elif route == copy.START:
            s.reach = []
        else:
            problems.append(
                f'route "{route}" has no way to reach it: no labelled navigate edge was found in code, '
                "so give it `parent` and `reach` in tools/screen_map_copy.py (or `reach: []` with "
                "`deeplink_only` and a reason)")
            continue
        if "parent" in entry:
            s.parent = entry["parent"]
        screens[route] = s
    for route in copy_routes:
        problems.append(f'copy exists for route "{route}" but no composable registers it - renamed or removed?')

    # Activities.
    acts = manifest_activities()
    copy_acts = dict(copy.ACTIVITIES)
    for a in acts:
        if a in copy_acts or a in copy.IGNORED_ACTIVITIES or a == copy.SHELL_ACTIVITY:
            continue
        problems.append(f'activity "{a}" is in the manifest but in neither ACTIVITIES nor '
                        "IGNORED_ACTIVITIES in tools/screen_map_copy.py")
    for a in list(copy_acts) + list(copy.IGNORED_ACTIVITIES):
        if a not in acts:
            problems.append(f'copy names activity "{a}" but the manifest has no such <activity>')
    for a, entry in copy_acts.items():
        f = None
        cls = entry.get("screen_fn")
        if cls:
            f = file_of(cls)
        s = Screen(id=entry["id"], name=entry["name"], kind="activity", what=entry.get("what", ""), activity=a,
                   parent=entry.get("parent"), reach=list(entry.get("reach", [])),
                   marker=entry.get("marker"), controls=list(entry.get("controls", [])),
                   voice=list(entry.get("voice", [])), needs=entry.get("needs", ""),
                   tags=test_tags(f), file=rel(f) if f else None)
        screens[s.id] = s

    # Matrix screens.
    dests = matrix_dests()
    copy_matrix = dict(copy.MATRIX)
    for d, label in dests.items():
        entry = copy_matrix.pop(d, None)
        if entry is None:
            problems.append(f'MatrixDest.{d} ("{label}") has no entry in tools/screen_map_copy.py MATRIX')
            continue
        sid = entry.get("id", "matrix/" + d.lower().replace("_", "-"))
        reach = list(entry["reach"]) if "reach" in entry else [("text", label)]
        s = Screen(id=sid, name=entry.get("name", f"Matrix {label}"), kind="matrix",
                   what=entry.get("what", ""), parent=entry.get("parent", "matrix/index"),
                   reach=reach, marker=entry.get("marker", label), controls=list(entry.get("controls", [])),
                   voice=list(entry.get("voice", [])), needs=copy.MATRIX_NEEDS)
        screens[sid] = s
    for d in copy_matrix:
        problems.append(f"copy exists for MatrixDest.{d} but the enum has no such value")
    mh = copy.MATRIX_HOME
    screens[mh["id"]] = Screen(id=mh["id"], name=mh["name"], kind="matrix", what=mh["what"],
                               parent=mh["parent"], reach=list(mh["reach"]), marker=mh.get("marker"),
                               controls=list(mh.get("controls", [])), voice=list(mh.get("voice", [])),
                               needs=copy.MATRIX_NEEDS)

    # Fleet drilldowns and other in-screen panes.
    drills = fleet_drilldowns()
    for key, entry in copy.DRILLDOWNS.items():
        anchor = entry.get("anchor")
        if anchor and anchor not in drills:
            problems.append(f'drilldown "{key}" names FleetDrilldown.{anchor}, which no longer exists')
        f = file_of(entry["screen_fn"]) if entry.get("screen_fn") else None
        screens[key] = Screen(id=key, name=entry["name"], kind="drilldown", what=entry.get("what", ""),
                              parent=entry["parent"], reach=list(entry["reach"]),
                              marker=entry.get("marker") or header_title(f),
                              controls=list(entry.get("controls", [])), voice=list(entry.get("voice", [])),
                              tags=test_tags(f), needs=entry.get("needs", ""),
                              file=rel(f) if f else None)

    # Cross-checks over every screen.
    tools = declared_voice_tools()
    for s in screens.values():
        if s.parent is not None and s.parent not in screens:
            problems.append(f'screen "{s.id}" has parent "{s.parent}", which is not a screen in the map')
        for t in s.voice:
            if t not in tools:
                problems.append(f'screen "{s.id}" lists voice tool "{t}", which no *Toolbox.kt declares')
        for kind, value in s.reach:
            if kind not in ("text", "desc", "tag", "key"):
                problems.append(f'screen "{s.id}" has a reach step of unknown kind "{kind}"')
            elif kind in ("text", "desc") and not selector_exists(value):
                problems.append(f'screen "{s.id}" taps {kind} "{value}", which no string literal in '
                                "app/src/main contains any more - the control was renamed or removed")
            elif kind == "tag" and not any(value == t or value in t for t in all_tags()):
                problems.append(f'screen "{s.id}" taps tag "{value}", which no testTag in app/src/main sets')
        if s.marker and not selector_exists(s.marker):
            problems.append(f'screen "{s.id}" waits for text "{s.marker}", which no string literal has any more')
        for kind, value, _why in s.controls:
            if kind in ("text", "desc") and not selector_exists(value):
                problems.append(f'screen "{s.id}" lists control {kind} "{value}", which no string literal has')
            if kind == "tag" and not any(value == t or value in t for t in all_tags()):
                problems.append(f'screen "{s.id}" lists control tag "{value}", which no testTag sets')
    # Path to launch must terminate (no cycles).
    for s in screens.values():
        seen, cur = set(), s
        while cur.parent is not None:
            if cur.id in seen:
                problems.append(f'screen "{s.id}" reaches a parent cycle at "{cur.id}"')
                break
            seen.add(cur.id)
            cur = screens.get(cur.parent)
            if cur is None:
                break
    return screens, problems


_ALL_TAGS: set[str] | None = None


def all_tags() -> set[str]:
    global _ALL_TAGS
    if _ALL_TAGS is None:
        tags = set()
        for p in all_kotlin():
            tags.update(test_tags(p))
        _ALL_TAGS = tags
    return _ALL_TAGS


def declared_voice_tools() -> set[str]:
    import voice_guide
    return set(voice_guide.declared_tools())


def path_from_launch(screens: dict[str, Screen], sid: str) -> list[tuple[str, list]]:
    """[(screen id, steps into it)], launch first."""
    chain = []
    cur = screens.get(sid)
    while cur is not None:
        chain.append((cur.id, cur.reach))
        cur = screens.get(cur.parent) if cur.parent else None
    return list(reversed(chain))


# ------------------------------------------------------------------------------------ render

def fmt_step(step) -> str:
    kind, value = step
    if kind == "key":
        return f"key `{value}`"
    if value.endswith("^"):
        return f"{kind} starting `{value[:-1]}`"
    return f"{kind} `{value}`"


def render(screens: dict[str, Screen], copy) -> str:
    out = [
        "<!-- GENERATED by tools/screen_map.py from the app's code and tools/screen_map_copy.py. -->",
        "<!-- Do not hand-edit: change the copy file or the code, then run the script. -->",
        "",
        "# LEGION phone screen map",
        "",
        copy.INTRO.strip(),
        "",
        f"{sum(1 for s in screens.values() if s.kind == 'route')} nav routes, "
        f"{sum(1 for s in screens.values() if s.kind == 'activity')} activities, "
        f"{sum(1 for s in screens.values() if s.kind == 'matrix')} Matrix screens, "
        f"{sum(1 for s in screens.values() if s.kind == 'drilldown')} in-screen drilldowns.",
        "",
        "## Index",
        "",
        "| Screen | Id | Open with | Voice tools |",
        "|---|---|---|---|",
    ]
    order = sorted(screens.values(), key=lambda s: (["route", "activity", "drilldown", "matrix"].index(s.kind), s.id))
    for s in order:
        how = "deep link" if s.deeplink else ("taps" if tap_path(screens, s.id) is not None else "not openable")
        voice = ", ".join(f"`{t}`" for t in s.voice) or "-"
        out.append(f"| [{s.name}](#{anchor(s)}) | `{s.id}` | {how} | {voice} |")
    out.append("")
    for kind, title in (("route", "Nav routes"), ("activity", "Activities"),
                        ("drilldown", "In-screen drilldowns"), ("matrix", "Matrix mode")):
        group = [s for s in order if s.kind == kind]
        if not group:
            continue
        out += [f"## {title}", ""]
        if kind == "matrix":
            out += [copy.MATRIX_NEEDS, ""]
        for s in group:
            out += render_screen(screens, s)
    out += ["## Selector kinds", "", copy.SELECTORS.strip(), ""]
    return "\n".join(out) + "\n"


def tap_path(screens: dict[str, Screen], sid: str) -> list | None:
    """Every step from launch to [sid], or None when some screen on the way has no fixed tap into
    it (a parent but an empty `reach`: the car's own name, a delivered page, an id in the route)."""
    steps = []
    for cid, reach in path_from_launch(screens, sid):
        s = screens[cid]
        if s.parent is not None and not reach:
            return None
        steps += reach
    return steps


def reach_text(screens: dict[str, Screen], s: Screen) -> str:
    steps = tap_path(screens, s.id)
    if steps is None:
        why = s.needs or "no fixed selector reaches it"
        return f"no fixed tap path ({why}); " + ("use the deep link" if s.deeplink else "see Needs")
    root = path_from_launch(screens, s.id)[0][0]
    start = "Matrix HOME" if screens[root].kind == "matrix" else "HOME"
    if not steps:
        return f"{start} (launch lands here)"
    return " > ".join([start] + [fmt_step(st) for st in steps]) + (
        " *(last step read from code)*" if s.reach_auto else "")


def anchor(s: Screen) -> str:
    return "s-" + re.sub(r"[^a-z0-9]+", "-", s.id.lower()).strip("-")


def render_screen(screens, s: Screen) -> list[str]:
    lines = [f'<a id="{anchor(s)}"></a>', f"### {s.name}", ""]
    if s.what:
        lines += [s.what, ""]
    facts = [f"- **Id:** `{s.id}`" + (f" (`LegionRoute.{s.const}`)" if s.const else "")]
    if s.file:
        facts.append(f"- **Code:** `{s.file}`")
    if s.deeplink:
        facts.append(f"- **Open:** `python tools/phone open {s.id}` (deep link `route={s.deeplink}`)")
    else:
        via = next((screens[c].deeplink for c, _ in reversed(path_from_launch(screens, s.id))
                    if screens[c].deeplink), None)
        how = f"deep link `route={via}`, then taps" if via else "taps from HOME"
        facts.append(f"- **Open:** `python tools/phone open {s.id}` ({how}; no deep link of its own)")
    if s.needs:
        facts.append(f"- **Needs:** {s.needs}")
    if s.marker:
        facts.append(f"- **On screen when open:** text `{s.marker}`")
    facts.append("- **From launch:** " + reach_text(screens, s))
    if s.tags:
        facts.append("- **Test tags:** " + ", ".join(f"`{t}`" for t in s.tags))
    if s.controls:
        facts.append("- **Key controls:**")
        for kind, value, why in s.controls:
            v = value[:-1] + "..." if value.endswith("^") else value
            facts.append(f"  - {kind} `{v}`: {why}")
    if s.voice:
        facts.append("- **Voice tools that open it:** " + ", ".join(f"`{t}`" for t in s.voice))
    return lines + facts + [""]


def main(argv: list[str]) -> int:
    import screen_map_copy as copy
    check = "--check" in argv
    screens, problems = build(copy)
    if problems:
        print("screen_map.py: DRIFT - the screen map and the code disagree:", file=sys.stderr)
        for p in problems:
            print("  - " + p, file=sys.stderr)
        return 1
    text = render(screens, copy)
    if check:
        current = OUT.read_text(encoding="utf-8") if OUT.exists() else ""
        if current != text:
            print("screen_map.py: docs/screen-map.md is stale; run python tools/screen_map.py", file=sys.stderr)
            return 1
        print(f"screen_map.py: OK, {len(screens)} screens")
        return 0
    OUT.write_text(text, encoding="utf-8", newline="\n")
    print(f"screen_map.py: wrote {OUT.relative_to(ROOT)} ({len(screens)} screens)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
