---
map: alfred-builds
ticket: "03"
title: "The ship step"
type: build
status: built
status-detail: "built 2026-11-05 on feat/alfred-builds-worker; owed: worker restart, engine deploy, the run (ticket 09)"
blockers: ["alfred-ota 04"]
blocked-by: ["[[04-worker-skills]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# The ship step

## What
- **`tools/claude_session/ship.py --task N --branch B --kind engine|phone|both|content`**, run by a worker helper only after the task's confirmation token (Kevin's yes):
  1. Fetch; rebase B on origin/dev. On conflict, stop and report in words; nothing ships.
  2. Run the targeted tests again, plus the server suite or phone JVM suite for what changed.
  3. Merge into dev, push dev, merge dev into main, push main (CLAUDE.md branching).
  4. engine: run `deploy/cloudrun/deploy.py` with the project env, then wait for the new revision and smoke-test `/api/health`.
  5. phone: `assembleFast`, then `tools/release_apk.py --notes "<one line>"`. The phone's updater asks Kevin to install.
  6. Report the revision name and/or release version.
- **The ship question.**
  - The helper reports `needs_confirmation` with "Ready: <one line>. Ship it?", plus "This also changes <X>" when the diff touches a guarded area (ruling 4).
  - Kevin's yes is the token.
- **Never ships red.** Tests failing means `failed` with the failing test names in words.
## Verification
- `tools/tests` with a fake git remote, a fake deploy and a fake publisher: order of steps, a conflict stops, red stops, the token is required.
