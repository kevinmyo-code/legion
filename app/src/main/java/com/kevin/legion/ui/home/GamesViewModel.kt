package com.kevin.legion.ui.home

import android.app.Application
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kevin.legion.games.AndroidGame
import com.kevin.legion.games.AndroidGames
import com.kevin.legion.games.BoxArtCache
import com.kevin.legion.games.Emulator
import com.kevin.legion.games.Game
import com.kevin.legion.games.GameLauncher
import com.kevin.legion.games.GamesFolderStore
import com.kevin.legion.games.HttpBoxArtFetcher
import com.kevin.legion.games.LastPlayedStore
import com.kevin.legion.games.LaunchOutcome
import com.kevin.legion.games.RomLibrary
import com.kevin.legion.games.SafRomTree
import com.kevin.legion.games.agoLabel
import com.kevin.legion.games.folderLabel
import com.kevin.legion.games.message
import com.kevin.legion.games.race.NO_ENGINE_SENTENCE
import com.kevin.legion.games.race.djangoGamesBackendOrNull
import com.kevin.legion.games.race.engineSentence
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val NOTICE_MS = 2_400L
private const val COVER_MAX_PX = 400
private const val ICON_PX = 192

/**
 * The Games chip and sheet's ViewModel (ADR 0058). No Hilt yet, matching [HomeViewModel] (CLAUDE.md
 * sec 8's migration order). The logic worth testing lives in the pure `games` package; this wires it
 * to the folder grant, the scan, the launcher and the cover cache.
 */
@Suppress("TooManyFunctions") // one function per Games-sheet action; the 12th is the Tetris opponent load
class GamesViewModel(app: Application) : AndroidViewModel(app) {
    private val folders = GamesFolderStore(app)
    private val lastPlayed = LastPlayedStore(app)
    private val launcher = GameLauncher.forContext(app)
    private val coverImages = CoverImages(
        app.contentResolver,
        BoxArtCache(File(app.filesDir, "boxart"), HttpBoxArtFetcher),
        COVER_MAX_PX,
    )

    private val _state = MutableStateFlow(GamesUiState(cachedCount = folders.cachedCount()))
    val state: StateFlow<GamesUiState> = _state.asStateFlow()

    private var scanJob: Job? = null
    private var noticeJob: Job? = null

    /** Re-reads the grant and rescans. Called on resume, so games added from a PC show up. */
    fun refresh() {
        scanJob?.cancel()
        refreshAndroidGames()
        val uri = folders.treeUri()
        if (uri == null) {
            _state.update { it.copy(folder = GamesFolderState.None, scanning = false, scanned = false) }
            return
        }
        val label = folderLabel(SafRomTree.rootDocumentId(uri))
        if (!folders.isGranted(uri)) {
            _state.update {
                it.copy(folder = GamesFolderState.Lost(label), scanning = false, scanned = false, games = emptyList())
            }
            return
        }
        _state.update { it.copy(folder = GamesFolderState.Granted(label), scanning = true) }
        scanJob = viewModelScope.launch {
            val app = getApplication<Application>()
            val result = withContext(Dispatchers.IO) {
                RomLibrary(SafRomTree(app.contentResolver, uri)).scan(SafRomTree.rootDocumentId(uri))
            }
            if (!result.unreadable) folders.cacheCount(result.games.size)
            val missing = Emulator.entries.filterNot(launcher.isInstalled).toSet()
            _state.update {
                it.copy(
                    scanning = false,
                    scanned = true,
                    unreadable = result.unreadable,
                    systemFoldersSeen = result.systemFoldersSeen,
                    games = result.games,
                    resume = resumeFor(result.games, it.androidGames),
                    missingEmulators = missing,
                )
            }
        }
    }

