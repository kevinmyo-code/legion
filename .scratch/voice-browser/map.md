---
map: voice-browser
title: "A sandbox browser inside LEGION that Alfred can drive by voice"
charted: 2026-11-03
charted-by: "Kevin + Opus"
effort: "`.scratch/voice-browser/`"
tickets: 3
open: 3
status: open
tags: [map]
---

# Voice browser

**Kevin, 2026-11-03, verbatim:**

> *"can we give the phone ui its own sandbox browser like, if i ask it to find me a picture of a red
> xj on stock height for example, i want it to be able to pull the search results up. or something
> else, like a restaurant's menu etc."*

> *"i want it to have everything chrome has, my logins, passwords etc. save cookies also. basicaly a
> browser that i can ask alfred to control by voice"*

## The decision (Kevin chose option B)

| Option | What it is | Why not |
|---|---|---|
| A. Chrome Custom Tabs | Chrome's own tab over LEGION, Chrome's real cookies and passwords | LEGION cannot read or drive a Custom Tab: no page text, no click, no scroll. Voice control dies at "open". |
| **B. LEGION's own browser (Android WebView)** | **Chosen.** A full-screen browser in LEGION, persistent cookies and storage, passwords filled by Android's Autofill framework (Google Password Manager, the same store Chrome uses), Alfred reads and clicks the page | Cookies are NOT Chrome's: he signs in to each site once, then it stays signed in. Google's own sign-in refuses WebView (ADR 0071, limits). |
| C. Drive Chrome with an accessibility service | Real Chrome, real logins, LEGION taps it | Brittle (scraping Chrome's own UI), a second accessibility service with powers far beyond a browser, and a Chrome UI change breaks it silently. |

**Default search: Google** (orchestrator's pick, Kevin did not object). Google Images for "find me a
picture of ...".

ADR: `docs/adr/0071-voice-browser.md`.

## Tickets

| # | Ticket | Type |
|---|---|---|
| 01 | [[01-browser-screen-and-voice-tool]] | build |
| 02 | [[02-feed-blocker-in-the-browser]] | build |
| 03 | [[03-on-phone-run]] | test |
