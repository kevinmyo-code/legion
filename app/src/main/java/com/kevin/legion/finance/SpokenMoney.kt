package com.kevin.legion.finance

import kotlin.math.abs

/**
 * Money and account numbers as they are SPOKEN, made by code (stage 7: the voice never phrases an
 * amount itself). One plain style, pinned by tests: "two thousand one hundred thirty-one dollars and
 * seventy-five cents"; whole dollars drop the cents ("forty-five dollars"); one dollar / one cent are
 * singular; a negative amount is "minus ..."; a mask is said digit by digit ("one zero zero zero").
 */
object SpokenMoney {
    private val ONES = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven",
        "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    )
    private val TENS = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
    private const val BILLION = 1_000_000_000L
    private const val MILLION = 1_000_000L
    private const val THOUSAND = 1_000L
    private val SCALES = listOf(BILLION to "billion", MILLION to "million", THOUSAND to "thousand")
    private const val CENTS = 100L
    private const val HUNDRED = 100L
    private const val TEEN_LIMIT = 20L
    private const val TEN = 10L

    /** Whole number in words, 0 up. */
    fun number(n: Long): String {
        require(n >= 0) { "number takes 0 or more" }
        if (n == 0L) return "zero"
        val parts = mutableListOf<String>()
        var rest = n
        for ((size, name) in SCALES) {
            if (rest >= size) {
                parts += "${belowThousand(rest / size)} $name"
                rest %= size
            }
        }
        if (rest > 0) parts += belowThousand(rest)
        return parts.joinToString(" ")
    }

    private fun belowThousand(n: Long): String {
        val hundreds = n / HUNDRED
        val rest = n % HUNDRED
        val head = if (hundreds > 0) "${ONES[hundreds.toInt()]} hundred" else ""
        val tail = when {
            rest == 0L -> ""
            rest < TEEN_LIMIT -> ONES[rest.toInt()]
            rest % TEN == 0L -> TENS[(rest / TEN).toInt()]
            else -> "${TENS[(rest / TEN).toInt()]}-${ONES[(rest % TEN).toInt()]}"
        }
        return listOf(head, tail).filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** [cents] as spoken dollars and cents (USD); another currency is named after the amount. */
    fun amount(cents: Long, currency: String = "USD"): String {
        val sign = if (cents < 0) "minus " else ""
        val whole = abs(cents) / CENTS
        val part = abs(cents) % CENTS
        val (one, many) = if (currency == "USD") "dollar" to "dollars" else currency to currency
        val dollars = "${number(whole)} ${if (whole == 1L) one else many}"
        val centsWords = if (part == 0L) "" else " and ${number(part)} ${if (part == 1L) "cent" else "cents"}"
        return "$sign$dollars$centsWords"
    }

    /** "one zero zero zero": a mask said digit by digit, never as a number. */
    fun digits(mask: String): String = mask.filter { it.isDigit() }.map { ONES[it - '0'] }.joinToString(" ")
}
