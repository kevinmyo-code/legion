package com.kevin.legion.service

import com.kevin.legion.greeting.GreetingBudget
import com.kevin.legion.greeting.GreetingOpening
import com.kevin.legion.greeting.GreetingPlan
import java.time.LocalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A tap or the wake word on Gemini Live opens with a personal greeting, with the scripted line as
 * the fallback (ADR 0091, alfred-greets ticket 02; amends ADR 0087's "a tap asks the model for
 * nothing"). The controller owns the sockets; this owns the decision and the clock.
 *
 * **How the model is called on Live.** The facts block rides in the opener turn
 * ([GeminiLiveSession.beginConversation]'s `opener`, the door the first-run hello and the
 * lost-thread line already use), so Live speaks the greeting itself in Alfred's Puck voice with no
 * second model call: one generation, billed like those two openers. A separate one-shot to write
 * the text first and Live to read it out would add a full round trip (about 1 s) before any audio
 * and still be a Live generation to speak it, so it costs more and is slower. The cost is the one
 * ADR 0091 accepts ("on Live too").
 *
 * **Fallback.** The pack fetch is bounded by [GreetingBudget.PACK_MS] inside [GreetingOpening]; the
 * model by [GreetingBudget.FIRST_OUTPUT_MS] here: no [noteSpeaking] by then and the scripted line
 * plays ([GeminiLiveSession.abandonOpener]). Nothing about either miss is spoken.
 */
internal class LiveGreeting(
    private val scope: CoroutineScope,
    private val opening: GreetingOpening,
    private val isCurrent: (GeminiLiveSession) -> Boolean,
    /** The scripted open: the line plus the mic, [LiveSessionController.openListening]'s warm form. */
    private val scripted: (GeminiLiveSession) -> Boolean,
    /** The scripted cue for the model-too-slow fallback. */
    private val cue: () -> ListenCue.Cue,
    /** Arms the phone's local command check for the first turn, as the scripted open does. */
    private val armFirstTurn: (GeminiLiveSession) -> Unit,
    private val firstOutputMs: Long = GreetingBudget.FIRST_OUTPUT_MS,
    private val log: (String) -> Unit = {},
) {
    @Volatile private var spoke = false
    private var watch: Job? = null

    /** Starts the pack fetch now, so it overlaps the connect. Never throws. */
    fun prepare(): Deferred<GreetingPlan> = scope.async(Dispatchers.IO) { opening.prepare(LocalTime.now()) }

    /** Live's own first audio arrived: the model made the budget. */
    fun noteSpeaking() {
        spoke = true
        watch?.cancel()
    }

    /**
     * Opens [s] with the greeting [prep] resolves to, else the scripted line. [onStale] runs when the
     * socket turned out to be gone (a warm one that died), so the controller can cold-connect.
     */
    fun open(s: GeminiLiveSession, prep: Deferred<GreetingPlan>, onStale: () -> Unit = {}) {
        spoke = false
        watch?.cancel()
        scope.launch {
            val plan = prep.await()
            // Tapped again, or ended, while the pack was being fetched.
            if (!isCurrent(s)) return@launch
            if (plan is GreetingPlan.Personal) {
                armFirstTurn(s)
                if (s.beginConversation(plan.prompt)) {
                    log("greeting personal topic=${plan.topic}")
                    opening.noteRaised(plan.topic)
                    watch = scope.launch {
                        delay(firstOutputMs)
                        if (!spoke && isCurrent(s) && s.abandonOpener(cue())) log("greeting model_slow scripted")
                    }
                    return@launch
                }
            } else if (plan is GreetingPlan.Scripted) {
                log("greeting scripted reason=${plan.reason}")
            }
            if (!scripted(s) && isCurrent(s)) onStale()
        }
    }
}
