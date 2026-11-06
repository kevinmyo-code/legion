"""The safety rails, enforced in code rather than in the skill's prose (phone-verify map, ticket 02).

A skill is read; a rail is run. An agent that skimmed the skill still cannot tap the vault's Show
through this CLI, because the refusal is here.

Three kinds of refusal:

- **Absolute.** No flag lifts them: anything that would put an ID vault value on the screen or the
  clipboard (ADR 0076), typing while the vault is up, and touching the phone's own Settings app.
  Uninstall, `pm clear` and wipes are not refused here because the CLI has no command that could do
  them, and there is no raw `shell` passthrough on purpose.
- **Confirmable.** A control whose words say it sends, pays, buys, deletes, forgets, ships or
  installs, and any tap while another app is in front. `--confirmed-by-kevin` lifts these. **An
  agent may pass it only after Kevin said yes to that exact action in chat**; the flag is recorded
  in the action log either way.
- **Paths.** Screenshots, dumps and logs hold Kevin's personal data, so they are written only under
  the gitignored `.phone-artifacts/` or outside the repo (the session scratchpad).
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path

PACKAGE = "com.kevin.legion"
VAULT_ACTIVITY = "com.kevin.legion/.ui.vault.VaultActivity"
REPO = Path(__file__).resolve().parents[2]
ARTIFACTS = REPO / ".phone-artifacts"

# Words that mean the tap does something to the world, not just to the screen. Matched as whole
# words in the control's own text, description and tag (tags split on - and _), and in its
# children's, because a Compose button's label is often a child Text.
DANGER_WORDS = (
    "send", "pay", "buy", "purchase", "order", "checkout",
    "delete", "remove", "forget", "erase", "wipe", "purge", "clear",
    "ship", "install", "uninstall", "update now",
    "call", "dial", "share", "publish", "submit", "transfer",
    "sign out", "disconnect", "revoke", "reset",
)
_DANGER_RE = re.compile(r"(?<![a-z])(" + "|".join(re.escape(w) for w in DANGER_WORDS) + r")(?![a-z])")

# The vault's reveal path: Show asks for the fingerprint and then shows the value, Copy puts it on
# the clipboard. Refused anywhere on the screen, vault or not, with no override.
REVEAL_WORDS = ("show", "copy", "reveal", "unmask")
_REVEAL_RE = re.compile(r"(?<![a-z])(" + "|".join(REVEAL_WORDS) + r")(?![a-z])")
REVEAL_TAGS = ("vault-show", "vault-copy")

# Inside the vault the only allowed tap is Back. The list of labels is readable from a dump or a
# screenshot; nothing else is touched (no Add, Edit, Delete, Save, Show, Copy).
VAULT_ALLOWED = ("back",)

# The phone's own Settings: never driven. "Phone settings" in LEGION's gear menu opens it.
SYSTEM_SETTINGS_PKGS = ("com.android.settings", "com.samsung.android.settings")
SYSTEM_SETTINGS_WORDS = ("phone settings",)


@dataclass
class Refusal(Exception):
    reason: str
    absolute: bool

    def __str__(self) -> str:
        how = (
            "No flag lifts this."
            if self.absolute
            else "Pass --confirmed-by-kevin only if Kevin said yes to this exact action in chat."
        )
        return f"REFUSED: {self.reason} {how}"


def _words(strings) -> str:
    return " ".join(re.sub(r"[-_]", " ", s) for s in strings).casefold()


def danger_word(strings) -> str | None:
    m = _DANGER_RE.search(_words(strings))
    return m.group(1) if m else None


def reveal_word(strings) -> str | None:
    m = _REVEAL_RE.search(_words(strings))
    return m.group(1) if m else None


def check_tap(words: list[str], focus: str, confirmed: bool) -> None:
    """Raise [Refusal] if a tap on a control carrying [words] is not allowed. [focus] is the
    resumed activity, `package/.Activity`."""
    in_vault = focus == VAULT_ACTIVITY
    tags = [w for w in words if w in REVEAL_TAGS]
    if tags:
        raise Refusal(f"'{tags[0]}' reveals or copies an ID vault value (ADR 0076).", absolute=True)
    if in_vault:
        joined = _words(words).strip()
        # Back's desc can sit on the button and on its icon too: every word must be an allowed one.
        if not words or not {w.strip().casefold() for w in words} <= set(VAULT_ALLOWED):
            raise Refusal(
                f"the ID vault is in front and the only tap allowed there is Back (got '{joined[:60]}'). "
                "Read the labels from a screenshot or dump instead.",
                absolute=True,
            )
        return
    rw = reveal_word(words)
    if rw and any("vault" in w.casefold() for w in words):
        raise Refusal(f"'{rw}' on a vault control reveals a value (ADR 0076).", absolute=True)
    pkg = focus.split("/", 1)[0]
    if pkg in SYSTEM_SETTINGS_PKGS or any(s in _words(words) for s in SYSTEM_SETTINGS_WORDS):
        raise Refusal("that is the phone's own Settings; system settings are never changed.", absolute=True)
    if confirmed:
        return
    if pkg and pkg != PACKAGE:
        raise Refusal(f"{pkg} is in front, not LEGION; this CLI drives LEGION only.", absolute=False)
    dw = danger_word(words)
    if dw:
        raise Refusal(f"the control says '{dw}', which acts on the world (send/pay/buy/delete/...).",
                      absolute=False)


def check_type(focus: str, confirmed: bool) -> None:
    if focus == VAULT_ACTIVITY:
        raise Refusal("the ID vault is in front; nothing is ever typed into it by an agent.", absolute=True)
    pkg = focus.split("/", 1)[0]
    if pkg in SYSTEM_SETTINGS_PKGS:
        raise Refusal("that is the phone's own Settings.", absolute=True)
    if not confirmed and pkg and pkg != PACKAGE:
        raise Refusal(f"{pkg} is in front, not LEGION.", absolute=False)


def check_say(focus: str) -> None:
    if focus == VAULT_ACTIVITY:
        raise Refusal("the ID vault is in front; leave it with `back` first.", absolute=True)


# A vault value never reaches the screen in this CLI's own flow (Show is refused, and the vault
# hides on stop), but a dump taken while Kevin left one showing would carry it as text. Anything
# number-like on the vault screen is masked before it is printed or written.
_SECRETISH = re.compile(r"[A-Za-z]?\d[\d\s\-]{3,}\d|[A-Z]\d{6,}[A-Z]?")


def redact_vault_text(s: str) -> str:
    return _SECRETISH.sub("[redacted]", s)


def artifact_path(p: str | Path | None, default_name: str) -> Path:
    """Where an artifact may be written. Inside the repo: only under `.phone-artifacts/`.
    Outside the repo (the session scratchpad): anywhere. Relative paths resolve under
    `.phone-artifacts/`, never the working directory, so a bare `shot.png` cannot land in a
    tracked folder."""
    if p is None:
        out = ARTIFACTS / default_name
    else:
        p = Path(p)
        out = p if p.is_absolute() else ARTIFACTS / p
    out = out.resolve()
    art = ARTIFACTS.resolve()
    repo = REPO.resolve()
    if _within(out, repo) and not _within(out, art):
        raise Refusal(
            f"{out} is inside the repo. Screenshots, dumps and logs hold Kevin's personal data and the "
            "repo is public: write under .phone-artifacts/ or the session scratchpad.",
            absolute=True,
        )
    out.parent.mkdir(parents=True, exist_ok=True)
    return out


def _within(p: Path, root: Path) -> bool:
    try:
        p.relative_to(root)
        return True
    except ValueError:
        return False
