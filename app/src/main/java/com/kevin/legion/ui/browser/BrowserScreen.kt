@file:Suppress("MatchingDeclarationName") // the screen is the file; BrowserActions is its callbacks

package com.kevin.legion.ui.browser

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kevin.legion.R
import com.kevin.legion.browser.BrowserUiState
import com.kevin.legion.browser.FeedBlock
import com.kevin.legion.ui.theme.soft.MsIcon

/** The hand path's buttons, each one call on the browser controller (ADR 0035). */
data class BrowserActions(
    val onSubmit: (String) -> Unit,
    val onBack: () -> Unit,
    val onForward: () -> Unit,
    val onReload: () -> Unit,
    val onClose: () -> Unit,
    val onClearNotice: () -> Unit,
)

/**
 * LEGION's browser (ADR 0071), stateless: the address/search bar and close on top, the page, then
 * back, forward and reload. [page] is the WebView in the app and a stand-in in a test; [blocked] is
 * the feed blocker's own screen, drawn over the page when a feed site was stopped.
 */
@Composable
fun BrowserScreen(
    state: BrowserUiState,
    actions: BrowserActions,
    page: @Composable () -> Unit,
    blocked: @Composable (FeedBlock) -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            AddressBar(state, actions)
            if (state.loading) {
                LinearProgressIndicator(
                    progress = { state.progress.coerceIn(0, FULL) / FULL.toFloat() },
                    modifier = Modifier.fillMaxWidth().testTag(TAG_PROGRESS),
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                page()
                if (state.url.isEmpty() && !state.loading && state.blocked == null) EmptyHint()
                state.blocked?.let { blocked(it) }
            }
            state.notice?.let { Notice(it, actions.onClearNotice) }
            if (state.blocked == null) NavRow(state, actions)
        }
    }
}

@Composable
private fun AddressBar(state: BrowserUiState, actions: BrowserActions) {
    val focus = LocalFocusManager.current
    var typed by remember(state.url) { mutableStateOf(state.url) }
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarButton(R.drawable.ms_close, "Close browser", enabled = true, onClick = actions.onClose)
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            singleLine = true,
            placeholder = { Text("Search Google or type an address") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = {
                focus.clearFocus()
                actions.onSubmit(typed)
            }),
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.weight(1f).testTag(TAG_ADDRESS),
        )
    }
}

@Composable
private fun NavRow(state: BrowserUiState, actions: BrowserActions) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarButton(R.drawable.ms_arrow_back, "Back", state.canGoBack, actions.onBack)
        BarButton(R.drawable.ms_arrow_forward, "Forward", state.canGoForward, actions.onForward)
        BarButton(R.drawable.ms_refresh, "Reload", state.url.isNotEmpty(), actions.onReload)
    }
}

@Composable
private fun BarButton(@DrawableRes icon: Int, label: String, enabled: Boolean, onClick: () -> Unit) {
    val tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else DISABLED_ALPHA)
    IconButton(onClick = onClick, enabled = enabled) {
        MsIcon(icon, contentDescription = label, tint = tint)
    }
}

@Composable
private fun EmptyHint() {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Text(
            "Ask for a page, a picture or a menu, or type above.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(32.dp),
        )
    }
}

@Composable
private fun Notice(text: String, onClear: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .clickable(role = Role.Button, onClickLabel = "Dismiss", onClick = onClear)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .testTag(TAG_NOTICE),
        )
    }
}

const val TAG_ADDRESS = "browser_address"
const val TAG_PROGRESS = "browser_progress"
const val TAG_NOTICE = "browser_notice"
private const val DISABLED_ALPHA = 0.38f
private const val FULL = 100
