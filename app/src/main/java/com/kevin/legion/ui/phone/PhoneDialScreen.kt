package com.kevin.legion.ui.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kevin.legion.service.CallerId
import com.kevin.legion.service.PlaceCallAction
import com.kevin.legion.ui.common.DeckButton
import com.kevin.legion.ui.common.DeckPane
import com.kevin.legion.ui.common.DeckScreenHeader
import com.kevin.legion.ui.common.DeckTextField
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import kotlinx.coroutines.launch

/**
 * `settings/phone` - the dial screen (command-center ticket 05, ADR 0035's second case: `place_call`
 * previously had no hands path at all, only a hardcoded-number debug screen).
 *
 * Calls [PlaceCallAction.dispatchVoiceCall] directly, twice - once unconfirmed to resolve and read
 * back, once confirmed to actually dial - the exact same function and the exact same two-turn shape
 * the voice tool uses. See [PhoneDialLogic] for why this is a second DOOR, not a second
 * implementation of the confirm gate.
 *
 * Two entry modes, a plain toggle rather than a new control: CONTACT searches the same
 * `ContactsContract` table [PlaceCallAction.lookupContacts] already queries for the voice tool;
 * NUMBER is spoken/typed digits, normalized and grouped for read-back by
 * [PlaceCallAction.normalizeDigits]/[PlaceCallAction.groupForSpeech] exactly as voice dialling is.
 *
 * All five screen states in [PhoneDialLogic.Step] are rendered distinctly - no such contact,
 * several matches (which lists them and asks, never auto-picks), no permission, and emergency
 * refusal are the ticket's four required failure sentences; [PhoneDialLogic.Step.Failed] is the
 * fifth state a hands path needs that voice's single spoken turn does not surface separately (a
 * confirmed dial that never connected).
 */
@Composable
fun PhoneDialScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sem = LocalLegionSemantics.current

    var contactMode by remember { mutableStateOf(true) }
    var contactText by remember { mutableStateOf("") }
    var numberText by remember { mutableStateOf("") }
    var step by remember { mutableStateOf<PhoneDialLogic.Step?>(null) }
    var busy by remember { mutableStateOf(false) }

    // Fields lock once a target has been resolved (Confirm) or a dial is settled (Called/Failed) -
    // editing mid-confirm would let the on-screen read-back stop matching what tap 2 actually
    // dials, which is the exact hazard the read-back exists to prevent.
    val fieldsEditable = step == null || step is PhoneDialLogic.Step.Rejected ||
        step is PhoneDialLogic.Step.EmergencyRefused

    fun runDispatch(confirmed: Boolean) {
        busy = true
        scope.launch {
            val result = PlaceCallAction.dispatchVoiceCall(
                contactQuery = if (contactMode) contactText else null,
                numberQuery = if (!contactMode) numberText else null,
                confirmed = confirmed,
                hasCallPermission = PlaceCallAction.hasCallPermission(context),
                hasContactsPermission = CallerId.hasContactsPermission(context),
                lookupContacts = { q -> PlaceCallAction.lookupContacts(context, q) },
                isEmergencyNumber = { n -> PlaceCallAction.isEmergencyNumberOnDevice(context, n) },
                dial = { n -> PlaceCallAction.dial(context, n) },
            )
            step = PhoneDialLogic.classify(result, wasConfirmed = confirmed)
            busy = false
        }
    }

    fun reset() {
        step = null
    }

    Column(Modifier.fillMaxSize()) {
        DeckScreenHeader(title = "Dial", onBack = onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DeckPane(header = "TARGET") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DeckButton(
                        text = "Contact",
                        onClick = { contactMode = true; reset() },
                        enabled = fieldsEditable,
                        confirming = contactMode,
                        modifier = Modifier.weight(1f),
                    )
                    DeckButton(
                        text = "Number",
                        onClick = { contactMode = false; reset() },
                        enabled = fieldsEditable,
                        confirming = !contactMode,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(12.dp))
                if (contactMode) {
                    DeckTextField(
                        value = contactText,
                        onValueChange = { contactText = it },
                        label = "Contact name",
                        enabled = fieldsEditable,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    )
                } else {
                    DeckTextField(
                        value = numberText,
                        onValueChange = { numberText = it },
                        label = "Phone number",
                        enabled = fieldsEditable,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    )
                }
                Spacer(Modifier.height(12.dp))
                DeckButton(
                    text = if (busy) "Working" else "Look up",
                    onClick = { runDispatch(confirmed = false) },
                    enabled = fieldsEditable && !busy &&
                        (if (contactMode) contactText.isNotBlank() else numberText.isNotBlank()),
                )
            }

            when (val s = step) {
                null -> {
                    Text(
                        "Enter a contact or a number, then Look Up. Nothing is dialled until you " +
                            "confirm the read-back on the next step.",
                        style = LegionType.stamp,
                        color = sem.faint,
                    )
                }
                is PhoneDialLogic.Step.Confirm -> {
                    DeckPane(header = "CONFIRM") {
                        Text(
                            "Calling:",
                            style = MaterialTheme.typography.labelSmall,
                            color = sem.faint,
                        )
                        Text(
                            s.readBack,
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DeckButton(
                                text = "Cancel",
                                onClick = { reset() },
                                enabled = !busy,
                                modifier = Modifier.weight(1f),
                            )
                            DeckButton(
                                text = if (busy) "Calling" else "Call",
                                onClick = { runDispatch(confirmed = true) },
                                enabled = !busy,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                is PhoneDialLogic.Step.EmergencyRefused -> {
                    DeckPane(header = "REFUSED") {
                        Text(s.message, style = LegionType.stamp, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(12.dp))
                        DeckButton(text = "Try again", onClick = { reset() })
                    }
                }
                is PhoneDialLogic.Step.Rejected -> {
                    DeckPane(header = "NOT PLACED") {
                        Text(s.message, style = LegionType.stamp, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(12.dp))
                        DeckButton(text = "Try again", onClick = { reset() })
                    }
                }
                is PhoneDialLogic.Step.Called -> {
                    DeckPane(header = "CALLING") {
                        // Only state in this screen allowed to speak an outcome-verb sentence -
                        // built from PlaceCallAction.dispatchVoiceCall's own success == true, which
                        // only follows an OBSERVED OFFHOOK transition (CLAUDE.md section 7).
                        Text(s.message, style = LegionType.stamp, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(12.dp))
                        DeckButton(text = "Done", onClick = { reset() })
                    }
                }
                is PhoneDialLogic.Step.Failed -> {
                    DeckPane(header = "DID NOT CONNECT") {
                        Text(s.message, style = LegionType.stamp, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(12.dp))
                        DeckButton(text = "Try again", onClick = { reset() })
                    }
                }
            }
        }
    }
}
