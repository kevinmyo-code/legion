package com.kevin.legion.dumb

import android.content.ContentResolver
import android.net.Uri
import android.provider.ContactsContract

/**
 * Turns the contact Kevin picked into a [ChosenPerson] (ADR 0062): the contact id, the display name,
 * and every phone number the contact has, reduced to digits. Needs READ_CONTACTS, because the phone
 * numbers live in a table the picker's one-contact grant does not cover. Returns null when the
 * contact cannot be read, never a half-filled person.
 */
object ContactReader {

    fun read(resolver: ContentResolver, contactUri: Uri): ChosenPerson? = runCatching {
        val (id, name) = resolver.query(
            contactUri,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { c ->
            if (c.moveToFirst()) c.getString(0) to c.getString(1) else null
        } ?: return null
        if (id.isNullOrEmpty() || name.isNullOrBlank()) return null
        ChosenPerson(id, name.trim(), numbersOf(resolver, id))
    }.getOrNull()

    private fun numbersOf(resolver: ContentResolver, contactId: String): List<String> =
        resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(contactId),
            null,
        )?.use { c ->
            buildList {
                while (c.moveToNext()) PhoneNumbers.normalize(c.getString(0)).takeIf { it.isNotEmpty() }?.let(::add)
            }.distinct()
        }.orEmpty()
}
