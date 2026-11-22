package com.kevin.legion.service

import android.content.Context
import com.kevin.legion.assistant.cards.CardSource
import com.kevin.legion.assistant.cards.ChecklistLine
import com.kevin.legion.assistant.cards.ConversationCard
import com.kevin.legion.assistant.cards.ConversationCards
import com.kevin.legion.assistant.cards.LiveCards
import com.kevin.legion.assistant.cards.PhoneCards
import com.kevin.legion.calendar.CalendarWindowRead
import com.kevin.legion.checklists.ChecklistController
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.notes.NotesController
import com.kevin.legion.service.tools.day.RelativeDay
import com.kevin.legion.ui.notes.buildInboxRows
import kotlinx.coroutines.flow.Flow

/**
 * The reads behind the live cards ([LiveCards], 2026-11-22): one card per [CardSource], built by the
 * same [PhoneCards] builder and the same controller read the voice tool uses, so a refreshed card
 * and a freshly asked one cannot differ. The tools call these too.
 */
object LiveCardReads {

    /** The tables a live card is read from; any write to one re-reads the open cards. */
    private val TABLES = arrayOf("events", "list_items", "checklists", "checklist_items", "checklist_ticks")

    /** Room's change signal for [TABLES], without the initial emission (the card was just read). */
    fun changes(context: Context): Flow<Set<String>> =
        CarDatabase.getDatabase(context).invalidationTracker.createFlow(*TABLES, emitInitialState = false)

    /** Follows the open cards for the life of the caller (the application). */
    suspend fun follow(context: Context) {
        val app = context.applicationContext
        LiveCards.follow(changes(app)) { read(app, it) }
    }

    suspend fun read(context: Context, source: CardSource): ConversationCard? = when (source) {
        is CardSource.Calendar -> calendar(context, source.startMs, source.endMs)
        is CardSource.Checklist -> checklist(context, source.checklistId)
        CardSource.Reminders -> reminders(context)
    }

    /** `read_calendar`'s card for the window, from [events] when the caller already read them. */
    fun calendarCard(events: List<com.kevin.legion.data.local.Event>, startMs: Long, endMs: Long): ConversationCard.Timeline =
        PhoneCards.calendar(events, RelativeDay.clock().zone, startMs).copy(source = CardSource.Calendar(startMs, endMs))

    private suspend fun calendar(context: Context, startMs: Long, endMs: Long): ConversationCard {
        val zone = RelativeDay.clock().zone
        val read = CalendarWindowRead.read(context, startMs, endMs, zone, RelativeDay.clock().millis())
        return calendarCard(read.events, startMs, endMs)
    }

    /** `manage_checklist read`'s card; null when the list is gone or could not be read. */
    suspend fun checklist(context: Context, checklistId: Long): ConversationCard.Checklist? {
        val list = CarDatabase.getDatabase(context).checklistDao().getById(checklistId) ?: return null
        val loaded = ChecklistController.itemsWithTickState(context, checklistId)
            as? ChecklistController.ChecklistItemsResult.Loaded ?: return null
        return checklistCard(list.id, list.name, loaded.items.map { ChecklistLine(it.item.text, it.ticked) })
    }

    fun checklistCard(checklistId: Long, name: String, lines: List<ChecklistLine>): ConversationCard.Checklist =
        PhoneCards.checklist(name, lines).copy(source = CardSource.Checklist(checklistId))

    /** `read_list`'s card: the reminders, in the order the tool reads them out. */
    suspend fun reminders(context: Context): ConversationCard.Checklist {
        val rows = buildInboxRows(NotesController.allItems(context), System.currentTimeMillis())
        return remindersCard(rows.map { ChecklistLine(it.text, it.done) })
    }

    fun remindersCard(lines: List<ChecklistLine>): ConversationCard.Checklist =
        PhoneCards.checklist(PhoneCards.TODO_LIST_NAME, lines).copy(source = CardSource.Reminders)

    /**
     * After a calendar write on [dayStartMs]..[dayEndMs]: when no open card shows that day, its day
     * card is put up, so he sees where the write landed (2026-11-22: an add meant for tomorrow went
     * on today, and the open card for tomorrow, rightly, did not change). An open card that shows the
     * day refreshes itself through [LiveCards.follow]. No-op while no conversation is open.
     */
    suspend fun showDayAfterWrite(context: Context, dayStartMs: Long, dayEndMs: Long) {
        val state = ConversationCards.state.value
        if (!state.open || LiveCards.calendarShows(state, dayStartMs)) return
        ConversationCards.publish(calendar(context, dayStartMs, dayEndMs))
    }
}
