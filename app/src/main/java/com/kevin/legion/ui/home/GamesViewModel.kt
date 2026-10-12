package com.kevin.legion.ui.home

import android.app.Application
import android.graphics.BitmapFactory
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
class GamesViewModel(app: Application) : AndroidViewModel(app) {
    private val folders = GamesFolderStore(app)
    private val lastPlayed = LastPlayedStore(app)
    private val launcher = GameLauncher.forContext(app)
    private val covers = BoxArtCache(File(app.filesDir, "boxart"), HttpBoxArtFetcher)

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

    /** The decoded cover, or null: not cached, a recorded miss, offline, or unreadable. */
    suspend fun cover(game: Game): ImageBitmap? {
        val file = covers.get(game) ?: return null
        return withContext(Dispatchers.Default) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (bounds.outWidth / sample > COVER_MAX_PX * 2) sample *= 2
            val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            // A file that will not decode is dropped so the next open fetches it again.
            if (bitmap == null) file.delete()
            bitmap?.asImageBitmap()
        }
    }
}

/** The system folder picker (`OPEN_DOCUMENT_TREE`) wired to [GamesViewModel]; call the result to open it. */
@androidx.compose.runtime.Composable
fun rememberRomFolderPicker(viewModel: GamesViewModel): () -> Unit {
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> uri?.let(viewModel::onFolderPicked) }
    return { launcher.launch(null) }
}
