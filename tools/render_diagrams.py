"""Render the architecture diagrams (`show_diagram`): `.mmd` in, SVG and catalogue out.

    python tools/render_diagrams.py                     # render docs/architecture/views + catalogue
    python tools/render_diagrams.py --check             # exit 1 if an SVG or catalogue is stale
    python tools/render_diagrams.py --private <folder>  # render a private folder outside the repo

Needs Node (npx) and Chrome; `--check` needs neither. The renderer itself lives in
`server/diagrams/render.py` so the server's tests can import it; this is the repo-root entry point.
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "server" / "diagrams"))

import render  # noqa: E402

if __name__ == "__main__":
    sys.exit(render.main(sys.argv[1:]))
