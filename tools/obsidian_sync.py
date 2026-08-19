#!/usr/bin/env python3
"""Regenerate the Obsidian layer over .scratch/ maps and tickets.

The tickets stay the source of truth. This script reads their existing
`Type:` / `Status:` / `Blocked by:` header lines, lifts them into YAML
frontmatter Obsidian Bases can query, and regenerates the per-map
dependency canvases and the ready-queue dashboard.

Idempotent. Re-run it after editing any ticket header.

    python tools/obsidian_sync.py --check   # report, write nothing
    python tools/obsidian_sync.py           # write
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCRATCH = ROOT / ".scratch"

# Header keys we lift out of the body, in the order they are emitted.
TICKET_KEYS = ["Type", "Status", "Blocked by", "Scope", "Effort"]
MAP_KEYS = ["Label", "Charted", "Effort", "Reached when", "Related", "Graduated from"]

HEADER_RE = re.compile(r"^([A-Z][A-Za-z ]{1,20}):[ \t]+(.*)$")
# A status is "done" for readiness purposes if it starts with one of these.
DONE_STATES = {"resolved", "closed", "killed", "archived", "graduated", "fixed", "superseded"}
NO_BLOCKER = {"-", "none", "(none)", "n/a", "", "--"}
# Free-text first words that are not really their own state.
STATE_ALIAS = {"mostly": "open", "in": "open", "partially": "open", "superseded": "closed"}


def node_id(*parts: str) -> str:
    """Stable 16-hex-char id, so canvases do not churn between runs."""
    return hashlib.sha1("|".join(parts).encode()).hexdigest()[:16]


def yaml_str(value: str) -> str:
    """Quote a scalar only when YAML would otherwise misread it."""
    if value == "":
        return '""'
    if re.search(r'[:#\[\]{}",&*?|<>=!%@`]', value) or value[0] in "-? " or value[-1] == " ":
        return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'
    return value


def split_header(text: str, want_keys: list[str]) -> tuple[str, dict[str, str], str]:
    """Return (h1_line, header_pairs, remaining_body).

    The header block is the run of `Key: value` lines that sits between the
    H1 and the first `##` section. Prose lines that merely look like a key
    are left alone, because only keys in `want_keys` are lifted.
    """
    if text.startswith("---\n"):  # already converted
        return "", {}, text

    lines = text.split("\n")
    h1 = ""
    start = 0
    for i, line in enumerate(lines[:5]):
        if line.startswith("# "):
            h1 = line
            start = i + 1
            break
    else:
        return "", {}, text

    pairs: dict[str, str] = {}
    consumed: set[int] = set()
    for i in range(start, min(start + 12, len(lines))):
        line = lines[i]
        if line.startswith("##"):
            break
        m = HEADER_RE.match(line)
        if m and m.group(1) in want_keys:
            pairs[m.group(1)] = m.group(2).strip()
            consumed.add(i)

    if not pairs:
        return "", {}, text

    body = [ln for i, ln in enumerate(lines) if i not in consumed]
    # Collapse the blank-line run the removed header left behind.
    out: list[str] = []
    for ln in body:
        if ln == "" and out and out[-1] == "":
            continue
        out.append(ln)
    return h1, pairs, "\n".join(out).lstrip("\n")


def parse_status(raw: str) -> tuple[str, str]:
    """`resolved (2026-08-16, verified built)` -> ('resolved', '2026-08-16, verified built')."""
    m = re.match(r"^([A-Za-z-]+)\s*(.*)$", raw.strip())
    if not m:
        return raw.strip().lower(), ""
    state = m.group(1).lower()
    detail = m.group(2).strip()
    if state in STATE_ALIAS:
        detail = (m.group(1) + " " + detail).strip()
        state = STATE_ALIAS[state]
    if detail.startswith("(") and detail.endswith(")"):
        detail = detail[1:-1]
    detail = detail.lstrip("-").strip()
    detail = re.sub(r"^\((.*?)\)\s*-\s*", r"\1, ", detail)
    return state, detail


def parse_blockers(raw: str) -> list[str]:
    cleaned = raw.strip().lower()
    if cleaned in NO_BLOCKER:
        return []
    out = []
    for part in re.split(r"[,/]| and ", raw):
        m = re.search(r"\d+", part)
        if m:
            num = m.group(0).zfill(2)
            if num not in out:
                out.append(num)
    return out


class Ticket:
    def __init__(self, path: Path, map_slug: str):
        self.path = path
        self.map = map_slug
        self.slug = path.stem
        m = re.match(r"^(\d+)", self.slug)
        self.num = m.group(1).zfill(2) if m else ""
        self.text = path.read_text(encoding="utf-8-sig")
        self.already = self.text.startswith("---\n")
        self.h1, self.pairs, self.body = split_header(self.text, TICKET_KEYS)
        self.converted = bool(self.pairs)
        if self.already:
            # Re-run: the header already lives in frontmatter. Read it back
            # so canvases and the dashboard stay correct without a reconvert.
            fm = self.text.split("\n---\n", 1)[0]
            got = dict(
                (k.strip(), v.strip().strip('"'))
                for k, _, v in (ln.partition(":") for ln in fm.split("\n"))
                if k.strip() and not k.startswith("-")
            )
            self.state = got.get("status", "")
            self.detail = got.get("status-detail", "")
            self.type = got.get("type", "")
            self.title = got.get("title", self.slug)
            self.blockers = re.findall(r"\d+", got.get("blockers", ""))
        else:
            raw_status = self.pairs.get("Status", "")
            self.state, self.detail = parse_status(raw_status) if raw_status else ("", "")
            self.blockers = parse_blockers(self.pairs.get("Blocked by", ""))
            self.type = self.pairs.get("Type", "").strip()
            self.title = self.h1[2:].strip() if self.h1 else self.slug

    @property
    def done(self) -> bool:
        return self.state in DONE_STATES


def existing_frontmatter_state(path: Path) -> tuple[str, list[str]]:
    """Read state and blockers back out of an already-converted file."""
    text = path.read_text(encoding="utf-8-sig")
    if not text.startswith("---\n"):
        return "", []
    fm = text.split("\n---\n", 1)[0]
    state = ""
    blockers: list[str] = []
    for line in fm.split("\n"):
        if line.startswith("status:"):
            state = line.split(":", 1)[1].strip().strip('"')
        if line.startswith("blockers:"):
            raw = line.split(":", 1)[1].strip()
            blockers = re.findall(r"\d+", raw)
    return state, blockers


def refresh_computed_fields(t: "Ticket", by_num: dict, check: bool) -> bool:
    """Recompute `open-blockers`, `blocked-by` and `ready` in an already-converted ticket.

    A surgical rewrite of three lines, never a re-render: everything else in the file - the body,
    `status-detail`, any field a human added - is left byte-identical. Returns True if the file
    changed (or would have, under --check).
    """
    text = t.path.read_text(encoding="utf-8-sig")
    if not text.startswith("---" + chr(10)):
        return False
    fm, rest = text.split(chr(10) + "---" + chr(10), 1)
    open_blockers = sum(1 for n in t.blockers if n in by_num and not by_num[n].done)
    # EXACTLY render_ticket's own formatting, quotes included - this function must be a no-op on a
    # file whose computed values have not changed, or every run would rewrite every ticket and the
    # diff would stop telling anyone anything.
    links = []
    for num in t.blockers:
        dep = by_num.get(num)
        links.append(f'"[[{dep.slug}]]"' if dep else f'"{num}"')
    wanted = {
        "blocked-by": f"[{', '.join(links)}]",
        "open-blockers": str(open_blockers),
        "ready": str(not t.done and open_blockers == 0).lower(),
    }
    out, changed = [], False
    for line in fm.split(chr(10)):
        key = line.split(":", 1)[0]
        if key in wanted:
            new_line = f"{key}: {wanted[key]}"
            if new_line != line:
                changed = True
            out.append(new_line)
        else:
            out.append(line)
    if not changed:
        return False
    if not check:
        t.path.write_text(chr(10).join(out) + chr(10) + "---" + chr(10) + rest, encoding="utf-8")
    return True


def collect() -> dict[str, list[Ticket]]:
    maps: dict[str, list[Ticket]] = {}
    dirs = {p.parent for p in SCRATCH.glob("*/map.md")} | {p.parent for p in SCRATCH.glob("*/issues")}
    for d in sorted(dirs):
        slug = d.name
        issues = sorted((d / "issues").glob("*.md")) if (d / "issues").is_dir() else []
        maps[slug] = [Ticket(p, slug) for p in issues]
    return maps


def render_ticket(t: Ticket, all_by_num: dict[str, Ticket]) -> str:
    blocker_links = []
    for num in t.blockers:
        dep = all_by_num.get(num)
        blocker_links.append(f'"[[{dep.slug}]]"' if dep else f'"{num}"')
    open_blockers = sum(1 for n in t.blockers if n in all_by_num and not all_by_num[n].done)

    fm = [
        "---",
        f"map: {t.map}",
        f"ticket: {yaml_str(t.num)}",
        f"title: {yaml_str(t.title)}",
        f"type: {yaml_str(t.type)}",
        f"status: {yaml_str(t.state)}",
        f"status-detail: {yaml_str(t.detail)}",
        f"blockers: [{', '.join(f'{chr(34)}{n}{chr(34)}' for n in t.blockers)}]",
        f"blocked-by: [{', '.join(blocker_links)}]",
        f"open-blockers: {open_blockers}",
        f"ready: {str(not t.done and open_blockers == 0).lower()}",
        "tags: [ticket]",
        "---",
        "",
    ]
    body = t.body if t.converted else t.text
    return "\n".join(fm) + body.rstrip() + "\n"


def render_map(path: Path, slug: str, tickets: list[Ticket]) -> str | None:
    if not path.exists():
        return None
    text = path.read_text(encoding="utf-8-sig")
    if text.startswith("---\n"):
        return None
    h1, pairs, body = split_header(text, MAP_KEYS)
    if not pairs:
        # Two maps write the date as prose ("Charted 2026-08-16, from a defect...")
        # rather than as a Key: value line. Scrape what is there and move on.
        h1 = next((ln for ln in text.split(chr(10))[:5] if ln.startswith("# ")), "")
        body = text
        m = re.search(r"Charted\s+(\d{4}-\d{2}-\d{2})", text[:600])
        pairs = {"Charted": m.group(1) if m else ""}
    charted = pairs.get("Charted", "")
    m = re.match(r"^(\d{4}-\d{2}-\d{2})\s*(.*)$", charted)
    charted_date, charted_by = (m.group(1), m.group(2).strip("() ")) if m else (charted, "")
    open_n = sum(1 for t in tickets if not t.done)
    fm = [
        "---",
        f"map: {slug}",
        f"title: {yaml_str(h1[2:].strip() if h1 else slug)}",
        f"charted: {yaml_str(charted_date)}",
        f"charted-by: {yaml_str(charted_by)}",
        f"effort: {yaml_str(pairs.get('Effort', ''))}",
        f"tickets: {len(tickets)}",
        f"open: {open_n}",
        f"status: {'open' if open_n else 'closed'}",
        "tags: [map]",
        "---",
        "",
    ]
    return "\n".join(fm) + body.rstrip() + "\n"


CANVAS_COLOR = {"open": "1", "grilling": "5", "done": "4"}


def render_canvas(slug: str, tickets: list[Ticket]) -> str:
    """One canvas per map: tickets as file nodes, blocked-by as edges.

    Laid out in dependency layers left to right, so what is buildable now
    sits in the leftmost column.
    """
    by_num = {t.num: t for t in tickets if t.num}
    depth: dict[str, int] = {}

    def layer(num: str, seen: frozenset[str] = frozenset()) -> int:
        if num in depth:
            return depth[num]
        if num in seen:  # cycle guard
            return 0
        t = by_num.get(num)
        if not t or not t.blockers:
            depth[num] = 0
            return 0
        d = 1 + max((layer(b, seen | {num}) for b in t.blockers if b in by_num), default=-1)
        depth[num] = d
        return d

    for num in by_num:
        layer(num)

    cols: dict[int, list[Ticket]] = {}
    for t in tickets:
        cols.setdefault(depth.get(t.num, 0), []).append(t)

    nodes, edges = [], []
    W, H, GAPX, GAPY = 320, 110, 200, 40
    for col, items in sorted(cols.items()):
        for row, t in enumerate(items):
            color = CANVAS_COLOR["done"] if t.done else CANVAS_COLOR.get(t.type, CANVAS_COLOR["open"])
            nodes.append({
                "id": node_id(slug, t.slug),
                "type": "file",
                "file": str(t.path.relative_to(ROOT)).replace("\\", "/"),
                "x": col * (W + GAPX),
                "y": row * (H + GAPY),
                "width": W,
                "height": H,
                "color": color,
            })
    for t in tickets:
        for b in t.blockers:
            if b in by_num:
                edges.append({
                    "id": node_id(slug, b, t.slug, "e"),
                    "fromNode": node_id(slug, by_num[b].slug),
                    "fromSide": "right",
                    "toNode": node_id(slug, t.slug),
                    "toSide": "left",
                })
    return json.dumps({"nodes": nodes, "edges": edges}, indent=2) + "\n"


def map_link(slug: str) -> str:
    """Two efforts have tickets but no map.md. Do not link at what is not there."""
    if (SCRATCH / slug / "map.md").exists():
        return "[[.scratch/{}/map{}|{}]]".format(slug, chr(92), slug)
    return slug + " (no map)"


def render_board(maps: dict[str, list[Ticket]]) -> str:
    """The one note that answers "what is next" without asking anyone.

    Bases can filter, but it cannot walk the blocker chain, so the ready
    queue is computed here and regenerated on every run.
    """
    lines = [
        "---",
        "title: Board",
        "tags: [board]",
        "---",
        "",
        "# Board",
        "",
        "Generated by `tools/obsidian_sync.py`. Do not hand-edit; edit the tickets and re-run.",
        "",
        "## Ready now",
        "",
        "Open tickets whose blockers are all resolved.",
        "",
        "| Map | Ticket | Type | What |",
        "|---|---|---|---|",
    ]
    ready_rows, blocked_rows = [], []
    for slug, tickets in sorted(maps.items()):
        by_num = {t.num: t for t in tickets if t.num}
        for t in tickets:
            if t.done:
                continue
            waiting = [n for n in t.blockers if n in by_num and not by_num[n].done]
            row = f"| {map_link(slug)} | [[{t.slug}\\|{t.num}]] | {t.type} | {t.title} |"
            if waiting:
                names = ", ".join(f"[[{by_num[n].slug}\\|{n}]]" for n in waiting)
                blocked_rows.append(row[:-1] + f" waiting on {names} |")
            else:
                ready_rows.append(row)

    lines += ready_rows or ["| - | - | - | nothing ready |"]
    lines += ["", "## Blocked", "", "| Map | Ticket | Type | What | Waiting on |", "|---|---|---|---|---|"]
    lines += blocked_rows or ["| - | - | - | nothing blocked | - |"]

    lines += ["", "## Maps", "", "| Map | Tickets | Open | Canvas |", "|---|---|---|---|"]
    for slug, tickets in sorted(maps.items()):
        open_n = sum(1 for t in tickets if not t.done)
        lines.append(f"| {map_link(slug)} | {len(tickets)} | {open_n} | [[.scratch/{slug}/{slug}.canvas\\|open]] |")
    return "\n".join(lines) + "\n"


TICKETS_BASE = """filters:
  and:
    - file.hasTag("ticket")

