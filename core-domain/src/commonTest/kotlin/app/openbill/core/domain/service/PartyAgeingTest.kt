package app.openbill.core.domain.service

import app.openbill.core.domain.model.PartyMovement
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.model.Money
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

class PartyAgeingTest {
    @Test
    fun bucketSumsMatchFifoWalk() {
        val asOf = LocalDate(2026, 3, 31)
        val r = Random(42)
        repeat(2000) { case ->
            val moves = List(r.nextInt(0, 12)) {
                val net = if (r.nextInt(5) == 0) 0L else r.nextLong(-50_000, 50_000)
                PartyMovement("p", "v$it", VoucherType.SALE, null, LocalDate(2025, 10, 1).plus(DatePeriod(days = r.nextInt(0, 220))), Money.of(net))
            }
            val opening = Money.of(listOf(0L, r.nextLong(-30_000, 30_000)).random(r))
            assertEquals(PartyAgeing.of(opening, moves, asOf), PartyAgeing.fromSums(opening, PartyAgeing.sums(moves, asOf)), "case $case")
        }
    }

    @Test
    fun overdueIsTheOpenReceivablePastTheCreditDays() {
        val asOf = LocalDate(2026, 3, 31)
        fun sale(day: LocalDate, rupees: Long) = PartyMovement("p", "v$day", VoucherType.SALE, null, day, Money.of(rupees * 100))
        val moves = listOf(
            sale(LocalDate(2026, 1, 10), 1000), // 80 days old
            sale(LocalDate(2026, 3, 1), 500), // 30 days old
            sale(LocalDate(2026, 3, 25), 200), // 6 days old
            sale(LocalDate(2026, 3, 28), -600), // payment settles the oldest first
        )
        // 30 days of credit: 400 left of the January sale is overdue; March's 500 is exactly 30 days, not yet.
        assertEquals(Money.of(40_000), PartyAgeing.overdue(Money.ZERO, moves, asOf, 30))
        assertEquals(Money.of(90_000), PartyAgeing.overdue(Money.ZERO, moves, asOf, 7))
        assertEquals(Money.of(110_000), PartyAgeing.overdue(Money.ZERO, moves, asOf, 0))
        assertEquals(Money.of(15_000), PartyAgeing.overdue(Money.of(15_000), emptyList(), asOf, 30), "the opening balance counts as overdue")
        assertEquals(Money.ZERO, PartyAgeing.overdue(Money.ZERO, listOf(sale(asOf, -100)), asOf, 0), "an advance is never overdue")
        // Period ends in the future: age measured on today (Feb 20), so only the January sale (41 days) is past 30 days.
        assertEquals(Money.of(40_000), PartyAgeing.overdue(Money.ZERO, moves, asOf, 30, measuredOn = LocalDate(2026, 2, 20)))
        assertEquals(Money.ZERO, PartyAgeing.overdue(Money.ZERO, moves, asOf, 45, measuredOn = LocalDate(2026, 2, 20)))
        assertEquals(Money.of(40_000), PartyAgeing.overdue(Money.ZERO, moves, asOf, 45, measuredOn = LocalDate(2027, 1, 1)), "a later today is capped at the period end")
    }
}
