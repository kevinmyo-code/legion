package com.kevin.legion.greeting

/** A pack body as `GET /api/greeting/facts` sends it, built from small pieces. */
object GreetingTestSupport {
    fun item(topic: String, says: String, raised: Boolean = false, kind: String? = null): String =
        """{"topic":"$topic","says":"$says","raised":$raised${kind?.let { ""","kind":"$it"""" } ?: ""}}"""

    fun facts(vararg items: String) =
        """{"state":"facts","reason":null,"says":"${items.size} on file.","note":null,"left_out":0,""" +
            """"items":[${items.joinToString(",")}]}"""

    fun empty(says: String) = """{"state":"empty","reason":null,"says":"$says","note":null,"left_out":0,"items":[]}"""

    fun unreadable(reason: String) =
        """{"state":"unreadable","reason":"$reason","says":"$reason","note":null,"left_out":0,"items":[]}"""

    fun pack(
        ended: String = empty("No calendar event ended."),
        today: String = empty("Nothing today."),
        due: String = empty("Nothing due."),
        body: String = empty("No workout."),
        money: String = empty("No caps."),
        said: List<String> = emptyList(),
        closed: List<String> = emptyList(),
        topicsRead: Boolean = true,
    ): String {
        val topics = if (topicsRead) {
            """{"state":"read","reason":null}"""
        } else {
            """{"state":"unreadable","reason":"could not read topics"}"""
        }
        val sources = """{"ended_events":$ended,"today_events":$today,"due_soon":$due,"body":$body,"money":$money}"""
        val saidJson = said.joinToString(",") { "\"$it\"" }
        val closedJson = closed.joinToString(",") { "\"$it\"" }
        return """{"generated_at":"2026-11-13T09:00:00-06:00","today":"2026-11-13","utc_offset":"-06:00",""" +
            """"zone_note":null,"sources":$sources,"said":[$saidJson],"closed":[$closedJson],"topics":$topics}"""
    }

    fun parsed(json: String): FactsPack = FactsParser.parse(json)

    val homeOnly = object : PlaceSignals {
        override fun fix() = 29.7 to -95.3
        override suspend fun area(latitude: Double, longitude: Double): AreaLookup = AreaLookup.Found("Houston, Texas")
        override suspend fun atHome(): Boolean? = true
    }

    fun signals(
        fix: Pair<Double, Double>? = 30.0 to -97.0,
        area: AreaLookup = AreaLookup.Found("Austin, Texas"),
        atHome: Boolean? = false,
    ) = object : PlaceSignals {
        override fun fix() = fix
        override suspend fun area(latitude: Double, longitude: Double) = area
        override suspend fun atHome() = atHome
    }
}

class FakeGreetingBackend(
    private val outcome: suspend () -> GreetingOutcome<FactsPack>,
) : GreetingBackend {
    val recorded = mutableListOf<Pair<String, String>>()
    var recordGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override suspend fun facts() = outcome()

    override suspend fun recordTopic(topicKey: String, state: String): Boolean {
        recordGate?.await()
        recorded += topicKey to state
        return true
    }
}