formulas:
  where: 'map + " / " + ticket'

properties:
  status-detail:
    displayName: "Detail"
  open-blockers:
    displayName: "Open blockers"
  formula.where:
    displayName: "Where"

views:
  - type: table
    name: "Ready now"
    filters:
      and:
        - 'ready == true'
    order:
      - formula.where
      - title
      - type
      - map
    groupBy:
      property: map
      direction: ASC

  - type: table
    name: "Blocked"
    filters:
      and:
        - 'ready == false'
        - 'open-blockers > 0'
    order:
      - formula.where
      - title
      - type
      - blocked-by
      - open-blockers

  - type: table
    name: "All open"
    filters:
      and:
        - 'status == "open"'
    order:
      - formula.where
      - title
      - type
      - status
      - blocked-by
    groupBy:
      property: map
      direction: ASC

  - type: table
    name: "Everything"
    order:
      - formula.where
      - title
      - type
      - status
      - status-detail
    groupBy:
      property: status
      direction: ASC

  - type: cards
    name: "By type"
    order:
      - title
      - map
      - status
    groupBy:
      property: type
      direction: ASC
"""

MAPS_BASE = """filters:
  and:
    - file.hasTag("map")

formulas:
  progress: 'if(tickets > 0, ((tickets - open) * 100 / tickets).round(0) + "%", "-")'

