---
map: voice-browser
ticket: "02"
title: "The feed blocker reaches the browser: feed sites bounce like feed apps"
type: build
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---

# Feed blocker in the browser

A browser that opens instagram.com would walk straight around ADR 0062/0066. So the browser asks the
same question the app bounce asks.

## Build

- **One map, package to domains** (`dumb/FeedDomains.kt`, beside `BlockSeed`): every seed package
  names its web domains (instagram, tiktok, reddit, x/twitter, facebook, threads, snapchat, youtube,
  pinterest, linkedin). Subdomains match (`m.facebook.com`, `old.reddit.com`).
- **Same decision**: a main-frame navigation to a mapped domain is blocked when the bounce is active
  (Matrix mode on, or "Block feed apps always" on) AND `BlockPolicy.verdict(package)` says blocked.
  A running 15 minute pass, Matrix mode's no-pass rule, and a manual allow or block all carry over
  with no second list.
- **Same words**: the browser shows the same bounce screen the app gets (Matrix: "Matrix mode is on";
  normal: "<App> is blocked" with the 15 minute pass), inside the browser.
- **Category intent** cannot reach a website (Android's category tag belongs to an installed package,
  not a host), so only the seed list's sites block in the browser. Stated in ADR 0071.
- Sub-frames (an embedded YouTube video on a recipe page) are not blocked.

## Verification

- Unit: domain mapping (subdomains match, lookalikes like `notreddit.com` do not), nothing blocks
  when both switches are off, a pass and a manual allow open it, Matrix mode ignores a pass.
