"""The locked safety text (ruling 3, 2026-11-17): code, never a spec, never per agent.

Every agent's system prompt starts with `LOCKED`, before its own spec prompt, so no spec can drop
or reword it. It is the web assistant's shared clauses, already generated from the phone's own
Kotlin twins and pinned by `tests/test_assistant_prompt.py` (`assistant/shared_clauses.py`): the
assistant frame, the honesty clause (`CANNOT_CLAUSE`, ADR 0031), record facts, currency, an
unreadable read is not an empty one, and the crisis clause. The phone keeps its crisis pre-check
and the confirm-gate UI (ruling 3); the confirm gate itself is code (`agents/legion/guards.py`).

Not found as named clauses on the phone, so not copied: the plan's ASK_BEFORE and NO_PASSWORDS.
Asking before a write is the confirm guard in code; passwords and ID numbers have no tool at all
(ADR 0076).
"""

from __future__ import annotations

from assistant.prompt import MEMORY_CLAUSE, web_shared_clauses

LOCKED: str = (web_shared_clauses() + MEMORY_CLAUSE).strip()
