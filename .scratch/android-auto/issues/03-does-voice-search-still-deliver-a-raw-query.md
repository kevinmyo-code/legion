---
map: android-auto
ticket: 03
title: "Does voice search still deliver a raw spoken query to a media app?"
type: research
status: resolved
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Does voice search still deliver a raw spoken query to a media app?

## Question

A media app has no keyboard and no free-text input, so `onPlayFromSearch` is the only sanctioned way
arbitrary spoken words reach LEGION without it grabbing the mic itself. The claim made while charting
- that "Hey Google, play &lt;anything&gt; on LEGION" arrives as a raw query string - is platform
knowledge, not a checked fact, and it is load-bearing for the third leg of the destination.

Establish, against primary sources (Android media session / `MediaSession.Callback` reference, the
Android for Cars media documentation, Google Assistant media actions documentation):

1. **Does `onPlayFromSearch` / `onPrepareFromSearch` still fire from Android Auto's Assistant**, and
   does the `query` argument carry the user's words substantially unaltered, or a parsed and
   restructured form?
2. **The `extras` bundle.** What structured hints arrive alongside (`EXTRA_MEDIA_TITLE`,
   `EXTRA_MEDIA_ARTIST`, `EXTRA_MEDIA_FOCUS`), and does the presence of a focus extra mean Assistant
   has already decided the query is a song title - i.e. is the raw sentence recoverable?
3. **App-name routing.** How Assistant matches "on LEGION" to an installed app: the app label, the
   media session's declared name, or a Play Store listing (which LEGION will never have). **Does a
   sideloaded app with an unusual name get routed at all?** This is the weak point.
4. **Empty query.** "Play LEGION" with no further words fires `onPlayFromSearch` with an empty
   string, which is the documented "play something" case. Confirm - it is a clean, free entry point
   for starting the call (settled decision 2) without any name matching risk.
5. **Is there a supported alternative** for getting spoken free text into a media app on Android Auto
   - `SEARCH_SUPPORTED` browse-root hints, a search action in the browse tree, `onSearch` - and does
   any of them present a voice affordance rather than a keyboard.
6. **`onCustomAction`**: does Assistant or the AA UI route anything into it that could carry text?

State which claims are **documented** and which are **inferred**, and name the smallest on-unit test
that settles the app-name routing question, since that is the one Google is least likely to document.

Findings go to `.scratch/android-auto/research/03-does-voice-search-still-deliver-a-raw-query.md`.

## Answer

**The claim made while charting is WRONG. `onPlayFromSearch` fires, but the query is not the raw
sentence - it is music entities, re-joined.** Full findings and citations:
[research/03-does-voice-search-still-deliver-a-raw-query.md](../research/03-does-voice-search-still-deliver-a-raw-query.md).
Resolved 2026-08-13 from a research agent's report; tags are the agent's, carried forward unchanged.
**Nothing was run on hardware.**

1. **It fires** (`documented`), **but the `query` argument is parsed, not raw.** Google's own intent
   contract defines it per search mode as "a string that contains any combination of the artist, the
   album, the song name, or the genre" - entities extracted, carrier words dropped. A field trace has
   "Play Live from Moderat on SoundCloud" arriving as `query = "live moderat"` (`field-report`).
   **The full sentence does appear at `android.intent.extra.user_query`**, but that key is
   **undocumented** and is not `SearchManager.USER_QUERY` (`"user_query"`). Field report only, so it
   cannot be a contract. Applied to LEGION: "ask LEGION how much fuel is left" would arrive stripped
   of exactly the words that carry the question.
2. **Extras carry no category decision** (`documented`): *Any* and *Unstructured* search modes share
   the focus value `vnd.android.cursor.item/*`. Read defensively - `user_query`, then `query`, then
   typed extras.
3. **App-name routing is UNSETTLED, and the one primary signal cuts against it.** Google documents
   the phrase shape "Play X on *(app name)*" on three pages and **never says what *(app name)*
   matches against**. The only primary statement touching name invocation is Play-Console-keyed:
   capabilities are registered server-side when an app is uploaded, and "Have an app published to the
   Play Store... 'Hey Google, open AppName'". **LEGION will never have that.** The counterweight is
   inference only: Android Auto builds its media list locally from installed `MediaBrowserService`
   declarations, so a local label list does exist in the car session. One third-party integration doc
   asserts matching is by launcher label, but that app ships a Play build, so it is no evidence for a
   never-indexed name. **Added risk:** this is NLU behaviour, not API, and the Assistant-to-Gemini
   cutover (2026-09-04) replaces the NLU wholesale.
4. **Empty query is solid** (`documented` three times over: AOSP javadoc, Android for Cars, the
   intent contract). **Caveat the ticket missed:** the documented empty-query phrases *still contain
   the app name*, so it removes parsing risk, not routing risk. **The genuinely name-free entry point
   is tapping the media list** - which is settled decision 2, and this finding strengthens it.
5. **`onSearch` is a real second channel** (`documented`, voice-first, gated on
   `BROWSER_SERVICE_EXTRAS_KEY_SEARCH_SUPPORTED`). Its query is also tokenised, so it does not rescue
   leg three, but it **needs no app-name matching**. One boolean plus one override; worth building
   regardless.
6. **`onCustomAction` is a fixed-vocabulary tap surface**, no documented route for assistant text.
   Useful for tap-to-talk (settled decision 5), useless as a text pipe.
7. **media3 loses nothing**: `MediaSessionLegacyStub` preserves the query and the whole extras bundle
   into `onSetMediaItems`.

**Consequence for the map.** The third leg of the destination - "`onPlayFromSearch` answers 'Hey
Google, ask LEGION X'" - is **weakened on both halves**: the words may not survive parsing, and the
routing to a sideloaded, never-indexed name may not happen at all. Handed to ticket 08 as a real
decision rather than a detail. Experiments named: E1 (a log-only browse service, four spoken phrases,
including a launcher-label rename to test whether matching is local), E1b (an `adb am start` intent
pre-check needing no head unit), E2 (a browse-tree search affordance). **All three need ticket 06
done first**, or every null result is a false negative.
