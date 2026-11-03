package com.kevin.legion.ui.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kevin.legion.browser.BrowserController
import com.kevin.legion.browser.BrowserUiState
import kotlinx.coroutines.flow.StateFlow

/**
 * The browser screen's ViewModel (CLAUDE.md section 8): one [StateFlow] and the hand-path actions,
 * each a call on the same [BrowserController] the voice tool drives (ADR 0035).
 */
class BrowserViewModel(private val controller: BrowserController) : ViewModel() {
    val state: StateFlow<BrowserUiState> = controller.state

    fun submit(text: String) = controller.submit(text)
    fun back() = controller.goBack()
    fun forward() = controller.goForward()
    fun reload() = controller.reload()
    fun clearNotice() = controller.clearNotice()
    fun dismissBlock() = controller.dismissBlock()
    fun openAfterPass(): Boolean = controller.openAfterPass()

    companion object {
        fun factory(controller: BrowserController): ViewModelProvider.Factory = viewModelFactory {
            initializer { BrowserViewModel(controller) }
        }
    }
}
