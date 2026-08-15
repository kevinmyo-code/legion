# What does a sideloaded media app need to appear in Android Auto?

Research for `.scratch/android-auto/issues/02-what-a-sideloaded-media-app-needs.md`
Researched 2026-08-13. Every claim tagged `documented` (with URL), `inferred`, or `field-report`.

## Headline

- A sideloaded media app **can** appear in Android Auto. The gate is Android Auto's own developer
  mode plus an "Unknown sources" setting, and it explicitly covers **media apps**. `documented`
- Google's Play Console car-app review is a **publishing** gate, not a runtime gate. An unpublished
  app never enters it. `documented` for the process, `inferred` for "therefore skipped".
- `media3` `MediaLibraryService` is the recommended path for new apps. `documented`
- Nothing in the docs describes a watchdog that punishes a media app for not producing audio. The
  documented states `STATE_CONNECTING` / `STATE_ERROR` are the sanctioned "I am doing something
  that is not playback" and "I failed" surfaces. Absence of a watchdog is **not documented either
  way** and needs the on-unit experiment in Q7. `inferred`

---

## 1. The exact manifest surface

Four separate things. Copy-pasteable, current as of the pages cited.

### 1a. `AndroidManifest.xml` — the Android Auto capability declaration

Goes inside `<application>`, not inside the service.
`documented` https://developer.android.com/training/cars/media/auto

```xml
<application>
    ...
    <meta-data android:name="com.google.android.gms.car.application"
        android:resource="@xml/automotive_app_desc"/>
    ...
</application>
```

Do **not** also add `com.android.automotive` — that is the Android Automotive OS declaration and
the docs say not to reference the GMS one in an AAOS app. AAOS is out of scope on this map.
`documented` https://developer.android.com/media/implement/surfaces/cars

### 1b. `res/xml/automotive_app_desc.xml`

`documented` https://developer.android.com/training/cars/media/auto

```xml
<automotiveApp>
    <uses name="media"/>
</automotiveApp>
```

That is the whole file. `<uses name="media"/>` is what puts the app in Android Auto's media list.

### 1c. The service declaration + intent filter

For a **media3 `MediaLibraryService`** (the recommended path, see Q2). The documented
background-playback manifest shape, which declares **both** actions so legacy
`MediaBrowserCompat` clients (Android Auto is one) can bind:
`documented` https://developer.android.com/media/media3/session/background-playback

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />

<service
    android:name=".LegionMediaLibraryService"
    android:foregroundServiceType="mediaPlayback"
    android:exported="true">
    <intent-filter>
        <action android:name="androidx.media3.session.MediaSessionService"/>
        <action android:name="android.media.browse.MediaBrowserService"/>
    </intent-filter>
</service>
```

Constant values, verified against the androidx source rather than a doc paraphrase:

| Constant | Value | Source |
|---|---|---|
| `MediaLibraryService.SERVICE_INTERFACE` | `androidx.media3.session.MediaLibraryService` | `documented`, androidx/media `libraries/session/.../MediaLibraryService.java` |
| `MediaBrowserServiceCompat.SERVICE_INTERFACE` | `android.media.browse.MediaBrowserService` | `documented`, same javadoc, quoted as the back-compat action |

The `MediaLibraryService` class javadoc's own minimal form:
`documented` https://github.com/androidx/media (release branch, `MediaLibraryService.java` javadoc)

```xml
<service android:name="NameOfYourService">
  <intent-filter>
    <action android:name="androidx.media3.session.MediaLibraryService"/>
  </intent-filter>
</service>
```

**Ticket 06 should paste the background-playback form (three actions/permissions above), not the
javadoc minimal form.** `exported="true"` is required for an external client such as Android Auto
to bind. `documented` (same background-playback page)

Media3 `MediaLibraryService` declares `androidx.media3.session.MediaSessionService` as its action
in the manifest example even though the class's own `SERVICE_INTERFACE` differs; media3's
`MediaLibraryService` extends `MediaSessionService`, so both resolve. `inferred` — if ticket 06
wants belt and braces, declare all three actions
(`MediaLibraryService`, `MediaSessionService`, `android.media.browse.MediaBrowserService`).

### 1d. Voice search intent filter (needed for "Hey Google, ask LEGION X" — map's destination)

Goes on the **activity** that should answer a play-from-search intent.
`documented` https://developer.android.com/training/cars/media/voice-actions

```xml
<intent-filter>
    <action android:name="android.media.action.MEDIA_PLAY_FROM_SEARCH" />
    <category android:name="android.intent.category.DEFAULT" />
