"""Pre-render the architecture diagrams: Mermaid source in, SVG and a catalogue out.

    python tools/render_diagrams.py                     # render the public views and catalogue
    python tools/render_diagrams.py --check             # exit 1 if an SVG or the catalogue is stale
    python tools/render_diagrams.py --private <folder>  # render a private folder (midcon, quanex)

Kevin, 2026-11-05: "can we add the project architecture diagrams like uml layers, views etc. so a
technical interviewer could ask and the diagrams pop up?" `show_diagram` shows one of these as a
card; the model picks a view from a fixed list and never draws one.

**Sources.** A view is one `.mmd` file. Its title and caption are its own `accTitle:` and
`accDescr:` lines, Mermaid's accessibility fields, so the SVG carries them as `<title>` and
`<desc>` and there is no second copy to drift.

- Public views (LEGION and the agentic workflow) live in `docs/architecture/views/`, named
  `<project>-<view>.mmd`. The catalogue they make, `server/diagrams/catalogue.json`, is what the
  engine image carries (the image is built from `server/` alone, as `docs_corpus.json` is).
- Private views (Midcon, Quanex) live OUTSIDE the repo, in `<folder>/<project>/<view>.mmd`. Each
  project folder gets a `manifest.json` beside its SVGs, which `manage.py load_recruiter_diagrams`
  reads into the engine's database. Nothing private is ever written into the repo.

**Stable node ids.** Mermaid prefixes and numbers every element id (`flowchart-engine-3`). After
rendering, each node, cluster, state, entity and sequence participant gets `data-node="<the id
in the source>"`, and the catalogue's `nodes` are read back from the stamped SVG, so it can never
list an id the picture lacks. The card highlights `[data-node=...]`, never Mermaid's own ids.

**Staleness without a browser.** Rendering needs Node, mermaid-cli and Chrome; the test suite
has none of them. Each view records `source_sha256`, a hash of its `.mmd`, the shared Mermaid
config and the renderer version. `--check` (and `tests/test_diagrams.py`) recompute that hash and
fail when a source changed and its SVG was not re-rendered. Same posture as the docs corpus and
`openapi.yaml`.

**Rendering.** `npx -y -p @mermaid-js/mermaid-cli@<MMDC_VERSION> mmdc`, or `$MMDC` if set,
driving the installed Chrome (`$CHROME_PATH`, else the default Windows path). `htmlLabels` is off,
so every label is SVG `<text>` and no `foreignObject` carries HTML into the page.

Stand-alone on purpose, as `assistant/gen_docs_corpus.py` is: no Django, no third-party import.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parents[1]
PUBLIC_DIR = REPO_ROOT / "docs" / "architecture" / "views"
CONFIG = HERE / "mermaid.config.json"
CATALOGUE = HERE / "catalogue.json"
MANIFEST = "manifest.json"

MMDC_VERSION = "12.2.0"
RENDERER = f"mermaid-cli {MMDC_VERSION}"
# The panel every diagram is drawn on, in the docs and in the chat card alike. One light neutral
# ground means one palette: an edge label or a sequence message sits on this, never on the dark
# dock, so it reads in both places.
BACKGROUND = "#f7f7f5"
DEFAULT_CHROME = r"C:\Program Files\Google\Chrome\Application\chrome.exe"

PUBLIC_PROJECTS = ("legion", "workflow")
PRIVATE_PROJECTS = ("midcon", "quanex")
PROJECTS = PUBLIC_PROJECTS + PRIVATE_PROJECTS

_VIEW_ID = re.compile(r"^[a-z][a-z0-9_]{1,40}$")
_ACC_TITLE = re.compile(r"^\s*accTitle\s*:\s*(.+?)\s*$", re.MULTILINE)
_ACC_DESCR = re.compile(r"^\s*accDescr\s*:\s*(.+?)\s*$", re.MULTILINE)
_START_TAG = re.compile(r"<g\b[^>]*>")
_ATTR = re.compile(r'([:\w-]+)="([^"]*)"')
SVG_NS = "{http://www.w3.org/2000/svg}"


class DiagramError(Exception):
    pass


# =============================================================================
# Sources
# =============================================================================


def source_hash(mmd: Path) -> str:
    digest = hashlib.sha256()
    digest.update(mmd.read_bytes().replace(b"\r\n", b"\n"))
    digest.update(b"\0")
    digest.update(CONFIG.read_bytes().replace(b"\r\n", b"\n"))
    digest.update(b"\0")
    digest.update(f"{RENDERER} {BACKGROUND}".encode())
    return digest.hexdigest()


def read_meta(mmd: Path) -> tuple[str, str]:
    """(title, caption) from the file's own `accTitle:` and `accDescr:` lines."""
    text = mmd.read_text(encoding="utf-8")
    title, caption = _ACC_TITLE.search(text), _ACC_DESCR.search(text)
    if title is None or caption is None:
        raise DiagramError(f"{mmd.name}: needs an accTitle: line and an accDescr: line")
    return title.group(1), caption.group(1)


