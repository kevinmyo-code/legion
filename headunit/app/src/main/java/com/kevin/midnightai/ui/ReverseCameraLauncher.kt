package com.kevin.midnightai.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevin.midnightai.ui.theme.AriaColors
import com.kevin.midnightai.ui.theme.AriaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Actually performs the handoff (the un-testable half of the reverse-camera
 * button - see [resolveReverseCameraLaunch] for the testable decision logic
 * this wraps). Builds an explicit-component intent when [ReverseCameraTarget.className]
 * is set, else falls back to the package's own launch intent, and classifies
 * the two real Android failure shapes into their own [ReverseCameraLaunchResult]
 * so a caller can say what went wrong instead of nothing happening silently
 * (CLAUDE.md sec 7's outcome-verb rule).
 */
fun launchReverseCameraTarget(context: Context, target: ReverseCameraTarget): ReverseCameraLaunchResult =
    resolveReverseCameraLaunch(target) { t ->
        try {
            val intent = if (t.className != null) {
                Intent().setClassName(t.packageName, t.className).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            } else {
                context.packageManager.getLaunchIntentForPackage(t.packageName)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    // getLaunchIntentForPackage returns null when the package has no
                    // launcher-category activity at all - exactly the "vendor camera
                    // app has no icon" case the free-text/class field exists for, so
                    // treat a null intent the same as "could not resolve an activity".
                    ?: throw ActivityNotFoundException("no launch intent for ${t.packageName}")
            }
            context.startActivity(intent)
            ReverseCameraLaunchResult.Launched
        } catch (e: ActivityNotFoundException) {
            ReverseCameraLaunchResult.NotFound(e.message)
        } catch (e: SecurityException) {
            ReverseCameraLaunchResult.Denied(e.message)
        } catch (e: Exception) {
            ReverseCameraLaunchResult.Failed(e.message)
        }
    }

/** The words behind each [ReverseCameraLaunchResult], for a Text() or a Toast. */
fun reverseCameraLaunchMessage(result: ReverseCameraLaunchResult): String? = when (result) {
    is ReverseCameraLaunchResult.NotConfigured ->
        "No reverse camera app chosen yet. Pick one in settings."
    is ReverseCameraLaunchResult.Launched -> null // success speaks for itself - the vendor app is now on screen
    is ReverseCameraLaunchResult.NotFound ->
        "Could not open the reverse camera app - it isn't installed, or the saved shortcut is wrong. Check the setting."
    is ReverseCameraLaunchResult.Denied ->
        "The system blocked opening the reverse camera app."
    is ReverseCameraLaunchResult.Failed ->
        "Opening the reverse camera app failed" + (result.detail?.let { ": $it" } ?: ".")
}

/** One installed launchable app, for the reverse-camera target picker list. */
private data class LaunchableApp(val label: String, val packageName: String, val activityName: String)

/**
 * Lets Kevin point the reverse-camera button at the right activity once, in
 * the car. Two entry paths, because vendor camera activities routinely have
 * NO launcher icon and so cannot appear in the list below at all (they are
 * reached only by the stock firmware's own reverse-gear signal):
 *  - Tap a listed app (uses that app's own main/launcher activity).
 *  - Type a package name and optional class name directly.
 */
@Composable
internal fun ReverseCameraPickerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf(emptyList<LaunchableApp>()) }
    LaunchedEffect(Unit) { apps = queryLaunchableApps(context) }

    var pkgField by remember { mutableStateOf(ReverseCameraSettings.get(context)?.packageName ?: "") }
    var classField by remember { mutableStateOf(ReverseCameraSettings.get(context)?.className ?: "") }
    var savedMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AriaColors.Background)
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                "‹ BACK",
                style = AriaType.neonLabel,
                color = AriaColors.Amber,
                modifier = Modifier.clickable { onBack() }.padding(vertical = 8.dp, horizontal = 4.dp),
            )
            Text(
                "REVERSE CAMERA TARGET",
                style = AriaType.stamp.copy(fontSize = 16.sp),
                color = AriaColors.TextPrimary,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.width(52.dp)) // balances BACK so the title stays centered
        }
        Spacer(Modifier.height(12.dp))

        Text(
            "Pick the app that shows the reverse camera on this head unit. Most " +
                "vendor camera activities have no icon and won't be in this list - " +
                "use the fields below to enter one directly (package name, and the " +
                "activity class if you know it).",
            style = AriaType.logbookBody.copy(fontSize = 13.sp),
            color = AriaColors.TextDim,
        )
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = pkgField,
            onValueChange = { pkgField = it },
            label = { Text("Package name") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = classField,
            onValueChange = { classField = it },
            label = { Text("Activity class (optional)") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "SAVE",
            style = AriaType.neonLabel,
            color = AriaColors.Amber,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, AriaColors.Amber.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                .clickable {
                    val target = ReverseCameraTargetCodec.decode(
                        if (classField.isBlank()) pkgField else "$pkgField/$classField",
                    )
                    if (target == null) {
                        savedMessage = "Enter at least a package name."
                    } else {
                        ReverseCameraSettings.set(context, target)
                        savedMessage = "Saved: ${ReverseCameraTargetCodec.encode(target)}"
                    }
                }
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
        savedMessage?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = AriaType.neonLabel.copy(fontSize = 11.sp), color = AriaColors.TextDim)
        }

        Spacer(Modifier.height(20.dp))
        Text("OR PICK A LISTED APP", style = AriaType.neonLabel.copy(fontSize = 11.sp), color = AriaColors.TextDim)
        Spacer(Modifier.height(8.dp))

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(apps, key = { it.packageName + "/" + it.activityName }) { app ->
                Text(
                    "${app.label}  (${app.packageName})",
                    style = AriaType.logbookBody.copy(fontSize = 13.sp),
                    color = AriaColors.TextPrimary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            pkgField = app.packageName
                            classField = app.activityName
                            ReverseCameraSettings.set(context, ReverseCameraTarget(app.packageName, app.activityName))
                            savedMessage = "Saved: ${app.packageName}/${app.activityName}"
                        }
                        .padding(vertical = 10.dp),
                )
            }
        }
    }
}

private suspend fun queryLaunchableApps(context: Context): List<LaunchableApp> =
    withContext(Dispatchers.IO) {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        context.packageManager
            .queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .filter { it.activityInfo.packageName != context.packageName }
            .distinctBy { it.activityInfo.packageName }
            .sortedBy { it.loadLabel(context.packageManager).toString().lowercase() }
            .map { ri ->
                LaunchableApp(
                    label = ri.loadLabel(context.packageManager).toString(),
                    packageName = ri.activityInfo.packageName,
                    activityName = ri.activityInfo.name,
                )
            }
    }
