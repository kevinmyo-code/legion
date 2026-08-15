# Can a sideloaded Car App Library app run on Android Auto?

Ticket: `.scratch/android-auto/issues/16-can-a-sideloaded-car-app-library-app-run.md`
Map: `.scratch/android-auto/map.md`
Researched: 2026-08-13

Every claim tagged `documented` (URL that owns it), `inferred`, or `field-report`. This ticket can
reverse an out-of-scope ruling, so read the tags, not the headline.

---

## Short answer

**Reading (B), and it is narrower than the ticket feared: (B) applies to REAL VEHICLES only.**

Google never writes one disambiguating sentence. But the two sentences that *do* exist sit on the
same page, one paragraph apart, and together they close it:

1. Real vehicle requires a trusted install source.
2. The only stated exception is Unknown sources.
3. Unknown sources excludes Car App Library apps.

Therefore a Car App Library app has no documented route into a real head unit that is not a trusted
install source. `inferred`, but from two verbatim `documented` sentences with nothing in between
them, which is the strongest form this evidence takes.

**The Desktop Head Unit is untouched by any of it.** The DHU's own documented prerequisite is
"Compile and install your app on the device". No Play, no trusted source, no developer-mode toggle.
`documented`. So the development loop exists and works for an app that will never be uploaded.

**Field reports agree with (B) on real units and name a mechanism Google does not document:**
gearhead appears to filter on the recorded *installer package*, and sideloaded template apps
reportedly become visible when reinstalled with `pm install -i "com.android.vending"`. This is
`field-report` and it is **contested** (one 2022 OsmAnd report says the trick stopped working on
Android 11+). It is the single cheapest experiment and it is the one that could still flip this to
(A)-in-practice.

**But question 3 is the harder blocker, and its answer is no.** No car app category honestly covers
a cross-aspect personal assistant. IOT is the closest and it covers the Shelly garage door *only*.
Money, notes, workouts and goals on a car screen are outside every category's stated criteria. That
is a rejection waiting if it ever met Play, and it is a dishonest declaration either way.

---

## Q1. Which reading is correct?

### The three verbatim sentences

`documented` https://developer.android.com/training/cars/testing

> "To test your app in real vehicles, you must install it from a trusted source such as Google Play,
> with one exception detailed in Allow unknown sources."

> "Android Auto has a developer option that lets you run apps that aren't installed from a trusted
> source."

> "This setting applies to media, messaging notifications, and parked apps but doesn't apply to apps
> built using the Android for Cars App Library."

### Why this resolves to (B), scoped

`inferred` (from the three quotes above, no intervening text): the "must install from a trusted
source" rule is stated for **real vehicles**. Unknown sources is named as the *one* exception. Car
App Library apps are named as outside that exception. An app class outside the only exception to a
"must" is subject to the "must". So: in a real vehicle, a Car App Library app must come from a
trusted source.

This is not reading (A). (A) would require the sentence to mean "these apps are exempt from the
trusted-source requirement", which would make them *more* freely installable than media apps, and
the same page's real-vehicle sentence forbids exactly that.

**Google never states (B) affirmatively.** There is no page saying "Car App Library apps cannot be
sideloaded". The conclusion is composed, not quoted. Label it accordingly wherever it is relayed.

`documented` - the DHU carve-out is explicit in the DHU page's own prerequisites, which never
mention install source:
https://developer.android.com/training/cars/testing/dhu

> "Compile and install your app on the device."
> "Enable developer mode on a mobile device running Android 9 (API level 28) or higher."
> "Install Android Auto on the device."

`documented` - Google's own Car App Library codelab runs a POI-category `CarAppService` from
Android Studio's Run button straight to DHU, with no Play step and no unknown-sources step:
https://developer.android.com/codelabs/car-app-library-fundamentals

`inferred`: the split is therefore **surface**, not app type. DHU accepts a locally installed CAL
app. A real head unit does not.

### What field reports say about real head units

All `field-report`. Docs are silent on mechanism, so these are admissible, and they are consistent.

- Organic Maps (an `androidx.car.app` NAVIGATION app) installed from F-Droid does **not** appear in
  Android Auto even with Unknown sources enabled, while F-Droid media apps on the same phone do.
  Maintainer: *"Unfortunately, it's a known Google's limitation specifically for Navigation apps"*.
  https://github.com/orgs/organicmaps/discussions/10532
