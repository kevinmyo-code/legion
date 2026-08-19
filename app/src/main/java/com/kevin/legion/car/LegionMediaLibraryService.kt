package com.kevin.legion.car

import android.app.ActivityManager
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.media.utils.MediaConstants
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import android.net.Uri
import androidx.media3.common.C
import com.kevin.legion.R
import com.kevin.legion.media.MusicController
import com.kevin.legion.media.NowPlayingController
import com.kevin.legion.media.NowPlayingInfo
import com.kevin.legion.service.AriaForegroundService
import com.kevin.legion.service.CompanionPhase
import com.kevin.legion.service.Phase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * WAVE 3 of the Android Auto probe harness (`.scratch/android-auto/map.md`) - a REAL browse tree,
 * per Kevin's direct ask ("i just need a push to talk button, and some kind of UI display to see my
 * aspects") and ticket 08's still-OPEN, still-PROVISIONAL decision on what the tree holds. Wave 1
 * ([onCreate]'s original doc, now superseded) served an empty root purely to prove visibility; this
 * wave serves real rows and wires push-to-talk to the actual live session.
 *
 * **Root shape (research 02 section 5 - browsable-only, ~4 items, real limit arrives at runtime):**
 * "Talk to LEGION" (push-to-talk, see [toggleTalk]), then a Fleet row carrying a LIVE deterministic
 * value in its title/subtitle built by [CarAspectSummaries.fleet] - the row IS the display Kevin
 * asked for, there is no separate screen. Both are BROWSABLE, not PLAYABLE: research 02 is explicit
 * that only browsable items are safe at root, and violating that degrades SILENTLY rather than
 * rejecting - logged every time in [libraryRootChildren] rather than risked blind. Tapping "Talk to
 * LEGION" fires [toggleTalk] as a side effect of browsing into it (its own children list is empty -
 * there is nowhere to browse TO, the tap itself is the action).
 *
 * **CORRECTED 2026-08-18** (Kevin, live on the Desktop Head Unit: "the interface and UI while in AA
 * right now sucks" - three of four tabs rendered "No items", because the old behaviour here was to
 * log and return an EMPTY child list for every aspect row). Fleet now opens onto a short list of
 * [CarAspectSummaries.CarRow]s ([CarAspectSummaries.fleetRows]) - live deterministic figures, read
 * at a glance, built from the same controllers the phone screens already read. **Not an LLM
 * briefing** - Kevin ruled against a Gemini call per tap (cost, latency, and CLAUDE.md §4 rule 5's
 * anchoring concern for anything spoken as fact). **A row tap does nothing but itself** - this is a
 * display, not a menu - and per Android Auto's browsable/playable-only item model (see
 * [talkActionItem]'s own doc for why a browsable child here would repeat the exact "No items" bug
 * one level deeper), every row is served as a PLAYABLE item whose play is a logged no-op returning
 * an EMPTY queue, in [ProbeLibraryCallback.onSetMediaItems] - the identical shape proven on a head
 * unit for [TALK_ACTION_MEDIA_ID], reused rather than re-invented.
 *
 * **RESCOPED 2026-08-18, an hour later** (Kevin, after seeing the four-tab build live: "i think the
 * android auto only has to show fleet data. we can leave out other stuff. because we're driving. we
 * just need 2 things. push to talk and codes/telemetry gauges"). This supersedes the four-tab root
 * above - `TODAY_MEDIA_ID`/`MONEY_MEDIA_ID` and their `onGetChildren` branches are deleted (grepped
 * first: [CarAspectSummaries.today]/`.money` had no callers outside this file and that object
 * itself, so nothing else is left half-served). Root is now exactly two rows: "Talk to LEGION" and
 * "Fleet". [FLEET_MEDIA_ID]'s own children are rebuilt in [CarAspectSummaries.fleetRows] around the
 * two things Kevin actually named - see that file's class doc for the row shapes.
 *
 * **Fact 2 from tonight, now load-bearing: Gearhead caches a subscription's children and does not
 * re-call [onGetChildren] for the same parentId on the same connection** (see the `TALK_MEDIA_ID`
 * branch below for where this was first proven). A gauge that never refreshes is worse than no
 * gauge, so [phaseWatcherJob]'s sibling [fleetRefreshJob] pushes [MediaLibrarySession.notifyChildrenChanged]
 * for [FLEET_MEDIA_ID] on a fixed 30 s tick - matching [com.kevin.legion.vehicle.TelemetryRecorder]'s
 * own cadence, not a push per PID read. `notifyChildrenChanged(parentId, itemCount, params)`'s
 * all-subscribers overload is a documented no-op when nobody is currently subscribed to that
 * parentId (media3 1.4.1's own `MediaLibraryService.java` KDoc, confirmed against the vendored
 * sources jar rather than assumed), so this loop costs nothing while Fleet is not the open tab.
 *
 * **Fact 3 from tonight, still UNVERIFIED, checked first because everything else here depends on
 * it: whether Android Auto renders a flags-0 (neither playable nor browsable) row at all.**
 * [infoRowItem] already serves Fleet's rows this way - that shape predates tonight's rescope, was
 * chosen specifically because a PLAYABLE info row hangs on "getting your selection" (see that
 * function's own doc for the exact on-device transcript), and nothing in this rescope changes it or
 * newly confirms it renders. If the next run shows Fleet's tab empty the way Today/Money once did,
 * that is the first thing to check - see the report this ticket shipped with for the exact rig steps.
 *
 * **Push-to-talk has TWO doors, both calling [toggleTalk]:** the "Talk to LEGION" row above, and a
 * [CommandButton] custom action on the transport bar (research 02 section 6a - the documented home
 * for a stateful PTT toggle, unbounded count, space-dependent rendering). Kept to exactly ONE custom
 * action per the brief. Both doors send the SAME [AriaForegroundService.ACTION_TALK] intent the
 * phone UI's `ui/assistant/AssistantStrip.kt` already sends on a tap - `LiveSessionController.onTap`
 * itself decides start-vs-stop from the session's own state, so this service never tracks that
 * decision locally; it only mirrors [CompanionPhase.phase] to decide which ICON to show.
 *
 * **The single most important thing in this file is [toggleTalk]'s try/catch.** Android 14 forbids
 * starting a `microphone` foreground service from the background (ticket 04's finding, named as the
 * single likeliest place this whole design fails), and nothing here may know in advance whether a
 * tap on the car surface counts as "foreground" to the platform. A probe that crashes teaches
 * nothing - every failure path here logs to [CarProbeLog] and leaves the surface usable.
 *
 * **WAVE 5 (2026-08-18, Kevin, after seeing the rig): "take the card, we show now playing with
 * media controls there. along with the push to talk button. if i need spotify i'll access it from
 * the launcher tray."** LEGION now becomes the ACTIVE media session and owns Android Auto's media
 * card outright, displacing Spotify's own card - an accepted, explicit tradeoff, not an oversight.
 * [LegionProxyPlayer] (renamed from the wave-1..4 `StubPlayer`, which really was a stub - it
 * produced no observable state at all) is a PROXY, not a player: it mirrors
 * [NowPlayingController.state] into the transport bar's metadata/art/play-pause, and forwards a
 * tap on PLAY/PAUSE/STOP to [MusicController] - the same object [com.kevin.legion.service.LiveToolbox]'s
 * `control_music` tool already calls - so the REAL session (almost always Spotify on this phone)
 * is what actually starts, pauses, or stops. LEGION never decodes or plays audio itself; there is
 * still no "real" `Player` underneath, only an honest mirror of one.
 *
 * **The loop this design creates, and where it is actually closed.** Once LEGION's own
 * [MediaSession] goes active, [android.media.session.MediaSessionManager.getActiveSessions] would
 * return it right alongside Spotify's - a session mirroring another session, both visible to the
 * same picker. If [NowPlayingController] or [MusicController] ever chose LEGION's own package as
 * the "real" source, the picture would repeat itself back at the card forever, and
 * `music_play_history` would log phantom rows for a "track" that was never anything but LEGION
 * quoting LEGION. **This is NOT handled in this file** - it is closed upstream, at the two places
 * that read [android.media.session.MediaSessionManager]: [NowPlayingController.choosePackage]
 * (the read side - what the card mirrors) and [MusicController]'s `activeSessions` (the control
 * side - what a tap here forwards to). Both exclude `com.kevin.legion`'s own package before any
 * other selection rule runs; see their own doc comments for why that ordering, not just the
 * exclusion, is load-bearing. This file only ever calls [MusicController]'s public functions and
 * reads [NowPlayingController.state] - it inherits the guarantee rather than re-implementing it,
 * which is deliberate: one place to get this right, not two.
 *
 * **What PAUSE and STOP mean here, decided and made honest (ticket 08 point 7).** PAUSE forwards
 * to [MusicController.pause] - the real music actually pauses. STOP has no distinct transport
 * primitive available on AVRCP/MediaSession (only play/pause/next/previous exist), so it maps to
 * the same real pause - see [LegionProxyPlayer.handleStop]'s own doc for why that is the honest
 * choice rather than a shortcut. **Neither one ever touches [toggleTalk] or
 * [AriaForegroundService.ACTION_TALK].** A driver pausing music mid-sentence must not hang up on
 * LEGION - the transport bar and the PTT custom action are two independent surfaces on the same
 * session, wired to two unrelated backends, and nothing in this file lets a tap on one reach the
 * other.
 *
 * **Skip slots: deliberately NOT reserved.** Research 02 section 6a's
 * `SESSION_EXTRAS_KEY_SLOT_RESERVATION_SKIP_TO_NEXT/PREV` extras exist to keep the native
 * prev/next slots visually EMPTY when an app has no skip capability at all, freeing that space for
 * custom actions instead of AA filling it with disabled defaults. LEGION never sets them (never
 * did, and this wave does not add them) - `inferred`, following research 02's own inference. The
 * reasoning is stronger now than it was for the original push-to-talk-only stub: [LegionProxyPlayer]
 * was tried against real native skip (`Player.COMMAND_SEEK_TO_NEXT`/`COMMAND_SEEK_TO_PREVIOUS`)
 * and DROPPED it after reading `androidx.media3.common.BasePlayer`'s own source rather than
 * assuming - `seekToNext()`/`seekToPrevious()` silently no-op via `ignoreSeek()` whenever
 * `hasNextMediaItem()`/`hasPreviousMediaItem()` is false, which it always is for a one-item mirrored
 * playlist (see [LegionProxyPlayer.getState]). Advertising those commands would have produced a
 * skip button that looks live and does nothing - worse than no button. So there is genuinely no
 * native skip to reserve space FOR, and leaving the extras unset lets AA's transport bar give the
 * freed prev/next-adjacent space to the [talkCommandButton] custom action instead of pushing it
 * toward overflow - exactly the "prime slot for PTT" outcome Kevin's brief asked for.
 */
class LegionMediaLibraryService : MediaLibraryService() {

    private var player: LegionProxyPlayer? = null
    private var session: MediaLibrarySession? = null

    // Backs onGetChildren's async work (aspect summaries read Room), the CompanionPhase
    // collector that keeps the transport custom action's icon/title in sync with the real session
    // state, and (wave 5) the NowPlayingController collector that keeps the proxy card truthful -
    // all cancelled in onDestroy.
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var phaseWatcherJob: Job? = null

    /** Pushes [FLEET_MEDIA_ID]'s live refresh - see the class doc's "Fact 2" paragraph for why this
     * exists at all (Gearhead never re-calls onGetChildren on its own) and why 30 s is the right
     * cadence rather than a push per PID read. Cancelled in onDestroy alongside [phaseWatcherJob]. */
    private var fleetRefreshJob: Job? = null

    /** Mirrors [NowPlayingController.state] into [LegionProxyPlayer] - see the class doc's WAVE 5
     * paragraph. Cancelled in onDestroy alongside the other two collectors. */
    private var nowPlayingWatcherJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        val proxyPlayer = LegionProxyPlayer(applicationContext)
        player = proxyPlayer
        session = MediaLibrarySession.Builder(this, proxyPlayer, ProbeLibraryCallback())
            .setId("legion-car-probe")
            .setCustomLayout(ImmutableList.of(talkCommandButton(active = false)))
            .build()
        CarProbeLog.log(
            "MediaLibraryService",
            "onCreate - wave 5, proxy now-playing card + two-tab browse tree + push-to-talk",
        )

        // Keeps the custom action's icon/title (tap-to-start vs tap-to-stop, settled decision 5)
        // truthful regardless of which door changed the session state - CompanionPhase is the
        // process-global mirror LiveSessionController.set() already publishes to, so this needs no
        // new plumbing into the session controller itself.
        phaseWatcherJob = serviceScope.launch {
            CompanionPhase.phase.collect { phase ->
                session?.setCustomLayout(ImmutableList.of(talkCommandButton(active = phase != Phase.IDLE)))
            }
        }

        // The card itself: NowPlayingController.state already excludes LEGION's own session (see
        // this class's doc for exactly where and why), so every value that reaches this collector
        // is genuinely external - Spotify, the phone's AVRCP bridge, whatever is really playing.
        // A null emission (nothing playing anywhere LEGION can see) is passed straight through
        // rather than special-cased here; LegionProxyPlayer.updateNowPlaying's own null branch is
        // what keeps the card from claiming to play something that does not exist.
        nowPlayingWatcherJob = serviceScope.launch {
            NowPlayingController.state.collect { info ->
                proxyPlayer.updateNowPlaying(info)
            }
        }

        // Fixed 30 s tick, not tied to any particular PID write - TelemetryRecorder's own cadence
        // is the anchor (see the class doc). notifyChildrenChanged's all-subscribers overload is a
        // documented no-op with no current subscriber, so this is free while Fleet is not open.
        fleetRefreshJob = serviceScope.launch {
            while (true) {
                delay(FLEET_REFRESH_INTERVAL_MS)
                session?.notifyChildrenChanged(FLEET_MEDIA_ID, Int.MAX_VALUE, null)
                CarProbeLog.log("MediaLibraryService", "fleetRefreshJob tick - notified $FLEET_MEDIA_ID")
            }
        }
    }

    /** Every binder connection - including Android Auto's own - lands here first. */
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession {
        CarProbeLog.log(
            "MediaLibraryService",
            "onGetSession package=${controllerInfo.packageName} uid=${controllerInfo.uid} trusted=${controllerInfo.isTrusted}",
        )
        return session ?: error("onGetSession called before onCreate built a session")
    }

    override fun onDestroy() {
        CarProbeLog.log("MediaLibraryService", "onDestroy")
        phaseWatcherJob?.cancel()
        fleetRefreshJob?.cancel()
        nowPlayingWatcherJob?.cancel()
        serviceScope.cancel()
        session?.release()
        session = null
        player?.release()
        player = null
        super.onDestroy()
    }

    /**
     * Builds [ROOT_MEDIA_ID]'s children (item 1's real browse tree), reading the root-children
     * limit off [browser]'s connection hints exactly as research 02 section 5 documents, and
     * logging the hint value and the served count EVERY time - "violations degrade silently" per
     * the docs, so the only way tonight's session can see a truncation is a log line, not the UI.
     */
    private suspend fun libraryRootChildren(browser: MediaSession.ControllerInfo): List<MediaItem> {
        val hints = browser.connectionHints
        val limit = hints.getInt(MediaConstants.BROWSER_ROOT_HINTS_KEY_ROOT_CHILDREN_LIMIT, DEFAULT_ROOT_CHILD_LIMIT)
        val supportedFlags = hints.getInt(
            MediaConstants.BROWSER_ROOT_HINTS_KEY_ROOT_CHILDREN_SUPPORTED_FLAGS,
            // The default research 02 section 5 documents is the PLATFORM MediaBrowser.MediaItem's
            // FLAG_BROWSABLE (android.media.browse), a different class from media3's own MediaItem
            // used everywhere else in this file - purely diagnostic (logged below, never branched
            // on), so the literal constant is inlined rather than importing the platform class just
            // for its value.
            FLAG_BROWSABLE_DEFAULT,
        )

        // CORRECTED 2026-08-18, third pass (Kevin, live on a real head unit at 800x480: "Talk to
        // LEGI..." and "Fleet · 2017 ..." both cut off in the tab bar). Root's own children are the
        // TABS, and the tab bar has almost no width - "Talk" and "Fleet" are now hardcoded here
        // rather than sourced from CarAspectSummaries.fleet()'s richer "Fleet · $label" string,
        // which used to feed the tab label directly and is exactly what got truncated. The vehicle
        // name and the live due-date/mileage figure are not lost - they are the Fleet tab's own
        // FIRST row (shapeConnectionRow, inside fleetRows), which is where a driver who actually
        // opened the tab reads them, with real room to not truncate.
        val all = listOf(
            browsableItem(TALK_MEDIA_ID, "Talk", "Tap to start a conversation", R.drawable.ic_car_talk_start),
            browsableItem(FLEET_MEDIA_ID, "Fleet", "Connection, codes, and live gauges", R.drawable.ic_car_fleet),
        )
        val served = all.take(limit)
        CarProbeLog.log(
            "MediaLibraryService",
            "libraryRootChildren limit=$limit supportedFlags=$supportedFlags built=${all.size} served=${served.size}" +
                if (served.size < all.size) " TRUNCATED - some root content was dropped" else "",
        )
        return served
    }

    /**
     * The one PLAYABLE item inside the "Talk to LEGION" tab - the actual push-to-talk button, and
     * the only one of the two doors that survived contact with a head unit.
     *
     * Titled for what a TAP does, not for what LEGION is, because the driver reads this while
     * moving. [CompanionPhase] decides start-vs-stop wording the same way the transport action's
     * icon already does, so the row never says "Start" while a session is live.
     */
    private fun talkActionItem(): MediaItem {
        val live = CompanionPhase.phase.value != Phase.IDLE
        return MediaItem.Builder()
            .setMediaId(TALK_ACTION_MEDIA_ID)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(if (live) "Stop talking" else "Start talking")
                    .setSubtitle(if (live) "LEGION is listening" else "Tap to open a conversation")
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    // Same mic/stop pair the transport-bar CommandButton already uses
                    // (talkCommandButton) - a driver who has opened the "Talk" tab sees the exact
                    // icon the toggle will show, not a generic placeholder.
                    .setArtworkUri(resourceUri(if (live) R.drawable.ic_car_talk_stop else R.drawable.ic_car_talk_start))
                    .build(),
            )
            .build()
    }

    /**
     * One [CarAspectSummaries.CarRow] rendered as a PLAYABLE (never browsable) media item -
     * the trick this whole tab shape rests on. A browsable row would open an empty sub-list one
     * level deeper (the exact "No items" bug this wave fixes); a playable one arrives through
     * [MediaLibrarySession.Callback.onSetMediaItems] every single tap, which [isInfoRowMediaId]
     * below routes to a logged no-op that returns an empty queue - see [talkActionItem]'s
     * neighbouring doc for why the tap-time behaviour has to be a real command, not a cached
     * browse subscription.
     */
    /**
     * An informational row. **Everything the driver needs is in the TITLE**, and the subtitle
     * repeats it only for the head units that do render one.
     *
     * On-device 2026-08-18, Kevin, of the Money tab: "1405.69 and 1208. no descriptors." The
     * subtitles WERE set - "spent this month", "in debit account" - and Android Auto's browse list
     * simply did not draw them. Two figures with no words next to them, which for money is not just
     * unhelpful, it is unreadable.
     *
     * That kills the plan of riding caveats in the subtitle. `CarAspectSummaries.fleet`'s own doc
     * assumed the opposite - that some head units SPEAK the subtitle, so caveats belong there -
     * and both things can be true at once: a subtitle may be spoken and never shown. The only slot
     * guaranteed to reach the driver's eye is the title, so "estimated", "guess, unconfirmed" and
     * "unverified" now live there.
     *
     * **Not playable, not browsable** - flags 0. Playable made every tap show "getting your
     * selection" and then hang, because onSetMediaItems deliberately returns an empty queue and
     * Android Auto sat waiting for playback that never came. Kevin: "clicking any of them doesnt
     * do anything. it says getting your selection and stops." A row that hangs is worse than a row
     * that ignores you. Whether Android Auto renders a flags-0 item at all is UNVERIFIED - it is
     * the exact thing to look at on the next run, and the fallback if it renders nothing is to go
     * back to playable and make the tap do something honest instead of nothing.
     */
    private fun infoRowItem(mediaId: String, title: String, subtitle: String, iconRes: Int?): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setIsBrowsable(false)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .apply { if (iconRes != null) setArtworkUri(resourceUri(iconRes)) }
                    .build(),
            )
            .build()

    /**
     * [rows] rendered under [tabPrefix] - e.g. `legion-fleet-row-0`, `legion-fleet-row-1` - so
     * [isInfoRowMediaId] can recognise any of them by prefix without enumerating every tab.
     *
     * [fleetRowIcon] lives here rather than in [CarAspectSummaries] deliberately - that file stays
     * a plain-JVM string builder with no Android resource IDs in it, per this class's own doc, so
     * `CarAspectSummariesTest` keeps running with no Robolectric. [rows] is always [fleetRows]'
     * fixed five-row shape (connection, codes, then [CarAspectSummaries.GAUGE_PIDS]' own order:
     * RPM, coolant, speed) - indexing by position is safe only because that order is fixed, not
     * data-dependent.
     */
    private fun infoRowItems(
        tabPrefix: String,
        rows: List<CarAspectSummaries.CarRow>,
        connected: Boolean,
    ): List<MediaItem> =
        rows.mapIndexed { index, row ->
            infoRowItem("$tabPrefix-row-$index", row.title, row.subtitle, fleetRowIcon(index, connected))
        }

    /** Icon for [infoRowItems]' row [index] - see that function's own doc for why the fixed
     * connection/codes/RPM/coolant/speed order is safe to index into rather than derive from data.
     * [connected] only matters for row 0, which has an on/off icon pair mirroring
     * [CarAspectSummaries.shapeConnectionRow]'s own two title branches. */
    private fun fleetRowIcon(index: Int, connected: Boolean): Int = when (index) {
        0 -> if (connected) R.drawable.ic_car_connection_on else R.drawable.ic_car_connection_off
        1 -> R.drawable.ic_car_codes
        2 -> R.drawable.ic_car_gauge_rpm
        3 -> R.drawable.ic_car_gauge_coolant
        4 -> R.drawable.ic_car_gauge_speed
        else -> R.drawable.ic_car_fleet
    }

    private fun browsableItem(mediaId: String, title: String, subtitle: String, iconRes: Int? = null): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .apply { if (iconRes != null) setArtworkUri(resourceUri(iconRes)) }
                    .build(),
            )
            .build()

    /**
     * `android.resource://` URI for a drawable, the documented route for a browse row's icon -
     * [MediaMetadata.setArtworkUri] becomes [androidx.media3.session.legacy.MediaDescriptionCompat]'s
     * `iconUri` (confirmed by reading `LegacyConversions.convertToMediaDescriptionCompat` in the
     * vendored `media3-session-1.4.1-sources.jar`, `.setIconUri(metadata.artworkUri)`), which is
     * the field Android Auto's own browse-row renderer reads for a leading icon. Whether Android
     * Auto's loader rasterizes a VECTOR drawable resource behind this scheme, as opposed to only a
     * raster PNG/WebP, is `reasoned` from ordinary Android image-loading convention, not confirmed
     * against Android Auto's own (closed-source) code or on a head unit - see this ticket's report
     * for the exact thing to look at on the next run.
     */
    private fun resourceUri(resId: Int): Uri =
        Uri.parse("android.resource://${applicationContext.packageName}/$resId")

    /**
     * Item 3's most important part. Sends the SAME [AriaForegroundService.ACTION_TALK] intent
     * `ui/assistant/AssistantStrip.kt` already sends on a tap - never a second path. [trigger] is
     * logged so a car-probe session can tell a row tap from a custom-action tap apart after the
     * fact. Every failure is caught and logged; this function NEVER lets an exception reach the
     * MediaLibrarySession binder.
     */
    private fun toggleTalk(trigger: String) {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val myPid = android.os.Process.myPid()
        val isForeground = am?.runningAppProcesses?.any {
            it.pid == myPid && it.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        } == true
        CarProbeLog.log(
            "CarProbeToggleTalk",
            "toggleTalk trigger=$trigger appConsidersItselfForeground=$isForeground " +
                "phaseBefore=${CompanionPhase.phase.value}",
        )
        try {
            startService(
                Intent(this, AriaForegroundService::class.java)
                    .setAction(AriaForegroundService.ACTION_TALK),
            )
        } catch (e: ForegroundServiceStartNotAllowedException) {
            CarProbeLog.log(
                "CarProbeForegroundStartDenied",
                "ForegroundServiceStartNotAllowedException trigger=$trigger " +
                    "appConsidersItselfForeground=$isForeground message=${e.message}",
            )
        } catch (e: IllegalStateException) {
            CarProbeLog.log(
                "CarProbeForegroundStartDenied",
                "IllegalStateException starting AriaForegroundService trigger=$trigger " +
                    "appConsidersItselfForeground=$isForeground message=${e.message}",
            )
        } catch (e: Exception) {
            // Belt-and-braces per the brief: "It must never crash in the car." Anything unforeseen
            // here still gets caught rather than reaching the binder and killing the media service.
            CarProbeLog.log(
                "CarProbeForegroundStartDenied",
                "unexpected ${e::class.simpleName} starting AriaForegroundService trigger=$trigger " +
                    "appConsidersItselfForeground=$isForeground message=${e.message}",
            )
        }
    }

    /**
     * The transport-bar push-to-talk [CommandButton] (research 02 section 6a). [active] flips the
     * icon and title between "Talk"/mic and "Stop"/square - settled decision 5's tap-to-start /
     * tap-to-stop, made legible in words on the button itself (a driver who cannot look twice
     * needs the icon and title to say which action a tap performs, not infer it from context).
     */
    private fun talkCommandButton(active: Boolean): CommandButton =
        CommandButton.Builder(if (active) CommandButton.ICON_STOP else CommandButton.ICON_UNDEFINED)
            .setSessionCommand(SessionCommand(PTT_CUSTOM_COMMAND, Bundle.EMPTY))
            .setIconResId(
                if (active) com.kevin.legion.R.drawable.ic_car_talk_stop
                else com.kevin.legion.R.drawable.ic_car_talk_start,
            )
            .setDisplayName(if (active) "Stop" else "Talk")
            .setEnabled(true)
            .build()

    /**
     * Logs every library/session callback verbatim before answering it, and NEVER lets a callback
     * throw back into the binder (item 4 + the "must never crash" requirement) - every suspend
     * body below is bridged onto a [SettableFuture] rather than blocking the binder thread.
     */
    private inner class ProbeLibraryCallback : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            CarProbeLog.log(
                "MediaLibraryService",
                "onConnect package=${controller.packageName} uid=${controller.uid} trusted=${controller.isTrusted}",
            )
            // The custom push-to-talk command must be explicitly granted here, or Android Auto's
            // binder rejects any onCustomCommand call against it (media3 default session commands
            // do not include app-defined custom ones).
            val sessionCommands = SessionCommands.Builder()
                .addSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.commands)
                .add(SessionCommand(PTT_CUSTOM_COMMAND, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.accept(
                sessionCommands,
                MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS,
            )
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            CarProbeLog.log(
                "MediaLibraryService",
                "onGetLibraryRoot browser=${browser.packageName} params=${describeLibraryParams(params)}",
            )
            val root = MediaItem.Builder()
                .setMediaId(ROOT_MEDIA_ID)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("LEGION")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                        .build(),
                )
                .build()
            // Content-style hint for the root's own two tabs (Talk, Fleet) - both BROWSABLE, so
            // this is the key the constant's own doc says governs them, per MediaConstants. Read
            // straight from the vendored androidx.media:media 1.7.0 sources jar
            // (androidx/media/utils/MediaConstants.java) rather than guessed:
            //   DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
            //   DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_LIST_ITEM = 3
            // Traced end to end through the media3-session-1.4.1 sources jar, not assumed: this
            // function returns its own LibraryParams (built below) as LibraryResult.ofItem's second
            // argument; MediaLibraryServiceLegacyStub.onGetRoot reads that back as `result.params`
            // and feeds it through LegacyConversions.convertToRootHints, which copies `params.extras`
            // verbatim into the legacy BrowserRoot's own extras Bundle - exactly the Bundle
            // MediaConstants' own doc says a BROWSABLE-scoped content-style hint belongs in to apply
            // tree-wide. **Deliberately NOT the PLAYABLE key, and deliberately NOT applied to Fleet's
            // five rows**: those rows are flags-0 (neither browsable nor playable, see infoRowItem's
            // own doc for why), and the constant's own doc scopes the PLAYABLE/BROWSABLE hints
            // strictly to items carrying that flag - a flags-0 item is outside what either hint is
            // documented to affect. Making Fleet's rows playable to bring them into a grid's scope
            // would reopen the on-device "getting your selection" hang this file's own class doc
            // already recorded and fixed by going flags-0 - not worth it for a grid. CATEGORY_LIST_ITEM
            // (not plain LIST_ITEM) asks for the tintable, non-fill icon treatment research 02
            // section 6a already documents for custom-action icons, matching the single-colour vector
            // icons this pass adds ([browsableItem]'s [Uri] artwork).
            val rootExtras = Bundle().apply {
                putInt(
                    MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
                    MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_LIST_ITEM,
                )
            }
            val rootParams = LibraryParams.Builder().setExtras(rootExtras).build()
            return Futures.immediateFuture(LibraryResult.ofItem(root, rootParams))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            CarProbeLog.log(
                "MediaLibraryService",
                "onGetChildren parentId=$parentId page=$page pageSize=$pageSize browser=${browser.packageName}",
            )
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            serviceScope.launch {
                try {
                    when (parentId) {
                        ROOT_MEDIA_ID -> {
                            val children = libraryRootChildren(browser)
                            future.set(LibraryResult.ofItemList(ImmutableList.copyOf(children), params))
                        }
                        TALK_MEDIA_ID -> {
                            // **Browsing into this row is NOT the button, and cannot be.** It used
                            // to call toggleTalk here, and that failed on-device 2026-08-18 for two
                            // reasons neither the docs nor research 02 predicted, both visible in
                            // the Desktop Head Unit:
                            //
                            // 1. Android Auto renders the four root browsable items as TABS and
                            //    AUTO-SELECTS the first one when the app opens. "Talk to LEGION" is
                            //    first, so this fired unbidden at open, before the driver touched
                            //    anything - a live session starting because an app was launched.
                            // 2. Gearhead CACHES a subscription's children. Switching to Fleet and
                            //    back to Talk produced NO onGetChildren call at all - logcat was
                            //    silent where the first load had logged
                            //    `Browse subscription for id:{legion-fleet} LOADED`. So the trigger
                            //    can fire at most ONCE per media id per connection, at a moment
                            //    nobody chose, and never again. Kevin: "i tapped it, nothing
                            //    happens."
                            //
                            // The tab now holds ONE PLAYABLE item instead. Playing an item is a
                            // real, repeatable command that arrives through onSetMediaItems every
                            // single time, is not cached, and is the shape Android Auto actually
                            // documents for "tapping this does something". Root stays
                            // browsable-only, so research 02 section 5's constraint is untouched -
                            // this item lives one level down, where playable is ordinary.
                            future.set(
                                LibraryResult.ofItemList(ImmutableList.of(talkActionItem()), params),
                            )
                        }
                        FLEET_MEDIA_ID -> {
                            // Room + one StateFlow snapshot (ObdBluetoothManager.connectionState),
                            // no network, NEVER a live OBD port read - see CarAspectSummaries.fleetRows'
                            // own doc. [fleetRefreshJob] is the answer to the caching finding recorded
                            // right below (Gearhead never re-calls onGetChildren for a parentId it has
                            // already loaded once this connection) - this branch alone would only ever
                            // serve one snapshot per connection without it.
                            val rows = CarAspectSummaries.fleetRows(applicationContext)
                            // Same StateFlow snapshot CarAspectSummaries.fleetRows itself reads
                            // (see that function's doc) - read again here rather than plumbed back
                            // out of fleetRows, purely to pick row 0's icon (fleetRowIcon); it is a
                            // free, non-blocking StateFlow read, not a second OBD query.
                            val connected = com.kevin.legion.vehicle.ObdBluetoothManager.connectionState.value ==
                                com.kevin.legion.vehicle.ObdBluetoothManager.ConnectionState.CONNECTED
                            future.set(
                                LibraryResult.ofItemList(
                                    ImmutableList.copyOf(infoRowItems(FLEET_MEDIA_ID, rows, connected)),
                                    params,
                                ),
                            )
                        }
                        else -> {
                            // Unrecognised parent - log and return nothing rather than guess.
                            CarProbeLog.log("MediaLibraryService", "onGetChildren unrecognised parentId=$parentId")
                            future.set(LibraryResult.ofItemList(ImmutableList.of(), params))
                        }
                    }
                } catch (e: Exception) {
                    CarProbeLog.log("MediaLibraryService", "onGetChildren parentId=$parentId FAILED: ${e.message}")
                    future.set(LibraryResult.ofItemList(ImmutableList.of(), params))
                }
            }
            return future
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            CarProbeLog.log(
                "MediaLibraryService",
                "onCustomCommand action=${customCommand.customAction} browser=${controller.packageName}",
            )
            if (customCommand.customAction == PTT_CUSTOM_COMMAND) {
                toggleTalk("custom action")
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            CarProbeLog.log("MediaLibraryService", "onSearch query=\"$query\" browser=${browser.packageName}")
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            CarProbeLog.log("MediaLibraryService", "onGetSearchResult query=\"$query\" page=$page pageSize=$pageSize")
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params))
        }

        /**
         * Where a legacy `onPlayFromSearch`/`onPlayFromMediaId` lands once media3 translates it.
         * Research 03's open question - whether the parsed [query] or the raw
         * `android.intent.extra.user_query` extra carries the full spoken sentence for
         * "Hey Google, ask LEGION X" - is answered by logging BOTH here, plus every other extras
         * key, rather than assuming which one holds the sentence. This wave does not act on a
         * search-driven play (that is ticket 08's still-open point 6); it only observes.
         */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            // The push-to-talk button firing (2026-08-18). A play request is the ONLY trigger on
            // this surface that arrives every time it is tapped - see TALK_MEDIA_ID's branch in
            // onGetChildren for the two ways browse-taps failed. Handled before the search logging
            // below because this is now the primary door, not an observation.
            if (mediaItems.any { it.mediaId == TALK_ACTION_MEDIA_ID }) {
                toggleTalk("play item")
                // Tell Android Auto the item is not queued for playback. Returning it would leave
                // the stub player "playing" a thing that produces no audio, and the transport bar
                // would then lie about what STOP and PAUSE do - ticket 08 point 7's exact concern.
                return Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(mutableListOf(), 0, 0L),
                )
            }
            // A Fleet/Today/Money row (ticket 08's second decision tonight: "tapping a row does
            // nothing, it is a display, not a menu"). Same no-op-and-empty-queue shape as the talk
            // button above, minus the toggleTalk side effect - logged so a probe session can see
            // exactly which row was tapped, never silently swallowed.
            val infoRow = mediaItems.firstOrNull { isInfoRowMediaId(it.mediaId) }
            if (infoRow != null) {
                CarProbeLog.log(
                    "MediaLibraryService",
                    "onSetMediaItems info row tapped mediaId=${infoRow.mediaId} - display only, no-op",
                )
                return Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(mutableListOf(), 0, 0L),
                )
            }
            val requestMetadata = mediaItems.firstOrNull()?.requestMetadata
            val query = requestMetadata?.searchQuery
            val extras = requestMetadata?.extras
            val userQuery = extras?.getString(EXTRA_USER_QUERY)
            val extrasDump = describeBundle(extras)
            CarProbeLog.log(
                "MediaLibraryService",
                "onSetMediaItems count=${mediaItems.size} startIndex=$startIndex " +
                    "query=${query?.let { "\"$it\"" }} " +
                    "extras.$EXTRA_USER_QUERY=${userQuery?.let { "\"$it\"" }} " +
                    "extras=$extrasDump",
            )
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs),
            )
        }
    }

    companion object {
        private const val ROOT_MEDIA_ID = "legion-root"
        private const val TALK_MEDIA_ID = "legion-talk"
        /** The playable child of [TALK_MEDIA_ID] - the button itself. See [talkActionItem]. */
        private const val TALK_ACTION_MEDIA_ID = "legion-talk-action"
        private const val FLEET_MEDIA_ID = "legion-fleet"

        // Research 02 section 5: "in most cases, expect this number to be four or fewer" when the
        // head unit sends no hint at all. Root only ever serves two rows now (see the class doc's
        // RESCOPED paragraph), well under this, so it stays as a defensive ceiling rather than a
        // number that needs shrinking to match.
        private const val DEFAULT_ROOT_CHILD_LIMIT = 4

        // TelemetryRecorder's own tick - see the class doc's "Fact 2" paragraph for why
        // fleetRefreshJob exists and why this cadence, not a push per PID read.
        private const val FLEET_REFRESH_INTERVAL_MS = 30_000L

        // android.media.browse.MediaBrowser.MediaItem.FLAG_BROWSABLE's value - see the inline
        // comment where this is used for why it is a literal, not an import.
        private const val FLAG_BROWSABLE_DEFAULT = 1

        // App-defined custom session command backing the transport-bar push-to-talk button.
        private const val PTT_CUSTOM_COMMAND = "com.kevin.legion.car.PUSH_TO_TALK"

        /**
         * Undocumented key research 03 found the raw spoken sentence may live at, when
         * `MediaItem.RequestMetadata.searchQuery`'s music-entity parsing has already stripped it
         * down (e.g. "live moderat" for "Play Live from Moderat on SoundCloud"). Cannot be a
         * contract since it is undocumented - this is exactly why the probe logs it rather than
         * assuming it is there.
         */
        private const val EXTRA_USER_QUERY = "android.intent.extra.user_query"

        /** True for any row [infoRowItems] built under Fleet - see [infoRowItem]'s doc. */
        private fun isInfoRowMediaId(mediaId: String): Boolean =
            mediaId.startsWith("$FLEET_MEDIA_ID-row-")

        private fun describeLibraryParams(params: LibraryParams?): String =
            params?.toString() ?: "null"

        private fun describeBundle(bundle: Bundle?): String {
            if (bundle == null) return "null"
            return bundle.keySet().joinToString(prefix = "{", postfix = "}") { key ->
                @Suppress("DEPRECATION")
                "$key=${bundle.get(key)}"
            }
        }
    }
}

