@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.kevin.legion.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kevin.legion.ui.apps.DockPin
import com.kevin.legion.ui.apps.DrawerApp
import com.kevin.legion.ui.apps.Loaded
import com.kevin.legion.ui.apps.iconKey
import com.kevin.legion.ui.theme.soft.SoftColors

/** A dimmed dock slot's reduced alpha - the same figure [com.kevin.legion.ui.checklists.ListsPageContent]'s
 * own archived-card ARCHIVED_ALPHA uses for the same "still visible, still tappable, plainly not
 * live" meaning. */
private const val DOCK_DIMMED_ALPHA = 0.5f

/**
 * One dock slot - a pin plus everything [AppDock] needs to render it, resolved from a
 * [com.kevin.legion.ui.apps.AppDrawerCache] snapshot rather than a second `LauncherApps` read
 * (ticket 06's own "Boundaries" section). [app] is `null` exactly when [pin] no longer matches an
 * installed app - stays in its slot, dimmed, "Not installed" (ticket's own wording), never dropped
 * silently: unpinning is still the user's own choice to make.
 */
data class DockSlotUi(
    val pin: DockPin,
    val app: DrawerApp?,
    val label: String,
    val icon: ImageBitmap?,
    val paused: Boolean,
)

/**
 * Resolves [pins] against a drawer snapshot - pure, so [DockSlotUiTest] can pin every case (an
 * installed app, one no longer installed, one whose work profile is paused) without Robolectric.
 * [loaded] `null` (the cache hasn't warmed yet, the same "instant, then a cheap refresh" state
 * [com.kevin.legion.ui.apps.AppsScreen] already tolerates) resolves every slot to "not installed"
 * rather than blocking the whole dock on one more read.
 */
internal fun buildDockSlots(pins: List<DockPin>, loaded: Loaded?): List<DockSlotUi> = pins.map { pin ->
    val app = loaded?.apps?.firstOrNull { it.packageName == pin.packageName && it.profileKey == pin.userSerial }
    val icon = app?.let { loaded?.icons?.get(iconKey(it.profileKey, it.packageName, it.className)) }
    DockSlotUi(
        pin = pin,
        app = app,
        label = app?.label ?: pin.packageName,
        icon = icon,
        paused = app?.isWork == true && loaded?.workPaused == true,
    )
}

/** Every callback [AppDock] needs - [onLaunch] gets the slot's own [DrawerApp] only when one
 * resolved (a "not installed" slot has nothing to launch); the caller decides the failure sentence
 * either way, same posture [com.kevin.legion.ui.apps.AppsScreen]'s own `onOpen` already holds. */
data class DockCallbacks(
    val onLaunch: (DockSlotUi) -> Unit,
    val onUnpin: (DockPin) -> Unit,
    val onMoveLeft: (DockPin) -> Unit,
    val onMoveRight: (DockPin) -> Unit,
)

/**
 * The pinned-app dock (home-launcher ticket 06) - five slots, one row, between the tile grid and
 * the talk bar (the talk bar itself is `AssistantStrip`, mounted as `MainActivity`'s Scaffold
 * `bottomBar`, outside this composable entirely - "above the talk bar" just means this row is the
 * last thing [HomeContent] itself draws). Renders even with zero slots, with its own worded empty
 * state - never a blank strip a user has to already know the feature exists to find.
 */
@Composable
fun AppDock(slots: List<DockSlotUi>, callbacks: DockCallbacks) {
    if (slots.isEmpty()) {
        Text(
            "Long-press an app in Apps to pin it here.",
            style = MaterialTheme.typography.labelSmall,
            color = SoftColors.text2,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
        )
        return
    }

    var longPressed by remember { mutableStateOf<DockPin?>(null) }

    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        slots.forEach { slot ->
            DockIcon(
                slot = slot,
                onClick = { callbacks.onLaunch(slot) },
                onLongClick = { longPressed = slot.pin },
            )
        }
    }

    longPressed?.let { pin ->
        val index = slots.indexOfFirst { it.pin == pin }
        DockSlotSheet(
            canMoveLeft = index > 0,
            canMoveRight = index in 0 until slots.size - 1,
            onDismiss = { longPressed = null },
            onUnpin = { callbacks.onUnpin(pin); longPressed = null },
            onMoveLeft = { callbacks.onMoveLeft(pin); longPressed = null },
            onMoveRight = { callbacks.onMoveRight(pin); longPressed = null },
        )
    }
}

@Composable
private fun RowScope.DockIcon(slot: DockSlotUi, onClick: () -> Unit, onLongClick: () -> Unit) {
    // 48dp touch target (ticket's own figure), the icon itself smaller inside it.
    androidx.compose.foundation.layout.Column(
        Modifier
            .weight(1f)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 2.dp)
            .alpha(if (slot.app == null || slot.paused) DOCK_DIMMED_ALPHA else 1f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(SoftColors.cardHigh),
            contentAlignment = Alignment.Center,
        ) {
            val icon = slot.icon
            if (icon != null) {
                Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(44.dp))
            }
            // icon == null with slot.app != null: the drawer snapshot hasn't warmed this icon yet -
            // the label below still shows, per ticket's own "show the label while an icon loads".
        }
        Text(
            when {
                slot.app == null -> "Not installed"
                slot.paused -> "Paused"
                else -> slot.label
            },
            style = MaterialTheme.typography.labelSmall,
            color = SoftColors.text2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** Long-press on a dock icon (ticket's own "Unpin and reorder from the dock") - Unpin always
 * offered, Move left/right only when there is somewhere to move to (a single-slot dock, or the
 * slot already at an end, has nothing to swap with). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun DockSlotSheet(
    canMoveLeft: Boolean,
    canMoveRight: Boolean,
    onDismiss: () -> Unit,
    onUnpin: () -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        androidx.compose.foundation.layout.Column {
            DockSheetRow("Unpin", onUnpin)
            if (canMoveLeft) DockSheetRow("Move left", onMoveLeft)
            if (canMoveRight) DockSheetRow("Move right", onMoveRight)
        }
    }
}

@Composable
private fun DockSheetRow(label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).clickable(onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = SoftColors.text)
    }
}
