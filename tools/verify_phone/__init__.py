"""Scripted device verification: named suites of checks run on the real phone after an update.

    python tools/verify_phone list                      the suites and their checks
    python tools/verify_phone run next2                 run a suite on the connected phone
    python tools/verify_phone run next2 --dry-run       the same suite against the in-repo fake phone
    python tools/verify_phone stale --base origin/dev   checks whose app code changed since <base>

Why (Kevin, 2026-11-22, "lets do all of it": create verification / maintain verification): the
post-update checklists he ran by hand become checks that drive the real app and read what it really
did. A check passes only on something observable on the phone (a card's words on screen, the
assistant's own LIVE_AUDIT tool lines, LEGION's own log tags), never on a unit test.

Each check is setup, act, verify, cleanup (`runner.Check`). Cleanup always runs and says whether the
test item is gone. A locked phone stops the run. Input is text, never the laptop speaker: assistant
turns go through the app's test-turn door (`service/testturn/`, adb shell only, the Settings switch
"Allow test turns from a computer" must be on), screen reads and taps through phonectl and its guard.
A check that needs a real voice is marked manual with what to do.

Output: a pass/fail table on stdout and a JSON log under ~/.legion/reports/verify/ (outside the
repo; it records statuses, step names and tool names, never screen text or what was said).

Maintaining it: every check names the app files and symbols it depends on (`Check.deps`);
`stale` lists the checks a branch diff may have made stale. CI runs `stale` as a listing only.
"""
