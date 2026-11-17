"""LEGION's agent specs: one YAML file and one prompt file per agent (plan section 13.4).

`load_specs()` reads `agents/specs/*.yaml`, validates each one and returns `AgentSpec`s:
- every tool exists in `engine_mcp.tools.TOOLS_BY_NAME` or is one of the engine twins
  (`agents/legion/twins.py`); relay tools are empty until the limb contract (stage 3);
- the aspects are among CLAUDE.md section 1's seven;
- read-through (third-party) tools appear only in a spec with `taint_ok: true`;
- the content hash of the YAML and its prompt matches `specs.lock`, so a spec changed without a
  version bump (and a lock update) fails the suite rather than shipping unnoticed.

Only `generalist` exists in this stage: today's engine tool surface (the web assistant's whole
registry, `assistant.surface.WEB_TOOL_NAMES`) plus the two read twins. The specialists and the
router are stage 5.
"""

from __future__ import annotations

import hashlib
import json
from functools import cache
from pathlib import Path

import yaml

from agents.legion.twins import TWINS
from agents.runtime.budget import LoopLimits
from agents.runtime.spec import AgentSpec
from assistant.read_through import THIRD_PARTY_TOOLS
from engine_mcp.tools import TOOLS_BY_NAME

SPECS_DIR = Path(__file__).resolve().parent.parent / "specs"
LOCK = SPECS_DIR / "specs.lock"
ASPECTS = frozenset({"fleet", "ledger", "body", "dates", "notes", "pantry", "places"})
_LIMIT_KEYS = {f for f in LoopLimits.__dataclass_fields__}


class SpecError(ValueError):
    pass


def content_hash(yaml_path: Path) -> str:
    raw = yaml.safe_load(yaml_path.read_text(encoding="utf-8"))
    prompt = (SPECS_DIR / raw["prompt"]).read_bytes()
    digest = hashlib.sha256()
    digest.update(yaml_path.read_bytes().replace(b"\r\n", b"\n"))
    digest.update(b"\0")
    digest.update(prompt.replace(b"\r\n", b"\n"))
    return digest.hexdigest()


def lock_file() -> dict:
    return json.loads(LOCK.read_text(encoding="utf-8")) if LOCK.exists() else {}


def write_lock() -> dict:
    """Recompute `specs.lock` (run after a deliberate spec change and a version bump)."""
    lock = {}
    for path in sorted(SPECS_DIR.glob("*.yaml")):
        raw = yaml.safe_load(path.read_text(encoding="utf-8"))
        lock[raw["name"]] = {"version": raw["version"], "sha256": content_hash(path)}
    LOCK.write_text(json.dumps(lock, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return lock


def tool_exists(name: str) -> bool:
    return name in TOOLS_BY_NAME or name in TWINS


def parse(path: Path, lock: dict | None = None) -> AgentSpec:
    raw = yaml.safe_load(path.read_text(encoding="utf-8"))
    name = raw.get("name")
    if not name or path.stem != name:
        raise SpecError(f"{path.name}: `name` must equal the file name")
    tools_block = raw.get("tools") or {}
    engine = tuple(tools_block.get("engine") or ())
    relay = tuple(tools_block.get("relay") or ())
    if relay:
        raise SpecError(f"{name}: relay tools need the limb contract (stage 3)")
    unknown = sorted(t for t in engine if not tool_exists(t))
    if unknown:
        raise SpecError(f"{name}: no such engine tool: {unknown}")
    if len(set(engine)) != len(engine):
        raise SpecError(f"{name}: a tool is listed twice")
    aspects = tuple(raw.get("aspects") or ())
    if not set(aspects) <= ASPECTS:
        raise SpecError(f"{name}: aspects must be among {sorted(ASPECTS)}")
    taint_ok = bool(raw.get("taint_ok", False))
    third = sorted(set(engine) & THIRD_PARTY_TOOLS)
    if third and not taint_ok:
        raise SpecError(f"{name}: read-through tools {third} need `taint_ok: true`")
    writes = raw.get("writes", "allowed")
    if writes not in ("allowed", "none"):
        raise SpecError(f"{name}: `writes` is allowed or none")
    confirm = frozenset(raw.get("confirm") or ())
    if not confirm <= set(engine):
        raise SpecError(f"{name}: `confirm` names a tool the spec does not have")
    limits = raw.get("limits") or {}
    if set(limits) - _LIMIT_KEYS:
        raise SpecError(f"{name}: unknown limits {sorted(set(limits) - _LIMIT_KEYS)}")
    prompt_path = SPECS_DIR / raw["prompt"]
    digest = content_hash(path)
    lock = lock_file() if lock is None else lock
    pinned = lock.get(name) or {}
    if pinned.get("sha256") != digest or pinned.get("version") != raw.get("version"):
        raise SpecError(
            f"{name}: specs.lock does not match this spec (bump `version`, then run "
            f"`python -c 'from agents.legion.specs import write_lock; write_lock()'`)"
        )
    return AgentSpec(
        name=name,
        version=int(raw["version"]),
        model=raw["model"],
        prompt=prompt_path.read_text(encoding="utf-8").strip(),
        tools=engine,
        writes_allowed=writes == "allowed",
        confirm=confirm,
        taint_ok=taint_ok,
        limits=LoopLimits(**limits),
        aspects=aspects,
        router_terms=tuple((raw.get("router") or {}).get("terms") or ()),
        content_hash=digest,
    )


@cache
def load_specs() -> dict[str, AgentSpec]:
    lock = lock_file()
    specs = {p.stem: parse(p, lock) for p in sorted(SPECS_DIR.glob("*.yaml"))}
    if "generalist" not in specs:
        raise SpecError("the generalist spec is required")
    return specs


def spec(name: str) -> AgentSpec:
    return load_specs()[name]
