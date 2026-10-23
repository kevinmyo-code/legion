package com.kevin.legion.dumb

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One person Kevin chose to let through in dumb mode (ADR 0062). Picked INSIDE LEGION from his
 * contacts; Android's starred flag is not read or written. Stored locally: the contact id (to match
 * a contact-lookup URI), the normalised numbers (to match `tel:` URIs and callers) and the display
 * name (to match a conversation title). This is Kevin's own data, not third-party content.
 */
data class ChosenPerson(
    val contactId: String,
    val displayName: String,
    /** Digits only, see [PhoneNumbers.normalize]. */
    val numbers: List<String>,
)

/** Persistence seam for the chosen list. */
interface ChosenPeopleStore {
    fun read(): List<ChosenPerson>
    fun write(people: List<ChosenPerson>)
}

/**
 * The chosen-people list, observable. Adding a contact already on the list replaces it (a contact
 * may have gained a number since).
 */
class ChosenPeople(private val store: ChosenPeopleStore) {
    private val _people = MutableStateFlow(store.read())
    val people: StateFlow<List<ChosenPerson>> = _people.asStateFlow()

    fun names(): List<String> = _people.value.map { it.displayName }

    fun add(person: ChosenPerson) {
        val next = _people.value.filterNot { it.contactId == person.contactId } + person
        commit(next)
    }

    fun remove(contactId: String) {
        commit(_people.value.filterNot { it.contactId == contactId })
    }

    /** True when [number] belongs to any chosen person. Used by call screening (ticket 03). */
    fun isChosenNumber(number: String?): Boolean =
        _people.value.any { p -> p.numbers.any { PhoneNumbers.same(it, number) } }

    private fun commit(next: List<ChosenPerson>) {
        store.write(next)
        _people.value = next
    }
}

/**
 * SharedPreferences persistence. Records are separated by U+001E, fields by U+001F and numbers by
 * a comma; those separators are stripped from names on the way in so a name can never forge a
 * field. `commit()` because a pick must survive the process ending right after it.
 */
class PrefsChosenPeopleStore(private val prefs: SharedPreferences) : ChosenPeopleStore {
    constructor(context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    override fun read(): List<ChosenPerson> = parse(prefs.getString(KEY, "").orEmpty())

    override fun write(people: List<ChosenPerson>) {
        prefs.edit().putString(KEY, format(people)).commit()
    }

    internal companion object {
        const val PREFS = "dumb_mode_people"
        private const val KEY = "people"
        private const val RECORD = '\u001E'
        private const val FIELD = '\u001F'
        private const val FIELD_COUNT = 3

        private fun clean(text: String) = text.filterNot { it == RECORD || it == FIELD }

        fun format(people: List<ChosenPerson>): String = people.joinToString(RECORD.toString()) { p ->
            val numbers = p.numbers.joinToString(",") { n -> n.filter(Char::isDigit) }
            listOf(clean(p.contactId), clean(p.displayName), numbers).joinToString(FIELD.toString())
        }

        fun parse(raw: String): List<ChosenPerson> = raw.split(RECORD).mapNotNull { record ->
            val f = record.split(FIELD)
            if (f.size == FIELD_COUNT && f[0].isNotEmpty()) {
                ChosenPerson(f[0], f[1], f[2].split(',').filter { it.isNotEmpty() })
            } else {
                null
            }
        }
    }
}
