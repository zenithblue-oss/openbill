package app.openbill.core.database

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class DatabaseTransactionRunnerTest {
    private lateinit var db: OpenBillDatabase
    private lateinit var runner: DatabaseTransactionRunner

    @BeforeTest
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder<OpenBillDatabase>()
            .setDriver(BundledSQLiteDriver())
            .build()
        runner = DatabaseTransactionRunner(db)
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun writeRollsBackVoucherWhenLineInsertFails(): Unit = runBlocking {
        db.seedBaseline()
        val voucherId = "voucher-rollback"
        val failure = runCatching {
            runner.write {
                db.voucherDao().insert(sampleVoucher(id = voucherId, number = null))
                db.voucherDao().insertLines(
                    listOf(
                        sampleLine(voucherId = voucherId, lineNumber = 1, id = "rb-line-1"),
                        sampleLine(voucherId = voucherId, lineNumber = 1, id = "rb-line-2"),
                    ),
                )
            }
        }

        assertTrue(failure.isFailure)
        assertNull(db.voucherDao().findById(voucherId))
        assertEquals(0, db.voucherDao().lineCount(voucherId))
    }

    @Test
    fun insertWithLinesRollsBackItsOwnTransaction(): Unit = runBlocking {
        db.seedBaseline()
        val voucherId = "voucher-tx-rollback"
        val failure = runCatching {
            db.voucherDao().insertWithLines(
                voucher = sampleVoucher(id = voucherId, number = null),
                lines = listOf(
                    sampleLine(voucherId = voucherId, lineNumber = 1, id = "tx-line-1"),
                    sampleLine(voucherId = voucherId, lineNumber = 1, id = "tx-line-2"),
                ),
            )
        }

        assertTrue(failure.isFailure)
        assertNull(db.voucherDao().findById(voucherId))
        assertEquals(0, db.voucherDao().lineCount(voucherId))
    }

    @Test
    fun writeSerializesConcurrentBlocks(): Unit = runBlocking {
        var active = 0
        var maxActive = 0
        var completed = 0

        val jobs = List(16) {
            launch(Dispatchers.Default) {
                runner.write {
                    active += 1
                    maxActive = maxOf(maxActive, active)
                    delay(10)
                    completed += 1
                    active -= 1
                }
            }
        }
        jobs.joinAll()

        assertEquals(1, maxActive)
        assertEquals(16, completed)
    }

    @Test
    fun readReturnsDataWithoutAWriteTransaction() = runBlocking {
        db.seedBaseline()
        val lineCount = runner.read { db.voucherDao().lineCount(Fixture.VOUCHER_ID) }
        assertEquals(2, lineCount)
    }

    @Test
    fun nestedWriteIsRejected(): Unit = runBlocking {
        val failure = runCatching {
            runner.write {
                runner.write { 42 }
            }
        }
        val error = failure.exceptionOrNull()
        assertIs<IllegalStateException>(error)
        assertTrue(error.message.orEmpty().contains("Nested"))
    }

    @Test
    fun concurrentSequenceIncrementsAreUniqueAndAtomic(): Unit = runBlocking {
        db.seedBaseline()
        val count = 20

        val allocated = (1..count).map {
            async(Dispatchers.Default) {
                runner.write {
                    db.voucherNumberSequenceDao().increment(
                        Fixture.FIRM_ID,
                        "sale",
                        Fixture.FINANCIAL_YEAR_ID,
                        Fixture.UPDATED_AT,
                    )
                }
            }
        }.awaitAll()

        assertEquals((1L..count.toLong()).toList(), allocated.sorted())
        assertEquals(
            count + 1L,
            db.voucherNumberSequenceDao()
                .get(Fixture.FIRM_ID, "sale", Fixture.FINANCIAL_YEAR_ID)
                ?.nextNumber,
        )
    }
}
