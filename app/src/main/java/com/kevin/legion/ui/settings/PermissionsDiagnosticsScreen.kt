package com.kevin.legion.ui.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.kevin.legion.location.BackgroundLocationAccess
import com.kevin.legion.location.LocationAccessState
import com.kevin.legion.service.CallActions
import com.kevin.legion.service.CallerId
import com.kevin.legion.service.PlaceCallAction
import com.kevin.legion.ui.CallHandlingRow
import com.kevin.legion.ui.LocationAccessRow
import com.kevin.legion.ui.SettingsNavRow
import com.kevin.legion.ui.common.DeckScreenHeader
import com.kevin.legion.ui.common.findActivity

/**
 * "Permissions and diagnostics" - the fifth subscreen `settings/` split into (command-center
 * ticket 02). Owns every runtime permission grant row (calls, location) and the Android Auto probe
 * harness readout - the two kinds of thing a user opens this screen to check on, not to configure
 * day to day.
 *
 * Every row is the same composable the old monolith called, unmoved in substance - including the
 * two-step foreground-then-background location permission chain, which needs
 * [com.kevin.legion.ui.common.findActivity] for the exact same
 * `shouldShowRequestPermissionRationale` check it always did (see that function's own doc for why
 * it moved to `ui/common/` rather than staying `internal` to one package).
 */
@Composable
fun PermissionsDiagnosticsScreen(onBack: () -> Unit, onOpenCarProbe: () -> Unit, onOpenDialer: () -> Unit = {}) {
    val context = LocalContext.current

    var canSeeCaller by remember { mutableStateOf(false) }
    var canAnswerCalls by remember { mutableStateOf(false) }
    var canPlaceCalls by remember { mutableStateOf(false) }
    var locationAccess by remember { mutableStateOf(BackgroundLocationAccess.current(context)) }
    // Set only inside requestBackgroundLocation's own callback below, right after a real denial -
    // NOT recomputed on resume, because shouldShowRequestPermissionRationale reads false both
    // "never asked" and "asked and permanently denied", and recomputing it blind on every resume
    // would offer the Settings shortcut to a user who has never even seen the background dialog
    // once.
    var offerLocationAppSettings by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        // Both halves of call handling are re-read on every resume rather than cached: the user
        // may have changed either one in system Settings while away.
        canSeeCaller = CallerId.hasCallLogPermission(context) &&
            CallerId.hasContactsPermission(context)
        canAnswerCalls = CallActions.hasPermission(context)
        canPlaceCalls = PlaceCallAction.hasCallPermission(context)
        val newLocationAccess = BackgroundLocationAccess.current(context)
        // Granted wipes the "offer Settings" flag - a user who fixed it in system Settings and
        // came back should see a clean GRANT-less row, not a stale settings shortcut.
        if (newLocationAccess == LocationAccessState.Granted) offerLocationAppSettings = false
        locationAccess = newLocationAccess
    }

    // Caller ID + voice answer/decline + place_call. Asked for as ONE dialog rather than four:
    // they are one feature to a human, and Android groups READ_PHONE_STATE/READ_CALL_LOG/
    // ANSWER_PHONE_CALLS/CALL_PHONE under PHONE anyway.
    val requestCallPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        canSeeCaller = CallerId.hasCallLogPermission(context) && CallerId.hasContactsPermission(context)
        canAnswerCalls = CallActions.hasPermission(context)
        canPlaceCalls = PlaceCallAction.hasCallPermission(context)
    }

    // Background location, second half of the two-step chain (ticket 01's rule 1: foreground must
    // be granted FIRST, in its own prompt - Android refuses to grant background otherwise). Fires
    // ONLY out of requestForegroundLocation below, never launched directly by a tap on the row.
    val requestBackgroundLocation = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        locationAccess = BackgroundLocationAccess.current(context)
        offerLocationAppSettings = if (granted) {
            false
        } else {
            // The exact test the ticket specifies: a real denial (not just "haven't asked yet")
            // where the system itself says it will not show a rationale-eligible dialog again -
            // that is Android's own signal that the ONLY way forward is system Settings.
            context.findActivity()?.shouldShowRequestPermissionRationale(
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            ) == false
        }
    }

    // Background location, first half: fine/coarse together, same "one feature, one dialog" shape
    // as requestCallPermissions above. Chains straight into the background request the moment
    // foreground lands.
    val requestForegroundLocation = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        locationAccess = BackgroundLocationAccess.current(context)
        val foregroundGranted = granted[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (foregroundGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            requestBackgroundLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            DeckScreenHeader(title = "Permissions and diagnostics", onBack = onBack)
            Column(
                Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                CallHandlingRow(
                    canSeeCaller = canSeeCaller,
                    canAnswer = canAnswerCalls,
                    canPlace = canPlaceCalls,
                    onGrant = {
                        requestCallPermissions.launch(
                            arrayOf(
                                Manifest.permission.READ_CALL_LOG,
                                Manifest.permission.READ_CONTACTS,
                                Manifest.permission.ANSWER_PHONE_CALLS,
                                Manifest.permission.CALL_PHONE,
                            )
                        )
                    },
                )

                Spacer(Modifier.height(8.dp))
                LocationAccessRow(
                    state = locationAccess,
                    onGrant = {
                        when (locationAccess) {
                            // No location at all yet - start the chain at the beginning. The
                            // background follow-up fires on its own out of
                            // requestForegroundLocation's callback once this lands.
                            LocationAccessState.None -> requestForegroundLocation.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                )
                            )
                            // Foreground already granted - either retry the background dialog, or,
                            // if the system has already told us it will not show one again, send
                            // the user straight to the app's own Settings page.
                            LocationAccessState.ForegroundOnly -> {
                                if (offerLocationAppSettings) {
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.fromParts("package", context.packageName, null),
                                        )
                                    )
                                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    requestBackgroundLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                                }
                            }
                            // Already granted - the row hides its own button in this state, so this
                            // branch should be unreachable, but it's a no-op rather than a crash if
                            // it ever is.
                            LocationAccessState.Granted -> {}
                        }
                    },
                )

                // Android Auto probe harness (`.scratch/android-auto/map.md` wave 1) - a debug
                // surface, not a user-facing feature, kept last for that reason.
                Spacer(Modifier.height(8.dp))
                // The dial screen is a capability, not a setting. The Home command center shipped
                // without a calling tile (ticket 01 chose day/mail/money/media for its tiles), so
                // this row next to the grant that enables it IS the standing front door - whether
                // calling also earns a Home tile is an open taste call for Kevin (ticket 10's
                // contested list), not a TODO.
                SettingsNavRow(
                    label = "Place a call",
                    status = "Dial a contact or a number by hand, same confirm step as voice",
                    onClick = onOpenDialer,
                )
                SettingsNavRow(
                    label = "Car probe",
                    status = "On-screen diagnostic log for Android Auto probes",
                    onClick = onOpenCarProbe,
                )

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