</intent-filter>
```

---

## 2. media3 or media-compat?

**media3 `MediaLibraryService` + `MediaLibrarySession`.** `documented`
https://developer.android.com/media/implement/surfaces/cars — the cars page states
`MediaLibraryService` is what you use when adding Android Auto/AAOS support, and describes the
media3 architecture (any `Player` implementation, no connectors) as the reason.
The media-browser-service overview lists both `MediaBrowserServiceCompat` (androidx.media) and
`MediaLibraryService` (media3) as supported classes, so compat is not dead, merely legacy.
`documented` https://developer.android.com/training/cars/media/create-media-browser

Required `Player` commands the session must advertise: `COMMAND_PLAY_PAUSE`, `COMMAND_STOP`,
`COMMAND_SET_MEDIA_ITEM`, `COMMAND_PREPARE`. `documented` (same cars page)

### What media3 does not give for free

1. **Legacy actions still matter.** Android Auto connects as a `MediaBrowserCompat` client, so the
   `android.media.browse.MediaBrowserService` action must be declared explicitly. `documented`
   (background-playback page)
2. **A real `Player`.** `MediaLibrarySession` takes a `Player`. LEGION has no audio to play, so it
   must supply a `Player` implementation (a `SimpleBasePlayer` subclass or similar) whose
   play command starts the call. This is a build cost media3 does not remove. `inferred`
3. **Pagination is not usable.** "Android Auto and AAOS don't support pagination. If you build your
   app with `MediaLibraryService` and `MediaLibrarySession`, don't rely on the `page` or `pageSize`
   parameters of the `onGetChildren` callback." `documented`
   https://developer.android.com/training/cars/media/create-media-browser/content-hierarchy
4. **Root hints.** Content limits, custom-action limits and supported root child flags all arrive as
   a `rootHints` Bundle, read via `MediaConstants` from `androidx.media`. media3 does not abstract
   these away. `documented` (content-hierarchy page)
5. **Platform token.** Apps on media3 use a `PlatformToken` rather than a `MediaSessionCompat.Token`;
   the templated-media-app docs note a custom `SessionCommand` is needed in
   `MediaLibrarySession.Callback` to return the underlying platform token. Relevant only if ticket
   06 ends up wanting the templated surface. `documented`
   https://developer.android.com/training/cars/apps/media

---

## 3. The sideload gate

**Both, and they are two steps, not one.**

**Step 1 — enable Android Auto developer mode.** `documented`
https://developer.android.com/training/cars/testing

> Android Auto has a developer mode that allows you to run apps that aren't installed from a
> trusted source.

- Android 10+: Settings > Apps & notifications > See all apps > Android Auto > Advanced >
  Additional settings in the app.
- Android 9 and lower: Android Auto app > menu > Settings.
- Then: About section near the bottom, tap **Version and permission info** ten times, confirm the
  **Allow development settings?** dialog.
- Developer options then appear in the overflow menu.

**It applies to media, messaging-notification, and parked apps. It does NOT apply to apps built on
the Android for Cars App Library.** `documented` (same page). LEGION is a media app, so it is
inside the covered set — this is the single most load-bearing fact for the whole map.

**Step 2 — the "Unknown sources" checkbox.** Sits inside the developer settings unlocked by step 1.
developer.android.com describes the *capability* ("run apps that aren't installed from a trusted
source") but **does not name the checkbox**. The named toggle is attested by Google's own Android
Auto Community threads and by press coverage of the developer menu. `field-report`
https://support.google.com/androidauto/thread/444013881 ,
https://www.androidauthority.com/android-auto-developer-settings-3621170/

So: developer mode is the door, Unknown sources is the switch behind it. Enabling developer mode
alone is not enough. `field-report`

### Does it survive updates, reboots, phone restarts?

- Docs say: "You only need to enable developer mode **once**", and give an explicit "quit developer
  mode" action in the overflow menu. That is the only persistence statement Google makes.
  `documented` https://developer.android.com/training/cars/testing
- The docs are **silent** on whether an Android Auto app update, an app-data clear, or a phone
  reboot resets either developer mode or the Unknown sources checkbox. No primary source found
  either way.
- Community threads exist about sideloaded apps not appearing *with* Unknown sources enabled, which
  suggests the setting is at minimum not sufficient on its own in every AA version, but the threads
  do not establish a reset behaviour. `field-report`, weak.

**Smallest on-device experiment that settles it** (rig: real head unit + OPPO A17k):

1. Enable developer mode + Unknown sources. Plug in. Confirm a sideloaded stub media app renders in
   the AA media list. Screenshot the AA developer settings screen.
2. Reboot the phone. Replug. Re-check the checkbox state **without touching it**. That isolates
   reboot.
3. Force-stop and clear cache (not data) on Android Auto. Replug. Re-check. That isolates process
   death.
4. Let Play update Android Auto (or install an APK bump of the AA package over itself). Replug.
   Re-check. That isolates the update case, which is the one that would make ticket 12 recurring.

Steps 1-3 are cheap and same-session. Step 4 is the one that actually matters and is the one that
cannot be forced on demand; the honest plan is to record the AA version number alongside the
checkbox state at step 1 and re-check after the next real AA update.

---

## 4. Does Google's category review apply to a sideloaded app?

**No. It is a Play Console publishing gate, keyed to release tracks.** `documented`
https://developer.android.com/training/cars/distribute

Documented shape of the gate:

| Track | Car form-factor review |
|---|---|
| Internal app sharing | none |
| Internal testing | none |
| Closed testing | non-blocking (you are told, submission still approved) |
| Open testing | **blocking** |
| Production | **blocking** |

Opting in is an explicit Play Console action: Advanced settings > Form factors > Add form factor >
Android Auto, then release to a track. `documented` (same page)

The car-app-quality FAQ confirms it is a manual review layered on the normal Play review: "Apps for
cars are subject to an additional manual review beyond normal Play Store review processes."
`documented` https://developer.android.com/docs/quality-guidelines/car-app-quality

**Therefore an app never uploaded to Play never enters the review and is not blocked by it.**
`inferred` — Google nowhere writes "sideloaded apps skip review"; the distribute page does not
address sideloading at all. The inference is from the review being described exclusively as a
function of Play release tracks, plus the testing page explicitly providing developer mode as the
mechanism to run non-trusted-source apps in a real car.

Consequence for LEGION: the quality guidelines are **advisory**, not binding. Worth reading anyway
for MA-1 (see Q7), which describes behaviour Android Auto itself may enforce.

---

## 5. Driver-distraction rules a media browse tree must obey

### Root level

- The root children limit arrives as a root hint. `documented`
  https://developer.android.com/training/cars/media/create-media-browser/content-hierarchy

```kotlin
import androidx.media.utils.MediaConstants