- CoMaps (Organic Maps fork), same symptom, closed as invalid because it is a deliberate Google
  restriction. The thread names the mechanism as **install-source verification** and the workaround
  as spoofing the installer to Play, via Obtainium + Shizuku or an installer tool.
  https://codeberg.org/comaps/comaps/issues/1234
- microG issue: marking a map app as installed by `com.android.vending` *"fixes the known issue of
  the map application not working with Android Auto if not installed by the Play Store"*.
  https://github.com/microg/GmsCore/issues/3030
- **Contradicting report**, and it is the one that matters: OsmAnd issue 15400 says on Android 11+
  *"apparently the package installer name is not needed anymore"*, yet a sideloaded OsmAnd still
  does not reach the launcher while a sideloaded VLC (media) does. Unresolved, still open.
  https://github.com/osmandapp/OsmAnd/issues/15400

`inferred`, and this is the reconciliation worth testing: `adb install -i` / `pm install -i` sets
the **installing** package name, which shell may set freely. It does not set the **initiating**
package name, which the platform records itself. If gearhead moved from checking the former to
checking `InstallSourceInfo.getInitiatingPackageName()`, the spoof would have worked on Android 7
and stopped working later, which is exactly the shape of the OsmAnd report. Unverified. Nothing in
AOSP or gearhead was read this session to confirm which field gearhead reads (gearhead is closed
source; research 01 already established that).

### The smallest experiment that settles it

One stub APK, one head-unit session. Kevin has all three rigs.

Build: empty `CarAppService`, `androidx.car.app.category.IOT`, `minCarApiLevel 1`,
`HostValidator.ALLOW_ALL_HOSTS_VALIDATOR`, one `PaneTemplate` reading "LEGION probe". Nothing else.
Separate package id from `com.kevin.legion` so a failed probe cannot poison the real app's record.

| Arm | Setup | Distinguishes |
|---|---|---|
| A | DHU, installed by Android Studio | Confirms the app and manifest are correct at all. Expect PASS. If this fails, everything below is noise. |
| B | Real head unit, AA developer mode + Unknown sources ON, same install | The literal ticket question. (A) predicts it appears. (B) predicts it does not. |
| C | Real head unit, same APK reinstalled `adb install -r -i com.android.vending <apk>` | Tests the installer-attribution theory. Appears here but not in B means the gate is install attribution and is defeatable. |
| D | Read back `adb shell dumpsys package <pkg>` and record `installerPackageName`, `installInitiatingPackageName`, `installOriginatingPackageName` after C | Tells you *which* field the spoof actually moved on the A17k's Android version, which is what makes C's result interpretable rather than folklore. |

Also record the AA build (research 01 has `17.2.662634-release` on the A17k) beside the result. This
is host behaviour, not framework behaviour, so it can change under an AA update.

Cost: one evening, shares a drive with research 01's E1/E2/E3. **Do not re-open the map's
out-of-scope ruling before arm B runs.** The map already says so and this finding does not overturn
it.

### One route Google documents that the map has not costed

`documented` https://developer.android.com/training/cars/distribute - the form-factor review table:

| Track | Form factor review |
|---|---|
| Internal sharing (Android Auto only) | None |
| Internal testing | None |
| Closed testing | Non-blocking |
| Open testing | **Blocking** |
| Production | **Blocking** |

`inferred`: **Google Play Internal app sharing is a trusted source with zero car review.** It is a
link, not a store listing, not a price, not a public artifact. It would satisfy the real-vehicle
trusted-source rule for a CAL app without entering category review at all. The map ruled out "Play
Store distribution and Google's Android Auto category approval"; internal app sharing is arguably
neither. It still costs a Play Console account and an upload per build, which is a real ergonomic
tax on a two-phone sideload workflow, and it still means Google holds the artifact. **This is a
decision for Kevin, not a research finding.**

---

## Q2. How is a Car App Library app developed at all?

`documented` - the loop, in Google's own order:

1. Enable Android **device** developer options (not Android Auto developer mode).
2. Compile and install the app on the phone.
3. Install/update Android Auto from Play.
4. SDK Manager > SDK Tools > **Android Auto Desktop Head Unit Emulator**.
5. Unlock the phone, run the DHU, accept ToS on first connect.

https://developer.android.com/training/cars/testing/dhu

