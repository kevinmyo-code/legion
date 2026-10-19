package com.kevin.legion.books

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.games.SafRomTree
import com.kevin.legion.games.folderLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/**
 * The SAF-backed [BookCatalog]: reads the folder grant, walks the tree, remembers every book it
 * sees in Room (so a position survives a rescan) and joins the two. The sheet and the voice tool
 * both read through this.
 */
class SafBookCatalog(
    context: Context,
    private val folders: BooksFolderStore = BooksFolderStore(context),
    private val now: () -> Long = System::currentTimeMillis,
) : BookCatalog {
    private val app = context.applicationContext
    private val dao get() = CarDatabase.getDatabase(app).bookDao()

    override suspend fun load(): CatalogState = withContext(Dispatchers.IO) {
        val tree = folders.treeUri() ?: return@withContext CatalogState.NoFolder
        val rootId = SafRomTree.rootDocumentId(tree)
        val label = folderLabel(rootId)
        if (!folders.isGranted(tree)) return@withContext CatalogState.Lost(label)
        val scan = BookLibrary(SafRomTree(app.contentResolver, tree)).scan(rootId)
        if (scan.unreadable) return@withContext CatalogState.Unreadable(label)
        folders.cacheCount(scan.books.size)
        dao.insertIfAbsent(scan.books.map { BookItem.newRow(it, now()) })
        val rows = dao.all().associateBy { it.documentId }
        CatalogState.Ready(label, scan.books.map { BookItem.of(it, rows[it.documentId]) })
    }

    /** The content URI of [book] under the granted tree, or null when no folder is granted. */
    fun uriOf(book: Book): Uri? = folders.treeUri()?.let { SafRomTree.documentUri(it, book.documentId) }
}

/** Which book the reader is showing right now, so a launch is OBSERVED rather than assumed. */
object ReaderPresence {
    val showing = MutableStateFlow<String?>(null)
}

/** Starts [ReaderActivity] and waits to SEE it come up (the NavScreenOpener pattern). */
class ActivityReaderLauncher(private val context: Context) : ReaderLauncher {
    override suspend fun launch(book: Book, listen: Boolean): Boolean {
        val intent = ReaderActivity.intent(context, book, listen).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val started = try {
            context.startActivity(intent)
            true
        } catch (e: android.content.ActivityNotFoundException) {
            android.util.Log.w(TAG, "reader launch failed: ${e.message}")
            false
        } catch (e: SecurityException) {
            android.util.Log.w(TAG, "reader launch refused: ${e.message}")
            false
        }
        if (!started) return false
        var waited = 0L
        while (ReaderPresence.showing.value != book.documentId && waited < WAIT_MS) {
            delay(POLL_MS)
            waited += POLL_MS
        }
        return ReaderPresence.showing.value == book.documentId
    }

    private companion object {
        const val TAG = "ReaderLauncher"
        const val WAIT_MS = 3_000L
        const val POLL_MS = 100L
    }
}

/** True when the phone has at least one text-to-speech engine (needs the TTS_SERVICE `<queries>` entry). */
class AndroidTtsAvailability(private val context: Context) : TtsAvailability {
    override fun available(): Boolean {
        val intent = Intent(android.speech.tts.TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
        return context.packageManager.queryIntentServices(intent, 0).isNotEmpty()
    }
}

/** Production wiring for the one controller the sheet and the voice tool share. */
fun BooksController.Companion.forContext(context: Context): BooksController {
    val app = context.applicationContext
    return BooksController(SafBookCatalog(app), ActivityReaderLauncher(app), AndroidTtsAvailability(app))
}

/**
 * [AudioFocusGate] over `AudioManager`: read-aloud holds media focus and pauses when it is taken.
 * Through the androidx.media compat classes because `AudioFocusRequest` is API 26 and minSdk is 24.
 */
class AndroidAudioFocus(context: Context) : AudioFocusGate {
    private val audio = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
    private var request: androidx.media.AudioFocusRequestCompat? = null

    override fun request(onLost: () -> Unit): Boolean {
        val focus = androidx.media.AudioFocusRequestCompat.Builder(androidx.media.AudioManagerCompat.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                androidx.media.AudioAttributesCompat.Builder()
                    .setUsage(androidx.media.AudioAttributesCompat.USAGE_MEDIA)
                    .setContentType(androidx.media.AudioAttributesCompat.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener { change ->
                // Any loss, transient or not, pauses: read-aloud never talks over a call or another player.
                if (change == android.media.AudioManager.AUDIOFOCUS_LOSS ||
                    change == android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                    change == android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
                ) {
                    onLost()
                }
            }
            .build()
        request = focus
        return androidx.media.AudioManagerCompat.requestAudioFocus(audio, focus) ==
            android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    override fun abandon() {
        request?.let { androidx.media.AudioManagerCompat.abandonAudioFocusRequest(audio, it) }
        request = null
    }
}
