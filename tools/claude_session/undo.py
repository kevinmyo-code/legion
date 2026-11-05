r"""Undo the last ship (map alfred-builds, ticket 05). "Alfred, undo the last update."

    python undo.py ask --task 50
    python undo.py run --task 51 --token <token from the yes>

`ask` (no token, sends nothing) finds the newest ship in `work/ships.jsonl` that has not been
undone, writes `undo-plan.json` into the task's work folder, and prints the question on its last
line: "Undo <what shipped>? <what that means>." The helper reports `needs_confirmation` with it.

`run` needs Kevin's yes token (`common.find_token`, one token, one undo) and refuses if another
ship landed after the question. Then:

- **engine:** Cloud Run traffic goes back to the revision that served before the ship
  (`gcloud run services update-traffic legion --to-revisions <prev>=100`), `/health` is checked,
  and a revert commit of the ship's merge lands on dev (and main, which mirrors dev).
  `ship.py`'s next deploy moves traffic to the new revision itself, so the pin does not stick.
- **phone:** Android never downgrades, so the previous phone ship's commit is built again
  (`assembleFast`; its versionCode comes from the build time, so it is new) and published with
  `release_apk.py --notes "undo of <x>"`. The phone then asks to install it.
- **content:** the book leaves the household library (`DELETE /api/books/library/<id>`).

The undo is appended to `work/ships.jsonl` (`kind: undo`, `undoes: <ship id>`), so the next
"undo" reaches one ship further back. The revert is not re-tested: it puts back code that was
already tested and shipped, and an undo is the fast path out of a bad ship. Stdlib only.
"""
from __future__ import annotations

import argparse
import sys
import uuid
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from common import Paths, TokenRefused, find_token, load_config, read_json, write_json_atomic  # noqa: E402
from ship import Refused, Shipper, World, read_ships  # noqa: E402

PLAN_NAME = "undo-plan.json"


def undoable(ships: list[dict]) -> list[dict]:
    """Ships not yet undone, oldest first."""
    undone = {r.get("undoes") for r in ships if r.get("kind") == "undo"}
    return [r for r in ships if r.get("kind") != "undo" and r.get("id") not in undone]


def previous_phone_ship(ships: list[dict], target: dict) -> dict | None:
    live = undoable(ships)
    before = live[:live.index(target)] if target in live else []
    for r in reversed(before):
        if (r.get("release") or {}).get("built_from"):
            return r
    return None


def describe_undo(target: dict, ships: list[dict]) -> str:
    parts = []
    if target.get("revision"):
        parts.append(f"the engine goes back to revision {target['revision']['previous']}")
    if target.get("release"):
        prev = previous_phone_ship(ships, target)
        parts.append("your phone gets the previous build again as a new update" if prev else
                     "there is no earlier phone build to go back to, so only the code is reverted")
    if target.get("commits"):
        parts.append("the change is reverted on dev")
    if target.get("kind") == "content":
        parts.append(f"{(target.get('book') or {}).get('title', 'the book')} leaves your Books")
    return (", ".join(parts[:-1]) + " and " + parts[-1]) if len(parts) > 1 else (parts[0] if parts else "")


