package com.kevin.legion.service.cascade

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import com.kevin.legion.BuildConfig
import com.kevin.legion.backend.engine.EngineConfig
import com.kevin.legion.service.LiveLatency
import kotlinx.coroutines.CoroutineScope

/**
 * The engine brain's Android half: the switch, the engine address and token, the screen and lock
 * state for a call's `needs`. [CascadeSession] calls [open] once per conversation; with the switch
 * off it returns null before reading anything else, so the cascade runs exactly as before.
 */
internal object EngineBrainOpen {

    /**
     * The conversation's engine brain, attaching in the background, or null: the switch is off, or
     * this phone has no engine address or token (logged once; the phone loop answers). [wireFor] is
     * the transport; a test passes one that fails on any use.
     */
    fun open(
        ctx: Context,
        scope: CoroutineScope,
        wireFor: (EngineConfig) -> EngineWire = { EngineWireHttp(it::baseUrl, it::token, scope) },
    ): EngineBrain? {
        val config = if (EngineBrainSettings.on(ctx)) EngineConfig(ctx) else null
        val usable = config != null && EngineConfig.isValidBaseUrl(config.baseUrl()) && config.isSignedIn()
        if (config != null && !usable) LiveLatency.log(LiveLatency.TAG, "engine_attach state=off why=not_signed_in")
        return if (config == null || !usable) {
            null
        } else {
            val log: (String) -> Unit = { LiveLatency.log(LiveLatency.TAG, it) }
            EngineBrain(wireFor(config), scope, { deviceState(ctx) }, log)
                .also { it.attach { EngineLimbManifest.offer(BuildConfig.VERSION_NAME) } }
        }
    }

    private fun deviceState(ctx: Context): EngineBrainRules.DeviceState {
        val keyguard = ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val power = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return EngineBrainRules.DeviceState(
            locked = keyguard?.isKeyguardLocked ?: true,
            screenOn = power?.isInteractive ?: false,
        )
    }
}