override fun onGetRoot(
    clientPackageName: String,
    clientUid: Int,
    rootHints: Bundle
): BrowserRoot {
    val maximumRootChildLimit = rootHints.getInt(
        MediaConstants.BROWSER_ROOT_HINTS_KEY_ROOT_CHILDREN_LIMIT,
        /* defaultValue= */ 4)
    // ...
}
```

- "In most cases, expect this number to be four or fewer." Root children become the navigation tabs.
  `documented`
- Only **browsable** items may be root children; playable items must be nested. Read the supported
  flags from `BROWSER_ROOT_HINTS_KEY_ROOT_CHILDREN_SUPPORTED_FLAGS`, default
  `MediaItem.FLAG_BROWSABLE`. `documented`
- Not all versions send the hints; in their absence assume browsable-only, max four. `documented`

### Depth and cumulative items

- Android Auto (**projected**) does not expose `CarUxRestrictions` to the app. That API lives in
  `android.car`, which is an Android Automotive OS surface. `inferred` — it is on
  developer.android.com under `android/car/drivingstate` and the whole `android.car` package is
  absent from a phone. The projected head unit enforces limits itself and communicates them via
  root hints, not via a queryable restrictions object.
- For AAOS, the numbers are real API: `getMaxContentDepth()` (max view traversals in a single task)
  and `getMaxCumulativeContentItems()` (max items across the whole traversal) under
  `UX_RESTRICTIONS_LIMIT_CONTENT` (value 32). `documented`
  https://developer.android.com/reference/android/car/drivingstate/CarUxRestrictions
- Documented illustrative figure: with a cumulative max of 60, 10 countries x 50 songs each, or
  20 x 40. `documented` (same page). Treat 60 as an example, not a constant.

### Do limits tighten while moving?

- On AAOS: yes, explicitly. `isRequiresDistractionOptimization()` flips true, active restrictions
  are queried, and restrictions vary by OEM and market. Apps must react to restriction *changes*,
  not to absolute driving state, and non-optimized foreground activities get stopped. `documented`
  (CarUxRestrictions)
- On projected Android Auto: **the docs are silent** about whether the root hints change value
  between parked and driving. The hint is read once per `onGetRoot`. `inferred` — a projected app
  most likely gets one static limit per connection.
  **Smallest experiment:** log the `rootHints` Bundle contents in `onGetRoot` to an on-screen
  diagnostic (logcat is useless on the A17k, per MEMORY.md), then force a fresh browser connection
  while parked and again while moving, and diff the bundle. If AA never re-roots mid-drive, the
  question is moot and the answer is "one limit, connection-scoped".

### How a violation presents

- **Silently degraded, not rejected**, at the root: "Some root content might be dropped or made
  less discoverable by the system" if the hints are not followed. `documented` (content-hierarchy)
- No documented hard rejection or exception for exceeding per-node counts. `inferred` from the
  absence of any such statement.

### Blanket rule worth carrying

"Aside from voice guidance audio for navigation apps, in-app media playback while driving is not
permitted" — this is about the app rendering its own UI/video, not about audio. `documented`
https://developer.android.com/training/cars/media

---

## 6. Custom actions

There are **two distinct mechanisms** and the ticket's question spans both. Get this right in 06.

### 6a. Playback custom actions (transport controls) — this is where push-to-talk goes

`documented` https://developer.android.com/training/cars/media/enable-playback

- Rendering order: reserved slots for `ACTION_SKIP_TO_PREVIOUS` / `ACTION_SKIP_TO_NEXT`, then
  custom actions **in the order added to `PlaybackStateCompat`**, then an overflow menu when space
  runs out.
- **No fixed documented count.** How many render is a function of head-unit space; the rest go to
  overflow. `documented` (the docs state the space-dependent behaviour and give no number).
- Reserve the skip slots so AA does not fill them, which is exactly what LEGION wants since there
  is no next/previous:

```kotlin
val extras = Bundle().apply {
    putBoolean(MediaConstants.SESSION_EXTRAS_KEY_SLOT_RESERVATION_SKIP_TO_NEXT, true)
    putBoolean(MediaConstants.SESSION_EXTRAS_KEY_SLOT_RESERVATION_SKIP_TO_PREV, true)
}
session.setExtras(extras)
```

  Note the semantics: setting these **reserves** the slots, keeping custom actions out of them.
  If LEGION wants its PTT button in a prime slot rather than overflow, it likely wants these
  **false/absent** so custom actions may occupy the freed space. `inferred` — the doc describes the
  reservation as preventing AA from filling reserved slots with custom actions.

- Icon constraints, all `documented`:
  - Vector drawables (`.xml`), not raster. Must scale across densities.
  - Tintable. Stateful actions (toggle on/off) need a separate icon per state, plus a disabled
    variant.
  - Optionally set `MediaConstants.EXTRAS_KEY_COMMAND_BUTTON_ICON_COMPAT` to a
    `CommandButton.ICON_*` constant so the system draws its own consistent glyph.

```kotlin
val customActionExtras = Bundle().apply {
    putInt(MediaConstants.EXTRAS_KEY_COMMAND_BUTTON_ICON_COMPAT,
           CommandButton.ICON_RADIO)
}

