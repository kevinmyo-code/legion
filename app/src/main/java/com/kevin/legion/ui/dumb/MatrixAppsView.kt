package com.kevin.legion.ui.dumb

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevin.legion.dumb.BlockPolicy
import com.kevin.legion.dumb.MatrixDrawer
import com.kevin.legion.ui.apps.AppDrawerCache
import com.kevin.legion.ui.apps.DrawerApp
import com.kevin.legion.ui.apps.Loaded
import com.kevin.legion.ui.apps.launchDrawerApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the drawer shows: the app list (blocked removed), how many were hidden, and the load it came from. */
internal class MatrixAppsView(val listed: List<DrawerApp>, val blocked: Int, val loaded: Loaded)

/**
 * Matrix mode's All apps (matrix-open ticket 02, ADR 0062 2026-10-24): a text-only alphabetical list
 * of launchable apps with blocked ones removed. The verdict is [BlockPolicy]'s, the same one the
 * bounce uses, so an app listed here is an app the bounce lets through. No icons. The footer says
 * how many are blocked and opens Matrix settings.
 */
@Composable
internal fun MatrixAllApps(policy: BlockPolicy, onBack: () -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    BackHandler(onBack = onBack)
    var message by remember { mutableStateOf<String?>(null) }
    val view by produceState<MatrixAppsView?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            val loaded = AppDrawerCache.peek() ?: runCatching { AppDrawerCache.refresh(context) }.getOrNull()
            loaded?.let {
                MatrixAppsView(
                    MatrixDrawer.listed(it.apps, policy::isBlocked),
                    MatrixDrawer.blockedCount(it.apps, policy::isBlocked),
                    it,
                )
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(DumbColors.ground)
            .padding(start = 28.dp, end = 28.dp, top = 40.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        MatrixAppsHeader(onBack)
        val current = view
        when {
            current == null -> Text("Reading the installed apps...", color = DumbColors.body, fontSize = 15.sp)
            current.listed.isEmpty() -> Text(
                "No apps are open to list. Change that in Matrix settings.",
                color = DumbColors.body,
                fontSize = 15.sp,
            )
            else -> MatrixAppsList(current, Modifier.weight(1f)) {
                message = launchDrawerApp(context, it, current.loaded)
            }
        }
        message?.let { Text(it, color = DumbColors.body, fontSize = 14.sp) }
        view?.let {
            Text(
                MatrixDrawer.footer(it.blocked),
                color = DumbColors.dim,
                fontSize = 13.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .clickable(
                        role = Role.Button,
                        onClickLabel = "Open Matrix mode settings",
                        onClick = onOpenSettings,
                    ),
            )
        }
    }
}

@Composable
private fun MatrixAppsList(view: MatrixAppsView, modifier: Modifier, onOpen: (DrawerApp) -> Unit) {
    LazyColumn(modifier) {
        items(view.listed, key = { "${it.profileKey}/${it.packageName}/${it.className}" }) { app ->
            Text(
                if (app.isWork) "${app.label} (work)" else app.label,
                color = DumbColors.text,
                fontSize = 22.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .clickable(role = Role.Button) { onOpen(app) }
                    .padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun MatrixAppsHeader(onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("All apps", color = DumbColors.text, fontSize = 30.sp, modifier = Modifier.weight(1f))
        PillButton("Back", onBack, primary = false)
    }
}
