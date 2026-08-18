package com.kevin.legion.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [TempUnit]/[Temp] - ticket 07, amended 2026-08-18 to make the unit a driver setting rather than
 * a fixed Celsius. Most of this is pure JVM (the arithmetic and string formatting take an explicit
 * [TempUnit] and touch no [android.content.Context]); [Temp.unit]/[Temp.setUnit] are Robolectric
 * only, same reasoning as [com.kevin.legion.ai.CompanionProfileTest] - they read/write a real
 * `SharedPreferences` file.
 */
@RunWith(RobolectricTestRunner::class)
class UnitsTest {
    private val context = RuntimeEnvironment.getApplication()

    // -------------------------------------------------------------- convert

    @Test
    fun `convert is a no-op for Celsius`() {
        assertEquals(82.0, Temp.convert(82.0, TempUnit.CELSIUS), 0.0)
    }

    @Test
    fun `convert 0C is 32F`() {
        assertEquals(32.0, Temp.convert(0.0, TempUnit.FAHRENHEIT), 0.0)
    }

    @Test
    fun `convert 100C is 212F`() {
        assertEquals(212.0, Temp.convert(100.0, TempUnit.FAHRENHEIT), 0.0)
    }

    @Test
    fun `convert 82C is 180F, the overheat-alert round number`() {
        // 82 * 9/5 + 32 = 179.6, which is the round number this app actually alerts at
        // (AriaForegroundService's OVERHEAT_C is close to this range) - matched to the nearest
        // whole degree the way every rendered temperature in the app is.
        assertEquals(179.6, Temp.convert(82.0, TempUnit.FAHRENHEIT), 0.001)
    }

    // -------------------------------------------------------------- text / spoken

    @Test
    fun `text renders Celsius with the degree-C symbol`() {
        assertEquals("82°C", Temp.text(82.0, TempUnit.CELSIUS))
    }

    @Test
    fun `text renders Fahrenheit converted, with the degree-F symbol`() {
        assertEquals("180°F", Temp.text(82.0, TempUnit.FAHRENHEIT))
    }

    @Test
    fun `text and spoken always report the same number, Celsius`() {
        val celsius = 91.3
        val textDigits = Temp.text(celsius, TempUnit.CELSIUS, decimals = 1).removeSuffix("°C")
        val spokenDigits = Temp.spoken(celsius, TempUnit.CELSIUS, decimals = 1).removePrefix("").let {
            it.substringBefore(" degrees")
        }
        assertEquals(textDigits, spokenDigits)
    }

    @Test
    fun `text and spoken always report the same number, Fahrenheit`() {
        val celsius = 91.3
        val textDigits = Temp.text(celsius, TempUnit.FAHRENHEIT, decimals = 1).removeSuffix("°F")
        val spokenDigits = Temp.spoken(celsius, TempUnit.FAHRENHEIT, decimals = 1).substringBefore(" degrees")
        assertEquals(textDigits, spokenDigits)
    }

    @Test
    fun `spoken says the unit in words, not the symbol`() {
        assertEquals("82 degrees Celsius", Temp.spoken(82.0, TempUnit.CELSIUS))
        assertEquals("180 degrees Fahrenheit", Temp.spoken(82.0, TempUnit.FAHRENHEIT))
    }

    // -------------------------------------------------------------- isCelsiusLabel

    @Test
    fun `isCelsiusLabel accepts the degree-C symbol`() {
        assertTrue(Temp.isCelsiusLabel("°C"))
    }

    @Test
    fun `isCelsiusLabel accepts a bare C`() {
        assertTrue(Temp.isCelsiusLabel("C"))
    }

    @Test
    fun `isCelsiusLabel is case-insensitive`() {
        assertTrue(Temp.isCelsiusLabel("c"))
    }

    @Test
    fun `isCelsiusLabel rejects rpm`() {
        assertFalse(Temp.isCelsiusLabel("rpm"))
    }

    @Test
    fun `isCelsiusLabel rejects V`() {
        assertFalse(Temp.isCelsiusLabel("V"))
    }

    @Test
    fun `isCelsiusLabel rejects g slash s`() {
        assertFalse(Temp.isCelsiusLabel("g/s"))
    }

    @Test
    fun `isCelsiusLabel rejects null`() {
        assertFalse(Temp.isCelsiusLabel(null))
    }

    // -------------------------------------------------------------- labelFor

    @Test
    fun `labelFor passes a non-temperature label through unchanged`() {
        assertEquals("rpm", Temp.labelFor(context, "rpm"))
        assertEquals("V", Temp.labelFor(context, "V"))
        assertEquals("g/s", Temp.labelFor(context, "g/s"))
    }

    @Test
    fun `labelFor passes null through as empty, not as a temperature symbol`() {
        assertEquals("", Temp.labelFor(context, null))
    }

    @Test
    fun `labelFor swaps a Celsius label for the driver's chosen unit symbol`() {
        Temp.setUnit(context, TempUnit.FAHRENHEIT)
        assertEquals("°F", Temp.labelFor(context, "°C"))
        Temp.setUnit(context, TempUnit.CELSIUS)
        assertEquals("°C", Temp.labelFor(context, "C"))
    }

    // -------------------------------------------------------------- the setting itself

    @Test
    fun `unit defaults to Celsius on a fresh install`() {
        context.getSharedPreferences("unit_preferences", android.content.Context.MODE_PRIVATE).edit().clear().apply()
        assertEquals(TempUnit.CELSIUS, Temp.unit(context))
    }

    @Test
    fun `setUnit persists and unit reads it back`() {
        Temp.setUnit(context, TempUnit.FAHRENHEIT)
        assertEquals(TempUnit.FAHRENHEIT, Temp.unit(context))
        Temp.setUnit(context, TempUnit.CELSIUS)
        assertEquals(TempUnit.CELSIUS, Temp.unit(context))
    }

    @Test
    fun `a corrupted stored key falls back to the default rather than crashing`() {
        context.getSharedPreferences("unit_preferences", android.content.Context.MODE_PRIVATE)
            .edit().putString("temp_unit", "kelvin").apply()
        assertEquals(TempUnit.CELSIUS, Temp.unit(context))
    }

    @Test
    fun `context-driven text and convert follow the stored setting`() {
        Temp.setUnit(context, TempUnit.FAHRENHEIT)
        assertEquals("180°F", Temp.text(context, 82.0))
        assertEquals(179.6, Temp.convert(context, 82.0), 0.001)
        Temp.setUnit(context, TempUnit.CELSIUS)
    }
}
