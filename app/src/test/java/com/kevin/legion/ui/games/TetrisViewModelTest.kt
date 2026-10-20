package com.kevin.legion.ui.games

import android.app.Application
import com.kevin.legion.games.race.FakeGamesBackend
import com.kevin.legion.games.race.MatchStatus
import com.kevin.legion.games.race.PlayerProgress
import com.kevin.legion.games.race.RACE_UNREACHABLE
import com.kevin.legion.games.race.TheirBoard
import com.kevin.legion.games.race.matchState
import com.kevin.legion.games.tetris.Board
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Pause, the pad and theme persistence, with a fake frame clock tied to the test scheduler. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TetrisViewModelTest {
    private val scheduler = TestCoroutineScheduler()
    private var fakeNanos = 0L
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        app.getSharedPreferences("legion_tetris", 0).edit().clear().commit()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun solo(): TetrisViewModel =
        TetrisViewModel(app, nanoClock = { fakeNanos }).also {
            it.start(TetrisMode.Solo)
            scheduler.runCurrent()
        }

    /** Advance both the virtual dispatcher and the game's clock in 16 ms frames. */
    private fun run(ms: Long) {
        repeat((ms / FRAME).toInt()) {
            fakeNanos += FRAME * NANOS_PER_MS
            scheduler.advanceTimeBy(FRAME)
            scheduler.runCurrent()
        }
    }

    private fun TetrisViewModel.pieceY() = state.value.board?.active?.y

    @Test
    fun `gravity moves the piece until paused, then stops, then resumes`() {
        val vm = solo()
        val start = vm.pieceY()!!
        run(3_000)
        val falling = vm.pieceY()!!
        assertTrue("gravity should move it ($start to $falling)", falling > start)

        vm.pause()
        val frozen = vm.state.value.board
        assertTrue(vm.state.value.paused)
        run(5_000)
        assertEquals("gravity and lock delay stopped", frozen?.active, vm.state.value.board?.active)
        assertEquals(frozen?.score, vm.state.value.board?.score)

        vm.resume()
        assertFalse(vm.state.value.paused)
        run(3_000)
        assertNotEquals(frozen?.active, vm.state.value.board?.active)
    }

    @Test
    fun `input is refused while paused`() {
        val vm = solo()
        val x = vm.state.value.board?.active?.x
        vm.pause()
        vm.padDown(PadButton.LEFT)
        vm.padUp(PadButton.LEFT)
        vm.padDown(PadButton.UP)
        scheduler.runCurrent()
        assertEquals(x, vm.state.value.board?.active?.x)
    }

    @Test
    fun `start toggles pause and auto pause stays paused until resume`() {
        val vm = solo()
        vm.padDown(PadButton.START)
        assertTrue(vm.state.value.paused)
        vm.padDown(PadButton.START)
        assertFalse(vm.state.value.paused)
        vm.autoPause()
        assertTrue(vm.state.value.paused)
        vm.autoPause()
        assertTrue("a second background event does not un-pause", vm.state.value.paused)
        vm.resume()
        assertFalse(vm.state.value.paused)
    }

    @Test
    fun `a race refuses to pause`() {
        // No engine is configured under test, so the race opens CLOSED; the guard is the race flag,
        // which is set the same way for a live race. The sentence the screen shows is a constant.
        val vm = TetrisViewModel(app, nanoClock = { fakeNanos })
        vm.start(TetrisMode.Invite("someone"))
        scheduler.runCurrent()
        assertTrue(vm.state.value.race)
        vm.pause()
        vm.padDown(PadButton.START)
        assertFalse(vm.state.value.paused)
        assertEquals("Races can't pause, the clock is shared.", RACE_NO_PAUSE)
    }

    @Test
    fun `the pad moves the piece and a held direction repeats after DAS`() {
        val vm = solo()
        val x = vm.state.value.board!!.active!!.x
        vm.padDown(PadButton.LEFT)
        scheduler.runCurrent()
        assertEquals(x - 1, vm.state.value.board!!.active!!.x)
        scheduler.advanceTimeBy(DAS_MS - 1)
        scheduler.runCurrent()
        assertEquals("no repeat before DAS", x - 1, vm.state.value.board!!.active!!.x)
        scheduler.advanceTimeBy(1)
        scheduler.runCurrent()
        assertEquals(x - 2, vm.state.value.board!!.active!!.x)
        vm.padUp(PadButton.LEFT)
        scheduler.advanceTimeBy(1_000)
        scheduler.runCurrent()
        assertEquals("release stops the repeat", x - 2, vm.state.value.board!!.active!!.x)
    }

    @Test
    fun `theme is remembered across view models and select cycles it`() {
        val first = solo()
        assertEquals(TetrisTheme.CLASSIC, first.state.value.theme)
        first.setTheme(TetrisTheme.NEON)
        assertEquals(TetrisTheme.NEON, TetrisViewModel(app, nanoClock = { fakeNanos }).state.value.theme)
        first.padDown(PadButton.SELECT)
        assertEquals(TetrisTheme.SOFT, first.state.value.theme)
        assertEquals(TetrisTheme.SOFT, TetrisThemeStore(app).load())
        first.playAgain()
        assertEquals("a new game keeps the theme", TetrisTheme.SOFT, first.state.value.theme)
    }

    @Test
    fun `the Drop key hard-drops at once, like Up`() {
        val vm = solo()
        val before = vm.state.value.board!!
        assertEquals(0, before.cells.count { it != 0 })
        vm.padDown(PadButton.DROP)
        vm.padUp(PadButton.DROP)
        scheduler.runCurrent()
        val after = vm.state.value.board!!
        assertEquals("the piece locked on the floor", 4, after.cells.count { it != 0 })
        assertTrue(after.cells.drop(after.cells.size - Board.COLUMNS).any { it != 0 })
    }

    // ---- the race against a fake engine: board out, their board in, and when to speak

    private fun racing(fake: FakeGamesBackend): TetrisViewModel {
        val now = System.currentTimeMillis()
        fake.matchResult = Result.success(
            matchState(MatchStatus.PLAYING, startAtMs = now - 10_000, endsAtMs = now + 170_000, serverNowMs = now)
                .copy(opponent = PlayerProgress(score = 300, board = THEIR_BOARD, updatedAtMs = now)),
        )
        return TetrisViewModel(app, { fakeNanos }, { fake }).also {
            it.start(TetrisMode.Match("m1"))
            run(200)
        }
    }

    @Test
    fun `a race sends my board with my score and shows theirs`() {
        val fake = FakeGamesBackend()
        val vm = racing(fake)
        run(1_100)
        assertEquals(TetrisStage.PLAYING, vm.state.value.stage)
        val sent = fake.progressSent.last()
        assertTrue(sent.board, sent.board.matches(Regex("[.IJLOSTZ]{220}")))
        assertEquals(THEIR_BOARD, vm.state.value.opponentBoard)
        assertEquals(TheirBoard.Live, vm.state.value.theirBoard)
        assertEquals(300, vm.state.value.opponentScore)
    }

    @Test
    fun `one failed poll says nothing, five quiet seconds say one line, and it clears on recovery`() {
        val fake = FakeGamesBackend()
        val vm = racing(fake)
        val good = fake.matchResult
        val down = Result.failure<Nothing>(IllegalStateException("Could not reach the engine: Socket timeout"))
        fake.matchResult = down
        fake.actionResult = down
        run(2_000)
        assertNull("a couple of failed polls are not news", vm.state.value.link)
        assertNull(vm.state.value.error)
        run(3_500)
        assertEquals(RACE_UNREACHABLE, vm.state.value.link)
        assertNull("no raw failure text, ever, in a race", vm.state.value.error)
        assertEquals("the game is still running", TetrisStage.PLAYING, vm.state.value.stage)

        fake.matchResult = good
        fake.actionResult = Result.success(Unit)
        run(1_100)
        assertNull(vm.state.value.link)
    }

    @Test
    fun `a progress post alone keeps the line away while reads fail`() {
        val fake = FakeGamesBackend()
        val vm = racing(fake)
        fake.matchResult = Result.failure(IllegalStateException("read failed"))
        run(7_000)
        assertNull(vm.state.value.link)
        assertTrue(fake.progressSent.size >= 6)
    }

    @Test
    fun `solo never reaches for the engine`() {
        var asked = 0
        val vm = TetrisViewModel(app, { fakeNanos }, { asked++; FakeGamesBackend() })
        vm.start(TetrisMode.Solo)
        run(6_000)
        assertEquals(0, asked)
        assertNull(vm.state.value.link)
        assertNull(vm.state.value.error)
    }

    private companion object {
        const val FRAME = 16L
        const val NANOS_PER_MS = 1_000_000L
        val THEIR_BOARD = ".".repeat(200) + "IIII......" + "ZZ....OO.."
    }
}
