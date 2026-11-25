package com.kevin.legion.carlink

/**
 * A [UnitTokenSource] that mints nothing, for the tests whose subject is the car link's own
 * behaviour rather than the engine hand-over: `CarLinkControllerTest`, `CarLinkViewModelTest` and
 * one case in `HandoverPlanTest`. It replaced the production stub `UnitTokenNotYetAvailable`, which
 * shipped in the app until 2026-11-24 and is the whole of break 1: every pairing handed the unit
 * `engine_token: null`. A no-op belongs in a test, not in the APK.
 *
 * Its real counterpart is `com.kevin.legion.backend.engine.DjangoUnitTokenSource`, and
 * `HandoverPlanTest.FakeUnitTokens` is the recording fake for the hand-over's own cases.
 */
class NoUnitTokens : UnitTokenSource {
    override suspend fun mint(name: String, vehicleId: String?): UnitToken? = null
    override suspend fun rotate(id: String): UnitToken? = null
    override suspend fun revoke(id: String): Boolean = false
}