`documented` - the codelab confirms the same loop end to end for a real CAL app, including that
the DHU launcher shows the app with no allowlist step:
https://developer.android.com/codelabs/car-app-library-fundamentals

**Does the documented loop work for an app that will never be uploaded to Play? Partly.**

- DHU: yes, fully. `inferred` from the prerequisites making no reference to install source.
- Android Automotive OS emulator: yes, but AAOS is out of scope on this map.
- **Real vehicle: no documented loop exists.** The only documented routes are a trusted source, or
  the unknown-sources exception which excludes CAL apps. `inferred`, per Q1.

`inferred` consequence for LEGION: the DHU answers "does it render, does the layout fit, does the
button appear". It cannot answer the three things this map actually needs a car for: the HFP mic,
audio focus against Spotify, and whether a real gearhead build filters the app. The DHU is a
rendering loop, not a verification rig. Do not let a green DHU run stand in for an on-unit step
under L11.

---

## Q3. Categories, and which one LEGION could honestly declare

### The list

`documented` https://developer.android.com/training/cars/apps/library/set-up-project - declare one
or more as a `<category>` in the `CarAppService` intent filter:

| Category constant | Google's stated purpose | Gate |
|---|---|---|
| `androidx.car.app.category.NAVIGATION` | "Provides turn-by-turn navigation instructions." | Standard car review |
| `androidx.car.app.category.POI` | "Provides functionality relevant to finding points of interest such as parking spots, charging stations, and gas stations." | Standard |
| `androidx.car.app.category.IOT` | "Enables users to take relevant actions on connected devices from within the car." | Standard, CarApi 6 |
| `androidx.car.app.category.WEATHER` | "Lets users see relevant weather information related to their current location or along their route." | Standard, CarApi 7 |
| `androidx.car.app.category.MEDIA` | "Lets users browse and play music, radio, audiobooks, and other audio content in the car." | Beta, CarApi 8, internal/closed tracks |
| `androidx.car.app.category.MESSAGING` | "Lets users communicate with short-form text messages." | Experimental / beta |
| `androidx.car.app.category.CALLING` | "Lets users communicate with voice calling." | Beta, internal/closed tracks only |