/**
 * Pure output of [describeProxyState] - just the three fields that decide WHICH branch of
 * [LegionProxyPlayer.getState] to build and what its playback fields say, stripped of every
 * media3 [androidx.media3.session.MediaLibrarySession]/[SimpleBasePlayer] type so this decision is
 * a plain JVM value testable without Robolectric or a Looper.
 */
internal data class ProxyPlaybackDescription(
    /** False when nothing is playing anywhere LEGION can see - [LegionProxyPlayer.getState]
     * must then build an empty, playlist-less player (media3's own constraint: an empty playlist
     * is only legal in STATE_IDLE/STATE_ENDED). */
    val hasItem: Boolean,
    val playbackState: Int,
    val playWhenReady: Boolean,
)

/**
 * The entire "what does [info] mean for the proxy card" decision, in one pure function:
 * - null -> nothing playing anywhere LEGION can see. [ProxyPlaybackDescription.hasItem] false,
 *   [Player.STATE_IDLE], not playing. The card must not claim to play something that does not
 *   exist - Kevin's own requirement for the offline/nothing-playing case.
 * - non-null -> there is a real track LEGION knows about, mirrored honestly whether or not it is
 *   currently advancing. Always [Player.STATE_READY] (the metadata is already known, nothing is
 *   "loading") with [ProxyPlaybackDescription.playWhenReady] following [NowPlayingInfo.isPlaying]
 *   exactly - the STRICT field (an explicit STATE_PLAYING report), not [NowPlayingInfo.isActive]'s
 *   lenient cosmetic reading, because a transport bar that says PLAYING is a factual claim, not a
 *   cosmetic animation.
 */
