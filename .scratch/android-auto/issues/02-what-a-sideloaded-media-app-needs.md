# What does a sideloaded media app need to appear in Android Auto?

Type: research
Status: resolved
Blocked by: -

## Question

The media surface is the door (settled decision 2), so if LEGION cannot appear in Android Auto's
media list, there is no way in. LEGION is **sideloaded onto two phones and will never be on the Play
Store** (CLAUDE.md §2 - the commercial model is dead), which is the awkward part: Android Auto
normally only surfaces apps installed from Play.

Establish, against primary sources (Android for Cars / Android Auto media app documentation,
`MediaBrowserServiceCompat` and `androidx.media3.session` references, Play Console policy pages where
they define the gate):

1. **The exact manifest surface** a media app needs to be seen by Android Auto: the
   `MediaBrowserService` (or `MediaLibraryService`) intent filter,
   `com.google.android.gms.car.application` metadata, the `automotive_app_desc` XML resource and its
   `<uses name="media" />` element. Give the real, current, copy-pasteable shapes.
2. **`media3` or the older `media-compat`?** Which is the supported path for a *new* Android Auto
   media app today, and does Android Auto require anything media3's `MediaLibraryService` does not
   give for free.
3. **The sideload gate.** What exactly makes an unpublished app visible: Android Auto's developer
   mode (tap the version number ten times), the "Unknown sources" toggle in AA settings, or both.
   **Does that toggle survive an Android Auto update, a reboot, or a phone restart?** If it resets,
   the setup step is recurring and that changes ticket 12's answer.
4. **Does Google's category review apply to a sideloaded app?** Media is an approved Android Auto
   category, but approval is a Play Console process. Confirm whether an unpublished app simply skips
   it or is blocked by it.
5. **The driver-distraction rules a media browse tree must obey**: maximum browsable depth, item
   count limits per node, whether limits tighten while the vehicle is moving, and how a violation
   presents (silently truncated, or rejected).
6. **Custom actions.** How many `MediaSession` custom actions Android Auto renders, where they
   appear, and their icon/title constraints. This is where a push-to-talk button would live if the
   call route dies (see ticket 07).
7. **Is "tap play, produce no audio" hostile to Android Auto?** LEGION's play button places a call
   rather than starting playback (settled decision 2). Note anything - a playback-state watchdog, a
   "nothing is playing" timeout, an error surface - that would punish a media app whose transport
   controls do not produce a media stream.

State which claims are **documented** and which are **inferred**.

Findings go to `.scratch/android-auto/research/02-what-a-sideloaded-media-app-needs.md`.

## Answer

**The door opens, and Google says so in as many words: Android Auto's developer mode explicitly
covers media apps.** Full findings and citations:
[research/02-what-a-sideloaded-media-app-needs.md](../research/02-what-a-sideloaded-media-app-needs.md).
Resolved 2026-08-13 from a research agent's report; tags below are the agent's, carried forward
unchanged and NOT independently re-verified by the orchestrator. **Nothing here was verified on
device.**

1. **The manifest surface is four separate pieces** (`documented`), all copy-pasteable in the
   findings: the `com.google.android.gms.car.application` meta-data pointing at
   `res/xml/automotive_app_desc.xml`; that file, whose entire content is
   `<automotiveApp><uses name="media"/></automotiveApp>`; the service, `exported="true"`,
   `foregroundServiceType="mediaPlayback"`, declaring **both** the `media3` action and the legacy
   `android.media.browse.MediaBrowserService` one; and an activity intent filter for
   `android.media.action.MEDIA_PLAY_FROM_SEARCH`, which is the Assistant route ticket 03 depends on.
2. **`media3` `MediaLibraryService`** is the documented path for a new car media app (`documented`).
   Not free: the legacy browse action must still be declared, a real `Player` must be supplied even
   though LEGION plays nothing (`inferred` as a build cost), pagination is unusable on Android Auto,
   and root hints still arrive through `androidx.media` `MediaConstants`.
3. **The gate is two steps and it is the load-bearing finding.** Developer mode (tap *Version and
   permission info* ten times) unlocks a menu; an Unknown sources setting inside it is the actual
   switch (`documented` for the process, `field-report` for the menu's exact shape). **Google states
   developer mode applies to media apps and does NOT apply to Car App Library apps** - which
   independently vindicates the disguise choice made while charting, for a reason nobody had at the
   time. **Persistence is undocumented**: "you only need to enable it once" is all Google says, and
   whether an Android Auto update resets it cannot be answered from primary sources. **Ticket 12
   asked exactly that question and does not get an answer** - a four-step on-unit experiment is named
   instead.
4. **Category review is a Play Console gate keyed to release tracks** (`documented`): open testing
   and production block on it, internal sharing and internal testing do not. **Sideloading is not
   addressed anywhere** - "an unpublished app therefore skips it" is the agent's `inferred`, not
   Google's statement. Weak claim, low stakes.
5. **Driver-distraction limits degrade silently, they never reject** (`documented`): "some root
   content might be dropped". Root is browsable-only, roughly four tabs, the real number read from
   `BROWSER_ROOT_HINTS_KEY_ROOT_CHILDREN_LIMIT` at runtime. `CarUxRestrictions`
   (`getMaxContentDepth`, `getMaxCumulativeContentItems`) is **AAOS-only and unreachable from a
   projected phone app** - so LEGION cannot ask how restricted it currently is. Hands ticket 08 a
   sharper constraint than it was written with.
6. **The ticket conflated two mechanisms.** Playback custom actions - where a push-to-talk button
   would live - have **no fixed count**; they are space-dependent, ordered as added, and overflow
   beyond, with skip-slot reservation available as a deliberate lever. Custom *browse* actions are a
   separate thing with a runtime limit from root hints, where 0 means unsupported.
7. **Nothing documented punishes a media app that never plays audio**, which is the answer settled
   decision 2 needed. `STATE_CONNECTING` is the sanctioned "doing something that is not playback"
   holding state and `STATE_ERROR` + `ERROR_RESOLUTION_ACTION_INTENT` the sanctioned failure surface
   with a one-tap escape (both `documented`). **Absence of a watchdog is `inferred` from an
   exhaustive read, not asserted**, and the stub-APK experiment is a prerequisite to ticket 06.
   **New obligation:** the docs *require* `ACTION_PAUSE` and `ACTION_STOP`. Nobody has said what
   pausing a phone call means. Handed to ticket 08.
