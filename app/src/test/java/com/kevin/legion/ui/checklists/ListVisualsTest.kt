package com.kevin.legion.ui.checklists

import com.kevin.legion.R
import com.kevin.legion.ui.theme.soft.AreaAccent
import org.junit.Assert.assertEquals
import org.junit.Test

/** Home-launcher ticket 04's own keyword table and stable fallback - plain JVM, no Robolectric
 * needed since [listVisual] touches nothing but a [String] and a [Long]. */
class ListVisualsTest {

    @Test
    fun `groceries matches the shopping keyword group`() {
        val v = listVisual("Groceries", id = 1)
        assertEquals(R.drawable.ms_shopping_cart, v.icon)
        assertEquals(AreaAccent.MONEY, v.accent)
    }

    @Test
    fun `matching is case insensitive and by substring`() {
        val v = listVisual("weekly GROCERY run", id = 1)
        assertEquals(R.drawable.ms_shopping_cart, v.icon)
    }

    @Test
    fun `todo and to do and tasks all match the task_alt group`() {
        assertEquals(R.drawable.ms_task_alt, listVisual("Todo", id = 1).icon)
        assertEquals(R.drawable.ms_task_alt, listVisual("To Do list", id = 1).icon)
        assertEquals(R.drawable.ms_task_alt, listVisual("Tasks", id = 1).icon)
    }

    @Test
    fun `bio workout gym and fitness all match fitness_center`() {
        listOf("Bio", "Workout", "Gym", "Fitness").forEach { name ->
            assertEquals(R.drawable.ms_fitness_center, listVisual(name, id = 1).icon)
        }
    }

    @Test
    fun `morning matches wb_twilight`() {
        assertEquals(R.drawable.ms_wb_twilight, listVisual("Morning routine", id = 1).icon)
    }

    @Test
    fun `evening night and bed all match bedtime`() {
        listOf("Evening", "Night routine", "Bedtime") .forEach { name ->
            assertEquals(R.drawable.ms_bedtime, listVisual(name, id = 1).icon)
        }
    }

    @Test
    fun `packing travel and trip all match luggage`() {
        listOf("Packing", "Travel prep", "Trip list").forEach { name ->
            assertEquals(R.drawable.ms_luggage, listVisual(name, id = 1).icon)
        }
    }

    @Test
    fun `house home chores and cleaning all match home`() {
        listOf("House", "Home", "Chores", "Cleaning").forEach { name ->
            assertEquals(R.drawable.ms_home, listVisual(name, id = 1).icon)
        }
    }

    @Test
    fun `meds medicine pills and vitamin all match medication`() {
        listOf("Meds", "Medicine", "Pills", "Vitamin").forEach { name ->
            assertEquals(R.drawable.ms_medication, listVisual(name, id = 1).icon)
        }
    }

    @Test
    fun `reading and books match menu_book`() {
        listOf("Reading list", "Books").forEach { name ->
            assertEquals(R.drawable.ms_menu_book, listVisual(name, id = 1).icon)
        }
    }

    @Test
    fun `work matches work`() {
        assertEquals(R.drawable.ms_work, listVisual("Work", id = 1).icon)
    }

    @Test
    fun `school study and class match school`() {
        listOf("School", "Study", "Class").forEach { name ->
            assertEquals(R.drawable.ms_school, listVisual(name, id = 1).icon)
        }
    }

    @Test
    fun `car matches directions_car`() {
        assertEquals(R.drawable.ms_directions_car, listVisual("Car maintenance", id = 1).icon)
    }

    @Test
    fun `an unmatched name falls back to the plain checklist glyph`() {
        assertEquals(R.drawable.ms_checklist, listVisual("Xylophone practice", id = 1).icon)
    }

    @Test
    fun `the fallback colour is stable for a given id, not re-rolled`() {
        val first = listVisual("Xylophone practice", id = 42)
        val second = listVisual("Xylophone practice", id = 42)
        assertEquals(first.accent, second.accent)
    }

    @Test
    fun `the fallback colour is id mod 8`() {
        val accents = AreaAccent.entries
        assertEquals(accents[3], listVisual("Xylophone practice", id = 3).accent)
        assertEquals(accents[0], listVisual("Xylophone practice", id = 8).accent)
        assertEquals(accents[5], listVisual("Xylophone practice", id = 13).accent)
    }
}
