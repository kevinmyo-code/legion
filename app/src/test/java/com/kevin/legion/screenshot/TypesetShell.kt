package com.kevin.legion.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.kevin.legion.ui.common.StatusLine
import com.kevin.legion.ui.theme.soft.SoftTheme

/**
 * The shell's StatusLine as `MainActivity` draws it above every route, the Typeset HOME included
 * (Kevin, 2026-11-10: "the same horizontal bar as when i press all apps"). Every button wired, so
 * the shot shows what the phone shows: sync, OBD, an alarm, the clock, Quiet, Apps, Claude, the gear.
 * Made-up values only: the repo is public.
 */
@Composable
internal fun ShellStatusBar() {
    SoftTheme {
        StatusLine(
            synced = true,
            obdConnected = false,
            clock = "14:20",
            onOpenSettings = {},
            onOpenPhoneSettings = {},
            onOpenApps = {},
            onToggleQuiet = {},
            quietOn = true,
            alarmCount = 1,
            onOpenAlarm = {},
            onOpenClaude = {},
            claudeRunning = 2,
        )
    }
}

/** HOME inside the shell: the StatusLine, then the page in the height left, as `MainActivity`'s Column. */
@Composable
internal fun ShellHome(page: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ShellStatusBar()
        Box(Modifier.weight(1f).fillMaxWidth().testTag(PAGE_TAG)) { page() }
    }
}

/** The page under the bar, so a test can tell the page's own nodes from the bar's. */
internal const val PAGE_TAG = "typeset-page"
