package com.kevin.legion.backend.engine

import com.kevin.legion.backend.BodyweightLogFields
import com.kevin.legion.backend.MealLogFields
import com.kevin.legion.backend.SleepLogFields
import com.kevin.legion.backend.WorkoutPlanItemFields
import com.kevin.legion.backend.engine.EngineTestSupport.json
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [DjangoBodyBackend] against `server/api/body.py`'s real shapes - eight tables, one interface.
 *
 * **The two fixtures are the live engine's own replies**, captured 2026-09-07 from
 * `GET .../api/body/bodyweight_logs/` and `.../api/body/meal_logs/` and trimmed to two rows each.
 * The refusal sentence asserted below was also measured against that engine, not written from the
 * Python: a `PUT` carrying `"weight_unit": "stones"` to an `origin_guid` the server has never seen
 * answered `400 {"weight_unit":["'stones' is not a valid weight_unit. Use one of: lbs, kg."]}` and
 * stored nothing (the table still held its two rows afterwards).
 *
 * The point of this aspect is not the eight tables. It is that
 * [com.kevin.legion.backend.BodySync]/[com.kevin.legion.backend.BodyOutboxDrain]/
 * [com.kevin.legion.backend.BodyBackfill] and every one of their tests are untouched by a second
 * transport existing - they take a [com.kevin.legion.backend.BodyBackend] and cannot tell which
 * one they were handed.
 */
@RunWith(RobolectricTestRunner::class)
class DjangoBodyBackendTest {

    private val context = RuntimeEnvironment.getApplication()

    private fun backend(engine: EngineTestSupport.RecordingEngine): DjangoBodyBackend =
        DjangoBodyBackend(EngineHttp(EngineTestSupport.signedInConfig(context), engine.client()))

    @Test
    fun `the bodyweight since-feed parses and carries the watermark, tombstones included`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine {
            json(EngineTestSupport.fixture("body_bodyweight_logs_page.json"))
        }

        val rows = backend(engine).fetchChangedBodyweightLogsSince(1786000000000L).getOrThrow()

        assertEquals(2, rows.size)
        val first = rows.single { it.serverId == "4a228390-2cdf-4eda-a2da-1fb20705456d" }
        assertEquals(198.0, first.weightValue, 0.001)
        assertEquals("lbs", first.weightUnit)
        assertEquals("REPORTED", first.trustTier)
        assertEquals("c7db8be1-adf2-4d25-8cbf-e39b5c3e6b92", first.originGuid)
        assertEquals(1786127119946L, first.loggedAtMs)
        assertFalse(first.deleted)