properties:
  formula.progress:
    displayName: "Done"
  charted-by:
    displayName: "Charted by"

views:
  - type: table
    name: "All maps"
    order:
      - title
      - map
      - status
      - tickets
      - open
      - formula.progress
      - charted
      - charted-by
    summaries:
      tickets: Sum
      open: Sum

  - type: table
    name: "Open maps"
    filters:
      and:
        - 'open > 0'
    order:
      - title
      - open
      - tickets
      - formula.progress
      - charted
"""

DECISIONS_BASE = """filters:
  and:
    - file.hasTag("adr")
    - '!file.hasTag("index")'

formulas:
  reversal: 'if(supersedes, "reverses " + supersedes, "")'

properties:
  decided-by:
    displayName: "By"
  formula.reversal:
    displayName: "Reverses"

views:
  - type: table
    name: "Standing"
    filters:
      not:
        - 'status == "superseded"'
    order:
      - file.name
      - status
      - decided
      - amended
      - formula.reversal
    groupBy:
      property: status
      direction: ASC

  - type: table
    name: "Locked"
    filters:
      and:
        - 'status == "locked"'
    order:
      - file.name
      - decided
      - source

  - type: table
    name: "Superseded"
    filters:
      and:
        - 'status == "superseded"'
    order:
      - file.name
      - decided
      - superseded-by

  - type: table
    name: "Everything"
    order:
      - file.name
      - status
      - decided
      - decided-by
      - supersedes
      - superseded-by
