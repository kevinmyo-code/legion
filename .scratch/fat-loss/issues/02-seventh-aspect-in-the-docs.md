---
map: fat-loss
ticket: "02"
title: "Body is the seventh aspect, in the docs"
type: build
status: resolved
status-detail: "CLAUDE.md section 1, ADR 0077, decisions.md, glossary and cutover5 corrected 2026-11-05; docs_check shows only the two pre-existing ADR 0061 paths"
blockers: ["01"]
blocked-by: ["[[01-kevins-rulings]]"]
tags: [ticket]
---
# Body is the seventh aspect, in the docs

Ruling 1 (*"10 ok"*). CLAUDE.md section 1 said six domains and that a seventh is a ruling, not a
refactor. This is that ruling.

- CLAUDE.md section 1's aspect list gains **body**, with the date and Kevin.
- ADR 0077 records it; `memory/library/decisions.md` gets the dated entry.
- The glossary and the cutover doc lines that say six (or list six) are corrected.
- `python tools/docs_check.py` passes.

## Verification

- `docs_check.py` green; `obsidian_sync.py --check` clean.
