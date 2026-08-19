---
map: spotify-voice
ticket: 10
title: "What play something like this can mean when Spotify no longer tells us"
type: grilling
status: resolved
status-detail: "Answered by Kevin 2026-08-19. Single-drive only, no stored preference, plays and queues but never saves."
blockers: []
blocked-by: []
open-blockers: 0
ready: false
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

## Resolution, 2026-08-19 - Kevin, grilled through

**Priority note, in his own words: "recommendation is not as important because i usually know what i
want."** This is the least valuable thing on the map and should be built LAST. The workhorses are
queue, like, shuffle and playlists-by-name.

### 1. Surfacing or discovery

**Both, labelled differently.** Every input LEGION holds is a record of what Kevin already played -
top items, recently played, saved library, his playlists, `legion_history` - and **nothing Spotify
still gives us says two artists sound alike.** So the "yours" half is a reordering of his own library
(honest, verifiable, and genuinely useful), and the "new" half can only come from the model's own
knowledge of music. They are different claims and they are said differently.

### 2. When a suggestion does not resolve

**Resolve-or-die for what it PLAYS; say it plainly when one dies.** A suggested track is searched
before it is ever spoken, and only survivors reach the queue. When one cannot be found, the
assistant says so and names the model as the source - never "Spotify doesn't have that", which would
be LEGION inventing a fact about the catalogue to cover a possible hallucination. Search is capped at
**10 results**, so misses will be routine.

### 3. What it may say about WHY

**Facts, delivered in character.** The reason must trace to a real row - play counts, dates, what he
saved - and Alfred may phrase it with personality. A *story* about his taste ("you've been in a
synthy mood lately") is forbidden: it is CLAUDE.md sec 7's unfalsifiable belief, and its failure mode
is self-reinforcing - repeat the story often enough and the engine starts picking to fit its own
theory rather than the record, narrowing the music invisibly.

### 4. The seed

**A (the current track), B (the drive itself), C (a spoken mood). D is dropped** - Kevin does not say
"play me something": *"i ususally say hey im on a mood for retro synth music."* **C is the primary
path**, which matters, because a genre request is the one shape none of his first-party data is
labelled for.

### 5. Where candidates come from for a mood

**A (ask the model for names, then resolve each) and C (filter his own library by model-assigned
genre), mixed in one response.** **B is dropped** - Spotify search's genre filtering was never
verified post-cull and comes from the same family of endpoints Spotify has been retiring; not worth
a spike for this map's least important feature.

**Repetition is handled BOTH ways** (Kevin, pushed back on prompt-only): the model is told, AND the
discovery lane is mechanically filtered against `legion_history` and recently-played. A prompt rule
is a request - this repo already lost a motorway to one - and only the filter can actually hold.
Neither needs a new table.

### 6. Writes

**It plays and it queues. It never saves.** Saving stays a thing Kevin asks for out loud through
ticket 05's `like`. Anything that quietly accumulates is a mechanism rather than a suggestion, and
sec 7's no-compulsion rule is directly in the way.

### 7. When it is wrong

**Session only. Nothing is stored.** A skip drops that track for the rest of the drive and is
forgotten. Storing skips as a fact about the TRACK was offered and declined; storing them as a fact
about his TASTE was never available - that is sec 7's forbidden case. The reason A wins on merit as
well as rules: **a skip is ambiguous.** He skips things he loves because they are wrong for the
moment, and a stored negative would teach the engine the wrong thing in a way nobody could ever
trace back.

### The version that gets built

Mood in. The "yours" half from `legion_history` plus the saved library, filtered by model-assigned
genre. The "new" half from model-named artists, each resolved through search before it is spoken.
Both halves filtered against recent plays, mixed into one queue, each labelled once, aloud.

**No table. No migration. No stored preference. One drive.** Learning across drives is a separate
effort and is deliberately not on this map.
