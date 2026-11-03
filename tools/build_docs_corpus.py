"""Rebuild `server/assistant/docs_corpus.json`, the `legion_docs` tool's corpus.

    python tools/build_docs_corpus.py          # write
    python tools/build_docs_corpus.py --check  # exit 1 if the committed copy is stale

Run it after editing README.md, server/README.md, docs/glossary.md,
docs/architecture/*.md or an ADR. The generator itself lives in
`server/assistant/gen_docs_corpus.py` so the server's tests can import it;
this is the repo-root entry point.
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "server" / "assistant"))

import gen_docs_corpus  # noqa: E402

if __name__ == "__main__":
    sys.exit(gen_docs_corpus.main(sys.argv[1:]))
