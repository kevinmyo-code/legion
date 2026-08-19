# Does voice search still deliver a raw spoken query to a media app?

Ticket: `.scratch/android-auto/issues/03-does-voice-search-still-deliver-a-raw-query.md`
Researched: 2026-08-13
Tags: every non-trivial claim is `documented` (with URL), `inferred`, or `field-report`.

---

## Verdict up front

| Claim from charting | Holds? |
|---|---|
| `onPlayFromSearch` still fires from Android Auto's assistant | **Yes**, `documented` |
| The `query` argument is "the raw spoken query" | **NO.** `documented` as a re-assembled token bag, not the user's sentence |
| The raw sentence is recoverable anyway | **Probably yes**, via an **undocumented** extra `android.intent.extra.user_query`. `field-report` only |
| "on LEGION" routes to a sideloaded app Google never indexed | **UNSETTLED.** Google's only primary statement points at Play Console registration. Must be tested on-unit |
| Empty query is a clean free entry point | **Yes**, `documented`, and it is the strongest leg of the three |

The load-bearing revision: **the third leg is weaker than charted.** `onPlayFromSearch` is a
*music-search* channel with a documented, lossy normalisation applied to the words. Treating it as
"a text pipe for arbitrary sentences to LEGION" is not what the platform says it is. The empty-query
case (settled decision 2, tap-or-say to start the call) is untouched by any of this and is safe.

---

## 1. Does `onPlayFromSearch` / `onPrepareFromSearch` still fire, and is the query raw?

**It fires.** `documented`:

> "When Android Auto or AAOS detects and interprets a voice action, Android Auto or AAOS delivers
> that voice action to the app through `onPlayFromSearch`."
> -- https://developer.android.com/training/cars/media/voice-actions