stateBuilder.addCustomAction(
    PlaybackStateCompat.CustomAction.Builder(
        CUSTOM_ACTION_START_RADIO,
        "Start Radio",
        R.drawable.ic_radio_vector
    ).setExtras(customActionExtras).build()
)
```

- Title: no documented length cap found. `inferred` — treat as truncatable, keep it two words.

### 6b. Custom browse actions (browse tree toolbars) — not the PTT surface

`documented`
https://developer.android.com/training/cars/media/create-media-browser/custom-browse-actions

- Limit is **runtime-queried**, not fixed:

```java
int actionLimit = rootHints.getInt(
    MediaConstants.BROWSER_ROOT_HINTS_KEY_CUSTOM_BROWSER_ACTION_LIMIT, 0);
```

  A limit of 0 or less means the head unit does not support the feature at all. `documented`
- Each action is a Bundle with `EXTRAS_KEY_CUSTOM_BROWSER_ACTION_ID`, `..._LABEL`, and
  `..._ICON_URI` (a tintable vector drawable referenced by URI). `documented`
- Placement: item-level actions render in a toolbar; browse-node root actions render in a
  **secondary toolbar under the primary toolbar**; overflow beyond capacity. `documented`

**Recommendation for ticket 07's fallback:** put push-to-talk in 6a, a playback custom action on
the `MediaSession`, because it is visible on the now-playing screen where a driver already is, and
because settled decision 5 (tap-to-start / tap-to-stop) maps to a stateful toggle icon, which 6a
documents support for. `inferred`

---

## 7. Is "tap play, produce no audio" hostile to Android Auto?

Nothing in the primary docs forbids it, and nothing documents a watchdog. What the docs *do* give:

### What is documented and cuts in LEGION's favour

- **`STATE_CONNECTING` is a sanctioned "working on it" state.** For search that cannot resolve
  quickly: "don't block in `onPlayFromSearch`. Instead, set playback state to `STATE_CONNECTING`
  and perform the search on an async thread." `documented`
  https://developer.android.com/training/cars/media/voice-actions
  This is the closest documented analogue to "play was tapped, something is happening, no stream
  yet". It is the state LEGION should sit in while the call is being placed. `inferred`
- **`STATE_ERROR` + `setErrorMessage()` is the documented failure surface**, with a localized
  user-facing string; and if the user must touch the phone, say so in the message. `documented`
  https://developer.android.com/training/cars/media/errors

```kotlin
mediaSession.setPlaybackState(
    PlaybackStateCompat.Builder()
        .setState(PlaybackStateCompat.STATE_ERROR)
        .setErrorMessage(PlaybackStateCompat.ERROR_CODE_NOT_AVAILABLE_IN_REGION,
                        getString(R.string.error_unsupported_region))
        .build())
