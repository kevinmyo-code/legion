---
name: maintain-screen-map
description: Keep docs/screen-map.md current when a LEGION phone screen, route, activity, Matrix screen, row label or test tag is added, renamed or removed - edit tools/screen_map_copy.py, re-run tools/screen_map.py, and fix the drift it names. Use when screen_map.py or the commit hook fails, after adding a composable route or an activity, and after renaming a control a reach path taps.
---

# Maintain the screen map

`docs/screen-map.md` tells an agent how to reach every phone screen; `tools/phone open` walks the
same model. A map that says "tap Memory" after the row became "Memories" sends an agent tapping at
nothing on Kevin's real phone. So the map is generated, and drift fails, the posture
`tools/voice_guide.py` already has.

## What comes from where

| Fact | Source | You edit |
|---|---|---|
| Route ids | `const val` in `ui/LegionRoute.kt`, registered by `composable(...)` in `ui/MainActivity.kt` | the code |
| Which screen opens which | `onOpenX = { navController.navigate(LegionRoute.Y) }` in the registration | the code |
| The tap label for that edge | `label = "..."`/`title = "..."`/`Text("...")` beside `onClick = onOpenX` in the parent screen's file | the code, or a `reach` override |
| Header text `open` waits for | `DeckScreenHeader(title = "...")` in the screen's file | the code, or `marker` |
| Test tags | `testTag("...")` in the screen's file | the code |
| Activities, Matrix screens, Fleet drilldowns | the manifest, `MatrixDest`, `FleetDrilldown` | the code |
| Name, one-line description, key controls, voice tools, reach overrides | `tools/screen_map_copy.py` | **the copy** |

## The loop

```
python tools/screen_map.py --check     # names every problem, exits 1 on drift or a stale doc
# edit tools/screen_map_copy.py (or the code)
python tools/screen_map.py             # rewrites docs/screen-map.md
python -m pytest tools/tests/test_screen_map.py -p no:cacheprovider
```

The commit hook runs `screen_map.py` beside `voice_guide.py` and stages `docs/screen-map.md`; a
failure shows as a `systemMessage`. It regenerates, it does not fix drift: that is this skill.

## Each problem, and its fix

- **`route "x" ... has no entry in ROUTES`** - a new `composable(...)`. Add `"x": dict(name=..., what=...)`
  to `ROUTES`. If the generator could not read a labelled edge into it, also give `parent` and
  `reach`.
- **`route "x" has no way to reach it`** - no labelled `navigate` edge in code. Add `parent` and
  `reach` (the taps from the parent). If no fixed selector reaches it (the car's own name, an id in
  the route), give `parent` and `reach=[]` and say why in `what` or `needs`; `open` uses the deep link.
- **`copy exists for route "x" but no composable registers it`** - the route was deleted or renamed.
  Delete or rename the entry.
- **`... taps text "Y", which no string literal ... contains`** - a control was renamed. Find the new
  text in the screen's file and update the step. Do not paste a dynamic string (a name, an amount):
  use a fixed prefix with `^` (`("desc", "Chat with ^")`), or a test tag.
- **`activity "x" is in the manifest but in neither ACTIVITIES nor IGNORED_ACTIVITIES`** - a new
  activity. A screen goes in `ACTIVITIES` with `id`, `parent` and `reach`; a trampoline or system
  hand-off goes in `IGNORED_ACTIVITIES` with its reason.
- **`MatrixDest.X has no entry`** - a new Matrix screen. Add `"X": dict(what=...)`; its tap label
  comes from the enum.
- **`lists voice tool "t", which no *Toolbox.kt declares`** - the tool was renamed. Use the name from
  `tools/voice_guide.py`'s list (`python -c "import sys; sys.path.insert(0,'tools'); import voice_guide; print(voice_guide.declared_tools())"`).
- **`docs/screen-map.md is stale`** - run the script without `--check`. Never hand-edit the doc;
  `tools/hooks/guard_generated.py` blocks it.

## Keep it honest

- **The repo is public.** Copy carries the app's own fixed strings only: never a vault label, a
  memory, an amount, a merchant or a person.
- **Prefer the code over the copy.** A label the generator can read from the code needs no `reach`
  here, and one that does not exist cannot rot.
- **A reach path is a claim about the real app.** After changing one, walk it once with
  `python tools/phone open <id> --by-taps` (skill `verify-on-phone`) if the phone is reachable, and
  say in the report whether you did.
- Adding a test tag a reach path or a rail depends on? Put it on the control in the code, and make
  sure its window root has `Modifier.exposeTestTags()` (a dialog or bottom sheet is its own window).
