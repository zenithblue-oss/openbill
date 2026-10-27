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

/** Bucketed sums of a party's positive and of its negative movements (opening excluded): the input of [PartyAgeing.fromSums]. */
data class AgeingSums(val positive: AgeingBuckets = AgeingBuckets(), val negative: AgeingBuckets = AgeingBuckets())

object PartyAgeing {
    /** Bucket sums of [movements] dated through [asOf], for [fromSums]. */
    fun sums(movements: List<PartyMovement>, asOf: LocalDate): AgeingSums {
        var pos = AgeingBuckets()
        var neg = AgeingBuckets()
        movements.filter { it.date <= asOf }.forEach { m ->
            val b = bucket(m.date.daysUntil(asOf), m.net)
            if (m.net > Money.ZERO) pos += b else neg += b
        }
        return AgeingSums(pos, neg)
    }

    /**
     * Same result as [of] without walking every movement: FIFO settlement always leaves the
     * newest amounts of the balance's sign open, so the balance is spread over that sign's
     * bucket sums, newest bucket first ([opening] is the oldest item).
     */
    fun fromSums(opening: Money, sums: AgeingSums): AgeingBuckets {
        val withOpening = { b: AgeingBuckets, keep: Boolean -> if (keep) b.copy(over90 = b.over90 + opening) else b }
        val pos = withOpening(sums.positive, opening > Money.ZERO)
        val neg = withOpening(sums.negative, opening < Money.ZERO)
        val balance = pos.total + neg.total
        if (balance == Money.ZERO) return AgeingBuckets()
        val from = if (balance > Money.ZERO) pos else neg
        val sign = if (balance > Money.ZERO) 1L else -1L
        var left = balance.paise * sign
        fun take(m: Money): Money {
            val t = minOf(m.paise * sign, left)
            left -= t
            return Money.of(t * sign)
        }
        return AgeingBuckets(take(from.upTo30), take(from.days31To60), take(from.days61To90), take(from.over90))
    }

    private fun bucket(days: Int, m: Money) = when {
        days <= 30 -> AgeingBuckets(upTo30 = m)
        days <= 60 -> AgeingBuckets(days31To60 = m)
        days <= 90 -> AgeingBuckets(days61To90 = m)
        else -> AgeingBuckets(over90 = m)
    }

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
