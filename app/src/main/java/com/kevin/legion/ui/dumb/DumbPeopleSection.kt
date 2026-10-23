package com.kevin.legion.ui.dumb

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kevin.legion.MidnightApplication
import com.kevin.legion.dumb.ChosenPerson
import com.kevin.legion.dumb.ContactReader
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics

/**
 * The chosen-people picker and the alert switch (ADR 0062). People are picked from Kevin's contacts
 * inside LEGION; Android's starred flag is untouched. READ_CONTACTS is asked for when he first taps
 * Add, because a picked contact's phone numbers live in a table the picker's one-contact grant does
 * not cover.
 */
@Composable
fun DumbPeopleSection() {
    val context = LocalContext.current
    val sem = LocalLegionSemantics.current
    val graph = remember(context) { (context.applicationContext as MidnightApplication).dumb }
    val people by graph.people.people.collectAsStateWithLifecycle()
    var notice by remember { mutableStateOf<String?>(null) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        if (uri != null) {
            val person = ContactReader.read(context.contentResolver, uri)
            if (person == null) {
                notice = "That contact could not be read, so nobody was added."
            } else {
                graph.people.add(person)
                notice = null
            }
        }
    }
    val askContacts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pick.launch(null) else notice = "Contacts access was refused, so nobody could be added."
    }

    Text("People who get through", style = MaterialTheme.typography.labelLarge)
    Text(
        "Their notifications reach you on any app and their calls ring. Everyone else is held.",
        style = LegionType.stamp,
        color = sem.faint,
    )
    if (people.isEmpty()) {
        Text("Nobody chosen yet.", style = LegionType.stamp, color = sem.estimated)
    }
    people.forEach { person -> PersonRow(person) { graph.people.remove(person.contactId) } }
    OutlinedButton(onClick = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        // The contacts picker is another app's window: tell the bounce LEGION opened it on purpose.
        graph.bounce.allowance.armGrace()
        if (granted) pick.launch(null) else askContacts.launch(Manifest.permission.READ_CONTACTS)
    }) { Text("Add from contacts") }
    notice?.let { Text(it, style = LegionType.stamp, color = sem.estimated) }

    AlertFallbackRow(forced = graph.alertSwitch.forced, onChange = { graph.alertSwitch.forced = it })
}

@Composable
private fun PersonRow(person: ChosenPerson, onRemove: () -> Unit) {
    val sem = LocalLegionSemantics.current
    Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(person.displayName, style = MaterialTheme.typography.bodyMedium)
                Text(
                    when (person.numbers.size) {
                        0 -> "No phone number on the contact: matched by name only, and their calls are not recognised."
                        1 -> "1 phone number"
                        else -> "${person.numbers.size} phone numbers"
                    },
                    style = LegionType.stamp,
                    color = if (person.numbers.isEmpty()) sem.estimated else sem.faint,
                )
            }
            OutlinedButton(onClick = onRemove) { Text("Remove") }
        }
    }
}

@Composable
private fun AlertFallbackRow(forced: Boolean, onChange: (Boolean) -> Unit) {
    val sem = LocalLegionSemantics.current
    var on by remember { mutableStateOf(forced) }
    Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text("Alert on the alarm stream", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (on) {
                        "On. A chosen person's message vibrates and sounds as an alarm would."
                    } else {
                        "Off. Their message tries to break through Do Not Disturb as a notification; turn this " +
                            "on if it stays silent."
                    },
                    style = LegionType.stamp,
                    color = sem.faint,
                )
            }
            Switch(checked = on, onCheckedChange = {
                on = it
                onChange(it)
            })
        }
    }
}
