package com.kevin.midnightai.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevin.midnightai.ui.theme.AriaColors
import com.kevin.midnightai.ui.theme.AriaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Cruise screen's full-screen app tray (Zero-as-home-screen launcher):
 * lists the installed launchable apps so the driver can open one, or escape to
 * the OS. The top-of-Cruise favorites dock was removed 2026-07-12.
 */

internal data class AppEntry(val label: String, val pkg: String, val icon: Bitmap)

@Composable
internal fun AppTrayOverlay(onDismiss: () -> Unit, onEscapeToOS: () -> Unit) {
    val context = LocalContext.current

    var apps by remember { mutableStateOf(emptyList<AppEntry>()) }
    LaunchedEffect(Unit) { apps = queryLauncherApps(context) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AriaColors.Background.copy(alpha = 0.97f)),
    ) {
        // Top bar: back + title + escape
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "‹ BACK",
                style = AriaType.neonLabel,
                color = AriaColors.Amber,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { onDismiss() }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
            Text(
                text = "APPS",
                style = AriaType.stamp.copy(fontSize = 18.sp),
                color = AriaColors.TextPrimary,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            Text(
                text = "⇱ OS",
                style = AriaType.neonLabel,
                color = AriaColors.TextDim,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .border(1.dp, AriaColors.PanelBorder, RoundedCornerShape(4.dp))
                    .clickable { onEscapeToOS() }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }

        // App grid
        if (apps.isEmpty()) {
            Text(
                text = "Loading…",
                style = AriaType.neonLabel,
                color = AriaColors.TextDim,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 60.dp, start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(apps, key = { it.pkg }) { app ->
                    AppCell(app) {
                        if (app.pkg in com.kevin.midnightai.media.SpotifyAppProbe.CANDIDATE_PACKAGES) {
                            com.kevin.midnightai.MidnightEvents.spotifyAppLaunched("app_tray", "driver tapped it")
                        }
                        context.packageManager.getLaunchIntentForPackage(app.pkg)
                            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            ?.let { context.startActivity(it) }
                        onDismiss()
                    }
                }
            }
        }
    }
}

@Composable
private fun AppCell(app: AppEntry, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Image(
            bitmap = app.icon.asImageBitmap(),
            contentDescription = app.label,
            modifier = Modifier.size(52.dp),
        )
        Text(
            text = app.label,
            style = AriaType.neonLabel.copy(fontSize = 10.sp),
            color = AriaColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** Lists launchable third-party apps (excludes us), sorted by label. IO work. */
private suspend fun queryLauncherApps(context: Context): List<AppEntry> =
    withContext(Dispatchers.IO) {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        context.packageManager
            .queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .filter { it.activityInfo.packageName != context.packageName }
            // Some apps expose more than one ACTION_MAIN/CATEGORY_LAUNCHER
            // activity (extra entry points, trampoline aliases); MATCH_ALL can
            // surface those too. Without deduping, the same app rendered twice
            // in the grid - and LazyVerticalGrid keys each cell by package
            // name (see items(apps, key = { it.pkg }) below), which a
            // duplicate package violates. One entry per package.
            .distinctBy { it.activityInfo.packageName }
            .sortedBy { it.loadLabel(context.packageManager).toString().lowercase() }
            .mapNotNull { ri ->
                runCatching {
                    AppEntry(
                        label = ri.loadLabel(context.packageManager).toString(),
                        pkg = ri.activityInfo.packageName,
                        icon = ri.loadIcon(context.packageManager).toBitmapSafe(96, 96),
                    )
                }.getOrNull()
            }
    }

/** Converts any Drawable to a Bitmap at the requested pixel size, safely. */
private fun android.graphics.drawable.Drawable.toBitmapSafe(w: Int, h: Int): Bitmap {
    if (this is BitmapDrawable && bitmap != null) {
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    setBounds(0, 0, w, h)
    draw(canvas)
    return bmp
}
