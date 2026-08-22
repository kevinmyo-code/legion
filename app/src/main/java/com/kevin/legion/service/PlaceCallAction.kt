package com.kevin.legion.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

/**
 * Placing an outbound call by voice (ticket 26, `.scratch/hands-and-senses/issues/26-build-place-a-call.md`,
 * resolved at `05-comms.md`, Kevin, 2026-08-21).
 *
 * **Texts are ruled out permanently, not deferred.** The resolution's own words: a call is
 * verifiable by ear and reversible, a wrong text cannot be un-sent and the user would not hear it
 * happen. Do not add one here later without re-reading that resolution.
 *
 * ### Two targets, one asymmetric risk
 *
 * Contacts resolve through the same `ContactsContract` machinery [CallerId] already uses for
 * incoming numbers, run backwards - a name in, a number out, instead of a number in, a name out.
 * Spoken digits are the WIDER option Kevin chose against the recommendation: speech-to-text on a
 * digit string is poor, and a misheard digit dials a stranger. That cost is not re-litigated here;
 * it is why the read-back below is load-bearing rather than a nicety.
 *
 * ### The read-back IS the confirm gate, not a suggestion in the tool description
 *
 * This follows the exact `confirmed` boolean shape `GarageController.dispatchVoiceActivate` and
 * `DtcClearController.dispatchAndRecord` already use for an irreversible real-world action: the
 * first call (confirmed=false) resolves the target and returns the sentence to say out loud -
 * the contact's name, or the digits grouped for speech - and dials NOTHING. Only a second call
 * with confirmed=true, after the model has said that sentence and the user said yes, actually
 * places the call. A prompt instruction alone ("say it before calling this") would be exactly as
 * unenforceable as asking the model nicely not to hallucinate; gating the dial itself behind a
 * second turn is what actually stands between a misheard digit and a stranger's phone ringing.
 *
 * ### Never guesses at a partial match
 *
 * A name matching nobody, or matching more than one person, ASKS - same posture as
 * `get_reported_crime_history` returning null rather than answering about the wrong jurisdiction.
 * Multiple phone numbers under the SAME display name are not treated as ambiguous (most people
 * have a mobile and a home number under one contact); only multiple DIFFERENT names are.
 *
 * ### Emergency numbers are refused before anything else
 *
 * Checked immediately after the target resolves, ahead of the confirm gate - an emergency number
 * is refused on the very first call, confirmed or not, so the assistant never even reads one back
 * as though it were a normal target to confirm.
 *
 * ### The honesty problem, same shape as [CallActions]
 *
 * Starting `Intent.ACTION_CALL` and having it not throw is not evidence a call was placed - the
 * intent can be swallowed by the platform with no exception at all. So [dial] (the production
 * implementation, injected into [dispatchVoiceCall] as everywhere else in this file) watches the
 * phone's own call state afterward the same way [CallActions.answer] does, and only a
 * RINGING/IDLE -> OFFHOOK transition is reported as placed. CLAUDE.md §7: an outcome verb follows
 * only an observed successful result, never an assumed one.
 */
object PlaceCallAction {

    private const val TAG = "PlaceCallAction"
    private const val CONFIRM_TIMEOUT_MS = 3_000L
    private const val POLL_INTERVAL_MS = 100L

    /** One phone number under one display name, as returned by a contacts lookup. */
    data class ContactMatch(val name: String, val number: String)

    /** What the voice tool hands back. Same shape as `GarageController.VoiceActivateResult`:
     * [success] is true only when the call was actually confirmed placed, [message] is always the
     * full sentence to speak. */
    data class VoiceCallResult(val success: Boolean, val message: String)

    /**
     * A safety net independent of the OS check below, in case that call throws or a locale table
     * is missing it. These three cover the US (911), the pan-European/GSM standard (112), and the
     * UK (999) - deliberately small, because the authoritative source is [isEmergencyNumberOnDevice]
     * and this exists only to catch that source failing closed rather than to replace it.
     */
    private val KNOWN_EMERGENCY_NUMBERS = setOf("911", "112", "999")

