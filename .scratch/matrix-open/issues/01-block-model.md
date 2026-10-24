---
map: matrix-open
ticket: "01"
title: "Build: block model, category + seed list + overrides"
type: build
status: built
status-detail: "Built 2026-10-24, suite green. Owed on the A25: dump ApplicationInfo.category of the installed feed apps; confirm Instagram bounces and shows the new copy, Chrome, Gmail and the banking app open, flip an app in Matrix settings > Blocked apps and see the bounce follow at once, and open a Brief News link in the browser."
blockers: []
tags: [ticket]
---

# Build: block model, category + seed list + overrides

Replace the allowlist in `dumb/` (`KeptApps`, `DumbBounce.shouldBounce`) with a blocklist.

- **Blocked** = launchable package, not LEGION, not Settings, not the Home app, AND
  (`ApplicationInfo.category` in SOCIAL, VIDEO, NEWS and the package is not exempt, OR package in
  the seed list) AND not
  overridden to allowed; OR overridden to blocked.
- **Seed list:** `com.instagram.android`, `com.zhiliaoapp.musically`, `com.ss.android.ugc.trill`,
  `com.reddit.frontpage`, `com.twitter.android`, `com.facebook.katana`, `com.instagram.barcelona`,
  `com.snapchat.android`, `com.google.android.youtube`, `com.pinterest`, `com.linkedin.android`. A seed, not an OEM name:
  clone-and-run is unaffected (an unknown app falls to its category and the override).
- **Category-exempt:** every package that answers `ACTION_VIEW https://`, `ACTION_SENDTO mailto:`,
  or is the default SMS app, resolved through `PackageManager` at run time (manifest `<queries>`).
  Measured on the A25 2026-10-24: Play tags Chrome, Firefox, Samsung Internet and Gmail SOCIAL. GAME is
  not a blocking category (Kevin: games allowed). Exempt beats category, never the seed list or a
  manual block.
- **Overrides** in the Matrix prefs file: per-package allow / block. Matrix settings screen gets a
  "Blocked apps" list: every launchable app with its state and why ("tagged Social", "known feed app",
  "you blocked it"), tap to flip. Said in words, not a colour.
- Existing kept externals (dialer, SMS, camera, Spotify, WhatsApp, clock, Maps) are never blocked by
  the seed or category; a manual block on them is allowed.
- Brief's News links in Matrix mode open in the browser again (the text-only rule's reason is gone).
- Bounce screen copy: "<App> is blocked in Matrix mode" and how to unblock (settings).

Verify: pure decision-function tests (category, seed, override both ways, kept externals, LEGION,
Settings, Home app, browser/mail exemption, GAME not blocked). On the A25: dump the category of installed feed apps; Instagram bounces, Chrome and
the banking app open.
