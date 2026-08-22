"""Find decisions that were made and then quietly forgotten.

CLAUDE.md sec 12 already carries the rule: *a resolved decision ticket must leave a BUILD ticket
behind, created at resolution time.* The reason is mechanical rather than tidy - the wiki and the
board list only OPEN tickets, so resolving a decision makes it VANISH, and a fully-decided,
entirely unbuilt feature then looks exactly like finished work.

The rule was written after that happened once. On 2026-08-22 it happened again: hands-and-senses
ticket 05 ("Comms: place a call, send a text") was resolved on 2026-08-21 with a full ruling -
calls yes, texts never, contacts plus spoken digits, read the target back, never guess a partial
match - and left no build ticket. Kevin asked whether he could place a call by voice, and the
honest answer was that nothing had been built and nothing on the board said so.

A rule nobody can check is a hope. This is the check.

A decision ticket has paid its debt when some other ticket in the same map names it as a blocker.
A decision that authorises no code at all opts out explicitly with `no-build-needed: true` in its
frontmatter - explicitly, so the opt-out is a decision someone made and not an absence nobody
noticed.

**Baselined on adoption, deliberately.** Run raw, this check named 70-odd resolved decisions, and
almost all of them were genuinely built - just built directly, without a build ticket in between,
back when the rule did not exist. A check that cries wolf 70 times gets ignored, which is worse
than no check at all. So `decision_debt_baseline.txt` records what was already outstanding the day
this was written, and only decisions resolved AFTER that fail the build.

Clearing the baseline is optional archaeology. Preventing the next miss is the point.

    python tools/decision_debt.py            # report, exit 1 if anything NEW is owed
    python tools/decision_debt.py --all      # ignore the baseline, show everything
    python tools/decision_debt.py --baseline # rewrite the baseline from today's state
"""
import glob
import io
import re
import sys

DECISION_TYPES = {"grilling", "decision"}
RESOLVED = {"resolved", "closed"}


def field(text, key):
    m = re.search(r"^" + key + r":\s*(.*)$", text, re.M)
    return m.group(1).strip().strip('"') if m else ""


def load():
    tickets = []
    for path in glob.glob(".scratch/*/issues/*.md"):
        head = io.open(path, encoding="utf-8", errors="replace").read()[:1200]
        if not head.startswith("---"):
            continue
        tickets.append({
            "path": path.replace("\\", "/"),
            "map": field(head, "map"),
            "num": field(head, "ticket"),
            "type": field(head, "type"),
            "status": field(head, "status"),
            "title": field(head, "title"),
            "blockers": re.findall(r'"(\d+)"', field(head, "blockers")),
            "optout": field(head, "no-build-needed") == "true",
        })
    return tickets


def orphans(tickets):
    out = []
    for t in tickets:
        if t["type"] not in DECISION_TYPES or t["status"] not in RESOLVED or t["optout"]:
            continue
        # Paid if anything in the same map names it as a blocker.
        if any(o["map"] == t["map"] and t["num"] in o["blockers"] for o in tickets):
            continue
        out.append(t)
    return out


BASELINE = "tools/decision_debt_baseline.txt"


def read_baseline():
    try:
        return {l.strip() for l in io.open(BASELINE, encoding="utf-8") if l.strip() and not l.startswith("#")}
    except IOError:
        return set()


def main():
    quiet = "--quiet" in sys.argv
    owed = orphans(load())
    if "--baseline" in sys.argv:
        header = [
            "# Decisions already outstanding when tools/decision_debt.py was adopted, 2026-08-22.",
            "# Almost all were genuinely built, just built without a build ticket in between.",
            "# Nothing here fails the check. Anything resolved AFTER today does.",
        ]
        lines = header + sorted(t["path"] for t in owed)
        io.open(BASELINE, "w", encoding="utf-8", newline=chr(10)).write(chr(10).join(lines) + chr(10))
        print("baseline written: %d entries" % len(owed))
        return 0
    if "--all" not in sys.argv:
        base = read_baseline()
        owed = [t for t in owed if t["path"] not in base]
    if not owed:
        if not quiet:
            print("no decision debt: every resolved decision left a build ticket or opted out")
        return 0
    if not quiet:
        print("DECISION DEBT - resolved, but nothing on the board carries the work:\n")
        for t in sorted(owed, key=lambda x: (x["map"], x["num"])):
            print("  %-26s %3s  %s" % (t["map"], t["num"], t["title"]))
            print("  %-26s      %s" % ("", t["path"]))
        print("\nEach needs a build ticket naming it as a blocker, or `no-build-needed: true`")
        print("in its frontmatter if the decision authorises no code.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
