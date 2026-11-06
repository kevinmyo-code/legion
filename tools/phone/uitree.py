"""Parse a `uiautomator dump` into a flat, compact list of nodes, and find nodes by selector.

Compose merges a clickable's children into it for accessibility, so the clickable row usually
carries its label's text itself; a plain Text node has no `clickable`. [find] therefore matches any
node, and [tap_target] walks up to the nearest clickable ancestor, which is what a finger would hit.

With `testTagsAsResourceId` on (MainActivity's root, the vault and the chat sheet), a Compose
`testTag("x")` shows as `resource-id="x"`; a View id shows as `com.kevin.legion:id/x`. `--tag`
matches either.
"""
from __future__ import annotations

import re
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field

BOUNDS_RE = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")


@dataclass
class Node:
    idx: int
    text: str
    desc: str
    rid: str
    cls: str
    pkg: str
    bounds: tuple[int, int, int, int]
    clickable: bool
    scrollable: bool
    enabled: bool
    focused: bool
    password: bool
    parent: int | None
    depth: int
    children: list[int] = field(default_factory=list)

    @property
    def tag(self) -> str:
        """The resource id without a package prefix: a Compose test tag, or a View id's name."""
        return self.rid.split(":id/", 1)[1] if ":id/" in self.rid else self.rid

    @property
    def center(self) -> tuple[int, int]:
        l, t, r, b = self.bounds
        return (l + r) // 2, (t + b) // 2

    @property
    def area(self) -> int:
        l, t, r, b = self.bounds
        return max(0, r - l) * max(0, b - t)

    def contains(self, x: int, y: int) -> bool:
        l, t, r, b = self.bounds
        return l <= x < r and t <= y < b

    def label(self) -> str:
        return " | ".join(s for s in (self.text, self.desc, self.tag) if s)


class DumpError(RuntimeError):
    pass


def parse(xml_text: str) -> list[Node]:
    """Nodes in document order. Raises [DumpError] on anything that is not a hierarchy."""
    start = xml_text.find("<?xml")
    if start < 0:
        start = xml_text.find("<hierarchy")
    if start < 0:
        raise DumpError("no <hierarchy> in the dump output: " + xml_text.strip()[:200])
    try:
        root = ET.fromstring(xml_text[start:].strip())
    except ET.ParseError as e:
        raise DumpError(f"dump is not valid XML: {e}") from e
    if root.tag != "hierarchy":
        raise DumpError(f"root element is <{root.tag}>, not <hierarchy>")
    nodes: list[Node] = []

    def walk(el, parent, depth):
        for child in el:
            if child.tag != "node":
                continue
            a = child.attrib
            m = BOUNDS_RE.match(a.get("bounds", ""))
            bounds = tuple(int(v) for v in m.groups()) if m else (0, 0, 0, 0)
            n = Node(
                idx=len(nodes),
                text=a.get("text", ""),
                desc=a.get("content-desc", ""),
                rid=a.get("resource-id", ""),
                cls=a.get("class", ""),
                pkg=a.get("package", ""),
                bounds=bounds,
                clickable=a.get("clickable") == "true" or a.get("long-clickable") == "true",
                scrollable=a.get("scrollable") == "true",
                enabled=a.get("enabled", "true") == "true",
                focused=a.get("focused") == "true",
                password=a.get("password") == "true",
                parent=parent,
                depth=depth,
            )
            nodes.append(n)
            if parent is not None:
                nodes[parent].children.append(n.idx)
            walk(child, n.idx, depth + 1)

    walk(root, None, 0)
    return nodes


def compact(nodes: list[Node], all_nodes: bool = False) -> list[dict]:
    """The nodes worth reading: anything with text, a description, a tag, or a click. Layout-only
    containers are dropped unless [all_nodes]."""
    out = []
    for n in nodes:
        if not all_nodes and not (n.text or n.desc or n.tag or n.clickable or n.scrollable):
            continue
        d = {"i": n.idx}
        if n.text:
            d["text"] = n.text
        if n.desc:
            d["desc"] = n.desc
        if n.tag:
            d["tag"] = n.tag
        d["bounds"] = list(n.bounds)
        flags = "".join(
            c for c, on in (("C", n.clickable), ("S", n.scrollable), ("F", n.focused), ("D", not n.enabled))
            if on
        )
        if flags:
            d["flags"] = flags
        out.append(d)
    return out


def _norm(s: str) -> str:
    return " ".join(s.split()).casefold()


def matches(n: Node, text: str | None = None, desc: str | None = None, tag: str | None = None,
            contains: bool = False) -> bool:
    """Exact (case- and whitespace-insensitive) by default; [contains] makes text/desc substrings.
    A `^` at the end of a text/desc means prefix, so `Chat with ^` matches any companion name."""
    def one(have: str, want: str) -> bool:
        h, w = _norm(have), _norm(want)
        if w.endswith("^"):
            return h.startswith(w[:-1].rstrip()) if w[:-1].strip() else False
        return (w in h) if contains else (h == w)

    if text is not None and not one(n.text, text):
        return False
    if desc is not None and not one(n.desc, desc):
        return False
    if tag is not None and n.tag != tag and n.rid != tag:
        return False
    return text is not None or desc is not None or tag is not None


def find(nodes: list[Node], **sel) -> list[Node]:
    return [n for n in nodes if matches(n, **sel)]


def tap_target(nodes: list[Node], n: Node) -> Node:
    """The nearest clickable node at or above [n], else [n] itself."""
    cur: Node | None = n
    while cur is not None:
        if cur.clickable:
            return cur
        cur = nodes[cur.parent] if cur.parent is not None else None
    return n


def node_at(nodes: list[Node], x: int, y: int) -> Node | None:
    """The deepest, smallest node whose bounds contain the point - what a tap there lands on."""
    hits = [n for n in nodes if n.contains(x, y) and n.area > 0]
    if not hits:
        return None
    return max(hits, key=lambda n: (n.depth, -n.area))


def subtree_words(nodes: list[Node], n: Node) -> list[str]:
    """Every text, description and tag in [n] and below: the words a refusal check reads. A
    Compose button's label can sit on the clickable or on a child Text, so both count."""
    out, stack = [], [n.idx]
    while stack:
        cur = nodes[stack.pop()]
        out.extend(s for s in (cur.text, cur.desc, cur.tag) if s)
        stack.extend(cur.children)
    return out