Still current for the Gemini transition. `documented`: the same Android for Cars guidance is stated
to apply "to Gemini too, if you've set it as your default assistant"; Google's own announcement is
that Gemini "will understand the same commands as Google Assistant."
(https://developer.android.com/training/cars/media/voice-actions ;
https://www.android.com/articles/how-to-use-gemini-on-android-auto/). Assistant wind-down date
2026-09-04 is press, `field-report` (9to5google). **The callback surface is unchanged; only the NLU
behind it changes.** That is a stability claim about the API, not about NLU output quality - see the
risk note in §3.

**AOSP javadoc, `documented`** (`frameworks/base` `media/java/android/media/session/MediaSession.java`,
https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/java/android/media/session/MediaSession.java):

```java
/**
 * Override to handle requests to begin playback from a search query. An
 * empty query indicates that the app may play any music. The
 * implementation should attempt to make a smart choice about what to play.
 */
public void onPlayFromSearch(String query, Bundle extras) {}
```

`onPrepareFromSearch` is the identical contract with "prepare" for "begin playback". Note the AOSP
javadoc says **nothing** about the query being verbatim.

### The query is NOT the user's sentence

This is the finding that matters. The platform contract for this channel is
`MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH`, and `SearchManager.QUERY` is documented
per-search-mode as an *assembled combination of the recognised entities*, `documented`
(https://developer.android.com/guide/components/intents-common, "Play music based on a search query"):

| `EXTRA_MEDIA_FOCUS` | Documented content of `QUERY` |
|---|---|
| *Any* -- `vnd.android.cursor.item/*` | "**an empty string**" |
| *Unstructured* -- `vnd.android.cursor.item/*` | "a string that contains any combination of the artist, the album, the song name, or the genre" |
| *Artist* -- `Audio.Artists.ENTRY_CONTENT_TYPE` | "any combination of the artist or the genre" |
| *Album* -- `Audio.Albums.ENTRY_CONTENT_TYPE` | "any combination of the album or the artist" |
| *Song* -- `vnd.android.cursor.item/audio` | "any combination of the album, the artist, the genre, or the title" |
| *Genre* -- `Audio.Genres.ENTRY_CONTENT_TYPE` | "the genre" |
| *Playlist* -- `Audio.Playlists.ENTRY_CONTENT_TYPE` | "any combination of the album, artist, genre, playlist, or title" |

Every row is *entities re-joined*, never "what the user said". Carrier words, word order and anything
the NLU failed to slot are not promised to survive.

Corroborated by a real device trace, `field-report` (Danny Preussler, SoundCloud's Android Auto
integration, https://proandroiddev.com/interpreting-voice-results-for-android-media-apps-in-cars-f36d7bdb26e1).
Spoken: **"Play Live from Moderat on SoundCloud."** Bundle received:

| key | value |
|---|---|
| `android.intent.extra.focus` | `vnd.android.cursor.item/*` |
| `android.intent.extra.user_query` | the full original command |
| `query` | `"live moderat"` |
| `android.intent.extra.artist` | `"Moderat"` |
| `android.intent.extra.title` | `"Live"` |

`query` lost "play", "from", "on SoundCloud", and reordered nothing usefully. **For a LEGION sentence
like "ask LEGION what my coolant temp is", the analogous mangling is fatal** -- `inferred`, but it is
the direct read of the documented contract above, not speculation about an edge case.

### The raw sentence: `android.intent.extra.user_query`

`SearchManager.USER_QUERY = "user_query"` is `documented` and means exactly the right thing
(AOSP `core/java/android/app/SearchManager.java`):

> "Use this key with `content.Intent.getStringExtra()` to obtain the query string typed in by the
> user. This may be different from the value of `QUERY` if the intent is the result of selecting a
> suggestion."

But the observed key is the **fully-qualified** `android.intent.extra.user_query`, which is **not**
the value of `SearchManager.USER_QUERY` (`"user_query"`) and is **not** a documented public constant
anywhere I could find. It is `field-report` only, from GSA's own bundle. **Do not hardcode it as a
contract.** Treat it as: read it if present, fall back to `query` plus the typed extras, never
require it.

### Media3 note

If LEGION publishes via `androidx.media3.session.MediaSession` rather than `MediaSessionCompat`, the
legacy callbacks are bridged, not lost. `documented` (source), `androidx/media`
`MediaSessionLegacyStub.java`:

```java
@Override
public void onPlayFromSearch(@Nullable String query, @Nullable Bundle extras) {
  handleMediaRequest(
      createMediaItemForMediaRequest(/* mediaId= */ null, /* mediaUri= */ null, query, extras),
      /* play= */ true);
}
```

which builds `MediaItem.RequestMetadata` with `.setSearchQuery(query).setExtras(extras)` and routes
to `onSetMediaItems`. **Nothing is dropped** -- the whole extras bundle survives, including the
undocumented `user_query`. `RequestMetadata.searchQuery` javadoc is thin: "The search query for the
requested media, or null if not applicable."
(https://github.com/androidx/media, `libraries/common/.../MediaItem.java`).

---

## 2. The `extras` bundle

**Documented set for Android Auto / AAOS**
(https://developer.android.com/training/cars/media/voice-actions):
`EXTRA_MEDIA_ALBUM`, `EXTRA_MEDIA_ARTIST`, `EXTRA_MEDIA_GENRE`, `EXTRA_MEDIA_PLAYLIST`,
`EXTRA_MEDIA_TITLE`. Note that page **does not list `EXTRA_MEDIA_FOCUS`**; the focus extra is
documented on the intent contract (`intents-common`, table above) and in the Assistant media guide
sample (https://developer.android.com/media/implement/assistant).

**Does a focus extra mean Assistant already decided it is a song title?** No, and this is the useful
part for LEGION.

- `documented`: the *Any* and *Unstructured* modes **share** the focus value
  `vnd.android.cursor.item/*`. A focus of `vnd.android.cursor.item/*` therefore carries **no**
  category decision at all -- it distinguishes "nothing structured found" from nothing.
- `field-report`: Preussler found the focus was **always** `vnd.android.cursor.item/*` in practice,
  a value with no SDK constant, and that keying off it was useless. His working approach was to
  ignore focus and test which of `EXTRA_MEDIA_ARTIST` / `EXTRA_MEDIA_ALBUM` / `EXTRA_MEDIA_TITLE`
  came back non-null.

**Recoverability of the raw sentence** -- three tiers, in the order LEGION should try them:

1. `android.intent.extra.user_query` -- full sentence. `field-report`, undocumented, may vanish.
2. `query` -- entity tokens, space-joined, lossy. `documented`.
3. Typed extras -- individual entities. `documented`.

`inferred`: for a non-music sentence the NLU has no artist/album/title to slot, so the most likely
outcome is *Unstructured* focus with `query` holding whatever nouns survived. Whether that is enough
to feed `AriaBrain` is a **product-quality** question that only the on-unit test in §7 can answer.

---

## 3. App-name routing -- the weak point. Honest answer: undocumented.

**Google documents the phrase shape but never the resolution mechanism.** `documented`
(https://developer.android.com/guide/topics/media-apps/interacting-with-assistant), which lists:

| Callback | Phrase |
|---|---|
| `onPlayFromSearch()` | "Play *(song \| artist \| album \| genre \| playlist)* on *(app name)*." |
| `onPlayFromSearch()` | "Play music or songs on *(app name)*." (empty query) |
| `onPlayFromSearch()` | "Play *(podcast)* on *(app name)*." |

Nowhere on that page, on `/training/cars/media/voice-actions`, or on `/media/implement/assistant`
does Google state what *(app name)* is matched against. I looked for it specifically. **It is not
documented.** Anyone who tells you it is the launcher label is reporting a field observation.

### The one primary statement that bears on it, and it cuts the wrong way

`documented` (https://developer.android.com/develop/devices/assistant/overview):

> "Try it out: **Have an app published to the Play Store on your device?** Launch it by telling
> Assistant, 'Hey Google, open AppName.' Assistant can open your app with no integration work
> required from you."

and

> "When you upload your app using the Google Play console, Google registers the capabilities declared
> in your app and makes them available for users to access from Assistant."

Read literally, Google's own framing of name-based invocation is **server-side, keyed to a Play
Console upload**. LEGION will never have one (map, Out of scope). That is the single strongest
primary signal, and it is a signal *against* the charted assumption.

**Counter-considerations, all `inferred`:**

- The `open AppName` sentence is about *launching an activity*, a different pipeline from media
  session routing. It is not a statement about `onPlayFromSearch`, so it is suggestive, not binding.
- Android Auto already builds its media-app list **locally**, from installed packages declaring a
  `MediaBrowserService` plus the `automotive_app_desc` `<uses name="media"/>` meta-data
  (`documented`: https://developer.android.com/training/cars/media). An on-device list of media apps
  with labels therefore exists in the car session. Whether the assistant's disambiguator reads that
  local list or a server-side catalogue is exactly the undocumented step.
- App Actions / built-in intents are the *explicitly server-registered* path, and they are being
  deprecated (Google Play developer community, `field-report`). Media apps are steered to the
  MediaSession path instead, which is the more locally-resolved of the two. Weak evidence for local
  matching.

### The strongest field evidence, and its limits

Music Assistant's own integration doc is the best public write-up of this exact problem,
`field-report` (https://github.com/music-assistant/mobile-app/blob/main/docs/ANDROID-AUTO.md):

> "The app's launcher label must remain 'Music Assistant' for routing to work -- Assistant matches
> by this label."

It also reports, all `field-report`, and every one of these is a hazard LEGION inherits:

- **Sideloaded/self-signed builds need Android Auto developer mode -> "Unknown sources"**, plus
  enabling the app in launcher customization. (This part is independently `documented`, see below.)
- **Users must opt in once per assistant.** Google Assistant: Settings -> Google Assistant -> Music,
  select the app or "No default provider". **Gemini: Apps / Connected apps, toggle the app on.**
- **"Default provider conflict": Spotify or another certified default can pre-empt routing.**
- **"Android Auto routing interference": "on <name>" may be ignored if another app owns the active
  `MediaSession`.** Pause the other app first.
- After install it can take minutes or a reboot for the NLU index to refresh.

**Limit on that evidence:** Music Assistant *does* ship a Play Store build, and its doc distinguishes
"Production Play Store builds work automatically" from the sideload path. So it is **not** proof that
a name Google has never indexed gets routed. It is proof that the label matters *given* an app Google
knows about. **The exact LEGION case -- never-indexed name, sideloaded, self-signed -- is untested by
any source I found.**

**The sideloading half is documented**, and is a real prerequisite either way
(https://developer.android.com/training/cars/testing):

> "Android Auto has a developer option that lets you run apps that aren't installed from a trusted
> source. This setting applies to media, messaging notifications, and parked apps..."

> "To test your app in real vehicles, you must install it from a trusted source such as Google Play,
> with one exception detailed in Allow unknown sources."

### Extra risk from the Gemini cutover

`inferred`, flagged because it is cheap to be wrong about: app-name routing is an NLU behaviour, not
an API. Even if it works on the head unit in August 2026, the Assistant -> Gemini migration is a
wholesale NLU replacement. **A LEGION design whose only voice entry point is name-matched routing is
betting on an undocumented behaviour across a platform migration.** Weight the empty-query and
media-list-tap entry points accordingly.

**Bottom line for the ticket: leg three is not established. Do not let a decision rest on it until
the §7 experiment runs.** "LEGION" is also a plain English word, which cuts both ways -- easy for ASR
to transcribe, easy for the NLU to swallow into the query as a noun instead of treating it as a
target app.

---

## 4. Empty query -- confirmed, and this one is solid

**Three independent documented statements, and they agree.**

1. AOSP javadoc: "An empty query indicates that the app may play any music. The implementation
   should attempt to make a smart choice about what to play."
2. Android for Cars: "Account for an empty `query` string, which can be sent by Android Auto or AAOS
   if the user doesn't specify search terms. For example, if the user says 'Play some music.'"
   (https://developer.android.com/training/cars/media/voice-actions)
3. Intent contract, *Any* mode: `EXTRA_MEDIA_FOCUS` = `vnd.android.cursor.item/*`, `QUERY` = "an
   **empty string**" (required). (https://developer.android.com/guide/components/intents-common)

The Assistant media guide lists the phrase "Play music or songs on *(app name)*." as the empty-query
producer (https://developer.android.com/guide/topics/media-apps/interacting-with-assistant).

**Caveat, and it is not a small one:** the *documented* empty-query phrases still contain
*(app name)*. **Empty query removes the search-parsing risk, not the app-routing risk.** The truly
name-free path is not voice at all -- it is the driver tapping LEGION in Android Auto's media list,
which is settled decision 2 and depends on nothing in this ticket. `inferred`, but directly from the
phrase table above.

Design consequence: LEGION must treat empty-query `onPlayFromSearch` as **"start the call"**, and it
must not wait for further input before acting -- `documented`, the Assistant guide requires a media
app receiving an empty search to begin playing immediately rather than prompt.

---

## 5. Alternatives for getting spoken free text in

### `onSearch` via the browse tree -- the real second channel

`documented` (https://developer.android.com/training/cars/media/create-media-browser/browsable-search):
declare it in `onGetRoot`'s extras.

```kotlin
val extras = Bundle()
extras.putBoolean(MediaConstants.BROWSER_SERVICE_EXTRAS_KEY_SEARCH_SUPPORTED, true)
return BrowserRoot(ROOT_ID, extras)
```

`documented`: `onSearch` is invoked when a user initiates a search query, and again when the user
opens the "Search results" affordance in the playback view to see results of the most recent search.
The same page states **"All apps must support voice searches"** and separately describes supporting
"the initiation of searches without voice" -- so **the affordance is voice-first with an optional
non-voice path**, which is the answer the ticket asked for.

**But the query is tokenised here too.** `documented`, `MediaBrowserServiceCompat.onSearch` javadoc:

> "The search query sent from the media browser. **It contains keywords separated by space.**"

`inferred`: "keywords separated by space" is the same lossy shape as `onPlayFromSearch`'s `query`.
**Neither media channel promises a sentence.** This does not rescue leg three; it is a second door
with the same lock.

**What it does buy**: `documented` -- `onSearch` fires from the *browse UI's own* search affordance,
reached by tapping search inside LEGION's browse tree. **That is a name-free entry point.** No "on
LEGION" required, because the user is already inside LEGION. `inferred`: this is the strongest
mitigation available for §3's risk, and it is cheap -- one boolean in `onGetRoot` plus an `onSearch`
override. **Recommend building it regardless of how leg three resolves.**

### Dead ends

- **App Actions / built-in intents** (`actions.intent.PLAY_MEDIA`, `shortcuts.xml` `<capability>`):
  explicitly Play-Console-registered (`documented`, assistant/overview) and being deprecated
  (`field-report`). Wrong path for a sideloaded app. Music Assistant does use it for the *phone-side*
  voice path, `field-report`, but that is off this map.
- **Grabbing the mic directly from a projected media app.** Not researched here; it is settled
  decision 3's territory and belongs to the call surface.

---

## 6. `onCustomAction` -- no, it carries no assistant text

`documented`, AOSP javadoc:

```java
/**
 * Called when a MediaController wants a PlaybackState.CustomAction to be performed.
 *
 * @param action The action that was originally sent in the PlaybackState.CustomAction.
 * @param extras Optional extras specified by the MediaController.
 */
public void onCustomAction(@NonNull String action, @Nullable Bundle extras) {}
```

The `action` string is one **the app itself** published in its own `PlaybackStateCompat`. Android
Auto renders each as a button in the playback view; tapping invokes the callback (`documented`,
https://developer.android.com/training/cars/media/enable-playback, plus the design guidance to use
monochrome vector icons and cap custom actions at 6, or 8 when Next/Previous are unused).

`inferred`: **no documented mechanism routes assistant-recognised free text into `onCustomAction`.**
The extras are "specified by the MediaController", and the only controller here is Android Auto
relaying a button tap with no user words attached. It is a **fixed-vocabulary tap surface**.

Still useful to LEGION, just not as a text channel: custom actions are the natural home for
tap-to-start-talking / tap-to-stop, which is exactly settled decision 5's shape (single tap, no
press-and-hold). `inferred`.

---

## 7. Smallest on-unit experiments

Rig available: real head unit + OPPO A17k (map, settled decision 6). L11 binds these.

**Prerequisite for all of them**, `documented` (training/cars/testing): Android Auto -> About -> tap
"Version and permission info" 10x -> Developer settings -> **Allow "Unknown sources"**, then enable
LEGION in launcher customization. Without this a self-signed LEGION will not appear at all and every
result below is a false negative.

### E1 -- the app-name routing test (settles §3, the weak point)

Smallest possible: a `MediaBrowserService` + `MediaSession` that does nothing but **log**. No call, no
audio, no browse tree beyond a root. Override `onPlayFromSearch` and dump `query` plus **every key in
`extras`** to an on-screen buffer.

Per MEMORY.md, **logcat is useless on the OPPO A17k for LEGION's own logs** -- it filters app logs.
So the dump must go to a UI surface, a file, or `MediaMetadata` rendered in the AA playback view. Do
not plan on `adb logcat`.

Then, plugged into the head unit, speak in this order and record which fire:

1. "Hey Google, play some music on LEGION" -> **empty query expected.** Settles Q4 on-unit and is the
   cheapest signal that name matching works at all.
2. "Hey Google, play jazz on LEGION" -> does a non-empty query arrive, and what is in it?
3. "Hey Google, ask LEGION what my coolant temperature is" -> **the real question.** Does anything
   fire? What survives in `query`? Is `android.intent.extra.user_query` present?
4. Rename the launcher label to something absurd, reinstall, repeat step 1. If routing dies with the
   label, matching is label-based and **local** -- which would be the finding that rescues leg three.

Pre-flight for each: pause every other media app first (`field-report`: an app owning the active
`MediaSession` can swallow "on <name>"), and check the assistant's default-music-provider setting.
Set it to "No default provider" so a certified default cannot pre-empt.

### E1b -- offline pre-check on the phone alone, no car

`documented` shell path (adapted from Music Assistant's `field-report`, and the intent contract in
intents-common):

```
adb shell am start -a android.media.action.MEDIA_PLAY_FROM_SEARCH \
  --es query "jazz" \
  --es android.intent.extra.focus "vnd.android.cursor.item/*"
```

This exercises **the intent path, not the assistant's NLU or its app matching**, so it proves the
handler and the extras plumbing while proving nothing about §3. Run it first anyway -- it removes
LEGION-side bugs before the car trip, so a null result in E1 means "Google did not route" rather than
"we do not know".

### E2 -- browse-tree search (settles §5's affordance question)

Add `BROWSER_SERVICE_EXTRAS_KEY_SEARCH_SUPPORTED = true` in `onGetRoot`, override `onSearch`, dump
the query the same way. On the head unit: does a search affordance render in LEGION's browse view,
and is it voice, keyboard, or blocked while driving? Confirms the name-free entry point.

---

## Recommendations to the map

1. **Do not close any decision on leg three until E1 step 3 runs.** Charting's claim that a raw query
   arrives is contradicted by the documented intent contract, so a design that parses `query` as a
   sentence is building on something Google says is not there.
2. **Build the empty-query entry point first.** It is triply documented, and it is the same "start
   the call" action as tapping the media item -- one code path, two triggers.
3. **Build `onSearch` browse-tree search regardless** (one boolean plus one override). It is the only
   free-text channel that needs no app-name matching, and it degrades gracefully if §3 fails.
4. **Read the extras defensively, in this order**: `android.intent.extra.user_query` if present ->
   `query` -> typed extras. Never require the undocumented key, never key off `EXTRA_MEDIA_FOCUS`
   alone.
5. **Flag Gemini cutover as a standing risk** on any decision that leans on name-matched routing.
6. **The developer-mode "Unknown sources" toggle is a permanent operating requirement**, not a test
   convenience. It belongs in whatever install runbook the build tickets produce.

---

## Sources

Primary, Google:
- https://developer.android.com/training/cars/media/voice-actions
- https://developer.android.com/training/cars/media/create-media-browser/browsable-search
- https://developer.android.com/training/cars/media
- https://developer.android.com/training/cars/media/enable-playback
- https://developer.android.com/training/cars/testing
- https://developer.android.com/media/implement/assistant
- https://developer.android.com/guide/topics/media-apps/interacting-with-assistant
- https://developer.android.com/guide/components/intents-common
- https://developer.android.com/develop/devices/assistant/overview

Primary, source:
- https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/java/android/media/session/MediaSession.java
- https://github.com/aosp-mirror/platform_frameworks_base/blob/master/core/java/android/app/SearchManager.java
- https://github.com/androidx/media -- `libraries/session/.../MediaSessionLegacyStub.java`,
  `libraries/common/.../MediaItem.java`

Field reports (used only where Google is silent, labelled as such above):
- https://proandroiddev.com/interpreting-voice-results-for-android-media-apps-in-cars-f36d7bdb26e1
- https://github.com/music-assistant/mobile-app/blob/main/docs/ANDROID-AUTO.md
- https://www.android.com/articles/how-to-use-gemini-on-android-auto/

---

## Assumptions ledger

| # | Claim | Tag |
|---|---|---|
| 1 | `onPlayFromSearch` is still the Android Auto voice-action delivery callback | `traced` -- Google doc, current |
| 2 | `query` is an assembled entity string, not the user's sentence | `traced` -- intent contract table, verbatim |
| 3 | Real bundles carry the full sentence at `android.intent.extra.user_query` | `reasoned` from one field report; **undocumented, unverified by me** |
| 4 | `android.intent.extra.user_query` is not the value of `SearchManager.USER_QUERY` (`"user_query"`) | `traced` -- AOSP source |
| 5 | `EXTRA_MEDIA_FOCUS` = `vnd.android.cursor.item/*` carries no category decision | `traced` -- *Any* and *Unstructured* share it |
| 6 | Focus is "always" that generic value in practice | `reasoned` from one field report; do not treat as platform behaviour |
| 7 | Empty query means "play anything, immediately" | `traced` -- three independent Google sources agree |
| 8 | Documented empty-query phrases still include *(app name)* | `traced` -- phrase table |
| 9 | App-name routing mechanism is undocumented by Google | `traced` -- searched the four relevant doc pages; absence, not silence-by-omission on my part |
| 10 | Assistant name invocation is framed by Google as Play-Console-registered | `traced` -- verbatim quote; **but it is about `open AppName`, not media routing** |
| 11 | Assistant matches media apps by launcher label | `reasoned` from one field report on a Play-published app; **the sideloaded never-indexed case is untested by any source** |
| 12 | Sideloaded media apps need AA developer mode + Unknown sources | `traced` -- Google doc, corroborated by field report |
| 13 | Default-provider and active-MediaSession conflicts can swallow "on <name>" | `reasoned` -- field report only; matters as test hygiene |
| 14 | Media3 preserves query and the whole extras bundle across the legacy bridge | `traced` -- `MediaSessionLegacyStub` source |
| 15 | `onSearch` query is space-separated keywords | `traced` -- `MediaBrowserServiceCompat` javadoc |
| 16 | Browse-tree search is a name-free entry point | `reasoned` from the documented invocation path |
| 17 | `onCustomAction` cannot carry assistant free text | `reasoned` -- AOSP javadoc plus absence of any routing doc; strong but negative evidence |
| 18 | Gemini keeps the same media callbacks | `traced` -- Google doc statement; the NLU-quality risk behind it is `reasoned` |
| 19 | Nothing here was run on the head unit or the OPPO A17k | `on-device`: **NONE.** E1/E1b/E2 in §7 are unperformed and are L11 gates |
