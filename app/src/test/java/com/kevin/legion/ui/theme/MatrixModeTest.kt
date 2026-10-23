package com.kevin.legion.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import com.kevin.legion.ui.dumb.MatrixColorScheme
import com.kevin.legion.ui.dumb.MatrixSemantics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** The Matrix theme switch is a pure choice: on picks Matrix, off hands back exactly what it was given. */
class MatrixModeTest {
    private val normal = darkColorScheme(primary = Color(0xFFABCDEF))

    @Test
    fun `mode on picks the Matrix scheme and semantics`() {
        assertSame(MatrixColorScheme, pickColorScheme(true, normal))
        assertSame(MatrixSemantics, pickSemantics(true, MatrixSemantics.copy(credit = Color.Red)))
    }

    @Test
    fun `mode off leaves the normal scheme and semantics untouched`() {
        assertSame(normal, pickColorScheme(false, normal))
        val sem = MatrixSemantics.copy(credit = Color.Red)
        assertEquals(sem, pickSemantics(false, sem))
    }
}