    fun hasCallPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Strips a spoken/dictated number down to digits (keeping a leading `+`) and rejects anything
     * that is not a plausible phone number length. Returns null rather than guessing - an
     * unparsable string is [VoiceCallResult] failure, never a best-effort dial.
     */
    internal fun normalizeDigits(raw: String): String? {
        val trimmed = raw.trim()
        val hasPlus = trimmed.startsWith("+")
        val digitsOnly = trimmed.filter { it.isDigit() }
        // Lower bound is 3, not 7 - short codes like 911/112/999 are exactly 3 digits, and
        // rejecting them here as "not a valid number" would misroute the emergency-refusal
        // check below into the wrong failure sentence entirely.
        if (digitsOnly.length < 3 || digitsOnly.length > 15) return null
        return (if (hasPlus) "+" else "") + digitsOnly
    }

    /**
     * The digits grouped for speech, e.g. "5551234567" -> "555, 123, 456, 7" - this is the exact
     * sentence the resolution calls load-bearing: reading a number back in one unbroken string is
     * how a misheard digit slips past a human confirming it.
     */
    internal fun groupForSpeech(digits: String): String {
        val plus = if (digits.startsWith("+")) "plus " else ""
        val body = digits.removePrefix("+")
        return plus + body.chunked(3).joinToString(", ")
    }

    /**
     * The pure confirm-gate + resolution logic, factored out of [com.kevin.legion.service.LiveToolbox]
     * so it is unit-testable with no `Context` at all - [lookupContacts], [isEmergencyNumber] and
     * [dial] are injected the same way `GarageController.dispatchVoiceActivate` injects `activate`,
     * so a test can assert exactly which of them ran and which never did (an emergency refusal must
     * never reach [dial], and neither must an unconfirmed first call).
     */
    suspend fun dispatchVoiceCall(
        contactQuery: String?,
        numberQuery: String?,
        confirmed: Boolean,
        hasCallPermission: Boolean,
        hasContactsPermission: Boolean,
        lookupContacts: (String) -> List<ContactMatch>,
        isEmergencyNumber: (String) -> Boolean,
        dial: suspend (String) -> Boolean,
    ): VoiceCallResult {
        val contact = contactQuery?.trim()?.takeIf { it.isNotBlank() }
        val number = numberQuery?.trim()?.takeIf { it.isNotBlank() }
        if (contact == null && number == null) {
            return VoiceCallResult(false, "Who should I call, or what number?")
        }
        if (!hasCallPermission) {
            return VoiceCallResult(
                false,
                "I do not have permission to place calls. Nothing was dialled. It can be granted " +
                    "in Setup.",
            )
        }

        // ---- resolve the target: a contact's number, or a validated digit string -------------
        val targetNumber: String
        val readBack: String
        if (contact != null) {
            if (!hasContactsPermission) {
                return VoiceCallResult(
                    false,
                    "I do not have permission to look up contacts. Nothing was dialled. It can be " +
                        "granted in Setup.",
                )
            }
            val matches = lookupContacts(contact)
            val distinctNames = matches.map { it.name }.distinct()
            when {
                matches.isEmpty() -> return VoiceCallResult(
                    false,
                    "I don't have anyone named \"$contact\" in contacts. Nothing was dialled.",
                )
                distinctNames.size > 1 -> return VoiceCallResult(
                    false,
                    "There's more than one match for \"$contact\" in contacts: " +
                        "${distinctNames.joinToString(", ")}. Which one?",
                )
                else -> {
                    targetNumber = matches.first().number
                    readBack = matches.first().name
                }
            }
        } else {
            val digits = normalizeDigits(number!!)
                ?: return VoiceCallResult(
                    false,
                    "\"$number\" doesn't look like a valid phone number. Nothing was dialled.",
                )
            targetNumber = digits
            readBack = groupForSpeech(digits)
        }

        // ---- emergency refusal, ahead of the confirm gate - never even read back as normal ----
        if (isEmergencyNumber(targetNumber)) {
            return VoiceCallResult(
                false,
                "$readBack is an emergency number. I won't place that call - dial it yourself.",
            )
        }

        // ---- the confirm gate itself: this IS the read-back, not a prompt instruction ---------
        if (!confirmed) {
            return VoiceCallResult(
                false,
                "Before I dial, say this back to the user and get a yes: \"$readBack\". Call this " +
                    "again with confirmed=true only after they confirm.",
            )
        }

        val placed = dial(targetNumber)
        return if (placed) {
            VoiceCallResult(true, "Calling $readBack.")
        } else {
            VoiceCallResult(false, "That didn't go through. The call was not placed.")
        }
    }