"""

LIBRARY_BASE = """filters:
  and:
    - file.hasTag("library")

views:
  - type: table
    name: "Live shelves"
    filters:
      not:
        - 'status == "frozen"'
    order:
      - shelf
      - kind
      - file.mtime

  - type: table
    name: "All shelves"
    order:
      - shelf
      - status
      - kind
      - file.mtime
    groupBy:
      property: status
      direction: ASC
"""

HOME = """---
title: LEGION
tags: [home]
---

# LEGION

One Android phone app, three aspects: fleet, ledger, pantry.

## Start here

| | |
|---|---|
| [[Board]] | What is ready to build right now, and what is waiting on what |
| [[Tickets.base\\|Tickets]] | Every ticket, filterable |
| [[Maps.base\\|Maps]] | Every wayfinder map and its progress |
| [[Library.base\\|Library]] | The memory shelves, live and frozen |
| [[Decisions.base\\|Decisions]] | Every standing decision, and what superseded what |
| [[adr-index\\|ADR index]] | The same set as a plain table |
| `docs/README.md` | Architecture, C4 diagrams, glossary |

## Rules and state

- `CLAUDE.md` holds the rules. `memory/MEMORY.md` holds the state.
- If they disagree: MEMORY.md wins for state, CLAUDE.md wins for rules.