    /**
     * Loads who a Tetris race can invite. Called each time the sheet opens, so a sign-in made since
     * is seen. No engine, or an engine that will not answer, becomes a sentence on the tile and
     * leaves Solo untouched.
     */
    fun loadOpponents() {
        val backend = djangoGamesBackendOrNull(getApplication<Application>())
        if (backend == null) {
            _state.update { it.copy(race = RaceOpponentsUi(unavailable = NO_ENGINE_SENTENCE)) }
            return
        }
        _state.update { it.copy(race = it.race.copy(loading = true, unavailable = null)) }
        viewModelScope.launch {
            val result = backend.opponents()
            _state.update {
                it.copy(
                    race = result.fold(
                        onSuccess = { who -> RaceOpponentsUi(opponents = who) },
                        onFailure = { e ->
                            RaceOpponentsUi(unavailable = "Racing is unavailable right now. ${e.engineSentence()}")
                        },
                    ),
                )
            }
        }
    }

    /** Native games are found by category on every resume; cheap, and needs no folder grant. */
    private fun refreshAndroidGames() {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val found = withContext(Dispatchers.IO) {
                AndroidGames.select(AndroidGames.read(app.packageManager), ownPackage = app.packageName)
            }
            _state.update { it.copy(androidGames = found, resume = resumeFor(it.games, found)) }
        }
    }

    private fun resumeFor(games: List<Game>, androidGames: List<AndroidGame>): ResumeUi? {
        val last = lastPlayed.read() ?: return null
        val ago = agoLabel(System.currentTimeMillis(), last.atMillis)
        val pkg = AndroidGames.packageOf(last.documentId)
        return if (pkg != null) {
            androidGames.firstOrNull { it.packageName == pkg }?.let { ResumeUi(null, it, ago) }
        } else {
            games.firstOrNull { it.documentId == last.documentId }?.let { ResumeUi(it, null, ago) }
        }
    }

    /** The raw `OPEN_DOCUMENT_TREE` result, straight from the picker. */
    fun onFolderPicked(uri: Uri) {
        folders.connect(uri)
        refresh()
    }

    fun play(game: Game) {
        val tree = folders.treeUri() ?: return
        val outcome = launcher.launch(game, SafRomTree.documentUri(tree, game.documentId).toString())
        finish(outcome, game.documentId)
    }

    fun playApp(game: AndroidGame) = finish(launcher.launchApp(game), AndroidGames.key(game))

    private fun finish(outcome: LaunchOutcome, key: String) {
        val started = outcome is LaunchOutcome.Started
        if (started) lastPlayed.write(key, System.currentTimeMillis())
        showNotice(GameNotice(outcome.message(), failed = !started))
        if (started) _state.update { it.copy(resume = resumeFor(it.games, it.androidGames)) }
    }

    fun playResume() {
        val resume = _state.value.resume ?: return
        resume.rom?.let(::play)
        resume.app?.let(::playApp)
    }

    private fun showNotice(notice: GameNotice) {
        noticeJob?.cancel()
        _state.update { it.copy(notice = notice) }
        noticeJob = viewModelScope.launch {
            delay(NOTICE_MS)
            _state.update { it.copy(notice = null) }
        }
    }

    /** The app's own icon from the package manager, or null if it cannot be loaded. */
    suspend fun appIcon(game: AndroidGame): ImageBitmap? = withContext(Dispatchers.Default) {
        runCatching {
            getApplication<Application>().packageManager.getApplicationIcon(game.packageName)
                .toBitmap(ICON_PX, ICON_PX).asImageBitmap()
        }.getOrNull()
    }

    /**
     * The decoded cover, or null: not cached, a recorded miss, offline, or unreadable. A cover image
     * kept beside the ROM wins over box art, so a romhack with no libretro cover can still have one.
     */
    suspend fun cover(game: Game): ImageBitmap? = coverImages.load(folders.treeUri(), game)
}

/** The system folder picker (`OPEN_DOCUMENT_TREE`) wired to [GamesViewModel]; call the result to open it. */
@androidx.compose.runtime.Composable
fun rememberRomFolderPicker(viewModel: GamesViewModel): () -> Unit {
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> uri?.let(viewModel::onFolderPicked) }
    return { launcher.launch(null) }
}
