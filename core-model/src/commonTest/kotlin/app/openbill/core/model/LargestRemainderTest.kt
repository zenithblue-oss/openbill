package app.openbill.core.model

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LargestRemainderTest {
    @Test
    fun splitsProportionallyAndBreaksTiesTowardEarlierIndices() {
        val shares = LargestRemainder.allocate(1_000L, longArrayOf(1L, 1L, 1L))

        assertEquals(listOf(334L, 333L, 333L), shares.toList())
        assertEquals(listOf(334L, 333L, 333L), LargestRemainder.allocate(1_000L, listOf(1L, 1L, 1L)))
        assertEquals(1_000L, shares.sum())
    }

    @Test
    fun assignsLeftoverUnitsByLargestRemainderWithEarlierIndexTies() {
        val shares = LargestRemainder.allocate(10L, longArrayOf(100L, 50L, 50L))

        assertEquals(listOf(5L, 3L, 2L), shares.toList())
        assertEquals(10L, shares.sum())
    }

    @Test
    fun zeroTotalReturnsZeroShares() {
        assertEquals(
            listOf(0L, 0L, 0L),
            LargestRemainder.allocate(0L, longArrayOf(1L, 2L, 3L)).toList(),
        )
        assertEquals(
            listOf(0L, 0L),
            LargestRemainder.allocate(0L, longArrayOf(0L, 5L)).toList(),
        )
    }

    @Test
    fun singleWeightReceivesTheWholeTotal() {
        assertEquals(
            listOf(12_345L),
            LargestRemainder.allocate(12_345L, longArrayOf(1L)).toList(),
        )
        assertEquals(
            listOf(7L),
            LargestRemainder.allocate(7L, longArrayOf(Long.MAX_VALUE)).toList(),
        )
    }

    @Test
    fun zeroWeightsReceiveNothing() {
        assertEquals(
            listOf(0L, 10L, 0L),
            LargestRemainder.allocate(10L, longArrayOf(0L, 1L, 0L)).toList(),
        )
    }

    @Test
    fun extremeWeightsDoNotOverflowIntermediateProducts() {
        val total = Long.MAX_VALUE
        val shares = LargestRemainder.allocate(total, longArrayOf(total - 1L, 1L))

        assertEquals(listOf(total - 1L, 1L), shares.toList())
        assertEquals(total, shares.sum())
    }

    @Test
    fun randomizedAllocationsSumExactlyAndFollowRemainderOrder() {
        val random = Random(20261021)
        repeat(1_000) {
            val size = random.nextInt(1, 9)
            val weights = LongArray(size) { random.nextLong(0L, 100_001L) }
            if (weights.all { it == 0L }) weights[0] = 1L
            val total = random.nextLong(0L, 1_000_000_001L)
            val weightSum = weights.sum()
            val expected = LongArray(size) { total * weights[it] / weightSum }
            val remainders = LongArray(size) { total * weights[it] % weightSum }
            val order = (0 until size).sortedWith(
                compareByDescending<Int> { remainders[it] }.thenBy { it },
            )
            repeat((total - expected.sum()).toInt()) { index ->
                expected[order[index]] += 1L
            }

            val actual = LargestRemainder.allocate(total, weights)

            assertEquals(expected.toList(), actual.toList())
            assertEquals(total, actual.sum())
        }
    }

    @Test
    fun rejectsInvalidInputs() {
        assertFailsWith<IllegalArgumentException> {
            LargestRemainder.allocate(-1L, longArrayOf(1L))
        }
        assertFailsWith<IllegalArgumentException> {
            LargestRemainder.allocate(1L, LongArray(0))
        }
        assertFailsWith<IllegalArgumentException> {
            LargestRemainder.allocate(1L, longArrayOf(0L, 0L))
        }
        assertFailsWith<IllegalArgumentException> {
            LargestRemainder.allocate(0L, longArrayOf(0L, 0L))
        }
        assertFailsWith<IllegalArgumentException> {
            LargestRemainder.allocate(1L, longArrayOf(-1L, 2L))
        }
    }

    @Test
    fun rejectsOverflowingWeightSum() {
        assertFailsWith<ArithmeticException> {
            LargestRemainder.allocate(1L, longArrayOf(Long.MAX_VALUE, Long.MAX_VALUE))
        }
    }
}
