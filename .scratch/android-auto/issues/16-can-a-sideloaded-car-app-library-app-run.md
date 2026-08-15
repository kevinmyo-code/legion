# Can a sideloaded Car App Library app run on Android Auto?

Type: research
Status: resolved
Blocked by: -

## Question

**This ticket exists because Kevin's stated requirement does not match the app this map charted.**

Kevin, 2026-08-13: *"I don't need the now playing bar... I just need a push to talk button, and some
kind of UI display to see my aspects."* A media app cannot deliver that. Media apps render lists of
**playable items** and own the active media session, which also evicts Spotify from the split-screen
card he wants kept (ticket 11 item 0). A pane of your own content plus your own action button is a
**Car App Library** app - `androidx.car.app` - which this map **ruled out of scope while charting**,
on a disguise choice made before any of this was known.

Everything turns on one sentence, which two research tickets have now hit from opposite directions
and neither could settle. Google's Android Auto testing page says the unknown-sources developer
setting **"doesn't apply to apps built using the Android for Cars App Library."** That admits two
opposite readings:

- **(A)** Those apps do not *need* the toggle - they are sideloadable by some other route, or freely.
- **(B)** Those apps cannot be sideloaded at all, and the toggle is irrelevant because Play is the
  only door.

If (A), Kevin's actual requirement is buildable and the media disguise was the wrong door.
If (B), it is unreachable on a sideloaded build and the media browse list is the ceiling.

Establish, against primary sources (developer.android.com Android for Cars App Library docs, the
Android Auto testing and distribution pages, `androidx.car.app` reference and release notes, Play
Console car-app policy pages, AOSP / androidx source):

1. **Which reading is correct?** Cite the exact page and quote it. If Google never disambiguates,
   say so and name the smallest experiment that settles it.
2. **How is a Car App Library app tested during development at all?** Google must document a
   development loop. What is it - a debug build plus developer mode, a Play internal-testing track, a
   Desktop Head Unit only, an allowlist? Does the documented loop work for an app that will **never**
   be uploaded to Play?
3. **Which categories exist today**, what each permits on screen, and what approval each needs. LEGION
   is a personal assistant over fleet, money, notes, body and goals. **Which category, if any, could
   it honestly declare?** Say plainly if the honest answer is "none of them fit and the closest is a
   stretch" - a category LEGION cannot legitimately claim is not a route, it is a rejection waiting.
   Cover at minimum POI, IOT, and whether a generic or template-only category exists.
4. **Is `androidx.car.app.category.CALLING` reachable?** Research 01 found the Android Auto in-call
   surface appears bound to it, and that it is a beta programme on Internal/Closed Play tracks only.
   Confirm its current status and whether a sideloaded debug build can declare it.
5. **What can a template actually draw?** For the best-fit category: which templates are permitted
   (`PaneTemplate`, `GridTemplate`, `ListTemplate`, `NavigationTemplate`), how many rows and actions,
   what refresh rate limits apply while driving, and **whether an always-visible action button - a
   push-to-talk - is possible**, or whether every action is a row in a list.
6. **`CarAudioRecord`** (API level 5, Android Auto 7.9+). Research 04 named it the only *documented*
   way for a projected app to read the car microphone. Confirm what it requires: which category,
   which permission, and whether it works alongside or instead of the app's own `AudioRecord`.
7. **Can one APK be both** a Car App Library app and a media app, or do they conflict?

State which claims are **documented** (with URL) and which are **inferred**. This ticket can reverse
an out-of-scope ruling on the map, so weak evidence must be labelled as weak.

Findings go to `.scratch/android-auto/research/16-can-a-sideloaded-car-app-library-app-run.md`.

## Answer

**What Kevin asked for exists and is documented. Two gates stand in front of it, and neither is
architectural: distribution, and category honesty.** Full findings and citations:
[research/16-can-a-sideloaded-car-app-library-app-run.md](../research/16-can-a-sideloaded-car-app-library-app-run.md).
Resolved 2026-08-13 from a research agent's report; tags are the agent's, carried forward unchanged.
**Nothing was verified on device.**

