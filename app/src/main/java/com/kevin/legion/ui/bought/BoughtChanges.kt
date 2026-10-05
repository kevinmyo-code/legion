package com.kevin.legion.ui.bought

import com.kevin.legion.purchases.Purchase
import com.kevin.legion.purchases.PurchaseFailures
import com.kevin.legion.purchases.PurchaseOutcome
import com.kevin.legion.purchases.PurchaseWording
import com.kevin.legion.purchases.PurchasesController
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val CENTS = 100

/**
 * Edit and Delete of an entry already in the bought log (2026-10-05: the phone could add an entry
 * and never remove or fix one). Works on [BoughtViewModel]'s one state flow, through the same
 * [PurchasesController] the rest of the screen uses.
 *
 * **Only for entries [Purchase.mayChange] allows** - the member's own, and backfilled ones with no
 * recorded logger, the web's rule; the controller refuses anything else again before sending.
 * **"Deleted" and "Saved" are said only from a 2xx.** Every other outcome says in words that
 * nothing happened (or, for an unreadable reply, that it is not known), and the list is re-read
 * either way so the screen shows what the engine holds.
 */
class BoughtChanges(
    private val controller: PurchasesController,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<BoughtUiState>,
    private val refresh: () -> Unit,
) {
    /** Opens the form on [entry], filled from it. */
    fun openEdit(entry: Purchase) {
        if (!entry.mayChange) return
        state.update {
            it.copy(
                mode = BoughtMode.LOG,
                savedMessage = null,
                problem = null,
                form = LogFormState(
                    item = entry.item,
                    dateText = LocalDate.ofEpochDay(entry.boughtOn.toLong()).toString(),
                    store = entry.store.orEmpty(),
                    price = entry.priceCents?.let { c -> "%d.%02d".format(c / CENTS, c % CENTS) }.orEmpty(),
                    note = entry.quantityNote.orEmpty(),
                    isPrivate = entry.isPrivate,
                    editing = entry,
                ),
            )
        }
    }

    /** Sends the edit. The form stays as typed on every failure, with [LogFormState.error] in words. */
    suspend fun saveEdit(form: LogFormState, editing: Purchase, boughtOn: Int, priceCents: Long?) {
        val outcome = controller.edit(
            entry = editing,
            item = form.item,
            boughtOn = boughtOn,
            store = form.store,
            priceCents = priceCents,
            note = form.note,
            isPrivate = form.isPrivate,
        )
        if (outcome is PurchaseOutcome.Ok) {
            val said = PurchaseWording.edited(outcome.value, controller.today())
            state.update { it.copy(mode = BoughtMode.SEARCH, savedMessage = said, problem = null) }
            refresh()
        } else {
            val item = form.item.trim().ifEmpty { "that" }
            val sentence = PurchaseFailures.changeFailed(outcome, "change", "changed", item)
            state.update { it.copy(form = form.copy(saving = false, error = sentence)) }
        }
    }

    /** Opens the confirm; nothing is deleted until [confirmDelete]. */
    fun askDelete(entry: Purchase) {
        if (entry.mayChange) state.update { it.copy(pendingDelete = entry, problem = null, savedMessage = null) }
    }

    fun cancelDelete() {
        state.update { it.copy(pendingDelete = null) }
    }

    /** Deletes the entry the confirm named; the list is re-read whatever the outcome. */
    fun confirmDelete() {
        val entry = state.value.pendingDelete ?: return
        state.update { it.copy(pendingDelete = null) }
        scope.launch {
            val outcome = controller.delete(entry)
            if (outcome is PurchaseOutcome.Ok) {
                state.update { it.copy(savedMessage = PurchaseWording.deleted(entry.item), problem = null) }
            } else {
                val sentence = PurchaseFailures.changeFailed(outcome, "delete", "deleted", "\"${entry.item}\"")
                state.update { it.copy(problem = sentence, savedMessage = null) }
            }
            refresh()
        }
    }
}
