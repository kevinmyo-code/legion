package com.kevin.legion.location

/**
 * A [PlaceGeocoder] that answers from what a test set, and records what it was asked. The default
 * reverse answer is [ReverseLookup.NotFound], so a test that does not care about addresses saves
 * by coordinates exactly as `tag_place` did before addresses existed.
 */
class FakePlaceGeocoder(
    var forwardAnswer: ForwardLookup = ForwardLookup.NotFound,
    var reverseAnswer: ReverseLookup = ReverseLookup.NotFound,
) : PlaceGeocoder {
    val forwardQueries = mutableListOf<String>()
    var reverseCalls = 0

    override suspend fun forward(query: String): ForwardLookup {
        forwardQueries += query
        return forwardAnswer
    }

    override suspend fun reverse(latitude: Double, longitude: Double): ReverseLookup {
        reverseCalls++
        return reverseAnswer
    }
}
