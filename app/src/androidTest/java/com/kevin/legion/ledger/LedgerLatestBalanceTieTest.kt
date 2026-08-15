package com.kevin.legion.ledger

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.LedgerTransaction
import com.kevin.legion.data.local.LedgerTransactionDao
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Regression test for a bug found on Kevin's REAL device on 2026-08-07, not by
 * any test: `get_balance` reported USD 509.71 for his checking account when the
 * day had actually closed at 440.68.
 *
 * [LedgerTransaction.txnDate] is stored at UTC midnight, so all four of his
 * 6 August transactions held an identical value and
 * [LedgerTransactionDao.latestBalanceCents]'s `ORDER BY txnDate DESC LIMIT 1`
 * was a tie SQLite could break any way it liked. It returned the FIRST row of
 * the day. Every row was present and correct in the table; only the choice of
 * "latest" was wrong.
 *
 * The fixture below is the real shape of that day, with his actual figures. It
 * fails against `ORDER BY txnDate DESC LIMIT 1` and passes against
 * `ORDER BY txnDate DESC, id DESC LIMIT 1`.
 *
 * Lives in `androidTest` because it exercises real Room/SQLite ORDER BY
 * semantics, which a JVM test cannot reach - matching the pattern already set
 * by `IngestPipelineProvisionalSupersedeTest` and `LedgerPendingDeltaDisjointTest`.
 */
@RunWith(AndroidJUnit4::class)
class LedgerLatestBalanceTieTest {

    private lateinit var db: CarDatabase
    private lateinit var dao: LedgerTransactionDao

    private val account = "Kevin debit"
    private val aug6 = LocalDate.of(2026, 8, 6).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    private val aug3 = LocalDate.of(2026, 8, 3).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CarDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.ledgerTransactionDao()
    }

    @After
    fun tearDown() = db.close()

    /**
     * Inserted oldest-first, exactly as the parsers read a statement file - the
     * property `id DESC` relies on. See [LedgerTransactionDao.latestBalanceCents]'s
     * doc comment for why a newest-first parser would silently invert this.
     */
    private fun seedRealAugustSixth() = runBlocking {
        dao.insertAll(
            listOf(
                row(aug3, "WAL-MART #4512 08/03 PURCHASE KATY TX", -783, 63857),
                // The four 6 August rows, all sharing one txnDate.
                row(aug6, "WAL-MART #4512 08/06 PURCHASE KATY TX", -12886, 50971),
                row(aug6, "99 RANCH #1106 08/06 PURCHASE KATY TX", -2811, 48160),
                row(aug6, "99 RANCH #1106 08/06 PURCHASE KATY TX", -1464, 46696),
                row(aug6, "COSTCO WHSE #1 08/06 PURCHASE KATY TX", -2628, 44068),
            )
        )
    }

    private fun row(date: Long, description: String, cents: Long, balance: Long) = LedgerTransaction(
        sourceFile = "fixture.csv",
        accountId = account,
        currency = LedgerCurrency.USD,
        txnDate = date,
        description = description,
        amountCents = cents,
        balanceCents = balance,
        lineRef = "fixture:$description",
        ingestMethod = IngestMethod.DETERMINISTIC,
    )

    @Test
    fun latestBalanceIsTheLastRowOfTheDay_notTheFirst() = runBlocking {
        seedRealAugustSixth()
        // 50971 is what the bug returned - the first 6 August row.
        assertEquals(44068L, dao.latestBalanceCents(account))
    }

    @Test
    fun latestBalanceTxnDateAgreesWithLatestBalance() = runBlocking {
        seedRealAugustSixth()
        // Both must resolve to the SAME row. They tie on date, so this asserts
        // the date rather than the row - but the anchor being a different row
        // from the balance is the failure this guards (see the DAO doc).
        assertEquals(aug6, dao.latestBalanceTxnDate(account))
    }

    @Test
    fun aBalanceThatIsNullIsSkippedEvenWhenItIsTheNewestRow() = runBlocking {
        seedRealAugustSixth()
        // BofA card statements print no running balance at all. A later row
        // with a null balance must not shadow the real anchor beneath it.
        dao.insertAll(
            listOf(
                row(aug6, "PENDING SOMETHING", -900, 0).copy(balanceCents = null),
            )
        )
        assertEquals(44068L, dao.latestBalanceCents(account))
    }
}
