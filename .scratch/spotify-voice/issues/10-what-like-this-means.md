---
map: spotify-voice
ticket: 10
title: "What play something like this can mean when Spotify no longer tells us"
type: grilling
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# What "play something like this" can mean when Spotify no longer tells us

## Question

**Spotify's recommendation surface is gone for us.** The 2024-11-27 cull killed the recommendations
endpoint, related-artists, audio-features and audio-analysis for any app registered after that date.
No seeded "more like this", no similarity graph, no feature vectors, no popularity signal, no
preview audio. See `research/01-api-capability-surface.md`, all `traced`.

Kevin's call, 2026-08-19: **we build our own, and we take that effort now.**

This ticket decides what "like this" is allowed to MEAN, before anything is built.

## Grill

1. **What is the seed?** The currently-playing track, the current artist, a mood said out loud, or
   the drive itself - time of day, length, where it is going? Each implies a different engine and
   they are not the same product.
2. **What are the inputs?** Everything available is first-party: top items over three time ranges,
   recently played, the saved library, his own playlists, and `legion_history`. **Nothing tells us
   two artists sound alike.** So: is a recommender with no similarity signal worth having, or does
   it just reshuffle things Kevin already knows he likes? Argue it honestly - "shuffle my own
   library cleverly" may be the entire realistic product, and if so this ticket should say that
   rather than promise a discovery it cannot perform.
3. **Is the LLM the similarity function?** Gemini knows Daft Punk and Justice are adjacent with no
   audio features at all. That is the one capability LEGION genuinely has that Spotify's dead
   endpoints had. But it is a **model guess about the world**, not a fact about the catalogue, so
   under CLAUDE.md sec 4 rule 5 it surfaces as an estimate. **Does it get to pick the music, or only
   to name candidates that are then checked against something real?**
4. **How is it labelled so it never reads as Spotify's?** `browse_my_music`'s `legion_history` source
   already carries this discipline - LEGION's own observation, described as such, any "favourite"
   named as LEGION's inference. The recommender needs the same, and stronger: **the driver cannot
   tell a good recommendation from a hallucinated one by listening.**
5. **Does a recommendation ever WRITE?** Queue it, play it, save it, build a playlist from it? The
   read-mostly posture and sec 7's no-compulsion rule both bear on this. A recommender that quietly
   builds a playlist is a mechanism, not a suggestion.
6. **What happens when it is wrong?** The driver says "no, not that". Is that stored - and if so, is
   a stored dislike a falsifiable fact, or an unfalsifiable belief about Kevin? Sec 7's memory rule
   is directly in the way.
7. **What is the cheapest version that is genuinely useful on a drive?** State the one-session
   version before the ambitious one. The ambitious one is an effort, and this map has a destination.

## Blocked by

Nothing. This is the decision that unblocks ticket 11, and it resolves before any recommendation
code is written.
