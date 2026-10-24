package com.kevin.legion.ui.dumb

import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.PantryCurrencyTotal
import com.kevin.legion.data.local.PantryLineItem
import com.kevin.legion.data.local.PantryReceipt
import com.kevin.legion.ui.pantry.PANTRY_ESTIMATE_SENTENCE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Matrix pantry: loading, empty, unreadable apart from empty; estimates and unverified totals in words. */
class MatrixPantryViewModelTest {
    private class Source(
        var receipts: List<MatrixReceipt> = emptyList(),
        var spend: List<MatrixSpend> = emptyList(),
        var receiptsThrow: Boolean = false,
        var spendThrow: Boolean = false,
    ) : MatrixPantrySource {
        override suspend fun receipts(): List<MatrixReceipt> {
            if (receiptsThrow) error("Room is closed")
            return receipts
        }

        override suspend fun spend(): List<MatrixSpend> {
            if (spendThrow) error("Room is closed")
            return spend
        }
    }

    private fun model(source: Source) =
        MatrixPantryViewModel(source, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)

    private fun receipt(unaccounted: Long? = null) = PantryReceipt(
        id = 1, store = "Fairprice", purchaseDate = 1_792_000_000_000L, currency = LedgerCurrency.SGD,
        totalCents = 1_250, sourceImagePath = "p", unaccountedCents = unaccounted,
    )

    private fun item(name: String, kcal: Int? = 120) =
        PantryLineItem(receiptId = 1, name = name, totalPriceCents = 350, caloriesKcal = kcal, proteinG = 4.0)

    @Test
    fun `loading, empty and unreadable are different sentences`() {
        val vm = model(Source())
        assertEquals(MatrixPantryText.LOADING, MatrixPantryText.listNote(vm.state.value)?.text)
        vm.refresh()
        assertEquals(MatrixPantryText.EMPTY, MatrixPantryText.listNote(vm.state.value)?.text)
        val broken = model(Source(receiptsThrow = true, spendThrow = true))
        broken.refresh()
        assertEquals(MatrixPantryText.UNREADABLE, MatrixPantryText.listNote(broken.state.value)?.text)
        assertTrue(broken.state.value.spendFailed)
    }

    @Test
    fun `a failed refresh keeps the receipts and warns they may be out of date`() {
        val source = Source(receipts = listOf(MatrixPantryText.receiptOf(receipt(), listOf(item("Milk")))))
        val vm = model(source)
        vm.refresh()
        source.receiptsThrow = true
        vm.refresh()
        assertEquals(MatrixPantryText.STALE, MatrixPantryText.listNote(vm.state.value)?.text)
        assertEquals(1, vm.state.value.receipts.size)
    }

    @Test
    fun `opening and closing a receipt`() {
        val vm = model(Source(receipts = listOf(MatrixPantryText.receiptOf(receipt(), listOf(item("Milk"))))))
        vm.refresh()
        vm.open(1)
        assertEquals("Fairprice", vm.state.value.selected?.title?.substringBefore("  "))
        vm.close()
        assertNull(vm.state.value.selected)
    }

    @Test
    fun `printed prices and estimates are separate sections and every estimate says estimate`() {
        val mystery = item("Mystery", kcal = null).copy(proteinG = null)
        val shown = MatrixPantryText.receiptOf(receipt(), listOf(item("Milk"), mystery))
        val lines = MatrixPantryText.detailLines(shown).map { it.text }
        val onReceipt = lines.indexOf("On the receipt")
        val estimates = lines.indexOf(MatrixPantryText.ESTIMATES_HEADING)
        assertTrue(onReceipt in 0 until estimates)
        assertTrue(lines.contains("Milk  SGD 3.50"))
        assertTrue(lines.contains(PANTRY_ESTIMATE_SENTENCE))
        val estimateLines = lines.drop(estimates + 2)
        assertEquals(2, estimateLines.size)
        assertTrue(estimateLines.all { it.contains("estimate: ") })
        assertTrue(estimateLines.last().endsWith("estimate: no estimate"))
        assertFalse(lines.take(estimates).any { it.contains("kcal") })
    }

    @Test
    fun `a receipt with an unexplained amount says Unverified in words, a clean one does not`() {
        val flagged = MatrixPantryText.receiptOf(receipt(unaccounted = 200), listOf(item("Milk")))
        assertTrue(flagged.unverified!!.startsWith("Unverified: SGD 2.00 of this total isn't explained"))
        assertEquals(MatrixTone.UNVERIFIED, MatrixPantryText.detailLines(flagged)[1].tone)
        assertNull(MatrixPantryText.receiptOf(receipt(), listOf(item("Milk"))).unverified)
    }

    @Test
    fun `spend with an unreconciled receipt behind it carries the not confirmed caveat`() {
        val plain = MatrixPantryText.spendOf(PantryCurrencyTotal(LedgerCurrency.SGD, 12_500))
        assertEquals("SGD  SGD 125.00", plain.line)
        assertNull(plain.caveat)
        val doubtful = MatrixPantryText.spendOf(PantryCurrencyTotal(LedgerCurrency.USD, 4_000, hasUnreconciled = true))
        assertTrue(doubtful.caveat!!.contains("is not confirmed accurate"))
    }

    @Test
    fun `the screen says there is no stock count`() {
        assertTrue(MatrixPantryText.NO_STOCK.contains("no stock count"))
    }
}
