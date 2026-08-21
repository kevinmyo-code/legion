---
map: wake-word
ticket: "09"
title: "The grammar is still hardcoded to hey moose"
type: task
status: resolved
status-detail: "Found on the phone 2026-08-20, the first time the engine has ever run in LEGION. Grammar loaded as [hey moose, hey mouse, hey moves]; the active companion is Alfred."
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# The grammar is still hardcoded to hey moose

## Question

Nothing to decide - the frozen design already says what this should be, and the code says so itself.

`WakeWordEngine.buildTargetWords()` returns a fixed `["hey moose", "hey mouse", "hey moves"]`, with
the name-driven path sitting commented out directly above it. Its own KDoc is explicit:

> TEMPORARY (2026-07-21, Kevin): the grammar is HARDCODED to "hey moose" while the companion name
> keeps resolving blank on the head unit... **This MASKS that bug, it does not fix it** - the frozen
> design (CLAUDE.md sec 8) is a runtime grammar built from `CompanionProfile.name`.

It was a workaround for a head unit LEGION no longer targets, and it never got reverted.

**Confirmed on the A25, 2026-08-20**, from the first run of this engine in LEGION's life:

```
UpdateGrammarFst(): ["hey moose", "hey mouse", "hey moves", "[unk]"]
```

The active companion is **Alfred**. The phone listens for "hey moose".

Three things follow, and the third is the one that makes this urgent:

1. It contradicts `CLAUDE.md` sec 1: the companion is user-named, and no assistant name may be
   hardcoded into anything.
2. It makes `refresh()` a permanent no-op, as the KDoc admits - the phrase list no longer varies
   with the profile, so the equality check always short-circuits.
3. **The new Settings row says "say hey alfred" while the engine hears "hey moose".** That row was
   added by [The Settings toggle that nothing currently writes](02-the-settings-toggle.md) hours
   ago, and it is already lying - the same class of defect as `CLAUDE.md` sec 7's outcome-verb rule,
   pointed at a settings string instead of a spoken one.

The work: restore the name-driven grammar, guard the blank-name case rather than silently building
an empty grammar that listens for nothing, and confirm on the device that the loaded grammar names
the actual companion.

**The homophone padding does not survive the change.** "hey mouse" and "hey moves" were guesses at
how a small model mishears "moose" specifically, and there is no way to guess them for an arbitrary
name. Dropping them is correct; whether padding is needed at all is a question for
[How many false triggers is too many, and how would Kevin ever know?](07-false-triggers.md).

## Answer

Fixed and verified on the A25, 2026-08-20. `buildTargetWords()` is name-driven again, exactly as the
frozen design says it should be, and the homophone padding is gone with the hardcode.

**Before:** `UpdateGrammarFst(): ["hey moose", "hey mouse", "hey moves", "[unk]"]`
**After:** `UpdateGrammarFst(): ["hey alfred", "[unk]"]`

The active companion is Alfred, so the grammar and the Settings row finally agree.

**A blank name now refuses to start**, loudly, rather than building an empty grammar. That path was
worth adding rather than assuming away: an empty grammar still holds the microphone and still looks
like a running engine, so it would listen forever for a phrase nobody can say - the same
"running but deaf" shape as
[The wake word cannot tell silence from a quiet room](08-silenced-not-quiet.md), reached by a
different road.

Un-hardcoding also restores `refresh()` to the job it was written for. Its own KDoc admitted the
hardcode had made it a permanent no-op; the phrase list varies with the profile again, so a name
change rebuilds the grammar as designed.
