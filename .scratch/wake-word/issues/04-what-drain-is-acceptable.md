---
map: wake-word
ticket: "04"
title: "What drain is acceptable, and what happens when it is not met?"
type: grilling
status: open
status-detail: ""
blockers: ["03"]
blocked-by: ["[[03-measure-the-battery-cost]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# What drain is acceptable, and what happens when it is not met?

## Question

With a real number in hand from [What always-on Vosk actually costs the A25 in a day](03-measure-the-battery-cost.md),
Kevin decides the scope he actually wanted.

The forks, to be put to him one at a time:

1. **Is the measured cost acceptable for always-on?** If yes, most of this map collapses and the
   remaining work is proving the trigger. If no, the next questions matter.
2. **If it is too expensive, what is the fallback?** Charging-only, drive-detected-only, a scheduled
   window, or a duty cycle that listens intermittently and accepts a slower trigger.
3. **Who decides, Kevin or the app?** A fixed rule he sets once, versus the app standing down on its
   own when the battery is low. The second is friendlier and is also the kind of silent behaviour
   change that makes a feature feel broken when it does not fire.
4. **What does the app owe him when it stands down?** Saying nothing means "hey <name>" silently
   stops working, which is the exact class of failure `CLAUDE.md` sec 7's outcome-verb rule exists
   to forbid on the speaking side.

Do not pre-answer these. The measurement may make several of them moot.