def public_sources() -> list[tuple[str, str, Path]]:
    """(project, view, path) for each public view, in file-name order."""
    out = []
    for mmd in sorted(PUBLIC_DIR.glob("*.mmd")):
        project, _, view = mmd.stem.partition("-")
        if project not in PUBLIC_PROJECTS or not _VIEW_ID.match(view):
            raise DiagramError(
                f"{mmd.name}: a public view is named <project>-<view>.mmd, project one of "
                f"{', '.join(PUBLIC_PROJECTS)}, view lower_snake_case"
            )
        out.append((project, view, mmd))
    return out


def private_sources(folder: Path) -> list[tuple[str, str, Path]]:
    out = []
    for project_dir in sorted(p for p in folder.iterdir() if p.is_dir()):
        if project_dir.name not in PRIVATE_PROJECTS:
            raise DiagramError(
                f"{project_dir}: a private folder holds only {', '.join(PRIVATE_PROJECTS)}"
            )
        for mmd in sorted(project_dir.glob("*.mmd")):
            if not _VIEW_ID.match(mmd.stem):
                raise DiagramError(f"{mmd}: a view's file name is lower_snake_case")
            out.append((project_dir.name, mmd.stem, mmd))
    return out


# =============================================================================
# Rendering
# =============================================================================


def _mmdc_command() -> list[str]:
    if os.environ.get("MMDC"):
        return [os.environ["MMDC"]]
    npx = shutil.which("npx") or shutil.which("npx.cmd")
    if npx is None:
        raise DiagramError("Rendering needs Node (npx) on the PATH, or $MMDC set to mmdc.")
    return [npx, "-y", "-p", f"@mermaid-js/mermaid-cli@{MMDC_VERSION}", "mmdc"]


def render_svg(mmd: Path, svg_id: str) -> str:
    """The raw SVG mermaid-cli draws for `mmd`, its root id `svg_id`."""
    chrome = os.environ.get("CHROME_PATH", DEFAULT_CHROME)
    with tempfile.TemporaryDirectory() as tmp:
        puppeteer = Path(tmp) / "puppeteer.json"
        puppeteer.write_text(json.dumps({"executablePath": chrome, "headless": "shell"}))
        out = Path(tmp) / "out.svg"
        env = {**os.environ, "PUPPETEER_SKIP_DOWNLOAD": "1"}
        command = _mmdc_command() + [
            "-q",
            "-p", str(puppeteer),
            "-c", str(CONFIG),
            "-i", str(mmd),
            "-o", str(out),
            "-I", svg_id,
            "-b", BACKGROUND,
        ]  # fmt: skip
        done = subprocess.run(command, capture_output=True, text=True, env=env, check=False)
        if done.returncode != 0 or not out.exists():
            raise DiagramError(f"{mmd.name}: mermaid-cli failed:\n{done.stderr or done.stdout}")
        return out.read_text(encoding="utf-8")