    // ---------------------------------------------------------------- production wiring

    /**
     * `ContactsContract.CommonDataKinds.Phone`, filtered by display name - the same table
     * [CallerId] reads, run in the other direction (name in, number out rather than number in,
     * name out). Returns one [ContactMatch] per phone number on a matching contact; multiple
     * numbers under the same name are collapsed to one choice by [dispatchVoiceCall], not here -
     * this function only reports what the provider actually has.
     */
    fun lookupContacts(context: Context, query: String): List<ContactMatch> = runCatching {
        val results = mutableListOf<ContactMatch>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$query%"),
            null,
        )?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIdx)?.trim().orEmpty()
                val num = cursor.getString(numberIdx)?.trim().orEmpty()
                if (name.isNotBlank() && num.isNotBlank()) results.add(ContactMatch(name, num))
            }
        }
        results
    }.getOrElse {
        Log.d(TAG, "contact lookup failed: ${it.message}")
        emptyList()
    }

    /**
     * The authoritative emergency check. **`isEmergencyNumber` lives on `TelephonyManager`, not
     * `TelecomManager`** - confirmed against the actual API-36 platform jar (`android.jar`) after
     * the first draft guessed `TelecomManager` and failed to compile with an unresolved reference,
     * which is exactly the "trace before you claim" mistake this codebase's own lessons file warns
     * against. Added at API 30; below that, `PhoneNumberUtils.isEmergencyNumber` (deprecated, still
     * functional) is the fallback. Both are the platform's own locale-aware table, not a guess this
     * app maintains. Unioned with [KNOWN_EMERGENCY_NUMBERS] so a thrown exception here fails CLOSED
     * (refuses) rather than open.
     */
    fun isEmergencyNumberOnDevice(context: Context, number: String): Boolean {
        val digits = number.removePrefix("+")
        if (digits in KNOWN_EMERGENCY_NUMBERS) return true
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                tm?.isEmergencyNumber(number) == true
            } else {
                @Suppress("DEPRECATION")
                PhoneNumberUtils.isEmergencyNumber(number)
            }
        }.getOrDefault(false)
    }

    private fun callState(context: Context): Int {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return TelephonyManager.CALL_STATE_IDLE
        return runCatching {
            @Suppress("DEPRECATION")
            tm.callState
        }.getOrDefault(TelephonyManager.CALL_STATE_IDLE)
    }

    /**
     * Fires `Intent.ACTION_CALL` and confirms by watching the phone's own call state, same
     * discipline as [CallActions.answer] - starting an intent that does not throw is not evidence
     * a call began, so this refuses to say "placed" on that alone.
     */
    suspend fun dial(context: Context, number: String): Boolean {
        val started = runCatching {
            context.startActivity(
                Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { Log.d(TAG, "ACTION_CALL threw: ${it.message}") }.isSuccess
        if (!started) return false

        var waited = 0L
        while (waited < CONFIRM_TIMEOUT_MS) {
            if (callState(context) == TelephonyManager.CALL_STATE_OFFHOOK) return true
            delay(POLL_INTERVAL_MS)
            waited += POLL_INTERVAL_MS
        }
        return callState(context) == TelephonyManager.CALL_STATE_OFFHOOK
    }
}
