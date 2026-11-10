package com.kevin.legion.ui.vault

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kevin.legion.ui.theme.soft.ProvideTypesetEdition
import com.kevin.legion.ui.theme.soft.SoftTheme
import com.kevin.legion.vault.VaultClipboard
import com.kevin.legion.vault.VaultItem

/**
 * The ID vault's own activity (memory-and-vault ticket 12, ADR 0076). Its own window so
 * **FLAG_SECURE covers the vault and nothing else**: no screenshot, no screen recording, a blank
 * recents thumbnail, without blanking the rest of LEGION. Reached from Settings, Data and privacy,
 * and from the `show_vault_item` voice tool, through the same [open] (ADR 0035).
 *
 * The value lives in [VaultViewModel] only, and [onStop] hides and wipes it, so leaving the screen
 * (home, another app, the lock screen) takes it off the screen for good; coming back needs the
 * fingerprint again. Nothing here logs.
 */
class VaultActivity : ComponentActivity() {

    private val viewModel: VaultViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        viewModel.focus(intent?.getStringExtra(EXTRA_LABEL))
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            // Opened by voice on one item: offer the fingerprint for it once, on arrival.
            LaunchedEffect(state.focusId, state.focusPrompted) {
                val listed = (state.view as? VaultView.Listed)?.items.orEmpty()
                val item = state.focusId?.let { id -> listed.firstOrNull { it.id == id } }
                if (item != null && !state.focusPrompted && state.shown == null) {
                    viewModel.focusPromptShown()
                    askFingerprint(item)
                }
            }
            ProvideTypesetEdition { SoftTheme {
                VaultContent(
                    state = state,
                    callbacks = VaultCallbacks(
                        onBack = ::finish,
                        onRetry = viewModel::refresh,
                        onReveal = ::askFingerprint,
                        onHide = viewModel::hide,
                        onCopy = { viewModel.state.value.shown?.let { VaultClipboard.copy(this, it.secret) } },
                        onAdd = viewModel::openAdd,
                        onEdit = viewModel::openEdit,
                        onForm = viewModel::editForm,
                        onSaveForm = viewModel::saveForm,
                        onCloseForm = viewModel::closeForm,
                        onDelete = viewModel::askDelete,
                        onConfirmDelete = viewModel::confirmDelete,
                        onCancelDelete = viewModel::cancelDelete,
                    ),
                )
            } }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.focus(intent.getStringExtra(EXTRA_LABEL))
    }

    override fun onStop() {
        viewModel.hide()
        super.onStop()
    }

    private fun askFingerprint(item: VaultItem) {
        VaultBiometric.prompt(
            activity = this,
            label = item.label,
            onUnlocked = { unlock -> viewModel.reveal(item, unlock) },
            onRefused = viewModel::say,
        )
    }

    companion object {
        private const val EXTRA_LABEL = "vault_label"

        /**
         * Brings the vault up, on [label]'s item when given. True when Android accepted the start;
         * that is not proof it is showing (a background start can be held back), which
         * [com.kevin.legion.service.VaultToolbox] checks separately.
         */
        fun open(context: Context, label: String?): Boolean {
            val intent = Intent(context, VaultActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (!label.isNullOrBlank()) intent.putExtra(EXTRA_LABEL, label)
            return try {
                context.startActivity(intent)
                true
            } catch (e: ActivityNotFoundException) {
                false
            } catch (e: SecurityException) {
                false
            }
        }
    }
}