def _node_id(attrs: dict, svg_id: str) -> str | None:
    """The source id an element was drawn from, or None for anything else."""
    if attrs.get("data-type") in ("participant", "actor") and attrs.get("data-id"):
        return attrs["data-id"]
    raw = attrs.get("id", "")
    prefix = f"{svg_id}-"
    if not raw.startswith(prefix):
        return None
    rest = raw[len(prefix) :]
    classes = attrs.get("class", "").split()
    if "node" in classes:
        m = re.match(r"^(?:flowchart|state|entity)-(.+)-\d+$", rest)
        if m and not m.group(1).startswith("root_"):
            return m.group(1)
        return None
    if "cluster" in classes:
        return rest
    return None


_LONG_NUMBER = re.compile(r"(?<![\w.])(-?\d+\.\d{3,})(?![\w.])")


def trim_numbers(svg: str) -> str:
    """Coordinates to two decimals. Mermaid writes fifteen; nobody can see past the second, and
    the long ones are most of a diagram's bytes."""

    def one(match: re.Match) -> str:
        value = f"{float(match.group(1)):.2f}".rstrip("0").rstrip(".")
        return "0" if value in ("-0", "") else value

    return _LONG_NUMBER.sub(one, svg)


def stamp(svg: str, svg_id: str) -> str:
    """`svg` with `data-node="<source id>"` on every node, cluster, state, entity and
    participant group."""

    def one(match: re.Match) -> str:
        tag = match.group(0)
        attrs = dict(_ATTR.findall(tag))
        node = _node_id(attrs, svg_id)
        if node is None or "data-node" in attrs:
            return tag
        return tag[:-1] + f' data-node="{node}">' if not tag.endswith("/>") else tag

    return _START_TAG.sub(one, svg)


def _words(element) -> str:
    return " ".join(" ".join(element.itertext()).split())


def nodes_of(svg: str) -> list[dict]:
    """[{id, label}] for each stamped element, in drawing order, each id once."""
    root = ET.fromstring(svg)
    seen: dict[str, str] = {}
    for g in root.iter(f"{SVG_NS}g"):
        node = g.get("data-node")
        if not node or node in seen:
            continue
        if "-entity-" in (g.get("id") or ""):
            label = node
        elif "cluster" in (g.get("class") or "").split():
            label = next(
                (
                    _words(c)
                    for c in g
                    if c.tag == f"{SVG_NS}g" and "cluster-label" in (c.get("class") or "")
                ),
                "",
            ) or _words(g)
        else:
            label = _words(g)
        seen[node] = label or node
    return [{"id": node, "label": label} for node, label in seen.items()]


def _body(source: str) -> str:
    """The diagram's statements: no comments, no accTitle/accDescr."""
    return "\n".join(
        line
        for line in source.splitlines()
        if not line.strip().startswith(("%%", "accTitle", "accDescr"))
    )


def _first_use(source: str, node: str) -> int:
    m = re.search(rf"(?<![\w-]){re.escape(node)}(?![\w-])", source)
    return m.start() if m else len(source)


def check_svg(svg: str, name: str) -> None:
    """Refuse an SVG that could carry script or HTML into the page."""
    lowered = svg.lower()
    for bad in ("<script", "foreignobject", "javascript:"):
        if bad in lowered:
            raise DiagramError(f"{name}: the rendered SVG contains {bad!r}; refusing it")
    if re.search(r"\son[a-z]+\s*=", svg):
        raise DiagramError(f"{name}: the rendered SVG carries an event-handler attribute")


def build_view(project: str, view: str, mmd: Path, svg: str) -> dict:
    title, caption = read_meta(mmd)
    source = _body(mmd.read_text(encoding="utf-8"))
    nodes = sorted(nodes_of(svg), key=lambda n: _first_use(source, n["id"]))
    if not nodes:
        raise DiagramError(f"{mmd.name}: no node could be read back from the SVG")
    return {
        "project": project,
        "view": view,
        "title": title,
        "caption": caption,
        "nodes": nodes,
        "source_sha256": source_hash(mmd),
        "svg": svg,
    }