1. **Reading (B), scoped to real vehicles - and this is `reasoned`, not documented.** Google never
   disambiguates in one sentence, but three verbatim sentences sit on one page with nothing between
   them: testing in real vehicles **must** use a trusted source, with one exception; the exception is
   the unknown-sources developer option; and that option "doesn't apply to apps built using the
   Android for Cars App Library". An app class outside the only exception to a "must" is subject to
   the "must". **That composition is the agent's, never Google's. Relay it as `reasoned`.**
2. **The Desktop Head Unit is untouched by any of it.** Its documented prerequisite is only "compile
   and install your app on the device", and Google's own Car App Library codelab runs a POI app from
   Android Studio's Run button straight to the DHU. **So a development loop exists for an app that
   will never see Play** - it just cannot reach a real head unit. The map declined to depend on the
   DHU while charting; that call should be revisited.
3. **Field reports name a mechanism Google does not document, and they contradict each other.**
   gearhead appears to filter on the recorded installer package, and sideloaded template apps
   reportedly appear after `pm install -i "com.android.vending"` (Organic Maps, CoMaps, microG).
   **Contested** by OsmAnd issue 15400, which says it stopped working on Android 11+. Likely
   explanation (`reasoned`): `-i` sets the *installing* package, not the *initiating* one.
4. **Question 3 is the harder wall and the answer is no.** The full category list is NAVIGATION, POI,
   IOT, WEATHER, MEDIA, MESSAGING, CALLING, plus SETTINGS and FEATURE_CLUSTER in source. **No generic
   or template-only category exists.** IOT is closest and covers the Shelly garage door alone, which
   matches Google's own worked examples - but money, notes, workouts and goals on screen are
   "features outside the app types intended for cars". **Even if the distribution trick works
   perfectly, LEGION would be declaring IOT for an app that is mostly not IOT.** That is a claim
   about honesty, and it is Kevin's to make or refuse.
5. **Question 5 is the good news, and it is exactly Kevin's requirement.** General-purpose templates
   carry **no category restriction**, so `PaneTemplate` is available whatever category is declared.
   It takes read-only rows plus **two prominent pane Actions** and **two ActionStrip actions**. **A
   persistent push-to-talk button is a first-class Action, not a list row**, and single-tap matches
   settled decision 5. Design constraint: keep row count and row titles fixed and vary only the
   values, or every refresh burns one of the five permitted task steps.
6. **CALLING is unchanged** from research 01: beta, internal/closed Play tracks only, nomination by
   form. Two gates in series behind item 1.
7. **`CarAudioRecord` needs no specific category**, only a live `CarAppService` session.
   `@RequiresCarApi(5)`, `RECORD_AUDIO`, fixed `audio/l16` at 16 kHz. The host may revoke mid-utterance
   with `read()` returning -1, **which is a user cancel and not an error**. It **replaces** the app's
   own `AudioRecord` rather than coexisting. Research 04's non-library microphone route survives
   intact, so this is an alternative, not a dependency.
8. **One APK can be both** a media app and a template app (`documented`, with a worked example), and
   the media half degrades gracefully if the template half is refused - though Google's fallback story
   is about AAOS versus not, not sideload versus Play.

**A route the map never costed: Play internal app sharing.** It carries **no car form-factor review**
(documented table) and **is a trusted source**. It is a link, not a listing, so it arguably falls
outside this map's "Play Store distribution and category approval" exclusion. It still needs an
honest category, which item 4 says LEGION does not have. **Kevin's call, not a research finding.**

**The out-of-scope ruling is NOT reversed - it is sharpened.** The Car App Library door looks closed
to a plain sideload on a real head unit, but that is `reasoned` plus contested field reports, **not
proven**. The thing Kevin asked for is documented and buildable. The blocker is distribution and
category honesty, not architecture. **Experiment:** one stub CAL APK declaring **both**
`<uses name="media"/>` and `<uses name="template"/>`, four arms - DHU sanity, real unit with unknown
sources, real unit reinstalled with `-i com.android.vending`, and reading back all three install-source
fields from `dumpsys package`. Shares one evening with ticket 14's E1-E3.