internal fun describeProxyState(info: NowPlayingInfo?): ProxyPlaybackDescription =
    if (info == null) {
        ProxyPlaybackDescription(hasItem = false, playbackState = Player.STATE_IDLE, playWhenReady = false)
    } else {
        ProxyPlaybackDescription(hasItem = true, playbackState = Player.STATE_READY, playWhenReady = info.isPlaying)
    }

/**
 * The [Player] behind [LegionMediaLibraryService]'s media card, since wave 5 a genuine PROXY
 * rather than the wave 1-4 `StubPlayer` it replaces. `SimpleBasePlayer` is still the documented
 * base for a `Player` with no real decode pipeline underneath - **nothing is ever actually decoded
 * or played by this class** - but every field [getState] reports now mirrors
 * [NowPlayingController.state] (see [updateNowPlaying]), and every transport command a driver taps
 * forwards to [MusicController], which is the object that actually reaches whatever session is
 * REALLY playing (Spotify, almost always, on this phone). See [LegionMediaLibraryService]'s own
 * class doc for the loop-prevention guarantee this class relies on but does not itself implement.
 *
 * [context] is the application context, needed only to call [MusicController]'s functions, which
 * take a `Context` rather than closing over one - passed in explicitly rather than reaching for
 * a static app-context singleton that does not exist in this codebase.
 */