def render_view(project: str, view: str, mmd: Path) -> dict:
    svg_id = f"dg-{project}-{view}".replace("_", "-")
    svg = trim_numbers(stamp(render_svg(mmd, svg_id), svg_id))
    check_svg(svg, mmd.name)
    return build_view(project, view, mmd, svg)


# =============================================================================
# Public: docs/architecture/views -> SVGs beside them + server/diagrams/catalogue.json
# =============================================================================


def _catalogue_text(views: list[dict]) -> str:
    return json.dumps({"renderer": RENDERER, "views": views}, indent=1, ensure_ascii=False) + "\n"


def load_catalogue() -> dict:
    return json.loads(CATALOGUE.read_text(encoding="utf-8"))


def write_public() -> int:
    views = []
    for project, view, mmd in public_sources():
        built = render_view(project, view, mmd)
        mmd.with_suffix(".svg").write_text(built["svg"], encoding="utf-8", newline="\n")
        views.append(built)
        print(f"rendered {mmd.name} ({len(built['nodes'])} nodes)")
    CATALOGUE.write_text(_catalogue_text(views), encoding="utf-8", newline="\n")
    print(f"wrote {CATALOGUE.relative_to(REPO_ROOT)} ({len(views)} views)")
    return 0


def stale_public() -> list[str]:
    """Why the committed SVGs or catalogue no longer match the sources; empty when current."""
    problems = []
    try:
        catalogue = load_catalogue()
    except (OSError, ValueError) as exc:
        return [f"{CATALOGUE.name} unreadable: {exc}"]
    by_key = {(v["project"], v["view"]): v for v in catalogue.get("views", [])}
    sources = public_sources()
    keys = {(p, v) for p, v, _ in sources}
    for key in sorted(set(by_key) - keys):
        problems.append(f"{key[0]}-{key[1]}: in the catalogue but has no .mmd")
    for project, view, mmd in sources:
        entry = by_key.get((project, view))
        svg_file = mmd.with_suffix(".svg")
        if entry is None:
            problems.append(f"{mmd.name}: not in the catalogue")
            continue
        if entry.get("source_sha256") != source_hash(mmd):
            problems.append(f"{mmd.name}: changed since its SVG was rendered")
        if (entry["title"], entry["caption"]) != read_meta(mmd):
            problems.append(f"{mmd.name}: title or caption differs from the catalogue")
        if not svg_file.exists():
            problems.append(f"{svg_file.name}: missing")
        elif svg_file.read_text(encoding="utf-8").replace("\r\n", "\n") != entry["svg"]:
            problems.append(f"{svg_file.name}: differs from the catalogue's copy")
    return problems


# =============================================================================
# Private: <folder>/<project>/*.mmd -> SVGs + manifest.json in the same folder
# =============================================================================


def write_private(folder: Path) -> int:
    by_project: dict[str, list[dict]] = {}
    for project, view, mmd in private_sources(folder):
        built = render_view(project, view, mmd)
        mmd.with_suffix(".svg").write_text(built["svg"], encoding="utf-8", newline="\n")
        entry = {k: v for k, v in built.items() if k != "svg"}
        entry["svg_file"] = mmd.with_suffix(".svg").name
        by_project.setdefault(project, []).append(entry)
        print(f"rendered {project}/{mmd.name} ({len(built['nodes'])} nodes)")
    for project, views in by_project.items():
        manifest = folder / project / MANIFEST
        manifest.write_text(
            json.dumps({"renderer": RENDERER, "project": project, "views": views}, indent=1) + "\n",
            encoding="utf-8",
            newline="\n",
        )
        print(f"wrote {manifest}")
    return 0


def main(argv: list[str]) -> int:
    try:
        if argv[:1] == ["--check"]:
            problems = stale_public()
            for p in problems:
                print(p)
            if problems:
                print("Re-run: python tools/render_diagrams.py")
                return 1
            print("diagrams current")
            return 0
        if argv[:1] == ["--private"] and len(argv) == 2:
            return write_private(Path(argv[1]))
        if argv:
            print(__doc__)
            return 2
        return write_public()
    except DiagramError as exc:
        print(exc, file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
