"""Shared REST client, prompt fingerprint, and spend accounting for tools/evals/.

Talks to the real Gemini REST generateContent endpoint with function calling, using
GEMINI_API_KEY read from local.properties - the same shape as
.scratch/aspect-engine/research/clerk-prototype/clerk_prototype.py, which this harness
generalizes into five versioned suites (ticket .scratch/ai-craft/issues/01-eval-harness.md).
"""
from __future__ import annotations

import hashlib
import json
import urllib.error
import urllib.request
from pathlib import Path
from typing import Optional

# tools/evals/common.py -> parents[0]=evals, [1]=tools, [2]=repo root.
REPO_ROOT = Path(__file__).resolve().parents[2]
LOCAL_PROPERTIES = REPO_ROOT / "local.properties"
API_URL = "https://generativelanguage.googleapis.com/v1beta/models"

# Same model id as ai/SubAgent.kt's DEFAULT_MODEL (grepped 2026-08-23/24: SubAgent.kt:504,
# "gemini-3.5-flash-lite"). This is the model every sub-agent path in the app actually runs
# on, so it is the only model worth grading - a harness that graded a different model would
# be pinning results to prompt behaviour the app never exercises.
MODEL_FLASH = "gemini-3.5-flash-lite"

# The prompt surfaces this harness's suites exercise, listed explicitly rather than globbed
# so the fingerprint only moves when a file that actually shapes a graded suite changes:
#   - ai/AriaBrain.kt    CANNOT_CLAUSE, PROACTIVE_CLAUSE, ASSISTANT_FRAME, SHARED_INSTRUCTIONS
#   - ai/Personas.kt     ALFRED/DOROTHY clause text (tone_judge grades copy written in this register)
#   - service/EngineToolbox.kt   the six CRUD meta-tool declarations clerk_crud mirrors
PROMPT_SURFACE_FILES = [
    REPO_ROOT / "app/src/main/java/com/kevin/legion/ai/AriaBrain.kt",
    REPO_ROOT / "app/src/main/java/com/kevin/legion/ai/Personas.kt",
    REPO_ROOT / "app/src/main/java/com/kevin/legion/service/EngineToolbox.kt",
]


def read_key() -> str:
    if not LOCAL_PROPERTIES.exists():
        raise RuntimeError(
            f"{LOCAL_PROPERTIES} not found. Copy it from the main repo checkout "
            "(it is gitignored, not part of clone-and-run) before a real run."
        )
    text = LOCAL_PROPERTIES.read_text(encoding="utf-8")
    for line in text.splitlines():
        if line.startswith("GEMINI_API_KEY="):
            key = line.split("=", 1)[1].strip()
            if key:
                return key
    raise RuntimeError("GEMINI_API_KEY not found (or empty) in local.properties.")


def prompts_fingerprint() -> str:
    """sha256 over the exact bytes of PROMPT_SURFACE_FILES, concatenated in listed order.

    Any edit to CANNOT_CLAUSE, PROACTIVE_CLAUSE, ASSISTANT_FRAME, a Persona clause, or an
    EngineToolbox tool description moves this hash. A report's fingerprint pins its result to
    the exact prompt text it graded - a regression is a diff against a known-good fingerprint
    in a prior report, never a vibe.
    """
    h = hashlib.sha256()
    for p in PROMPT_SURFACE_FILES:
        h.update(p.read_bytes())
    return h.hexdigest()


class CallBudget:
    """Tracks and hard-caps total Gemini REST calls across one harness invocation.

    Spend discipline per the ticket: default cap ~150 calls/invocation, and a suite that
    would blow the budget must fail loudly before spending a cent, not partway through.
    """

    def __init__(self, cap: int):
        self.cap = cap
        self.used = 0

    def spend(self, n: int = 1) -> None:
        self.used += n
        if self.used > self.cap:
            raise RuntimeError(
                f"Call budget exceeded: {self.used} calls attempted, cap is {self.cap}. "
                "Pass --max-calls to raise it if this run is intentional, or run fewer "
                "suites/fewer --runs."
            )


class GeminiClient:
    """Thin REST wrapper. Mirrors ai/SubAgent.kt's shape closely enough to be a fair
    reliability probe: generateContent, function calling via `tools`, and
    toolConfig.functionCallingConfig.mode = NONE to force a tool-free final answer
    (SubAgent.investigate's own force-answer-on-last-round move)."""

    def __init__(self, api_key: str, budget: CallBudget):
        self.api_key = api_key
        self.budget = budget

    def generate(
        self,
        model: str,
        contents: list,
        system_instruction: Optional[str] = None,
        tools: Optional[list] = None,
        force_text: bool = False,
    ) -> dict:
        body: dict = {"contents": contents}
        if system_instruction:
            body["systemInstruction"] = {"parts": [{"text": system_instruction}]}
        if tools:
            body["tools"] = [{"functionDeclarations": tools}]
        if force_text:
            body["toolConfig"] = {"functionCallingConfig": {"mode": "NONE"}}
        url = f"{API_URL}/{model}:generateContent?key={self.api_key}"
        data = json.dumps(body).encode("utf-8")
        req = urllib.request.Request(
            url, data=data, headers={"Content-Type": "application/json"}, method="POST"
        )
        self.budget.spend(1)
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            return {"_http_error": e.code, "_body": e.read().decode("utf-8", errors="replace")}


def extract_calls(resp: dict) -> list:
    cand = (resp.get("candidates") or [{}])[0]
    parts = (cand.get("content") or {}).get("parts") or []
    return [p["functionCall"] for p in parts if "functionCall" in p]


def extract_text(resp: dict) -> Optional[str]:
    cand = (resp.get("candidates") or [{}])[0]
    parts = (cand.get("content") or {}).get("parts") or []
    text = "".join(p.get("text", "") for p in parts if "text" in p)
    return text or None
