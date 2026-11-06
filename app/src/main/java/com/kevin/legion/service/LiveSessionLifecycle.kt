package com.kevin.legion.service

/**
 * The pure decisions behind [LiveSessionController]'s socket lifecycle (Kevin, 2026-11-05: *"stop
 * keeping a voice session open while idle; only open one when you tap or say the wake word."*).
 *
 * [LiveSessionController] needs a live Context, a [GeminiLiveSession] and Room to construct at all,
 * so - as with its companion's `shouldRetryColdConnect` and `resumeHandleAfterClose` - the rules
 * live here as plain functions a JVM test walks directly ([LiveSessionLifecycleTest]). The
 * controller calls exactly these; nothing here touches a socket.
 */
internal object LiveSessionLifecycle {

    /** What a [LiveEvent.Closed] opens next. Only the two non-[NOTHING] answers open a socket. */
    enum class AfterClose {
        /** Leave it closed. The next tap, wake word or raise opens one if it needs one. */
        NOTHING,

        /** Silero failed mid-conversation and closed the socket to come back on server VAD: resume
         *  the same conversation at once, mic open, no greeting. Someone is talking. */
        RESUME_CONVERSATION,

        /** A tap's cold connect closed before it ever connected, for a reason a retry can fix: one
         *  silent retry for that tap. Someone just asked. */
        RETRY_COLD,
    }

    /**
     * The whole "does a close reopen" rule. [conversationMode] is whether a person's conversation
     * owned the socket; [coldRetryArmed] is whether the closing connect was a tap's
     * (`Pending.CONVERSATION` with a replayable start). There is deliberately no branch for an
     * unattended close: client inactivity, a speak-only line finishing, a parked socket expiring,
     * a stop and a goAway all answer [NOTHING]. The prewarm loop this replaced reopened on those.
     */
    fun afterClose(
        reason: String,
        conversationMode: Boolean,
        destroyed: Boolean,
        coldRetryArmed: Boolean,
        everConnected: Boolean,
        alreadyRetried: Boolean,
    ): AfterClose = when {
        destroyed -> AfterClose.NOTHING
        reason == GeminiLiveSession.TURN_FALLBACK_REASON && conversationMode -> AfterClose.RESUME_CONVERSATION
        coldRetryArmed &&
            LiveSessionController.shouldRetryColdConnect(conversationMode, everConnected, reason, alreadyRetried) ->
            AfterClose.RETRY_COLD
        else -> AfterClose.NOTHING
    }

    /**
     * The single-owner guard on events: only the session the controller currently holds may drive
     * it. A superseded session's late event (its Closed above all) used to reach the controller's
     * handler and could null out the session that replaced it, leaving that socket open with
     * nobody holding it.
     */
    fun <T : Any> accepts(source: T?, current: T?): Boolean = source != null && source === current

    /** Where a proactive line goes, given what the controller holds right now. */
    enum class SpeakRoute(val label: String) {
        /** Nothing held: open a short-lived speak-only session for this line. */
        COLD_START("cold startProactive (mic suppressed)"),

        /** A conversation is running: fold the line into it. */
        FOLD_IN("fold-in (mic WILL reopen after, by design)"),

        /** A connected idle socket (a conversation parked by its backstop): speak on it. */
        SPEAK_ON_WARM("warm speakOnWarm (mic suppressed)"),

        /** A socket is connecting with nothing queued on it: speak once it is up. */
        QUEUE_ON_CONNECT("mid-connect PROACTIVE_WARM (mic suppressed)"),

        /** A socket is connecting FOR something (a tap's conversation, another raise): refuse,
         *  rather than overwrite what it is connecting for. */
        REFUSE_CONNECT_IN_FLIGHT("refused: a connect is in flight for something else"),
    }

    fun speakRoute(hasSession: Boolean, inConversation: Boolean, warm: Boolean, connectQueued: Boolean): SpeakRoute =
        when {
            !hasSession -> SpeakRoute.COLD_START
            inConversation -> SpeakRoute.FOLD_IN
            warm -> SpeakRoute.SPEAK_ON_WARM
            !connectQueued -> SpeakRoute.QUEUE_ON_CONNECT
            else -> SpeakRoute.REFUSE_CONNECT_IN_FLIGHT
        }
}