`documented` - additional constants exist in `CarAppService` source that the setup page does not
list: `CATEGORY_SETTINGS_APP` (`androidx.car.app.category.SETTINGS`, CarApi 6, "settings pages and
error resolution screens"), `CATEGORY_FEATURE_CLUSTER`, and the deprecated `PARKING` / `CHARGING`
(both folded into POI).
https://github.com/androidx/androidx/blob/androidx-main/car/app/app/src/main/java/androidx/car/app/CarAppService.java

**There is no generic, template-only, or "other" category.** Checked the setup page, the library
overview and the `CarAppService` constant list. `documented` by exhaustion of the enumerated list;
`inferred` that the absence is deliberate.

`documented` - the requirement is stated as binding, not advisory:

> "Your app must belong to one of the supported categories and meet specific design requirements
> before it can be listed on Google Play for Android Auto and Android Automotive OS."

https://developer.android.com/training/cars/apps

`documented` - car app quality: *"Apps must not include features outside the app types intended for
cars."* https://developer.android.com/docs/quality-guidelines/car-app-quality

### What IOT permits, since it is the closest

`documented`, criteria `IT-1`, same quality-guidelines page. **While driving**, an IOT app **may**:

- View the current state of devices (garage door open/closed, light on/off, alarm armed).
- Simple, one-touch on/off control, including turning a pre-programmed scene or routine on and off.
- Notify the user about an event at home or another location.

While driving it **must not**: any app setup; any create/modify/reorder of scenes or routines; any
fine-grained control (thermostat degrees, dimmer levels).

### The honest ruling for LEGION

**None of them fit, and the closest is a stretch that only covers one aspect.**

- **IOT** is the only category LEGION can point at real functionality for: `vehicle/` already has
  the Shelly garage integration, and "open the garage door" plus "is the garage door open" is
  *exactly* `IT-1`'s two worked examples. But `IT-1` describes an app about connected devices. A
  `PaneTemplate` showing ledger balances, goals, notes and workouts under an IOT declaration is
  outside the stated criteria and would be "features outside the app types intended for cars".
- **POI** requires finding points of interest. LEGION has `location/PlaceController` and tagged
  places, so a places screen is defensible, but the assistant is not a POI finder and the aspects
  are not places.
- **WEATHER** is claimable for `weather/WeatherController` alone and nothing else. Same shape as
  IOT: a real but tiny sliver.
- **NAVIGATION** is a lie. LEGION does no turn-by-turn; the map killed embedded nav (CLAUDE.md §3).
- **MEDIA / MESSAGING / CALLING** are all beta and Play-track-gated, which compounds Q1's problem
  rather than solving it.

`inferred`: the practical position is that **the category is a runtime key, not just a policy
label** - the host reads it to decide which special-purpose templates the app may push - and a
sideloaded app never meets Play review, so nobody at Google ever audits the claim. Research 02
already established review is track-scoped. So declaring IOT would *function*. It would just not be
true, and "it functions because nobody checks" is not a route this project takes. The honest framing
for Kevin: **if the CAL door is taken, LEGION is declaring itself something it is not, and the only
category with any real substance behind it covers the garage door and nothing else.**

---

## Q4. Is `androidx.car.app.category.CALLING` reachable?

**No, and its status has not moved since research 01.**

`documented` https://developer.android.com/training/cars/communication/calling

> "Calling experiences are in beta. At this time, anyone can publish communication apps with calling
> experiences to internal testing and closed testing tracks on the Play Store. Publishing to open
> testing and production tracks will be permitted at a later date."

Plus: do not promote calling builds to open testing or production, such submissions are rejected;
early access is by form nomination; the app must integrate Core-Telecom and support the remote
surface callbacks at all times, not only under Android Auto.

`documented` - if an app supports both calling and messaging, both categories go in the same intent
filter.

**Can a sideloaded debug build declare it?** Declaring a category is one manifest line, so nothing
prevents the string being present. Whether gearhead honours it is the Q1 question again, plus a
beta-programme gate on top. `inferred`: two gates in series, and the outer one (Q1) already has no
documented route on a real head unit.

`inferred`: this does **not** change research 01's conclusion. The self-managed `ConnectionService`
route is category-free at the framework layer and reaches the car over HFP regardless. Research 01's
E1 remains the experiment that matters for the in-call surface; ticket 16 adds nothing that rescues
the CALLING category.

---

## Q5. What can a template actually draw, and is a persistent button possible?

**Yes. A persistent action button is possible, and it is not a list row.** This is the finding that
most directly serves Kevin's stated requirement.

`documented` https://developer.android.com/design/ui/cars/guides/templates/overview - the
general-purpose templates carry **no category restriction column** at all: List, Grid, Sectioned
Item, **Pane**, Message, Long Message, Search, Sign-in, Tab. Only the special-purpose four are
category-bound: Navigation (NAVIGATION), Place List map (POI), Map + Content (NAVIGATION / POI /
WEATHER), Media Playback (MEDIA).

`inferred`: so whatever category LEGION declared, `PaneTemplate`, `GridTemplate` and `ListTemplate`
are all available. The category choice does not restrict the pane Kevin wants.

### PaneTemplate specifically

`documented`
https://github.com/androidx/androidx/blob/androidx-main/car/app/app/src/main/java/androidx/car/app/model/PaneTemplate.java

- Rows: bounded by `ConstraintManager.CONTENT_LIMIT_TYPE_PANE`, queried from the host at runtime.
  Rows beyond the limit are **discarded by the host**, silently.
- Each row: max 2 text lines. Rows **cannot** carry a `Toggle` or an `OnClickListener`. A pane row
  is read-only text.
- **Up to 2 `Action`s in the Pane itself.** These are the prominent buttons.
- **Up to 2 `Action`s in the `ActionStrip`**; one may have a title, the rest must be icon-only.
- Header is hidden unless a header action, header title, or action strip is set.

`documented`
https://github.com/androidx/androidx/blob/androidx-main/car/app/app/src/main/java/androidx/car/app/constraints/ConstraintManager.java
- `CONTENT_LIMIT_TYPE_LIST` / `GRID` / `PLACE_LIST` / `ROUTE_LIST` / `PANE`. Values are host-supplied
  minimums from `R.integer#content_limit_*`; hosts may support more. Query with `getContentLimit`,
  never hardcode.

`documented`
https://github.com/androidx/androidx/blob/androidx-main/car/app/app/src/main/java/androidx/car/app/model/ActionStrip.java
- "a map template may display them as a group of floating action buttons (FABs) over the map
  background". Standard (non-custom) action types cannot be duplicated. Spans in action titles are
  ignored.

`inferred`, and this is the shape to hand forward: **a `PaneTemplate` with LEGION's aspect summary
as read-only rows plus a push-to-talk `Action` in the pane and a second `Action` (mute or end) is
exactly what the template is built for.** It is not a list of rows where the button is one row. It
matches settled decision 5 (tap-to-start / tap-to-stop) because a CAL `Action` is a single-tap
`OnClickListener`, and CAL has no press-and-hold gesture anywhere.

### Refresh limits while driving

`documented` (PaneTemplate javadoc) - a new template counts as a **refresh** rather than a new step
if either the previous template was in a loading state, or the title is unchanged **and** the row
count and row titles are unchanged. Changing row *text* while keeping titles is a refresh. Changing
the number of rows or their titles is a new step.

`documented` https://developer.android.com/design/ui/cars/guides/ux-requirements/plan-task-flows
- the host limits a task to **five templates**. The limit counts templates, not `Screen` instances.
  Refreshes, back, and reset do not count. The final step must not be a list- or grid-based
  template. Do not build a flow of five list/grid templates in a row.

`inferred`: LEGION's pane can update its numbers indefinitely without burning steps, provided row
count and row titles stay fixed. That is a real design constraint on how the aspect summary is
composed: fixed rows with changing values, never a variable-length list. **No documented refresh
rate cap in Hz was found** on any page read this session; the constraint is structural (what counts
as a step) rather than temporal. Absence of evidence.

---

## Q6. `CarAudioRecord`

`documented`
https://github.com/androidx/androidx/blob/androidx-main/car/app/app/src/main/java/androidx/car/app/media/CarAudioRecord.java
and https://developer.android.com/reference/androidx/car/app/media/CarAudioRecord
and https://developer.android.com/training/cars/apps/library/car-microphone

- `@RequiresCarApi(5)`. Android Auto 7.9+ per research 04.
- Requires `android.permission.RECORD_AUDIO`, declared in the manifest **and** granted at runtime.
- Created as `CarAudioRecord.create(carContext)`. **It takes a `CarContext`.** A `CarContext` exists
  only inside a live `CarAppService` `Session`.
- Format is fixed: `AUDIO_CONTENT_MIME` = `audio/l16`, `AUDIO_CONTENT_SAMPLING_RATE` = 16000 Hz,
  `AUDIO_CONTENT_BUFFER_SIZE` = 512 bytes. Mono 16-bit PCM at 16 kHz.
- The host draws a microphone indicator on the car screen while recording.
- **When the user dismisses that indicator, the next `read()` returns `-1`** and the app must
  discard the data and stop.
- Guidance: acquire audio focus before recording so you do not capture ongoing media; stop if focus
  is lost.

**Which category does it need?** None specifically. `inferred` - it needs a `CarAppService` and a
`CarContext`, which is the whole library, not a particular category. No category is named in the
javadoc or the training page.

**Alongside or instead of the app's own `AudioRecord`?** `inferred`: **instead**, for the car mic.
`CarAudioRecord` is a host-mediated stream with its own fixed format and its own user-visible
dismiss affordance. It is not a route that a plain `AudioRecord` can be swapped onto, and there is
no documented way to run both against the same physical mic. LEGION's `GeminiLiveSession` already
owns an `AudioRecord`; adopting `CarAudioRecord` means a second capture path selected by whether a
car session is live, not a replacement for the phone path.

`inferred`: 16 kHz mono PCM matches what Gemini Live wants for input, so the format is not a
blocker. The blockers are that it requires the library (Q1), and that the host can revoke the
stream mid-utterance with a `-1` that LEGION's session loop would have to handle as a user cancel,
not as an error.

**This does not change research 04's ruling.** `MODE_IN_COMMUNICATION` + `setCommunicationDevice`
remains a public-API route to the same physical microphone with no library and no category. It is
still the route that survives if Q1's answer is (B).

---

## Q7. Can one APK be both a Car App Library app and a media app?

**Yes. Documented, with a worked example.**

`documented` https://developer.android.com/training/cars/apps/media

```xml
<automotiveApp xmlns:android="http://schemas.android.com/apk/res/android">
    <uses name="media"/>
    <uses name="template"/>
</automotiveApp>
```

> "You still must provide a `MediaSession` for playback controls, and a `MediaBrowserService` or
> `MediaLibraryService`, which is used for recommendations and other smart experiences."

> "If you distribute a single APK, it will support vehicles that are enabled for Android Automotive
> OS with the Car App Library host and fall back to a `MediaBrowserService` or `MediaLibraryService`
> application if not, even for older Android versions."

Single-APK case also needs
`<uses-feature android:name="android.software.car.templates_host.media" android:required="false"/>`.

For `CATEGORY_MEDIA` specifically: `<uses-permission android:name="androidx.car.app.MEDIA_TEMPLATES"/>`
and `minCarApiLevel` 8, because `MediaPlaybackTemplate` is CAL API 8+.

`inferred`, and it is the useful reading for this map: **the two declarations do not conflict, and
the fallback is exactly the shape LEGION would want.** An APK carrying both `<uses name="media"/>`
and `<uses name="template"/>` shows its media browse tree wherever the template half is refused.
If Q1's arm B says gearhead drops the CAL half on a sideloaded build, the media half still appears,
because unknown sources explicitly covers media. That is a graceful degradation, not a broken app.

`inferred` caveat: this is Google's documented single-APK story for the **AAOS-vs-not** fallback,
not for a **sideloaded-vs-Play** fallback. Nothing documents what a projected Android Auto host does
with an app declaring both when it refuses one half. Arm B of the Q1 experiment should be run with
a stub carrying **both** declarations, so it answers Q7's real question in the same session at no
extra cost.

---

## What this means for the map's out-of-scope ruling

The map rules `androidx.car.app` out of scope and warns "Do not re-open it on inference".

**This finding does not re-open it.** It sharpens the ruling into two separable facts:

1. **On a real head unit, the CAL door is closed to a plain sideload.** `inferred` from two
   documented sentences plus four consistent field reports. Not verbatim, not proven on device.
2. **The thing Kevin actually asked for is a `PaneTemplate` with an `Action`, and it exists.**
   `documented`. So the requirement is not architecturally impossible; it is distribution-blocked.

That reframes the map's open question from "should CAL come back in scope" to a Kevin decision with
three branches, none of which is a research finding:

- **Accept the media ceiling.** Push-to-talk lives in a playback custom action (research 02, Q6a).
  No category lie, no Play, works today. Loses the aspect pane.
- **Run the Q1 experiment, arms B/C/D.** If C works, the CAL door is defeatable by install
  attribution, on a mechanism Google does not document and may change under any AA update. Two
  phones, permanent maintenance tax, and it still needs a category declaration LEGION cannot make
  honestly.
- **Play internal app sharing.** Documented, zero car review, trusted source. Costs a Play Console
  account and an upload per build, and still needs an honest category, which is the blocker Q3
  says LEGION does not have.

`inferred`: **Q3 is the harder wall than Q1.** Even if arm C works perfectly, LEGION would be
declaring IOT for an app that is 15 percent garage door and 85 percent something no category
describes. Worth putting to Kevin in that order.

---

## Assumptions ledger

| # | Claim | Tag |
|---|---|---|
| 1 | Testing page's three sentences (real vehicles / developer option / doesn't apply to CAL) quoted verbatim | traced (developer.android.com/training/cars/testing, exact text retrieved) |
| 2 | Those three sentences compose to reading (B) for real vehicles | reasoned. Composed, never stated by Google. **This is the ticket's answer and it is an inference.** |
| 3 | DHU accepts a locally installed CAL app; prerequisites never mention install source | traced (DHU page prerequisites + the CAL codelab's run steps) |
| 4 | gearhead filters CAL apps by recorded installer package, defeatable with `pm install -i com.android.vending` | field-report, four sources, **and contested** by OsmAnd 15400 for Android 11+ |
| 5 | `pm install -i` sets the installing package but not the initiating package, which would explain the Android 11+ regression | reasoned. `InstallSourceInfo` javadoc was NOT retrieved this session. Unverified. |
| 6 | No documented development loop reaches a real head unit for a never-published CAL app | reasoned from claims 1-3; absence of evidence across testing, dhu, distribute, apps pages |
| 7 | Play internal app sharing carries no car form-factor review | traced (developer.android.com/training/cars/distribute review table) |
| 8 | Internal app sharing would satisfy the trusted-source rule | reasoned. Google does not define "trusted source" anywhere read this session. |
| 9 | Full category list and their exact constant strings | traced (set-up-project page + `CarAppService.java` on androidx-main) |
| 10 | No generic / template-only / other category exists | traced by exhaustion of the enumerated list; the *deliberateness* is reasoned |
| 11 | IOT `IT-1` permitted and forbidden actions while driving | traced (car-app-quality, quoted) |
| 12 | No category honestly fits LEGION; IOT covers the Shelly garage door only | reasoned, and it is a judgement about LEGION, not a fact about Google |
| 13 | Category is a runtime key the host reads, and a sideloaded app is never audited against the quality criteria | reasoned; research 02 established the review is track-scoped |
| 14 | CALLING is beta, internal/closed Play tracks only, form-nominated early access | traced (developer.android.com/training/cars/communication/calling, current as of this session) |
| 15 | General-purpose templates (incl. PaneTemplate) carry no category restriction | traced (design/ui/cars/guides/templates/overview table); the "therefore any category can push a Pane" step is reasoned |
| 16 | PaneTemplate: rows host-limited and silently discarded, 2 text lines per row, no Toggle, no row click, 2 pane Actions, 2 ActionStrip actions with one titled | traced (androidx-main `PaneTemplate.java` javadoc) |
| 17 | A persistent push-to-talk button is possible as a pane `Action` | reasoned from claim 16; never built, never rendered |
| 18 | Five-template task limit; refresh does not count; refresh means unchanged title + unchanged row count and titles | traced (plan-task-flows + `PaneTemplate.java`) |
| 19 | No documented refresh rate cap while driving | reasoned from absence across the pages read; not proof |
| 20 | `CarAudioRecord`: `@RequiresCarApi(5)`, RECORD_AUDIO, `create(carContext)`, audio/l16 16 kHz 512-byte buffer, `-1` on user dismissal | traced (androidx-main `CarAudioRecord.java` + reference page + car-microphone training page) |
| 21 | `CarAudioRecord` needs no specific category, only a live `CarAppService` session | reasoned from the `CarContext` parameter; no page states a category requirement either way |
| 22 | `CarAudioRecord` replaces rather than coexists with the app's own `AudioRecord` for the car mic | reasoned; no documentation addresses running both |
| 23 | One APK may declare both `<uses name="media"/>` and `<uses name="template"/>` | traced (training/cars/apps/media, quoted) |
| 24 | The media half survives if a projected host refuses the template half on a sideload | reasoned. Google's single-APK fallback story is about AAOS-vs-not, **not** sideload-vs-Play. |
| 25 | Everything on a real head unit | **NOT verified.** Nothing in this finding was run on the A17k, the DHU, or the car. Arms A-D are all outstanding. |

## Sources

- https://developer.android.com/training/cars/testing
- https://developer.android.com/training/cars/testing/dhu
- https://developer.android.com/training/cars/distribute
- https://developer.android.com/training/cars/apps
- https://developer.android.com/training/cars/apps/library/set-up-project
- https://developer.android.com/training/cars/apps/library/car-microphone
- https://developer.android.com/training/cars/apps/iot
- https://developer.android.com/training/cars/apps/media
- https://developer.android.com/training/cars/communication/calling
- https://developer.android.com/codelabs/car-app-library-fundamentals
- https://developer.android.com/design/ui/cars/guides/templates/overview
- https://developer.android.com/design/ui/cars/guides/ux-requirements/plan-task-flows
- https://developer.android.com/docs/quality-guidelines/car-app-quality
- https://developer.android.com/reference/androidx/car/app/media/CarAudioRecord
- androidx-main source: `car/app/app/src/main/java/androidx/car/app/CarAppService.java`,
  `.../model/PaneTemplate.java`, `.../model/ActionStrip.java`,
  `.../constraints/ConstraintManager.java`, `.../media/CarAudioRecord.java`
- Field reports only: https://github.com/orgs/organicmaps/discussions/10532 ,
  https://codeberg.org/comaps/comaps/issues/1234 ,
  https://github.com/microg/GmsCore/issues/3030 ,
  https://github.com/osmandapp/OsmAnd/issues/15400 ,
  https://support.google.com/androidauto/thread/444013881