private class LegionProxyPlayer(private val context: Context) : SimpleBasePlayer(Looper.getMainLooper()) {

    // The single source of truth for every field getState() reports. Null means "nothing is
    // playing anywhere LEGION can currently see" - not "unknown", not "loading" - and getState()
    // renders that as a genuinely empty, IDLE player rather than guessing. Written only from
    // updateNowPlaying, which the service's NowPlayingController collector calls on the main
    // thread - the same Looper this player was constructed with, so invalidateState() is always
    // called from the thread SimpleBasePlayer expects.
    @Volatile private var nowPlaying: NowPlayingInfo? = null

    /** Called by [LegionMediaLibraryService]'s `nowPlayingWatcherJob` on every
     * [NowPlayingController.state] emission - the only way this player's reported state ever
     * changes on its own (as opposed to in direct response to a driver's tap). */
    fun updateNowPlaying(info: NowPlayingInfo?) {
        nowPlaying = info
        invalidateState()
    }

    /**
     * Builds this tick's [State] purely from [nowPlaying] - there is no other state to reconcile
     * against, which is what makes this a faithful mirror rather than an independent player that
     * could drift from reality.
     *
     * **Native skip (`COMMAND_SEEK_TO_NEXT`/`COMMAND_SEEK_TO_PREVIOUS`) is deliberately never
     * advertised.** This player's playlist is always exactly one item (the current track, when
     * there is one) - there is no second item to skip TO. `androidx.media3.common.BasePlayer`'s
     * own `seekToNext()`/`seekToPrevious()` check `hasNextMediaItem()`/`hasPreviousMediaItem()`
     * before ever calling into a subclass's `handleSeek`, and silently `ignoreSeek()` when false -
     * confirmed by reading that source, not assumed. A one-item playlist can never satisfy either
     * check, so advertising those commands would render a skip control that looks live and does
     * nothing on every tap. See [LegionMediaLibraryService]'s class doc for why leaving that slot
     * unclaimed is also the reason the skip-slot reservation extras stay unset.
     */
    override fun getState(): State {
        val info = nowPlaying
        val description = describeProxyState(info)
        val availableCommands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_PREPARE,
                Player.COMMAND_STOP,
                Player.COMMAND_SET_MEDIA_ITEM,
            )
            .build()

