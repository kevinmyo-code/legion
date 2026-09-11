package com.kevin.legion.news

import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Exercises [FeedSubscriptionController] - the hands path for the feed URLs one-home ticket 07
 * persists (ticket 06 resolution point 2: "unambiguously his"). Robolectric through the real
 * [com.kevin.legion.data.local.CarDatabase.getDatabase] path, same shape as
 * `checklists/ChecklistControllerTest.kt` - see [RoomTestReset]'s own doc for why the singleton
 * needs resetting per method.
 */
@RunWith(RobolectricTestRunner::class)
class FeedSubscriptionControllerTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    @After
    fun drainRoomInvalidationTracker() {
        RoomTestReset.drainArchDiskIoPool()
    }

    @Test
    fun `a valid http url is added and observable`() = runBlocking {
        val result = FeedSubscriptionController.add(context, "https://example.com/feed.xml", "Example")
        assertTrue(result is FeedSubscriptionController.AddResult.Added)
        val rows = FeedSubscriptionController.observeAll(context).first()
        assertEquals(1, rows.size)
        assertEquals("https://example.com/feed.xml", rows[0].url)
        assertEquals("Example", rows[0].title)
    }

    @Test
    fun `a blank url is refused, in words, and nothing is added`() = runBlocking {
        val result = FeedSubscriptionController.add(context, "   ", null)
        assertTrue(result is FeedSubscriptionController.AddResult.Refused)
        assertTrue((result as FeedSubscriptionController.AddResult.Refused).reason.isNotBlank())
        assertEquals(0, FeedSubscriptionController.observeAll(context).first().size)
    }

    @Test
    fun `a url with no scheme is refused, in words`() = runBlocking {
        val result = FeedSubscriptionController.add(context, "example.com/feed.xml", null)
        assertTrue(result is FeedSubscriptionController.AddResult.Refused)
        assertEquals(0, FeedSubscriptionController.observeAll(context).first().size)
    }

    @Test
    fun `a duplicate url is refused rather than silently accepted twice`() = runBlocking {
        FeedSubscriptionController.add(context, "https://example.com/feed.xml", null)
        val second = FeedSubscriptionController.add(context, "https://example.com/feed.xml", "Renamed")
        assertTrue(second is FeedSubscriptionController.AddResult.Refused)
        assertEquals(1, FeedSubscriptionController.observeAll(context).first().size)
    }

    @Test
    fun `removing a subscription drops it from the observed list`() = runBlocking {
        FeedSubscriptionController.add(context, "https://example.com/feed.xml", null)
        val id = FeedSubscriptionController.observeAll(context).first().first().id
        FeedSubscriptionController.remove(context, id)
        assertEquals(0, FeedSubscriptionController.observeAll(context).first().size)
    }
}
