package com.kevin.legion.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where the pasted token is kept. [CompanionProfile] backs it on the phone; a fake backs it in tests. */
interface MapboxTokenStore {
    fun load(): String
    fun save(token: String)
    fun clear()
}

/** What a consumer of the token needs to know: the effective token and whether Mapbox refused it. */
data class MapboxTokenState(val token: String, val rejected: Boolean) {
    val isSet: Boolean get() = NavFormat.hasToken(token)
}

/** The read side, for [MapboxNavController]: no storage, no SDK, so a test can fake it in a line. */
interface MapboxTokenSource {
    val state: StateFlow<MapboxTokenState>

    /** Mapbox itself said the token is bad (an auth failure on a route or style request). */
    fun markRejected()
}

/** Verdict on text pasted into Setup. The messages are what the user is told, in words. */
enum class MapboxTokenCheck(val message: String?) {
    OK(null),
    BLANK("Paste a token first."),
    SECRET(
        "That is a secret token (sk.). Never put one in the app: it can change your Mapbox " +
            "account. Create a public token (starts with pk.) and paste that instead.",
    ),
    NOT_PUBLIC("That is not a public Mapbox token. It must start with pk."),
}

/**
 * The one owner of the Mapbox token's resolution order (mapbox-nav ticket 08 and 09):
 * **pasted token, then the baked dev token (`BuildConfig.MAPBOX_ACCESS_TOKEN`), then none.**
 * The Application, the nav controller and Setup all read through it, so the order cannot drift.
 * A plain class, no `object` (CLAUDE.md sec 8); the Application owns the one instance because the
 * SDK's token is process-wide.
 *
 * [applyToSdk] receives the effective token whenever it changes (in production, assigns
 * `MapboxOptions.accessToken`), so a paste or a clear takes effect with no app restart. A pasted
 * token that replaces a rejected one clears the rejection: the verdict was about the old token.
 *
 * **No REST check.** Rejection is learnt from the SDK's own failures ([markRejected]), so the app
 * never calls Mapbox other than through the mobile SDK (ToS 2.9.1).
 */
class MapboxTokenProvider(
    private val store: MapboxTokenStore,
    private val bakedToken: String,
    private val applyToSdk: (String) -> Unit,
) : MapboxTokenSource {
    private val _state = MutableStateFlow(MapboxTokenState(resolve(), rejected = false))
    override val state: StateFlow<MapboxTokenState> = _state.asStateFlow()

    /** The effective token right now; blank means navigation is not set up. */
    fun token(): String = _state.value.token

    /** True when the user has pasted a token of their own (as opposed to the baked dev one). */
    fun hasPasted(): Boolean = NavFormat.hasToken(store.load())

    /** Pushes the current token into the SDK. Application start calls this; a blank token is skipped. */
    fun applyAtStartup() {
        if (_state.value.isSet) applyToSdk(_state.value.token)
    }

    /** Validates and stores [input]. Returns the verdict; nothing is stored unless it is [MapboxTokenCheck.OK]. */
    fun save(input: String): MapboxTokenCheck {
        val verdict = check(input)
        if (verdict != MapboxTokenCheck.OK) return verdict
        store.save(input.trim())
        changed()
        return verdict
    }

    /** Removes the pasted token; the baked dev token (if any) takes over again. */
    fun clear() {
        store.clear()
        changed()
    }

    override fun markRejected() {
        _state.value = _state.value.copy(rejected = true)
    }

    private fun changed() {
        _state.value = MapboxTokenState(resolve(), rejected = false)
        applyToSdk(_state.value.token)
    }

    private fun resolve(): String = store.load().trim().ifBlank { bakedToken.trim() }

    companion object {
        /** Pure so it is unit-testable: `pk.` public tokens only, and `sk.` refused by name. */
        fun check(input: String): MapboxTokenCheck {
            val t = input.trim()
            return when {
                t.isEmpty() -> MapboxTokenCheck.BLANK
                t.startsWith("sk.", ignoreCase = true) -> MapboxTokenCheck.SECRET
                !t.startsWith("pk.") || t.length <= PK_PREFIX_LEN || t.any { it.isWhitespace() } ->
                    MapboxTokenCheck.NOT_PUBLIC
                else -> MapboxTokenCheck.OK
            }
        }

        private const val PK_PREFIX_LEN = 3
    }
}
