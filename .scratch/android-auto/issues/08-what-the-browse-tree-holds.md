---
map: android-auto
ticket: 08
title: "What is in the browse tree, and who writes the briefings?"
type: grilling
status: open
status-detail: "points 1-5, 7 and 8 answered on the head unit 2026-08-18; only point 6 (onPlayFromSearch) is still open"
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

## Answered on a head unit, 2026-08-18

Answered by BUILDING and looking, on Google's Desktop Head Unit over wireless adb (see
[ticket 06](06-get-a-sideloaded-build-visible-in-android-auto.md) for the rig). Every line below is
`on-device` unless it says otherwise.

| Point | Answer |
|---|---|
| 1. Root items | **Two, not four.** Talk to LEGION, and Fleet. Kevin, mid-session: "the android auto only has to show fleet data... because we're driving. we just need 2 things. push to talk and codes/telemetry gauges." Today and Money were built, seen, and deleted. |
| 2. Depth | Flat. Rows inside a tab, nothing browsable below the root. |
| 3. Deterministic or LLM | **DETERMINISTIC**, chosen directly. No Gemini call on a tap in the car. Everything comes from Room and one `StateFlow`. |
| 4. Does it change while driving | **Yes, and it has to be PUSHED.** `notifyChildrenChanged` on a 30s tick matching `TelemetryRecorder`'s own. See the caching finding below - without the push, a gauge freezes at whatever it read when the tab opened, while still looking live. |
| 5. What a tap does | **Nothing, on an info row.** The one exception is the talk button, which is a playable item. |
| 6. `onPlayFromSearch` | **STILL OPEN.** Untouched tonight. |
| 7. What PAUSE and STOP mean | **Both pause the real audio.** Settled by the media-card work: MediaSession offers no distinct stop primitive, so pause is the most honest mapping - the sound genuinely stops, matching the icon. Neither ever reaches the live conversation; pausing music mid-sentence must not hang up on LEGION. |
| 8. Offline | Answered by construction. Nothing in a browse callback touches network or Bluetooth, and **every row states its own age** - "live", "4 min ago, not live", "waiting for first reading", "not connected". A stale number shown as current is the failure this avoids. |

### Four head-unit behaviours no amount of reading would have found

1. **Root browsable items render as TABS, and the first is AUTO-SELECTED at open.** The original design fired push-to-talk by browsing into a row, so it fired unbidden the moment the app opened.
2. **Gearhead CACHES a subscription's children.** Tapping Fleet and back to Talk produced no `onGetChildren` at all - logcat silent where the first load had printed `Browse subscription for id:{legion-fleet} LOADED`. So a browse-tap can fire at most ONCE per media id per connection. Kevin: "i tapped it, nothing happens." **This is why point 4 needs a push rather than a re-fetch.**
3. **Android Auto does NOT draw browse-list subtitles.** Money rendered as a bare `1405.69` and `1208` with nothing beside them: "no descriptors." Every word a driver needs goes in the TITLE. This contradicts `CarAspectSummaries.fleet`'s own older assumption that caveats belong in the subtitle because some units SPEAK it - both can be true, and only the title is guaranteed to reach the eye.
4. **A PLAYABLE row that returns an empty queue HANGS** on "getting your selection". **Flags-0 rows - neither playable nor browsable - DO render and ignore taps**, confirmed on-device after the fix. That is the shape for a display-only row, and nothing documented says so.

### The dashboard media card: attempted, abandoned

Kevin asked for the push-to-talk button on Android Auto's home screen beside Maps and Spotify, then
for LEGION's card to mirror now-playing with transport controls. Built (`c9b5494`), and **it does not
win the card**: "spotify card takes full. i see legion at the bottom only." Android Auto gives the
dashboard card to the app that is actually PLAYING, and a mirroring session is the weaker claim
against the very app it mirrors.

Reporting `PLAYING` anyway would have been the app asserting it plays audio it does not play, to win
a UI slot - the same class of untruth as the invented dentist appointment, aimed at the system
instead of at the driver. Not done. Kevin: "nvm i dont need it to take the card."

**A real defect survives that work and is NOT fixed:** the proxy never mirrored. With Spotify paused,
`dumpsys media_session` showed `com.kevin.legion state=NONE(0)` with a stale `updated` timestamp,
so the `NowPlayingController` to proxy link is not firing. It is inert rather than wrong on screen,
because nothing renders the card, but it is shipped and broken.

