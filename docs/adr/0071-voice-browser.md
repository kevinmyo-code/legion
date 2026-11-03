---
status: accepted
decided: 2026-11-03
decided-by: Kevin
supersedes: []
source: "[[decisions#2026-11-03 - LEGION gets its own browser, driven by voice (Kevin)]]"
tags: [adr]
---

# 71. LEGION has its own browser, and Alfred drives it by voice

## Standing

**LEGION's browser is its own Android WebView, not Chrome.** A full-screen screen in the phone app
with an address/search bar, back, forward, reload and close. Kevin chose it (option B) over Chrome
Custom Tabs, which LEGION cannot read or drive, and over driving Chrome through an accessibility
service, which is brittle and far too powerful. Kevin's words and the comparison are on
`.scratch/voice-browser/map.md`.

**The profile persists.** Cookies and site storage live in WebView's own store and survive closing
the browser and restarting the app. They are not Chrome's cookies: he signs in to each site once.
JavaScript is on; file and content access are off; downloads are refused in words; pop-up windows are
off, so a link that would open a new tab opens in place. **Third-party cookies are on** (Chrome's own
default), because single sign-on redirects need them.

**Passwords are Android's Autofill framework's**, the same Google Password Manager store Chrome uses.
The WebView is marked important for autofill and the framework reads its fields. **The model never
sees or types a password:** the page reader reports a password field as a password field with no
value, and the `type` action refuses one.

**Search is Google.** Words that are not a web address become a Google search; `kind: images` is
Google Images.

**One voice tool, `browser`**, to keep the Live setup payload small: `open`, `read`, `click`,
`scroll`, `type`, `back`, `close`. CLAUDE.md section 7 binds every result: "opened" only after the
page finished loading in that call, "clicked" only when an element was found and clicked. A question
to be answered in words goes to `ask_engine` (the engine's `web_search`); `browser` is for seeing a
page.

**Ask first, and the ask is enforced in code.** Submitting a form, anything labelled pay, buy,
purchase, order, checkout, send, post, publish, delete, remove, book, reserve, confirm, subscribe or
transfer, and typing into any field on a checkout or payment page, returns a question and a one-use
token instead of acting. Only a second call with that token and `confirmed: true`, on the same page
and element, within 90 seconds, acts. The `type` action never presses Enter.

**Page text is read-through** (CLAUDE.md section 7): `browser` is in
`LiveToolbox.EPISODIC_EXCLUDED_TOOLS` and the engine's `READ_THROUGH_TOOLS` mirror. Nothing a page says
is stored, synced or remembered; `remember` is refused in a turn that read a page.

**The feed blocker reaches the browser.** Each seed feed app (`BlockSeed.PACKAGES`) names its web
domains in one map, `dumb/FeedDomains.kt`. A main-frame navigation to one of them is blocked when the
bounce is active (Matrix mode, or "Block feed apps always") and `BlockPolicy` says that app is
blocked, so ADR 0066's 15 minute pass, Matrix mode's no-pass rule and a manual allow or block all
apply unchanged. The browser shows the same bounce words and pass the app bounce shows.

**Hands path (ADR 0035):** "Browser" on Matrix home's kept list, and a launcher entry named "Browser",
which every app drawer lists.

## Consequences

- **Category intent does not reach websites.** Android's Social/Video/News tag belongs to an
  installed package, not a host, so only the seed list's sites block in the browser. A new feed site
  is a line in `FeedDomains`, not a category rule.
- **Embedded frames are not blocked**: a YouTube video embedded in a recipe page plays. Blocking
  sub-frames would break ordinary pages.
- **Google's own sign-in refuses WebView** (`disallowed_useragent`). Sites that sign in with Google
  may not work; sites with their own passwords do. LEGION does not disguise its user agent.
- Pages that need pop-up windows (some payment and OAuth flows) do not work in place.
- Adding "Browser" to Matrix home's kept list changes a ruled list (ADR 0062); it sits after Brief,
  before Books, so All apps still follows Books.
