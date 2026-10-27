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
}
