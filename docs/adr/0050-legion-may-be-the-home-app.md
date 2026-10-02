---
status: accepted
decided: 2026-09-27
decided-by: Kevin
supersedes: []
source: "[[decisions#2026-09-27 - LEGION may be the phone's home app]]"
tags: [adr]
---

# 50. LEGION may be the phone's home app

## Standing

**ACCEPTED 2026-09-27 by Kevin.** LEGION declares `CATEGORY_HOME` and may be selected as the
phone's home app. This reverses the "Not a launcher" clause of CLAUDE.md section 1, written at the
2026-07-31 pivot away from Midnight AI, which was a car head-unit launcher.

Kevin, asked whether to prototype first: *"no need for prototype. lets do it and put it on the phone
and see. the phone is a throwaway so no worries."*

## Why the old clause no longer holds

The pivot's "Not a launcher" rejected a **product**: a head-unit launcher with a commercial model,
where the launcher role drove the design. Nothing about that returns. HOME is already the one
surface LEGION opens to (one-home, 2026-09-10), and being the home app is only the way to make it the
first thing seen on every unlock. The phone-only, non-commercial and clone-and-run rulings are
untouched. The home role is opt-in in Android's own Settings, and a stranger's clone is not a
launcher unless they choose it.

## What a home app owes, and it is binding

1. **It can open every other app,** work profile included. A home app that strands the user is
   worse than no home app (`ui/apps/AppsScreen.kt`, reached from APPS in the header on every
   screen).
2. **Home always lands on HOME,** clearing whatever was open.
3. **Back on HOME does nothing while LEGION is the default home app,** because there is nothing
   behind a home screen. As an ordinary app it still exits.
4. **A failure says so in words.** An app that won't open, or work apps that can't be paused, get a
   sentence, never a dead tap.

## Accepted costs, stated so they are not mistaken for surprises

- **A LEGION crash now strands the phone** until Android restarts the home app. Kevin accepted this
  for a throwaway phone. Before this becomes the model for anyone else's phone, the home view should
  be isolated from the voice service, database start-up and sync.
- Samsung's gesture-navigation animations are less polished with a third-party home app.
- The home app is never killed for memory, so LEGION's footprint becomes permanent.
- One UI Home can't be uninstalled, so switching back is always one setting away.
