---
map: web-assistant
ticket: "09"
title: "Android: the typed chatbox"
type: build
status: built
status-detail: "Built on feat/android-typed-chat, suite green; owes a run on the phone"
blockers: ["05", "06"]
blocked-by: ["[[05-chat-and-voice-ui]]", "[[06-android-typed-chat]]"]
open-blockers: 0
ready: false
tags: [ticket]
---

# Android: the typed chatbox

## Build

Per 05 and 06: a typed box on the phone, text-only replies, same tools and honesty rules as voice,
no regression to push-to-talk or the wake word. Gates per CLAUDE.md §6.

## Built (2026-10-04)

- Typed box beside the talk pill: `ui/assistant/TypedChatUi.kt` (field, reply panel,
  `LocalTypedChat`), wired in `AssistantStrip.kt`; ViewModel `AssistantChatViewModel`.
- Controller path: `LiveSessionController.onTyped` -> `GeminiLiveSession.sendTypedTurn` (same
  `clientContent` text turn as `sendText`; playback muted for the turn; reply from output
  transcription). Overlap rules in the pure `service/TypedTurnPolicy.kt`, panel state in the pure
  `service/ChatTranscript.kt`; both unit tested.
- `MainActivity` now has `adjustResize` so the bottom bar lifts above the keyboard.

### Verification accounting (CLAUDE.md sec 8, L11)

- compile, unit tests, screenshot baselines: done (see the build report).
- On the phone, owed: type a question; type while it speaks; type with no session open; push-to-talk
  still spoken; a tool call by typing ("add milk to groceries") reported only on success; the
  keyboard lifting the bar (the `adjustResize` change).
- Ruling asked, not taken: the panel keeps a mail-reading turn's text in memory until the session
  ends or New conversation (it is the answer the user asked for); nothing is persisted.

## Reworked 2026-10-05: tap to open (Kevin: "the chat box is kinda annoying ... make it tap to open a chatbot panel")

The always-visible field kept taking focus and raising the keyboard. Now:

- The strip is the full-width talk pill plus one compact chat button ("Chat with <companion>").
  No text field on the strip, so nothing on it can take focus.
- The button opens `ChatPanelSheet` (`ui/assistant/ChatPanel.kt`), a Material modal bottom sheet:
  transcript, "Not saved", New conversation, the text field (focused and keyboard raised only on
  open), and a mic button so push-to-talk still works while the modal sheet covers the strip.
  Closing (swipe, back, close button) clears focus and hides the keyboard; the conversation is
  untouched.
- A typed reply arriving while closed puts a dot on the button and "new reply" in its label
  (`ChatPanelState`); spoken replies do not. Opening clears it.
- `adjustResize` removed from `MainActivity`: the field now lives in the sheet's own window.
- Typed-turn rules (06) unchanged.

Owed on the phone: no keyboard at app start/resume/after a spoken turn/after a companion switch;
open and close; reply while closed (dot, panel stays shut); push-to-talk with the panel open and
closed; companion switch relabels the button and placeholder; the keyboard lifting the sheet's
field without the strip jumping.
