---
map: web-assistant
ticket: "06"
title: "Android typed chat: a text turn into the Live session, or a separate text model"
type: decision
status: resolved
status-detail: "Opus proposal: typed turns into the same Live session, shown as text"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---

# Android typed chat: a text turn into the Live session, or a separate text model

## Question

Kevin ruled typed chat answers in text only. The phone's Live session is audio-out.

- Send the typed turn into the same Live session (`sendText` exists) and show the output
  transcription as text with playback muted? Or open the Live session with TEXT modality for typed
  chat? Or a separate non-Live text call with the same tools?
- What happens when a typed chat and push-to-talk overlap.
- Same tools, same honesty clause either way.

## Answer (2026-10-04)

Kevin ruled typed chat answers in text only. Opus's proposal, built unless vetoed (research 01: the
Live model answers in audio only, typed and spoken turns can share one session):

- A typed message goes into the existing Live session as a text turn (`sendText` / `clientContent`).
- For a typed turn, playback is muted and the reply is shown from `outputAudioTranscription` as
  text. Push-to-talk turns stay spoken.
- Typing while a voice reply is playing interrupts it (same as speaking over it).
- With no session open, typing opens one (mic stays closed) so tools and honesty rules are identical
  to voice.
