package com.kevin.legion.location

import org.junit.Assert.assertEquals
import org.junit.Test

/** [AddressChoice] - which forward-lookup result, if any, a save may use. Pure. */
class AddressChoiceTest {
    private val a = GeocodedAddress("123 Main St, Katy, TX 77494", 1.0, 2.0)
    private val b = GeocodedAddress("123 Main St, Katy, TX 77493", 3.0, 4.0)

    @Test
    fun `a single result is the answer`() {
        assertEquals(AddressChoice.Decision.One(a), AddressChoice.decide("123 main", listOf(a)))
    }

    @Test
    fun `duplicates of one address are one result`() {
        assertEquals(AddressChoice.Decision.One(a), AddressChoice.decide("123 main", listOf(a, a.copy(latitude = 1.1))))
    }

    @Test
    fun `several distinct results are several, in the geocoder's order`() {
        assertEquals(
            AddressChoice.Decision.Several(listOf(a, b)),
            AddressChoice.decide("123 Main St Katy", listOf(a, b)),
        )
    }

    @Test
    fun `a query that IS one candidate, punctuation and case aside, picks it`() {
        assertEquals(AddressChoice.Decision.One(b), AddressChoice.decide("123 main st katy tx 77493", listOf(a, b)))
    }
}
