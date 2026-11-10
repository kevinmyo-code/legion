package com.kevin.legion.brief

import android.content.Context
import com.kevin.legion.MidnightApplication
import com.kevin.legion.openloops.OpenLoopsBrief
import com.kevin.legion.openloops.OpenLoopsController
import com.kevin.legion.sitrep.SitrepModule
import java.time.ZoneId

/** What the Brief modal shows in place of a blank: a brief, or a sentence saying why there is none. */
sealed interface BriefView {
    /**
     * A brief. [header] is when it was built, in words. [savedCopy] is null for a fresh read, and is
     * the sentence saying this is the phone's saved copy, how old, and why, when the engine could
     * not be read.
     */
    data class Showing(val brief: ServerBrief, val header: String, val savedCopy: String?) : BriefView

    /** The engine answered and has no brief for this member yet. Its own sentence. */
    data class NoneYet(val sentence: String) : BriefView

    /** The engine could not be read and the phone has no saved copy. Why, in words. */
    data class Unavailable(val sentence: String) : BriefView
}

/** A Refresh press. Never a silent no-op: either a new brief or a sentence saying what did not happen. */
sealed interface RefreshOutcome {
    data class Refreshed(val view: BriefView.Showing) : RefreshOutcome

    data class NotRefreshed(val sentence: String) : RefreshOutcome
}

/** The `get_sitrep` result. [success] is false when no brief from the engine (fresh or saved) was read. */
data class VoiceBrief(val success: Boolean, val text: String)

/**
 * The daily brief on the phone (ai-upgrade ticket 02): the engine's brief, the offline copy, and the
 * phone's live calendar and weather. **One controller for both paths** (ADR 0035): the Brief modal's
 * ViewModel and the `get_sitrep` voice tool both call this, so the hands path and the voice path
 * cannot drift into saying different things.
 *
 * **Never pushed by itself** (ruling 2): nothing here schedules, notifies or speaks. It answers when
 * asked. One caller asks unprompted since 2026-11-10: the wake-up brief
 * (`spokenbrief/WakeBrief.kt`, ADR 0082) reads [forVoice] at an alarm dismiss, with no mail, and
 * hands the text to the proactive bus, so the spoken brief is this one, never a second.
 *
 * A class with its dependencies in the constructor, built by hand in [forApp] until Hilt lands
 * (CLAUDE.md section 8); `MidnightApplication.brief` is the one instance.
 */