## Regenerating

The tickets are the source of truth. After editing a ticket header, run:

```
python tools/obsidian_sync.py
```

That rewrites frontmatter, the per-map canvases, and [[Board]].
"""



def render_adr_index() -> str | None:
    """The ADR table, rebuilt from each ADR's own frontmatter.

    Hand-maintaining this drifts the moment someone supersedes something and
    forgets the index, which is the failure the ADR set exists to prevent.
    """
    adr_dir = ROOT / "docs" / "adr"
    if not adr_dir.is_dir():
        return None

    rows = []
    for path in sorted(adr_dir.glob("[0-9]*.md")):
        text = path.read_text(encoding="utf-8")
        fm = {}
        if text.startswith("---" + chr(10)):
            for line in text.split(chr(10) + "---" + chr(10), 1)[0][4:].split(chr(10)):
                k, sep, v = line.partition(":")
                if sep and not k.startswith((" ", "-")):
                    fm[k.strip()] = v.strip()
        title = ""
        for line in text.split(chr(10)):
            if line.startswith("# "):
                title = line[2:].split(". ", 1)[-1].strip()
                break
        rows.append((path.stem, title, fm))

    live = [r for r in rows if r[2].get("status") != "superseded"]
    dead = [r for r in rows if r[2].get("status") == "superseded"]

    out = [
        "---",
        "title: ADR index",
        "tags: [adr, index]",
        "---",
        "",
        "# Decisions",
        "",
        "Generated by `tools/obsidian_sync.py` from each ADR's frontmatter. Do not hand-edit.",
        "",
        "An ADR says what is binding **now**. `memory/library/decisions.md` says what happened **when**.",
        "Format and the test for whether something deserves an ADR:",
        "`.claude/skills/domain-modeling/ADR-FORMAT.md`.",
        "",
        "`locked` means a CLAUDE.md section 2 pivot decision: not reopenable without Kevin.",
        "",
        "## Standing",
        "",
        "| # | Decision | Status | Decided | Amended |",
        "|---|---|---|---|---|",
    ]
    for slug, title, fm in live:
        num = slug.split("-")[0]
        out.append(
            "| {} | [[{}\\|{}]] | {} | {} | {} |".format(
                num, slug, title, fm.get("status", ""), fm.get("decided", ""),
                fm.get("amended", "") or "-")
        )

    out += ["", "## Superseded", "",
            "Kept with their original text. What was believed before, and why it changed.",
            "", "| # | Decision | Superseded by |", "|---|---|---|"]
    for slug, title, fm in dead:
        num = slug.split("-")[0]
        by = fm.get("superseded-by", "").strip("[]")
        by_link = "[[{}\\|{}]]".format(by, by) if by else "-"
        out.append("| {} | [[{}\\|{}]] | {} |".format(num, slug, title, by_link))

    return chr(10).join(out) + chr(10)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="report, write nothing")
    args = ap.parse_args()

    maps = collect()
    all_tickets = [t for ts in maps.values() for t in ts]
    if not all_tickets:
        print("no tickets found under .scratch/*/issues/", file=sys.stderr)
        return 1

    wrote = 0
    for slug, tickets in maps.items():
        by_num = {t.num: t for t in tickets if t.num}

        for t in tickets:
            if t.already:
                # Already converted, so the body and the hand-written fields stay untouched -
                # re-rendering from `pairs` would destroy them, which is why this used to `continue`
                # outright. But the three COMPUTED fields have to be recomputed, or they freeze at
                # whatever they were the day the file was converted: resolve a ticket and its own
                # frontmatter still claims `ready: true` forever, while the Board (rebuilt from
                # scratch every run) correctly drops it. CLAUDE.md sec 12 promises a re-run fixes a
                # stale board; it did not fix the tickets the Bases actually query.
                if refresh_computed_fields(t, by_num, check=args.check):
                    wrote += 1
                continue
            if not t.converted:
                print(f"  SKIP (no header block): {t.path.relative_to(ROOT)}")
                continue
            out = render_ticket(t, by_num)
            if not args.check:
                t.path.write_text(out, encoding="utf-8")
            wrote += 1

        map_path = SCRATCH / slug / "map.md"
        rendered = render_map(map_path, slug, tickets)
        if rendered and not args.check:
            map_path.write_text(rendered, encoding="utf-8")
            wrote += 1

        canvas = SCRATCH / slug / f"{slug}.canvas"
        if not args.check:
            canvas.write_text(render_canvas(slug, tickets), encoding="utf-8")

    vault = ROOT / "vault"
    if not args.check:
        vault.mkdir(exist_ok=True)
        (vault / "Tickets.base").write_text(TICKETS_BASE, encoding="utf-8")
        (vault / "Maps.base").write_text(MAPS_BASE, encoding="utf-8")
        (vault / "Library.base").write_text(LIBRARY_BASE, encoding="utf-8")
        (vault / "Decisions.base").write_text(DECISIONS_BASE, encoding="utf-8")
        (vault / "Board.md").write_text(render_board(maps), encoding="utf-8")
        if not (vault / "LEGION.md").exists():
            (vault / "LEGION.md").write_text(HOME, encoding="utf-8")

        adr_index = render_adr_index()
        if adr_index:
            (ROOT / "docs" / "adr" / "adr-index.md").write_text(adr_index, encoding="utf-8")

    open_n = sum(1 for t in all_tickets if not t.done)
    ready_n = 0
    for slug, tickets in maps.items():
        by_num = {t.num: t for t in tickets if t.num}
        ready_n += sum(
            1 for t in tickets
            if not t.done and not any(n in by_num and not by_num[n].done for n in t.blockers)
        )
    verb = "would convert" if args.check else "converted"
    print(f"{len(maps)} maps, {len(all_tickets)} tickets, {open_n} open, {ready_n} ready")
    print(f"{verb} {wrote} files")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
