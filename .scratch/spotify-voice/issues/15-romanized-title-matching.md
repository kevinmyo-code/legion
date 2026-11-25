---
map: spotify-voice
ticket: "15"
title: "Build: a title said in romanized form matches the catalogue row written in its own script"
type: build
status: open
status-detail: "charted 2026-11-24; ruled a ticket of its own rather than part of that day's music bug fixes"
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# A romanized title matches the row written in its own script

## What the code does today

`SpotifyMatch` compares token sets after normalisation, and normalisation does not transliterate.
Two spellings of one title in different scripts share no tokens, so every rule in the gate scores
zero: `sequencesEqual` fails, `containsSequence` fails, and token overlap is 0.0. The candidate is
rejected however obviously right it is.

A translated title fails the same way, for the same reason.

The 2026-11-24 fix narrows this: when the query itself carries the artist, a confirmed artist plus
Spotify's own top hit is accepted across scripts. That covers a request naming the artist. It does
NOT cover a romanized or translated title with no artist in it, which is this ticket.

## Scope of the build

1. A transliteration or phonetic comparison for the title halves, applied only when the two sides
   share no script - never in place of the existing token rules, which must keep behaving exactly
   as they do for same-script titles.
2. Which languages. Pinyin is the first case. Burmese and Japanese are plausible next; each is its
   own romanisation scheme and none is free, so the list is a decision, not a sweep.
3. Where it runs. The build carries no transliteration library, and `SpotifyMatch` is deliberately
   pure, Android-free and JVM-testable, so a dependency needing Android or the network does not
   belong in it. The engine is the other candidate.
4. The failure stays honest: an unmatched romanization still plays nothing and still names the
   closest hits. A loose phonetic match that plays the wrong song is worse than today's miss.

## Measure before building it

Spotify's own search may already handle the romanized form server-side, in which case the gate is
the only thing refusing it. So: run romanized forms of a handful of titles against the search
endpoint and count how often the right row comes back at all.

- If it comes back and only the gate rejects it, this is a gate rule, not a library, and the
  artist-confirmed path above may already cover most of it.
- If it does not come back, no gate change can help and the work is a query-side transliteration.

That measurement decides whether this ticket is small or large, and it has not been taken.