class BriefController(
    /** Null when this device has no engine address or token. Asked per call, so a sign-in takes effect. */
    private val backend: () -> BriefBackend?,
    private val cache: BriefCache,
    private val phone: BriefPhone,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    /** ADR 0085: the proposed to-dos line, or null for none. Null source: no line at all. */
    private val openLoops: OpenLoopsLines? = null,
) {

    /** The two renderings of the proposed to-dos line ([com.kevin.legion.openloops.OpenLoopsController]). */
    interface OpenLoopsLines {
        suspend fun voiceLine(): String?

        suspend fun screenLine(): String?
    }

    /**
     * The latest brief. A fresh read is cached and shown. A 404 is the engine's own sentence, shown
     * as is and never covered by an older saved copy (the engine was reached and is the authority).
     * Any other failure falls back to the saved copy, said in words, or to the failure sentence when
     * there is no copy. Never throws, never blank.
     */
    suspend fun load(): BriefView {
        val engine = backend() ?: return fallback(BriefWords.NO_ENGINE)
        val reply = engine.latest()
        return when (val got = reply.getOrNull()) {
            is BriefReply.Got -> fresh(got)
            is BriefReply.NoneYet -> BriefView.NoneYet(got.sentence)
            null -> fallback(BriefWords.readFailure(reply.exceptionOrNull()))
        }
    }

    /** `POST /api/brief/refresh`. A new brief replaces the cache; any failure leaves it untouched. */
    suspend fun refresh(): RefreshOutcome {
        val engine = backend() ?: return RefreshOutcome.NotRefreshed(BriefWords.NO_ENGINE)
        return engine.refresh().fold(
            onSuccess = { RefreshOutcome.Refreshed(fresh(it)) },
            onFailure = { RefreshOutcome.NotRefreshed(BriefWords.refreshFailure(it)) },
        )
    }

    /** Calendar and weather from the phone, live. */
    suspend fun phoneSections(): List<PhoneSection> =
        phone.sections(null) + listOfNotNull(openLoops?.screenLine()?.let { PhoneSection(OpenLoopsBrief.SCREEN_TITLE, it) })

    suspend fun newslettersEnabled(): Boolean = phone.newslettersEnabled()

    /** The Gmail newsletter summary, live and read-through: returned, never cached. */
    suspend fun newsletters(): String = phone.newsletters()

    /**
     * What `get_sitrep` returns: the same brief the modal shows, then the phone's calendar and
     * weather, then the newsletters when that module is on. [modules] is the tool's optional filter:
     * `fleet` keeps the engine's Car section, `news` its News section and the newsletters, `calendar`
     * and `weather` the phone's; with a filter, Canvas and Money are left out.
     *
     * [readMail] false skips the newsletter module entirely: no Gmail call and no line about mail,
     * not even "none" (ADR 0082: the unprompted wake-up brief reads no mail until Kevin says yes;
     * mail is read only to answer something he asked). `get_sitrep` and the Brief modal keep the
     * default. The engine's own sections carry no mail (`server/brief/models.py`).
     */
    suspend fun forVoice(modules: Set<SitrepModule>?, readMail: Boolean = true): VoiceBrief {
        val view = load()
        val out = StringBuilder()
        val read = view is BriefView.Showing
        when (view) {
            is BriefView.Showing -> out.append(BriefVoiceText.render(view, modules))
            is BriefView.NoneYet -> out.append("No brief was read. ").append(view.sentence)
            is BriefView.Unavailable -> out.append("No brief was read. ").append(view.sentence)
        }
        if (!read) out.append("\nCanvas, money, car and web news are not included.")
        if (modules != null && modules.isEmpty()) {
            out.append("\nNone of the sections asked for exist, so no section was read. ")
                .append("Ask without naming sections for the whole brief.")
        }
        phone.sections(modules).forEach { out.append("\n").append(it.title.uppercase()).append(' ').append(it.text) }
        // ADR 0085: one line asking about proposed to-dos, only in the whole brief, never a filtered one.
        if (modules == null) openLoops?.voiceLine()?.let { out.append("\n").append(it) }
        val newsAsked = readMail && (modules == null || SitrepModule.NEWS in modules)
        if (newsAsked && phone.newslettersEnabled()) {
            out.append("\nNEWSLETTERS, read from your mail on this phone just now and not stored:\n")
                .append(phone.newsletters())
        }
        return VoiceBrief(success = read, text = out.toString())
    }

    private fun fresh(got: BriefReply.Got): BriefView.Showing {
        val now = clock()
        cache.write(got.raw, now)
        return BriefView.Showing(got.brief, BriefWords.built(got.brief.builtAtMs, now, zone()), savedCopy = null)
    }

    private fun fallback(why: String): BriefView {
        val saved = cache.read() ?: return BriefView.Unavailable(why)
        val now = clock()
        return BriefView.Showing(
            brief = saved.brief,
            header = BriefWords.built(saved.brief.builtAtMs, now, zone()),
            savedCopy = BriefWords.savedCopy(saved.savedAtMs, now, why),
        )
    }

    companion object {
        /** The production wiring: this device's engine, SharedPreferences, the sitrep's own sections. */
        fun forApp(context: Context): BriefController {
            val app = context.applicationContext
            return BriefController(
                backend = { djangoBriefBackendOrNull(app) },
                cache = PrefsBriefCache(app),
                phone = SitrepBriefPhone(app),
                openLoops = OpenLoopsController.forContext(app).let { c ->
                    object : OpenLoopsLines {
                        override suspend fun voiceLine() = c.voiceLine()

                        override suspend fun screenLine() = c.screenLine()
                    }
                },
            )
        }

        /** `MidnightApplication.brief` when the app is ours (always, outside a bare test context). */
        fun of(context: Context): BriefController =
            (context.applicationContext as? MidnightApplication)?.brief ?: forApp(context)
    }
}
