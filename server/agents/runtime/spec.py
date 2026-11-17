"""An agent spec as the runtime sees it (plan section 13.4): a name, a model, a prompt, a tool
allowlist, whether it may write, which tools need a yes first, and its limits. Where specs come
from (YAML files, validation against a tool registry) is the caller's business."""

from __future__ import annotations

from dataclasses import dataclass, field

from agents.runtime.budget import LoopLimits


@dataclass(frozen=True)
class AgentSpec:
    name: str
    version: int
    model: str
    prompt: str
    tools: tuple[str, ...]
    writes_allowed: bool = True
    confirm: frozenset[str] = frozenset()
    # Only a spec with this set may hold read-through (third-party text) tools.
    taint_ok: bool = False
    limits: LoopLimits = field(default_factory=LoopLimits)
    aspects: tuple[str, ...] = ()
    router_terms: tuple[str, ...] = ()
    content_hash: str = ""

    @property
    def ref(self) -> str:
        return f"{self.name}@{self.version}"