class Undoer(Shipper):
    def plan_path(self, task) -> Path:
        return self.paths.task_work(task) / PLAN_NAME

    def ask(self, a) -> str:
        ships = read_ships(self.paths)
        live = undoable(ships)
        if not live:
            raise Refused("there is no ship to undo")
        target = live[-1]
        write_json_atomic(self.plan_path(a.task), {"task": a.task, "target": target["id"],
                                                   "at": self.world.now()})
        what = describe_undo(target, ships)
        return f"Undo {target['notes'].rstrip('.')}? {what[:1].upper() + what[1:]}." if what else \
            f"Undo {target['notes'].rstrip('.')}?"

    def run(self, a) -> str:
        try:
            tok_path, tok = find_token(self.paths, a.task, a.token, self.world.now(), "undo_used")
        except TokenRefused as e:
            raise Refused(f"{e}; nothing was undone") from None
        if tok.get("kind") != "yes":
            raise Refused("only Kevin's yes to the undo question can undo; nothing was undone")
        plan = None
        for t in (a.task, tok.get("parent")):
            if t is not None:
                plan = read_json(self.plan_path(t), None) or plan
                if plan:
                    break
        if not plan:
            raise Refused("no undo question was asked for this task; run undo.py ask first")
        ships = read_ships(self.paths)
        live = undoable(ships)
        if not live or live[-1]["id"] != plan["target"]:
            raise Refused("another ship or undo landed since the question; ask again. Nothing "
                          "was undone")
        target = live[-1]
        tok["undo_used"] = True
        write_json_atomic(tok_path, tok)
        record = {"id": f"{a.task}-{uuid.uuid4().hex[:8]}", "kind": "undo", "task": a.task,
                  "undoes": target["id"], "notes": f"undo of {target['notes']}",
                  "at": self.world.now()}
        said, problems = [], []
        if target.get("revision"):
            record["revision"] = self.restore_revision(target["revision"])
            said.append(f"the engine is back on {record['revision']['to']}")
        wt = None
        if target.get("commits"):
            wt = self.undo_worktree(a.task)
            try:
                record["revert"] = self.revert(wt, target)
                said.append("the change is reverted on dev")
            except Refused as e:
                problems.append(str(e))
        if target.get("release"):
            prev = previous_phone_ship(ships, target)
            if prev is None:
                problems.append("there is no earlier phone build to go back to")
            else:
                try:
                    wt = wt or self.undo_worktree(a.task)
                    commit = prev["release"]["built_from"]
                    self.git(wt, "checkout", "--detach", commit)
                    self.copy_local_files(wt)
                    self.preflight("phone", wt)
                    record["release"] = self.publish_phone(a.task, wt, target["notes"],
                                                           label=f"undo of {target['notes']}")
                    record["release"]["built_from"] = commit
                    said.append(f"phone update {record['release']['version_code']} puts back the "
                                "previous build; your phone will ask to install it")
                except Refused as e:
                    problems.append(f"the phone was not rolled back: {e}")
        if target.get("kind") == "content":
            try:
                self.remove_book(target.get("book") or {})
                said.append(f"{(target.get('book') or {}).get('title', 'the book')} is out of your Books")
            except Refused as e:
                problems.append(str(e))
        self.append(record)
        text = f"Undid {target['notes'].rstrip('.')}: " + ", ".join(said) + "." if said else \
            f"Nothing of {target['notes']} could be undone."
        if problems:
            text += " But " + "; ".join(problems) + "."
        return text

    def restore_revision(self, rev: dict) -> dict:
        prev = rev.get("previous")
        if not prev:
            raise Refused("the ship did not record the revision before it, so the engine was not "
                          "moved")
        code, out = self.world.run(["gcloud", "run", "services", "update-traffic", self.service,
                                    "--region", self.region, "--project", self.project,
                                    "--to-revisions", f"{prev}=100"], env=self.gcloud_env(),
                                   timeout=300)
        if code != 0:
            raise Refused(f"Cloud Run refused to move traffic back to {prev}: {out.strip()[-200:]}. "
                          "Nothing was undone")
        self.health(rev.get("url") or (self.describe().get("status") or {}).get("url", ""))
        return {"from": rev.get("new"), "to": prev}

    def undo_worktree(self, task) -> Path:
        wt = self.paths.task_work(task) / "undo-wt"
        if not (wt / ".git").exists():
            wt.parent.mkdir(parents=True, exist_ok=True)
            self.git(self.repo, "fetch", "origin")
            self.git(self.repo, "worktree", "add", "--detach", str(wt), "origin/dev")
        else:
            self.git(wt, "fetch", "origin")
        return wt

    def revert(self, wt: Path, target: dict) -> dict:
        merge = target["commits"]["dev_merge"]
        self.git(wt, "checkout", "--detach", "origin/dev")
        code, _ = self.world.run(["git", "-C", str(wt), "revert", "--no-edit", "-m", "1", merge])
        if code != 0:
            self.world.run(["git", "-C", str(wt), "revert", "--abort"])
            raise Refused("the revert commit conflicts with later work on dev, so dev is "
                          "unchanged; the code needs reverting by hand")
        code, _ = self.world.run(["git", "-C", str(wt), "push", "origin", "HEAD:refs/heads/dev"])
        if code != 0:
            raise Refused("pushing the revert to dev was refused; dev is unchanged")
        dev = self.sha(wt, "HEAD")
        self.git(wt, "checkout", "--detach", "origin/main")
        code, _ = self.world.run(["git", "-C", str(wt), "merge", "--ff-only", dev])
        if code != 0:
            self.git(wt, "merge", "--no-ff", dev, "-m", "Merge dev into main")
        code, _ = self.world.run(["git", "-C", str(wt), "push", "origin", "HEAD:refs/heads/main"])
        if code != 0:
            raise Refused("the revert is on dev, but pushing main was refused")
        return {"dev": dev, "main": self.sha(wt, "HEAD")}

    def remove_book(self, book: dict) -> None:
        if not book.get("id"):
            raise Refused("the ship did not record the book's id, so it was not removed")
        base = self.cfg.get("engine_url", "").rstrip("/")
        status, _ = self.world.http("DELETE", f"{base}/api/books/library/{book['id']}",
                                    self.engine_headers())
        if status not in (200, 204, 404):
            raise Refused(f"the library refused to remove the book (HTTP {status})")


def main(argv: list[str] | None = None, paths: Paths | None = None, world: World | None = None,
         say=print) -> int:
    ap = argparse.ArgumentParser(prog="undo.py", description=__doc__.splitlines()[0])
    sub = ap.add_subparsers(dest="mode", required=True)
    ask = sub.add_parser("ask")
    ask.add_argument("--task", type=int, required=True)
    run = sub.add_parser("run")
    run.add_argument("--task", type=int, required=True)
    run.add_argument("--token", required=True)
    a = ap.parse_args(sys.argv[1:] if argv is None else argv)
    paths = paths or Paths()
    cfg = load_config(paths)
    if cfg.get("scope") != "full":
        say("Not undone: only the Alienware ships and undoes.")
        return 1
    undoer = Undoer(paths, cfg, world or World(paths, cfg), say)
    try:
        if a.mode == "ask":
            q = undoer.ask(a)
            say("Question for Kevin (report it with --status needs_confirmation --question):")
            say(q)
        else:
            say(undoer.run(a))
    except Refused as e:
        say(f"Not undone: {e}.")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
