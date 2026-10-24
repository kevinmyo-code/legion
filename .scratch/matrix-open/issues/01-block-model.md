---
map: matrix-open
ticket: "01"
title: "Build: block model, category + seed list + overrides"
type: build
status: open
blockers: []
tags: [ticket]
---

# Build: block model, category + seed list + overrides

Replace the allowlist in `dumb/` (`KeptApps`, `DumbBounce.shouldBounce`) with a blocklist.

- **Blocked** = launchable package, not LEGION, not Settings, not the Home app, AND
  (`ApplicationInfo.category` in SOCIAL, VIDEO, GAME, NEWS, OR package in the seed list) AND not
  overridden to allowed; OR overridden to blocked.
- **Seed list:** `com.instagram.android`, `com.zhiliaoapp.musically`, `com.ss.android.ugc.trill`,
  `com.reddit.frontpage`, `com.twitter.android`, `com.facebook.katana`, `com.instagram.barcelona`,
  `com.snapchat.android`, `com.google.android.youtube`, `com.pinterest`. A seed, not an OEM name:
  clone-and-run is unaffected (an unknown app falls to its category and the override).
- **Overrides** in the Matrix prefs file: per-package allow / block. Matrix settings screen gets a
  "Blocked apps" list: every launchable app with its state and why ("tagged Social", "known feed app",
  "you blocked it"), tap to flip. Said in words, not a colour.
- Existing kept externals (dialer, SMS, camera, Spotify, WhatsApp, clock, Maps) are never blocked by
  the seed or category; a manual block on them is allowed.
- Brief's News links in Matrix mode open in the browser again (the text-only rule's reason is gone).
- Bounce screen copy: "<App> is blocked in Matrix mode" and how to unblock (settings).

Verify: pure decision-function tests (category, seed, override both ways, kept externals, LEGION,
Settings, Home app). On the A25: dump the category of installed feed apps; Instagram bounces, Chrome and
the banking app open.
