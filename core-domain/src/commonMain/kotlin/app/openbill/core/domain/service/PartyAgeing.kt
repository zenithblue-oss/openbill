package app.openbill.core.domain.service

import app.openbill.core.domain.model.PartyMovement
import app.openbill.core.model.Money
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil

/**
 * Signed ageing buckets (positive = receivable, negative = payable/advance). They always sum
 * to the party's closing balance, so ageing reconciles with the ledger by construction.
 */
data class AgeingBuckets(
    val upTo30: Money = Money.ZERO,
    val days31To60: Money = Money.ZERO,
    val days61To90: Money = Money.ZERO,
    val over90: Money = Money.ZERO,
) {
    val total: Money get() = upTo30 + days31To60 + days61To90 + over90

    operator fun plus(o: AgeingBuckets) = AgeingBuckets(
        upTo30 + o.upTo30, days31To60 + o.days31To60, days61To90 + o.days61To90, over90 + o.over90,
    )

    fun asList(): List<Pair<String, Money>> =
        listOf("0-30" to upTo30, "31-60" to days31To60, "61-90" to days61To90, "90+" to over90)
}

object PartyAgeing {
    /**
     * FIFO ageing of a party's ledger: movements of the opposite sign settle the oldest open
     * amounts first. [opening] (party master opening balance) counts as the oldest item.
     */
    fun of(opening: Money, movements: List<PartyMovement>, asOf: LocalDate): AgeingBuckets {
        val open = ArrayDeque<Pair<LocalDate?, Long>>()
        fun apply(date: LocalDate?, amount: Long) {
            var left = amount
            while (left != 0L && open.isNotEmpty() && (open.first().second > 0) != (left > 0)) {
                val (d, head) = open.removeFirst()
                val settled = minOf(kotlin.math.abs(head), kotlin.math.abs(left))
                val headLeft = head - settled * sign(head)
                left -= settled * sign(left)
                if (headLeft != 0L) open.addFirst(d to headLeft)
            }
            if (left != 0L) open.addLast(date to left)
        }
        if (opening != Money.ZERO) apply(null, opening.paise)
        movements.filter { it.date <= asOf && it.net != Money.ZERO }
            .sortedBy { it.date }
            .forEach { apply(it.date, it.net.paise) }
        var b = AgeingBuckets()
        for ((date, amount) in open) {
            val m = Money.of(amount)
            val days = date?.daysUntil(asOf) ?: Int.MAX_VALUE
            b = when {
                days <= 30 -> b.copy(upTo30 = b.upTo30 + m)
                days <= 60 -> b.copy(days31To60 = b.days31To60 + m)
                days <= 90 -> b.copy(days61To90 = b.days61To90 + m)
                else -> b.copy(over90 = b.over90 + m)
            }
        }
        return b
    }

    private fun sign(v: Long): Long = if (v < 0) -1L else 1L
}