```

- **Actionable errors get a button**: `PLAYBACK_STATE_EXTRAS_KEY_ERROR_RESOLUTION_ACTION_LABEL`
  plus `PLAYBACK_STATE_EXTRAS_KEY_ERROR_RESOLUTION_ACTION_INTENT` (a `PendingIntent`). That is a
  documented, legitimate way to hand a driver a one-tap escape hatch. `documented`
  https://developer.android.com/training/cars/media/errors
- **`STATE_NONE` makes the playback UI inaccessible** — "only use when the app has nothing to
  play." Avoid it; it would hide LEGION's own play button. `documented`
  https://developer.android.com/training/cars/media/enable-playback
- **Initial state must be non-playing.** Documented requirement: an app must not autoplay on
  connect; initial state is one of `STATE_STOPPED` / `STATE_PAUSED` / `STATE_NONE` / `STATE_ERROR`.
  Car app quality `MA-1` says the same: "must not autoplay on startup or without user initiated
  action." `documented` (enable-playback; car-app-quality)
  LEGION complies trivially, since it never autoplays anything.
- **Required actions** the session must advertise: `ACTION_PLAY`, `ACTION_PAUSE`, `ACTION_STOP`,
  `ACTION_PLAY_FROM_MEDIA_ID`, `ACTION_PLAY_FROM_SEARCH`. `documented` (enable-playback)
  Note `ACTION_PAUSE` and `ACTION_STOP` are required; ticket 06 must decide what pause means when
  the "playback" is a phone call. `inferred` that mapping pause -> mute and stop -> hang up is the
  natural reading.

### What is NOT documented

- **No documented playback-state watchdog, no "nothing is playing" timeout, no penalty for a session
  that never reaches `STATE_PLAYING`.** Searched the media-for-cars pages (overview, auto,
  enable-playback, errors, voice-actions, content-hierarchy) and the car app quality guidelines.
  Nothing. The quality guidelines constrain the opposite direction: they forbid *auto*play.
- **No documented statement that the play control must produce an audio stream.**
- Two real risks remain unresolvable from docs and must be settled on the unit:
  1. Android Auto may keep showing a loading/buffering affordance indefinitely, or bounce back to
     the browse view, if the state stays `STATE_CONNECTING`.
  2. Placing a telephony call from a media `onPlay()` may itself cause AA to swap to the call UI
     and tear down or background the media session, which would be the *desired* outcome but could
     equally look like a crash to AA.

**Smallest on-device experiment that settles Q7** (real head unit + A17k, one stub APK, one
evening):

1. Build a stub `MediaLibraryService` with a one-node browse tree and a `Player` that does nothing.
2. Tap play. Set `STATE_CONNECTING` and **never leave it**. Sit for 5 minutes. Record: does AA show
   a spinner forever, time out, show an error, or drop back to browse? Screenshot the head unit
   (logcat is unusable on this phone).
3. Repeat with the state parked at `STATE_PLAYING` and a silent player reporting a monotonically
   advancing position. Same observations. This is the "lie about playing" fallback if step 2 fails.
4. Repeat with the state going `STATE_CONNECTING` -> real self-managed call placed. Record whether
   AA renders the call and what happens to the media session.

Step 4 also feeds ticket 01 (the ruling the whole map rests on), so run all four in one sitting.

---

## Answers in one table

| Q | Answer | Confidence |
|---|---|---|
| 1 | `com.google.android.gms.car.application` meta-data + `automotive_app_desc.xml` with `<uses name="media"/>` + service with both `androidx.media3.session.MediaSessionService` and `android.media.browse.MediaBrowserService` actions, `exported="true"`, `foregroundServiceType="mediaPlayback"` | `documented` |
| 2 | media3 `MediaLibraryService`. Costs: declare the legacy browse action, supply a real `Player`, no pagination, read root hints via `androidx.media` `MediaConstants` | `documented` + `inferred` on the costs |
| 3 | **Both.** Developer mode (tap Version and permission info x10) unlocks the menu; an Unknown sources setting inside it is the actual switch. Explicitly covers media apps. Docs say enable once; **silent on reset after an AA update** | mixed: `documented` / `field-report` |
| 4 | No. Review is bound to Play release tracks; open testing and production block, internal sharing and internal testing do not. Sideloading is unaddressed in the docs | `documented` gate shape, `inferred` conclusion |
| 5 | Root: browsable-only, ~4, read `BROWSER_ROOT_HINTS_KEY_ROOT_CHILDREN_LIMIT`. Deeper: hint-driven on projected AA; `CarUxRestrictions` is AAOS-only. Violations degrade **silently** | `documented` for root, `inferred` for the AA/AAOS split |
| 6 | Playback custom actions: unbounded count, space-dependent, overflow beyond; ordered as added; vector + tintable + per-state icons; reserve or free the skip slots deliberately. Custom **browse** actions are a separate mechanism with a runtime limit from root hints | `documented` |
| 7 | Not documented as hostile. `STATE_CONNECTING` is the sanctioned in-between; `STATE_ERROR` + resolution `PendingIntent` is the sanctioned failure. **No documented watchdog.** Needs the four-step stub experiment | `inferred`, needs `on-device` |

---

## Open items handed back to the map

1. **Does the Unknown sources setting survive an Android Auto update?** Docs silent. Directly
   determines whether ticket 12's setup step is one-time or recurring. Only a real AA update
   settles it; record the AA version alongside the toggle state now.
2. **Does a never-`STATE_PLAYING` session get punished?** Docs silent. Stub-APK experiment above.
   This is a prerequisite to ticket 06, not a nicety.
3. **Do projected root hints change between parked and driving?** Docs silent. Log the bundle.
4. **What do `ACTION_PAUSE` and `ACTION_STOP` mean when playback is a phone call?** Both are
   documented-required. A design call for ticket 06, not a research finding.

---

## Assumptions ledger

| Claim | Tag |
|---|---|
| Manifest meta-data, `automotive_app_desc.xml` contents, service intent-filter actions and permissions | `traced` to developer.android.com pages cited inline; not compiled |
| `MediaLibraryService.SERVICE_INTERFACE` = `androidx.media3.session.MediaLibraryService` | `traced` to androidx/media release-branch source |
| media3 recommended over media-compat for new car media apps | `traced` (developer.android.com/media/implement/surfaces/cars) |
| Developer mode covers media apps and excludes Car App Library apps | `traced` (developer.android.com/training/cars/testing) |
| "Unknown sources" is a distinct named checkbox inside AA developer settings | `reasoned` from field reports; **not** in first-party docs |
| Developer mode persists ("only need to enable once") | `traced`; persistence across an AA *update* is `reasoned` at best and untested |
| Play car review is track-scoped and therefore skipped by a sideloaded app | gate shape `traced` (developer.android.com/training/cars/distribute); "therefore skipped" is `reasoned` |
| Root children ~4, browsable-only, hint-driven; no pagination; violations degrade silently | `traced` (content-hierarchy page) |
| `CarUxRestrictions` is AAOS-only and not queryable from a projected AA phone app | `reasoned` from package location; not verified on device |
| Custom playback action ordering, slot reservation, vector/tintable icon rules | `traced` (enable-playback page) |
| Custom browse action limit comes from `BROWSER_ROOT_HINTS_KEY_CUSTOM_BROWSER_ACTION_LIMIT`, 0 means unsupported | `traced` (custom-browse-actions page) |
| PTT belongs in playback custom actions rather than browse actions | `reasoned` |
| No documented watchdog punishing a session that never plays | `reasoned` from an exhaustive read of the six cars/media pages plus car-app-quality; absence of evidence, not evidence of absence |
| `STATE_CONNECTING` is the right holding state while the call is placed | `reasoned` from its documented use for slow `onPlayFromSearch` |
| Every on-unit behaviour in Q3/Q5/Q7 | **not** `on-device`. Nothing here has been run on the head unit or the A17k. |

## Sources

- https://developer.android.com/training/cars/media
- https://developer.android.com/training/cars/media/auto
- https://developer.android.com/training/cars/media/create-media-browser
- https://developer.android.com/training/cars/media/create-media-browser/content-hierarchy
- https://developer.android.com/training/cars/media/create-media-browser/custom-browse-actions
- https://developer.android.com/training/cars/media/enable-playback
- https://developer.android.com/training/cars/media/errors
- https://developer.android.com/training/cars/media/voice-actions
- https://developer.android.com/training/cars/testing
- https://developer.android.com/training/cars/distribute
- https://developer.android.com/training/cars/apps/media
- https://developer.android.com/media/implement/surfaces/cars
- https://developer.android.com/media/media3/session/background-playback
- https://developer.android.com/docs/quality-guidelines/car-app-quality
- https://developer.android.com/reference/android/car/drivingstate/CarUxRestrictions
- https://github.com/androidx/media (`libraries/session/src/main/java/androidx/media3/session/MediaLibraryService.java`)
- Field reports only: https://support.google.com/androidauto/thread/444013881 ,
  https://www.androidauthority.com/android-auto-developer-settings-3621170/