        val url = engine.requests.single().url
        assertEquals("/api/body/bodyweight_logs/", url.encodedPath)
        assertEquals("2026-08-06T07:06:40Z", url.parameters["since"])
        // No `active=1`: a changed-since feed must keep tombstones, or BodySync's tombstone branch
        // never fires. See BodyBackend's own class doc.
        assertNull(url.parameters["active"])
    }

    @Test
    fun `a tombstoned row survives the since-feed and is reported deleted`() = runBlocking {
        val body = """
            {"results": [{
              "id": "4a228390-2cdf-4eda-a2da-1fb20705456d", "weight_value": 198.0,
              "weight_unit": "lbs", "logged_at": "2026-08-07T18:25:19.946000Z",
              "trust_tier": "REPORTED", "provenance": "USER",
              "created_at": "2026-09-02T16:41:35.267570Z", "updated_at": "2026-09-07T09:00:00Z",
              "deleted_at": "2026-09-07T09:00:00Z",
              "origin_guid": "c7db8be1-adf2-4d25-8cbf-e39b5c3e6b92"
            }], "next": null}
        """.trimIndent()
        val engine = EngineTestSupport.RecordingEngine { json(body) }

        val row = backend(engine).fetchChangedBodyweightLogsSince(0L).getOrThrow().single()

        assertTrue(row.deleted)
        // The origin_guid is what BodySync matches a tombstone to a local row by, so it has to
        // survive the tombstone too - a deleted row with no key is a tombstone nobody can apply.
        assertEquals("c7db8be1-adf2-4d25-8cbf-e39b5c3e6b92", row.originGuid)
    }

    @Test
    fun `the meal estimates and their nulls both round-trip`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine {
            json(EngineTestSupport.fixture("body_meal_logs_page.json"))
        }

        val meals = backend(engine).fetchChangedMealLogsSince(0L).getOrThrow()

        val rice = meals.single { it.serverId == "691bc4e2-2644-4bc5-8929-875036c603f7" }
        // Estimates, never gated (CLAUDE.md section 4 rule 5). Nothing here rounds or re-derives
        // them; the server labels all four as estimates in its own generated schema.
        assertEquals(765, rice.caloriesKcal)
        assertEquals(28.0, rice.proteinG!!, 0.001)
        assertNull(rice.sourceImagePath)
        assertEquals("REPORTED", rice.trustTier)
    }

    @Test
    fun `an upsert PUTs to the origin_guid and leaves the guid out of the body`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine {
            json(
                """{"id": "4a228390-2cdf-4eda-a2da-1fb20705456d", "weight_value": 197.5,
                    "weight_unit": "lbs", "logged_at": "2026-09-07T12:00:00Z",
                    "trust_tier": "REPORTED", "provenance": "USER",
                    "created_at": "2026-09-07T12:00:00Z", "updated_at": "2026-09-07T12:00:00Z",
                    "deleted_at": null, "origin_guid": "c7db8be1-adf2-4d25-8cbf-e39b5c3e6b92"}""",
            )
        }

        backend(engine).upsertBodyweightLog(
            "c7db8be1-adf2-4d25-8cbf-e39b5c3e6b92",
            BodyweightLogFields(197.5, "lbs", 1788782400000L, "REPORTED"),
        ).getOrThrow()

        val request = engine.requests.single()
        // PUT, not POST: the identity is in the URL, which is what makes draining the same queued
        // entry twice unable to produce a second row.
        assertEquals("PUT", request.method.value)
        assertEquals(
            "/api/body/bodyweight_logs/c7db8be1-adf2-4d25-8cbf-e39b5c3e6b92/",
            request.url.encodedPath,
        )
        val sent = (request.body as TextContent).text
        assertTrue(sent.contains("\"weight_value\":197.5"))
        assertTrue(sent.contains("\"trust_tier\":\"REPORTED\""))
        // The URL is the authority on identity (SyncedModelViewSet.upsert overwrites the body's
        // copy from the path), so a second copy in the body could only ever disagree.
        assertFalse(sent.contains("origin_guid"))
        assertFalse(sent.contains("provenance"))
    }

    @Test
    fun `a nullable field clears rather than silently keeping its old value`() = runBlocking {
        // explicitNulls = true is the load-bearing setting: an omitted key on a DRF write leaves
        // the STORED value in place, so a caller clearing a sleep note would find it still there.
        val engine = EngineTestSupport.RecordingEngine {
            json(
                """{"id": "d491a19e-9811-4236-8fb2-c9a39457f01f", "sleep_date": "2026-08-08",
                    "duration_minutes": 330, "quality": null, "notes": null,
                    "logged_at": "2026-08-08T12:12:15.959000Z", "trust_tier": "REPORTED",
                    "provenance": "USER", "created_at": "2026-09-02T16:41:39.131874Z",
                    "updated_at": "2026-09-07T12:00:00Z", "deleted_at": null,
                    "origin_guid": "ef2d7425-b2b2-47cc-a5c1-47fab3aec26b"}""",
            )
        }

        backend(engine).upsertSleepLog(
            "ef2d7425-b2b2-47cc-a5c1-47fab3aec26b",
            SleepLogFields(
                sleepDateEpochMs = 1786147200000L,
                durationMinutes = 330,
                quality = null,
                notes = null,
                loggedAtMs = 1786191135959L,
                trustTier = "REPORTED",
            ),
        ).getOrThrow()

        val sent = (engine.requests.single().body as TextContent).text
        assertTrue(sent.contains("\"quality\":null"))
        assertTrue(sent.contains("\"notes\":null"))
        // A DATE column, sent as a bare date rather than a timestamp.
        assertTrue(sent.contains("\"sleep_date\":\"2026-08-08\""))
    }

    @Test
    fun `a plan item's date column goes out as a date and comes back as UTC midnight`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine {
            json(
                """{"id": "0f380e2d-1da2-40d2-bc54-678a9e1d4095", "exercise": "Barbell Back Squat",
                    "target_sets_per_week": 6, "effective_from_week": "2026-08-03",
                    "reps_per_set": null, "provenance": "USER",
                    "created_at": "2026-09-02T16:41:40.303340Z",
                    "updated_at": "2026-09-02T16:41:40.303340Z", "deleted_at": null,
                    "origin_guid": "1d3d0324-4755-4292-aa11-c01a298ad2e0"}""",
            )
        }

        val saved = backend(engine).upsertWorkoutPlanItem(
            "1d3d0324-4755-4292-aa11-c01a298ad2e0",
            WorkoutPlanItemFields("Barbell Back Squat", 6, 1785715200000L, null),
        ).getOrThrow()

        val sent = (engine.requests.single().body as TextContent).text
        assertTrue(sent.contains("\"effective_from_week\":\"2026-08-03\""))
        // 2026-08-03T00:00:00Z. The Supabase transport uses the identical convention, so a row
        // does not shift a day when the transport is flipped.
        assertEquals(1785715200000L, saved.effectiveFromWeekEpochMs)
    }

    @Test
    fun `the engine's own refusal reaches the caller verbatim, not paraphrased`() = runBlocking {
        // Measured against the live engine on 2026-09-07 - the exact body, and nothing was stored.
        val sentence = "'stones' is not a valid weight_unit. Use one of: lbs, kg."
        val engine = EngineTestSupport.RecordingEngine {
            json("""{"weight_unit": ["$sentence"]}""", HttpStatusCode.BadRequest)
        }

        val result = backend(engine).upsertBodyweightLog(
            "c7db8be1-adf2-4d25-8cbf-e39b5c3e6b92",
            BodyweightLogFields(198.0, "stones", 1L, "REPORTED"),
        )

        val failure = (result.exceptionOrNull() as EngineHttpException).failure
        assertTrue(failure is EngineFailure.Refused)
        assertTrue((failure as EngineFailure.Refused).body.contains(sentence))
        // The unit rule lives in Django and nothing here re-checks it: the request WAS sent, and
        // the refusal came back. A client-side copy would have refused before the round trip and
        // this assertion would fail.
        assertEquals(1, engine.requests.size)
        assertEquals(sentence, engineRefusalSentence(failure.body))
    }

    @Test
    fun `every soft delete is a DELETE on its own table's origin_guid route`() = runBlocking {
        val paths = mutableListOf<String>()
        val engine = EngineTestSupport.RecordingEngine { json("", HttpStatusCode.NoContent) }
        val subject = backend(engine)
        val guid = "c7db8be1-adf2-4d25-8cbf-e39b5c3e6b92"

        assertTrue(subject.softDeleteBodyweightLog(guid).getOrThrow())
        assertTrue(subject.softDeleteMealLog(guid).getOrThrow())
        assertTrue(subject.softDeleteMealTarget(guid).getOrThrow())
        assertTrue(subject.softDeleteSleepLog(guid).getOrThrow())
        assertTrue(subject.softDeleteSleepTarget(guid).getOrThrow())
        assertTrue(subject.softDeleteWorkoutPlan(guid).getOrThrow())
        assertTrue(subject.softDeleteWorkoutPlanItem(guid).getOrThrow())
        assertTrue(subject.softDeleteWorkoutSetLog(guid).getOrThrow())

        engine.requests.forEach {
            assertEquals("DELETE", it.method.value)
            paths += it.url.encodedPath
        }
        assertEquals(
            listOf(
                "bodyweight_logs", "meal_logs", "meal_targets", "sleep_logs",
                "sleep_targets", "workout_plans", "workout_plan_items", "workout_set_logs",
            ).map { "/api/body/$it/$guid/" },
            paths,
        )
    }

    @Test
    fun `an unreachable engine fails in words and BodyOutbox may queue behind it`() = runBlocking {
        // Body DOES have a durable outbox (unlike places), and Unreachable is the branch a caller
        // is allowed to queue behind - the only one where the engine cannot have applied the
        // write. The queueing itself is BodyWriteThrough's, tested in BodyOutboxDrainTest against
        // a fake; what this asserts is that this transport reports the branch that entitles it.
        val http = EngineHttp(EngineTestSupport.signedInConfig(context), EngineTestSupport.unreachableClient())

        val result = DjangoBodyBackend(http).upsertMealLog(
            "1392d79b-ae1b-47cf-84ed-4488fb7cc9e8",
            MealLogFields("Rice with pork", 765, 28.0, 87.0, 34.0, 1L, null, "REPORTED"),
        )

        assertTrue(result.isFailure)
        val failure = (result.exceptionOrNull() as EngineHttpException).failure
        assertTrue(failure is EngineFailure.Unreachable)
        assertTrue(failure.sentence.startsWith("Couldn't save that meal."))
    }
}
