---
map: voice-browser
ticket: "01"
title: "The browser screen, its persistent profile, and the one `browser` voice tool"
type: build
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---

# Browser screen and voice tool

Kevin's words and the A/B/C decision are on the map. ADR 0071 holds what is binding.

## Build

- **Screen**: Compose + Android WebView, full screen, its own activity so the page survives between
  voice commands. Address/search bar, back, forward, reload, close. Reached by voice and by hand (ADR
  0035): Matrix home's kept list gets "Browser", and a launcher entry named "Browser" puts it in every
  app drawer (the soft drawer and Matrix All apps list launcher entries).
- **Profile**: WebView's own cookie jar and DOM storage, persisted by WebView across sessions. JS on.
  File and content access off. Downloads refused in words. Third-party cookies ON (Chrome's default;
  single sign-on redirects need them). No pop-up windows: `target=_blank` opens in the same view.
- **Passwords**: Android's Autofill framework fills them (WebView reports its form fields to the
  autofill service itself). The model never sees a password field's value and `type` refuses any
  password field.
- **Voice tool `browser`** (one declaration, Live payload): actions `open` (url or query, kind
  web|images), `read`, `click` (number or text), `scroll` (up/down/top), `type` (into a numbered
  field), `back`, `close`. "Opened" only after the page finished loading; "clicked" only when an
  element was found and clicked.
- **Ask first** (blocking, a confirm token that lapses in 90 s): submitting a form; anything labelled
  pay, buy, order, checkout, send, post, delete, book, confirm (and close kin); typing into any field
  on a checkout or payment page.
- **Read-through**: `browser` joins `LiveToolbox.EPISODIC_EXCLUDED_TOOLS` and the engine mirror
  `READ_THROUGH_TOOLS`. Page text answers a question and is dropped.
- **Spoken answers**: a question to be answered in words goes to `ask_engine` (the engine's
  `web_search`); `browser` is for SEEING a page.

## Verification

- Unit: URL/query building, ask-first classifier, element numbering/formatting, password refusal,
  confirm-token lapse.
- Compose: the browser chrome (bar, buttons, blocked panel) under Robolectric.
- `compileDebugKotlin -Pnokey`, `testDebugUnitTest` totals from JUnit XML, `assembleFast`.
- On the phone: ticket 03.
