package com.kevin.legion.service

import com.kevin.legion.service.conversationlog.ConversationLog
import com.kevin.legion.service.conversationlog.PostResult
import com.kevin.legion.service.conversationlog.TurnOutbox
import com.kevin.legion.service.conversationlog.TurnSaver
import com.kevin.legion.service.conversationlog.TurnTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A test or eval conversation (memory chain ticket 450) never saves a turn: a scripted line must not
 * become "what we talked about today". The control case proves the saver is wired, so the test can fail.
 */
class LiveAuditTestConversationTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var box: TurnOutbox

    private val offline = object : TurnTransport {
        override fun isUsable() = false

        override suspend fun post(json: String): PostResult = PostResult.Unreachable("offline")
    }

    @Before fun install() {
        box = TurnOutbox(tmp.root.resolve("turns.jsonl"), offline)
        ConversationLog.installTurnSaverForTest(
            TurnSaver(
                box = box,
                writer = { it.run() },
                scope = CoroutineScope(Dispatchers.Unconfined),
                keep = { true },
                log = { _, _ -> },
            ),
        )
    }

    @After fun uninstall() = ConversationLog.installTurnSaverForTest(null)

    private fun speak(audit: LiveAudit) {
        audit.enabled = true
        audit.sessionStart("model", emptySet())
        audit.user("what is on my list", final = true)
        audit.assistant("Milk and eggs.", readThrough = false)
        audit.turnComplete()
    }

    @Test fun `a conversation with the default audit saves its turn`() {
        speak(LiveAudit(sink = {}, onSession = {}))
        assertEquals(1, box.pending())
    }

    @Test fun `a test conversation saves nothing`() {
        speak(LiveAudit.forTestConversation { })
        assertEquals(0, box.pending())
    }
}
