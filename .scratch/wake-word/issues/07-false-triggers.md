---
map: wake-word
ticket: "07"
title: "How many false triggers is too many, and how would Kevin ever know?"
type: grilling
status: open
status-detail: ""
blockers: ["06"]
blocked-by: ["[[06-prove-it-on-the-phone]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# How many false triggers is too many, and how would Kevin ever know?

## Question

The 2026-07-19 note claims **no false triggers across a real drive**. That was a fixed 2-3 phrase
grammar in a car, on a different microphone, with road noise instead of a living room. It is a
`tested` claim about hardware LEGION no longer targets.

Once the trigger is proven on the phone:

1. **What is the actual false-trigger rate** across a normal day - television, conversation, music,
   a podcast saying a similar name?
2. **What rate is tolerable?** A wake word that opens a Gemini turn on its own costs money on Kevin's
   own key and speaks out loud in a room. Both make a false positive worse than an annoyance.
3. **How would he ever notice a slow drift?** The debug event ring is debug-build-only and holds 20
   entries. If false triggers are a thing that needs watching, something has to surface them without
   a laptop attached.
4. **Is `MIN_TRIGGER_GAP_MS` (4s) still the right floor** on a phone that hears more conversation
   than a car cabin did?

The opposite failure matters just as much and is easier to miss: **a wake word that never fires
looks identical to a quiet room.** Whatever is decided here should make both directions visible.