        if (!description.hasItem || info == null) {
            // Nothing playing anywhere LEGION can see. An empty playlist is only legal alongside
            // STATE_IDLE/STATE_ENDED (see handlePrepare's old doc, corrected below) - IDLE plus no
            // playlist is the honest shape for "there is nothing to show", not a workaround.
            return State.Builder()
                .setAvailableCommands(availableCommands)
                .setPlaybackState(description.playbackState)
                .setPlayWhenReady(description.playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .build()
        }

        val mediaItem = MediaItem.Builder()
            .setMediaId(NOW_PLAYING_MEDIA_ID)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(info.title)
                    .setArtist(info.artist)
                    .setAlbumTitle(info.album)
                    .setArtworkUri(info.albumArtUri?.let(Uri::parse))
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build(),
            )
            .build()
        val itemData = MediaItemData.Builder(NOW_PLAYING_MEDIA_ID)
            .setMediaItem(mediaItem)
            .setDurationUs(if (info.duration > 0) info.duration * 1_000L else C.TIME_UNSET)
            // No real timeline underneath to scrub within - the position shown is whatever the
            // real session last reported, not something this proxy can seek inside on its own.
            .setIsSeekable(false)
            .build()

        return State.Builder()
            .setAvailableCommands(availableCommands)
            .setPlaylist(listOf(itemData))
            .setPlaybackState(description.playbackState)
            .setPlayWhenReady(description.playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setContentPositionMs(info.position)
            .build()
    }

