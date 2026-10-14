package com.kevin.midnightai.service

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/**
 * The minimal ViewTree owners a Jetpack Compose [androidx.compose.ui.platform.ComposeView]
 * needs to run inside a raw WindowManager overlay window — where there's no
 * Activity or Fragment to supply them. Compose reads a [LifecycleOwner] (to scope
 * effects), a [SavedStateRegistryOwner], and a [ViewModelStoreOwner] off the view
 * tree; without all three, `setContent` crashes. Driven by
 * [CompanionBadgeController]: [start] before adding the view, [destroy] after
 * removing it.
 */
class OverlayOwners : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val viewModelStore = ViewModelStore()
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    /** Moves the owners to RESUMED so Compose effects run. Call before addView. */
    fun start() {
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    /** Tears the owners down. Call after removeView so effects cancel and VMs clear. */
    fun destroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}
