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
import com.kevin.legion.service.AriaForegroundService
import com.kevin.legion.service.CompanionPhase
import com.kevin.legion.service.Phase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * WAVE 3 of the Android Auto probe harness (`.scratch/android-auto/map.md`) - a REAL browse tree,
 * per Kevin's direct ask ("i just need a push to talk button, and some kind of UI display to see my
 * aspects") and ticket 08's still-OPEN, still-PROVISIONAL decision on what the tree holds. Wave 1
 * ([onCreate]'s original doc, now superseded) served an empty root purely to prove visibility; this
 * wave serves four real rows and wires push-to-talk to the actual live session.
 *
 * **Root shape (research 02 section 5 - browsable-only, ~4 items, real limit arrives at runtime):**
 * "Talk to LEGION" (push-to-talk, see [toggleTalk]), then one row per aspect (Fleet/Today/Money),
 * each carrying a LIVE deterministic value in its title/subtitle built by [CarAspectSummaries] - the
 * row IS the display Kevin asked for, there is no separate screen. All four are BROWSABLE, not
 * PLAYABLE: research 02 is explicit that only browsable items are safe at root, and violating that
 * degrades SILENTLY rather than rejecting - logged every time in [libraryRootChildren] rather than
 * risked blind. Tapping "Talk to LEGION" fires [toggleTalk] as a side effect of browsing into it
 * (its own children list is empty - there is nowhere to browse TO, the tap itself is the action);
 * tapping an aspect row only logs and returns an empty child list, per the brief's explicit "rows
 * are informational... do NOT invent a text-to-speech path".
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
 */
class LegionMediaLibraryService : MediaLibraryService() {

    private var player: StubPlayer? = null
    private var session: MediaLibrarySession? = null

    // Backs onGetChildren's async work (aspect summaries read Room) and the CompanionPhase
    // collector that keeps the transport custom action's icon/title in sync with the real session
    // state - cancelled in onDestroy.
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var phaseWatcherJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        val stubPlayer = StubPlayer()
        player = stubPlayer
        session = MediaLibrarySession.Builder(this, stubPlayer, ProbeLibraryCallback())
            .setId("legion-car-probe")
            .setCustomLayout(ImmutableList.of(talkCommandButton(active = false)))
            .build()
        CarProbeLog.log("MediaLibraryService", "onCreate - wave 3, real browse tree + push-to-talk")

        // Keeps the custom action's icon/title (tap-to-start vs tap-to-stop, settled decision 5)
        // truthful regardless of which door changed the session state - CompanionPhase is the
        // process-global mirror LiveSessionController.set() already publishes to, so this needs no
        // new plumbing into the session controller itself.
        phaseWatcherJob = serviceScope.launch {
            CompanionPhase.phase.collect { phase ->
                session?.setCustomLayout(ImmutableList.of(talkCommandButton(active = phase != Phase.IDLE)))
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

        val fleet = CarAspectSummaries.fleet(applicationContext)
        val today = CarAspectSummaries.today(applicationContext)
        val money = CarAspectSummaries.money(applicationContext)

        val all = listOf(
            browsableItem(TALK_MEDIA_ID, "Talk to LEGION", "Tap to start a conversation"),
            browsableItem(FLEET_MEDIA_ID, fleet.first, fleet.second),
            browsableItem(TODAY_MEDIA_ID, today.first, today.second),
            browsableItem(MONEY_MEDIA_ID, money.first, money.second),
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
                    .build(),
            )
            .build()
    }

    private fun browsableItem(mediaId: String, title: String, subtitle: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build(),
            )
            .build()

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
            return Futures.immediateFuture(LibraryResult.ofItem(root, params))
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
                        else -> {
                            // Aspect rows: informational only, per the brief - log and do nothing.
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
        private const val TODAY_MEDIA_ID = "legion-today"
        private const val MONEY_MEDIA_ID = "legion-money"

        // Research 02 section 5: "in most cases, expect this number to be four or fewer" when the
        // head unit sends no hint at all.
        private const val DEFAULT_ROOT_CHILD_LIMIT = 4

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
 * The smallest [Player] that satisfies [MediaLibrarySession.Builder]'s requirement for one -
 * research 02 section 2's "media3 does not give for free" item 2. `SimpleBasePlayer` is the
 * documented base for a `Player` with no real media pipeline: every transport command below is
 * logged and the internal state flipped so Android Auto's UI reflects it, but **nothing is ever
 * actually decoded or played**. Unchanged from wave 1 - the real "play" surface for wave 3 is the
 * browse-tree tap and the custom action, not this stub's own play/pause (there is no playable
 * [MediaItem] anywhere in this tree for AA to hand this player).
 */
private class StubPlayer : SimpleBasePlayer(Looper.getMainLooper()) {

    private var playWhenReady = false
    private var playbackState = Player.STATE_IDLE

    override fun getState(): State {
        val availableCommands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_PREPARE,
                Player.COMMAND_STOP,
                Player.COMMAND_SET_MEDIA_ITEM,
            )
            .build()
        return State.Builder()
            .setAvailableCommands(availableCommands)
            .setPlaybackState(playbackState)
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        CarProbeLog.log(
            "MediaLibraryService",
            if (playWhenReady) "onPlay - no playable item in this tree, no-op" else "onPause",
        )
        this.playWhenReady = playWhenReady
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    /**
     * **Stays IDLE, deliberately.** This used to set `STATE_READY`, and on-device 2026-08-18 that
     * threw on every single push-to-talk tap:
     *
     * ```
     * java.lang.IllegalArgumentException: Empty playlist only allowed in STATE_IDLE or STATE_ENDED
     *     at androidx.media3.common.SimpleBasePlayer$State.<init>
     *     at com.kevin.legion.car.StubPlayer.getState
     *     at com.kevin.legion.car.StubPlayer.handlePrepare
     * ```
     *
     * media3 asserts that a player with no playlist cannot be READY, and this player never has a
     * playlist: the talk item is an ACTION, not audio, so onSetMediaItems deliberately returns an
     * empty queue rather than leaving the transport bar claiming to play something silent. READY
     * plus an empty queue is a contradiction media3 refuses to build a State from.
     *
     * The throw was swallowed by media3's own ImmediateFuture and only surfaced in logcat, so the
     * button worked while this fired twice a session. A caught exception on the happy path is still
     * a bug - it means the state machine is being asked for something impossible every time.
     */
    override fun handlePrepare(): ListenableFuture<*> {
        CarProbeLog.log("MediaLibraryService", "onPrepare - staying IDLE, this player never holds a playlist")
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        CarProbeLog.log("MediaLibraryService", "onStop")
        playWhenReady = false
        playbackState = Player.STATE_IDLE
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        CarProbeLog.log(
            "MediaLibraryService",
            "Player.handleSetMediaItems count=${mediaItems.size} startIndex=$startIndex startPositionMs=$startPositionMs",
        )
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        CarProbeLog.log("MediaLibraryService", "Player.onRelease")
        return Futures.immediateVoidFuture()
    }
}
