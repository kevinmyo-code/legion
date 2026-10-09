package com.kevin.legion.navigation.resolve

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.Contacts
import androidx.core.content.ContextCompat
import com.kevin.legion.backend.EventKind
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.location.PlaceController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CancellationException

/**
 * The phone-side readers behind the resolver's sources. Each one turns "could not read" into the
 * Unreadable answer with a sentence, never into an empty list (CLAUDE.md sec 1: unreadable and
 * empty are different sentences). Nothing read here is written anywhere.
 */
class PhonePlacesReader(private val context: Context) : SavedPlacesReader {
    @Suppress("TooGenericExceptionCaught") // a Room or engine failure must become words, not a crash
    override suspend fun read(): PlacesRead = try {
        PlacesRead.Places(
            PlaceController.all(context)
                .filter { !it.deleted }
                .map { SavedPlaceRow(it.label, it.latitude, it.longitude) },
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        PlacesRead.Unreadable("your saved places could not be read (${e.message ?: e::class.java.simpleName})")
    }
}

/** LEGION's own agenda (the Room `events` store the Google import feeds), `event` rows only. */
class PhoneEvents(private val context: Context) : UpcomingEvents {
    @Suppress("TooGenericExceptionCaught") // same reason as above
    override suspend fun upcoming(nowMs: Long): EventsRead = try {
        val rows = CarDatabase.getDatabase(context).eventDao().activeByKindFrom(EventKind.EVENT, nowMs, LIMIT)
        EventsRead.Events(rows.map { CalendarEventRow(it.title, it.startsAt ?: nowMs, it.location) })
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        EventsRead.Unreadable("the calendar could not be read (${e.message ?: e::class.java.simpleName})")
    }

    override fun canReadDeviceCalendar(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val LIMIT = 100
    }
}

/** Calendar suggestions from the start of today on (`calendar/EventSuggestions.kt`); read, never written. */
class PhoneSuggestions(private val context: Context) : SuggestionsReader {
    @Suppress("TooGenericExceptionCaught") // same reason as above
    override suspend fun read(nowMs: Long): SuggestionsRead = try {
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val from = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = today.plusDays(HORIZON_DAYS).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        val rows = com.kevin.legion.calendar.EventSuggestions.inLocalWindow(context, from, to, zone)
        SuggestionsRead.Rows(
            rows.mapNotNull { e ->
                val start = e.startsAt ?: return@mapNotNull null
                val meta = com.kevin.legion.calendar.SuggestionMeta.parse(e.structuredMeta)
                SuggestionRow(
                    title = e.title,
                    startMs = start,
                    allDay = e.allDay,
                    city = meta?.city,
                    venue = meta?.venue,
                    address = meta?.address,
                    location = e.location,
                    structured = meta != null,
                )
            },
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SuggestionsRead.Unreadable("calendar suggestions could not be read (${e.message ?: e::class.java.simpleName})")
    }

    private companion object {
        const val HORIZON_DAYS = 60L
    }
}

/** Postal addresses from `ContactsContract.CommonDataKinds.StructuredPostal`, filtered by display name. */
class PhoneContacts(private val context: Context) : ContactsReader {
    @Suppress("TooGenericExceptionCaught") // same reason as above
    override suspend fun read(names: List<String>): ContactsRead {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return ContactsRead.Unreadable(ContactSource.NO_PERMISSION)
        return try {
            withContext(Dispatchers.IO) { ContactsRead.Contacts(query(names)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ContactsRead.Unreadable("contacts could not be read (${e.message ?: e::class.java.simpleName})")
        }
    }

    private fun query(names: List<String>): List<ContactAddress> {
        val byName = linkedMapOf<String, MutableList<String>>()
        for (name in names) {
            val uri = StructuredPostal.CONTENT_URI
            forEachRow(uri, StructuredPostal.DISPLAY_NAME, StructuredPostal.FORMATTED_ADDRESS, name) { n, a ->
                val addresses = byName.getOrPut(n) { mutableListOf() }
                if (a.isNotBlank()) addresses += a
            }
            // A contact with a matching name but no postal row never appears in StructuredPostal, so
            // the "in your contacts but has no address" sentence needs the name lookup too.
            forEachRow(Contacts.CONTENT_URI, Contacts.DISPLAY_NAME, null, name) { n, _ ->
                byName.getOrPut(n) { mutableListOf() }
            }
        }
        return byName.map { (n, a) -> ContactAddress(n, a.distinct()) }
    }

    /** Calls [row] with each matching row's display name and (optional) address text; blank names are skipped. */
    private fun forEachRow(
        uri: Uri,
        nameColumn: String,
        addressColumn: String?,
        name: String,
        row: (String, String) -> Unit,
    ) {
        val columns = listOfNotNull(nameColumn, addressColumn).toTypedArray()
        context.contentResolver.query(uri, columns, "$nameColumn LIKE ?", arrayOf("%$name%"), null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val n = cursor.getString(0).orEmpty()
                if (n.isNotBlank()) {
                    row(n, if (addressColumn == null) "" else cursor.getString(1).orEmpty().replace('\n', ' ').trim())
                }
            }
        }
    }
}
