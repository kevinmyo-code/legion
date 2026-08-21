package com.kevin.legion.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/**
 * Turns an incoming call's raw number into something worth saying out loud (2026-08-21, Kevin:
 * *"i wanna know whos calling. right now only announces that theres a call"*).
 *
 * ### Why it only ever said "a call"
 *
 * Not an oversight - a platform restriction the old code documented and worked around.
 * `PhoneStateListener.onCallStateChanged` hands the number to an app that holds `READ_CALL_LOG`
 * and an **empty string** to one that does not; AOSP's `TelephonyRegistry` computes
 * `r.canReadCallLog() ? phoneNumber : ""` on every single delivery. LEGION held
 * `READ_PHONE_STATE` only, so the number was always `""` and a generic line was the honest answer.
 *
 * ### Four outcomes, and none of them is a guess
 *
 * This is the raise-contract rule (settled decision 20) applied to one more permissioned source:
 * **unreadable and empty are different sentences.** Every branch below is a fact the assistant can
 * state, and there is deliberately no branch that invents a plausible name.
 *
 * | Situation | [describe] returns | What gets said |
 * |---|---|---|
 * | A matching contact | [Caller.Known] | the person's name |
 * | A number with no contact | [Caller.NumberOnly] | the number, digit by digit |
 * | Number withheld or blocked | [Caller.Withheld] | "a withheld number" |
 * | Permission missing | [Caller.CannotTell] | "I can't see who it is" - never "unknown caller" |
 *
 * The last row is the one that matters. **"I cannot see who is calling" and "the caller is
 * unknown" are different claims**, and collapsing them tells Kevin the network withheld a number
 * when in fact the app was never allowed to look. Same failure shape as rendering a refused
 * calendar permission as an empty day.
 */
object CallerId {

    /** What is known about who is ringing. */
    sealed class Caller {
        /** [name] came from the user's own contacts, matched on [number]. */
        data class Known(val name: String, val number: String) : Caller()

        /** A real number that matches no contact. */
        data class NumberOnly(val number: String) : Caller()

        /** The call carries no number - withheld, blocked, or a payphone. The NETWORK does not
         * say who it is, which is itself a fact and is spoken as one. */
        data object Withheld : Caller()

        /** `READ_CALL_LOG` and/or `READ_CONTACTS` is not granted, so the app cannot look. **Not
         * the same as [Withheld]** - see the class doc. */
        data object CannotTell : Caller()
    }

    fun hasCallLogPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED

    fun hasContactsPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Resolves [rawNumber] as delivered by `PhoneStateListener`.
     *
     * **An empty string is the platform's "you may not know", not "there is no number"** - that is
     * exactly what `TelephonyRegistry` substitutes when `READ_CALL_LOG` is missing - so an empty
     * number with the permission absent is [Caller.CannotTell], and an empty number WITH the
     * permission granted is a genuinely [Caller.Withheld] one. The two look identical at the call
     * site and mean opposite things, which is why the permission is checked before the blank.
     */
    fun identify(context: Context, rawNumber: String?): Caller {
        val number = rawNumber?.trim().orEmpty()
        if (!hasCallLogPermission(context)) return Caller.CannotTell
        if (number.isEmpty()) return Caller.Withheld
        if (!hasContactsPermission(context)) return Caller.NumberOnly(number)
        val name = lookupContactName(context, number)
        return if (name != null) Caller.Known(name, number) else Caller.NumberOnly(number)
    }

    /**
     * `ContactsContract.PhoneLookup` - the platform's own caller-ID table, which does the messy
     * number matching (country codes, formatting, trunk prefixes) so this file does not have to
     * and cannot get it subtly wrong.
     *
     * Returns null on anything unexpected rather than throwing: this runs from a
     * `PhoneStateListener` callback while the phone is ringing, and a crash there would take the
     * announcement AND the service with it.
     */
    private fun lookupContactName(context: Context, number: String): String? = runCatching {
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(number),
        )
        context.contentResolver.query(
            uri,
            arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
            null, null, null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.trim()?.ifBlank { null } else null
        }
    }.getOrNull()

    /**
     * The phrase the announcement prompt states as fact. Pure, so it is unit-testable without a
     * `Context` - and it is the sentence that must never overclaim, so it is worth testing.
     */
    fun describe(caller: Caller): String = when (caller) {
        is Caller.Known -> "the caller is ${caller.name} (from the user's contacts)"
        is Caller.NumberOnly ->
            "the caller's number is ${caller.number}, and it matches nobody in the user's contacts"
        Caller.Withheld -> "the network gave no number for this call - it is withheld or blocked"
        Caller.CannotTell ->
            "you CANNOT see who is calling - the app lacks call-log permission. Say you don't " +
                "know who it is; do NOT say the number is unknown or withheld, because you have " +
                "not been told either way"
    }
}
