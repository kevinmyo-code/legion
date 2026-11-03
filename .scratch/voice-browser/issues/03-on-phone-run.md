---
map: voice-browser
ticket: "03"
title: "On the phone: the voice browser end to end"
type: test
status: open
status-detail: ""
blockers: ["01", "02"]
blocked-by: ["[[01-browser-screen-and-voice-tool]]", "[[02-feed-blocker-in-the-browser]]"]
open-blockers: 2
ready: false
tags: [ticket]
---

# On-phone run

1. "Find me a picture of a red XJ on stock height": Google Images opens; Alfred says opened only after
   it loaded.
2. "Pull up the menu for <restaurant>": search opens; "read it" gives numbered links; "click 3" opens.
3. Sign in to one site with a saved password: Autofill offers it; close the browser, kill the app,
   reopen: still signed in.
4. "Type my email into field 2" works; any password field is refused.
5. On a shop: "click buy" asks first; "no" leaves it; "yes" in the next utterance clicks; a yes two
   minutes later does not.
6. With "Block feed apps always" on: instagram.com in the bar bounces with the same words; a 15 minute
   pass opens it; in Matrix mode a pass does not.
7. Browser from Matrix home's list, and from the app drawer's "Browser" entry.
8. After a page was read, "remember that" is refused (read-through).
