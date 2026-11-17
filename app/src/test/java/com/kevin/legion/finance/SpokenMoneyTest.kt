@file:Suppress("MaxLineLength") // fixtures: invented amounts read best on one line

package com.kevin.legion.finance

import org.junit.Assert.assertEquals
import org.junit.Test

/** [SpokenMoney]: one plain spoken style, pinned. Invented amounts. */
class SpokenMoneyTest {
    @Test
    fun `dollars and cents in words`() {
        assertEquals("two thousand one hundred thirty-one dollars and seventy-five cents", SpokenMoney.amount(213_175))
        assertEquals("three thousand four hundred twelve dollars and fifty cents", SpokenMoney.amount(341_250))
        assertEquals("one thousand one hundred ninety-five dollars and fifty-five cents", SpokenMoney.amount(119_555))
        assertEquals("eighty-five dollars and twenty cents", SpokenMoney.amount(8_520))
    }

    @Test
    fun `zero cents, singulars, zero and big amounts`() {
        assertEquals("forty-five dollars", SpokenMoney.amount(4_500))
        assertEquals("one dollar", SpokenMoney.amount(100))
        assertEquals("one dollar and one cent", SpokenMoney.amount(101))
        assertEquals("zero dollars and five cents", SpokenMoney.amount(5))
        assertEquals("zero dollars", SpokenMoney.amount(0))
        assertEquals(
            "one million two hundred thirty-four thousand five hundred sixty-seven dollars and eighty-nine cents",
            SpokenMoney.amount(123_456_789),
        )
        assertEquals("ten thousand dollars", SpokenMoney.amount(1_000_000))
        assertEquals("one hundred dollars", SpokenMoney.amount(10_000))
    }

    @Test
    fun `negative amounts say minus`() {
        assertEquals("minus forty-five dollars", SpokenMoney.amount(-4_500))
        assertEquals("minus one thousand one hundred ninety-five dollars and fifty-five cents", SpokenMoney.amount(-119_555))
    }

    @Test
    fun `a mask is said digit by digit`() {
        assertEquals("one zero zero zero", SpokenMoney.digits("1000"))
        assertEquals("seven eight two three", SpokenMoney.digits("7823"))
        assertEquals("four four one zero", SpokenMoney.digits("****4410"))
    }

    @Test
    fun `another currency is named after the amount`() {
        assertEquals("twelve SGD and fifty cents", SpokenMoney.amount(1_250, "SGD"))
    }
}
