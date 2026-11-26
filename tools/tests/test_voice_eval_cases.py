"""The committed voice-eval case files' own shape: free, no key, no Gradle, no network.

`tools/tests/test_voice_eval.py` covers the scorer. This file covers the DATA the scorer reads, and
the prose that quotes it, so a wrong count or a mislabelled case fails on every PR instead of at the
next paid run. Run from the repo root:

    python -m pytest tools/tests/test_voice_eval_cases.py -p no:cacheprovider
"""

from __future__ import annotations

import json
import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
CASES = REPO / "tools" / "voice_eval" / "cases.json"
GATE = REPO / "tools" / "voice_eval" / "gate_cases.txt"
GATES_YML = REPO / ".github" / "workflows" / "gates.yml"


def gate_ids() -> list[str]:
    lines = GATE.read_text(encoding="utf-8").splitlines()
    return [x.strip() for x in lines if x.strip() and not x.strip().startswith("#")]


def test_the_gates_header_states_the_number_of_cases_the_gate_list_holds():
    """2026-11-25: the header said 36 while the list held 38, and so did the public note.

    A count in prose rots silently. This is the check that replaces remembering.
    """
    header = GATES_YML.read_text(encoding="utf-8")
    stated = re.search(r"The phone eval runs the (\d+) named\s*#\s*cases once", header)
    assert stated, "gates.yml no longer states how many cases the phone eval runs"
    assert int(stated.group(1)) == len(gate_ids()), (
        f"gates.yml says {stated.group(1)} cases; gate_cases.txt holds {len(gate_ids())}"
    )


def test_every_gate_id_is_a_committed_case():
    """The Kotlin offline test asserts this too; here it costs no Gradle run."""
    ids = set()
    for f in ("cases.json", "reply_length.json"):
        data = json.loads((REPO / "tools" / "voice_eval" / f).read_text(encoding="utf-8"))
        ids |= {c["id"] for c in data["cases"]}
    missing = [i for i in gate_ids() if i not in ids]
    assert not missing, f"gate_cases.txt names ids no case file has: {missing}"
    assert len(gate_ids()) == len(set(gate_ids())), "an id is listed twice in gate_cases.txt"