    /**
     * Forwards PLAY/PAUSE to [MusicController] rather than flipping a local flag - the wave 1-4
     * `StubPlayer` had no playable item anywhere in its tree so this was always a no-op; the proxy
     * has a real target to command. **Deliberately no optimistic local state change here**: the
     * card only ever reports what [NowPlayingController] has actually observed, on the next
     * [updateNowPlaying] tick, never what LEGION merely hopes just happened - the same latency a
     * driver would see using Spotify's own card, since both routes end at the same real session.
     */
    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        CarProbeLog.log(
            "MediaLibraryService",
            if (playWhenReady) "onPlay - forwarding MusicController.play" else "onPause - forwarding MusicController.pause",
        )
        if (playWhenReady) MusicController.play(context) else MusicController.pause(context)
        return Futures.immediateVoidFuture()
    }

    /**
     * **No longer stays IDLE unconditionally - CORRECTED for wave 5.** The wave 1-4 doc here
     * explained a real on-device crash: `SimpleBasePlayer.State`'s constructor throws
     * `IllegalArgumentException: Empty playlist only allowed in STATE_IDLE or STATE_ENDED` if a
     * player with no playlist reports READY, and the old `StubPlayer` NEVER had a playlist (the
     * talk item was an action, not audio). That constraint has not gone away - it is still enforced
     * in [getState]'s null-`nowPlaying` branch above - but this player now DOES have a notional
     * playlist whenever something is really playing, so `handlePrepare` no longer needs to force
     * IDLE to satisfy it. It simply recomputes and republishes whatever [getState] would already
     * say from the current [nowPlaying] snapshot; if that snapshot is null, the same IDLE branch
     * that avoided the original crash still applies, automatically.
     */
    override fun handlePrepare(): ListenableFuture<*> {
        CarProbeLog.log("MediaLibraryService", "onPrepare - recomputing state from the last known NowPlayingInfo")
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    /**
     * **What STOP means, decided (ticket 08 point 7).** AVRCP/MediaSession expose only
     * play/pause/next/previous - there is no distinct "stop the stream" primitive to forward to,
     * so STOP maps to the same real [MusicController.pause] call PAUSE does. This is the honest
     * choice available, not an evasion: the real audio genuinely stops, matching what the STOP icon
     * promises, and the alternative (a STOP that does nothing, or that means something a driver
     * cannot predict from the icon) would be worse. **Never touches [AriaForegroundService] or the
     * live conversation** - see [LegionMediaLibraryService]'s class doc for why that boundary is
     * load-bearing rather than incidental.
     */
    override fun handleStop(): ListenableFuture<*> {
        CarProbeLog.log("MediaLibraryService", "onStop - forwarding MusicController.pause (no distinct stop primitive exists)")
        MusicController.pause(context)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        // No external caller is expected to reach this - the session-level callback below
        // (ProbeLibraryCallback.onSetMediaItems) already intercepts and no-ops the talk/info rows
        // before they would ever be handed to the player, and this proxy's own playlist is built
        // entirely from NowPlayingController rather than from an externally-set queue. Logged in
        // case some unexpected caller (a future search-to-play path, say) reaches it anyway.
        CarProbeLog.log(
            "MediaLibraryService",
            "Player.handleSetMediaItems count=${mediaItems.size} startIndex=$startIndex startPositionMs=$startPositionMs - unexpected caller, no-op",
        )
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        CarProbeLog.log("MediaLibraryService", "Player.onRelease")
        return Futures.immediateVoidFuture()
    }

    private companion object {
        /** The mirrored track's media id - stable across ticks since it identifies "whatever is
         * currently really playing", not any one song; a new title at the same id is exactly how
         * a track change is expected to look. */
        private const val NOW_PLAYING_MEDIA_ID = "legion-now-playing"
    }
}
