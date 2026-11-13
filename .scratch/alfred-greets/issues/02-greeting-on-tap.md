---
map: alfred-greets
ticket: "02"
title: "A personal greeting on every tap and wake word"
type: build
status: open
status-detail: ""
blocked-by: []
tags: [ticket]
---

# Greeting on tap

Every tap and wake word: Alfred greets from the facts pack, on Live and on the Kyutai cascade, then
listens. The scripted Puck line stays as the fallback when the pack or the model does not answer in
time. Varies by time of day and never repeats a question already asked today. Amends ADR 0087's
tap rule; its Live tool budget is unchanged.

## Verification

Latency from tap to first audio measured on the phone, Live and cascade, against the scripted line.
