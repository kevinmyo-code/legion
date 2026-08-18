---
map: android-auto
ticket: 08
title: "What is in the browse tree, and who writes the briefings?"
type: grilling
status: open
status-detail: ""
blockers: ["02", "03"]
blocked-by: ["[[02-what-a-sideloaded-media-app-needs]]", "[[03-does-voice-search-still-deliver-a-raw-query]]"]
open-blockers: 0
ready: true
tags: [ticket]
---
# What is in the browse tree, and who writes the briefings?

## Question

The media browse tree is the door (settled decision 2) and the tap-to-hear surface. Ticket 02 gives
the depth and item-count limits Android Auto enforces while moving; ticket 03 says whether
`onPlayFromSearch` gives a usable third leg. This ticket decides what the tree actually contains.

Decide:

1. **The root's items.** LEGION has six domains (fleet, ledger, pantry, body, notes/lists/calendar,
   plus goals/advisors) and 69 tools. The tree cannot mirror that. What earns a root slot? A
   plausible floor: "Talk to LEGION" (the call trigger), "Today", "Fleet". Kevin picks.
2. **Depth.** One flat list, or browsable nodes (Fleet -> per-vehicle)? Every level is a tap while
   driving. Ticket 02 sharpened the constraint: the root is **browsable-only, roughly four tabs**,
   the real limit arrives at runtime in `BROWSER_ROOT_HINTS_KEY_ROOT_CHILDREN_LIMIT`, violations
   **degrade silently** rather than reject ("some root content might be dropped"), and
   `CarUxRestrictions` is AAOS-only so **LEGION cannot ask how restricted it currently is**. Design
   for the limit rather than discovering it.
3. **The load-bearing one: are briefings deterministic text or LLM-generated?**
   - **Deterministic** - built from the existing controllers and `advisor/`'s five digest builders,
     rendered to speech locally. Free, instant, offline, identical every time, and it cannot invent.
   - **LLM** - a `SubAgent` one-shot per tap. Warmer, adapts, and costs a Gemini call on **Kevin's
     own key** every time he taps a list item in the car, with latency before any sound comes out.
   CLAUDE.md §7 makes tool-count-as-prompt-tokens an explicit cost concern and the advisors are
   already pull-only; a briefing that fires a model call on a *tap* is a new cost shape. It also
   brushes §4 rule 5: anything spoken as fact must be anchored, and a generated briefing over
   ledger figures is exactly where an unlabelled estimate could slip in.
4. **Does the tree change while driving?** Static nodes are simplest. Dynamic titles ("Fleet - 1
   warning") are more useful and mean pushing `notifyChildrenChanged` from live data.
5. **What a briefing item does when tapped**: speaks and stops, or speaks and leaves the session open
   for a follow-up question (which drags in ticket 10's lifecycle).
6. **`onPlayFromSearch` handling**, given ticket 03's answer: does a spoken query start a call and
   feed the query in as the first turn, or get answered one-shot without a call?
7. **What does PAUSE mean for a phone call?** Handed over by ticket 02: Android Auto's media docs
   **require** a session to support `ACTION_PAUSE` and `ACTION_STOP`, and LEGION's play button places
   a call rather than starting playback. Pause could mute the mic, hold the call, or be a no-op that
   lies. Stop could end the call. Whatever is chosen, the transport controls must not do something
   different from what their icons say to a driver who cannot look.
8. **Offline.** CLAUDE.md §7 requires graceful degradation. A deterministic briefing works with no
   network; an LLM one does not, and neither does the live call. What does the tree do with no signal?

Use `/prototype` if the tree's shape is easier to judge as a concrete artifact than as a discussion.
