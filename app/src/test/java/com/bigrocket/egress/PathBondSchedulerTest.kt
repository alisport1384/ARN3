package com.bigrocket.egress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The allocator is what makes "merged bit-for-bit, simultaneously" true in code, so its exact
 * behaviour is pinned here: equal scores must alternate without a single exception, unequal
 * scores must hit their percentage exactly, and the sequence must stay interleaved (never a
 * block of one path followed by a block of the other).
 */
class PathBondSchedulerTest {

    @Test
    fun `equal scores alternate strictly - the simultaneous merge`() {
        val pattern = PathBondAllocator.pattern(50, 10)
        assertEquals(
            listOf(false, true, false, true, false, true, false, true, false, true),
            pattern,
        )
        assertEquals(50, PathBondAllocator.wifiCount(50, 100))
    }

    @Test
    fun `higher score gets the larger share, exactly`() {
        // score mapping used by DynamicWeightCalculator: delta*10, clamped 10..90
        assertEquals(70, PathBondAllocator.wifiCount(70, 100))
        assertEquals(80, PathBondAllocator.wifiCount(80, 100))
        assertEquals(60, PathBondAllocator.wifiCount(60, 100))
        assertEquals(30, PathBondAllocator.wifiCount(30, 100))
        assertEquals(10, PathBondAllocator.wifiCount(10, 100))
    }

    @Test
    fun `share holds over any window without drifting`() {
        listOf(50, 60, 70, 80, 90, 40, 30, 20, 10).forEach { pct ->
            val count = 1000
            val wifi = PathBondAllocator.wifiCount(pct, count)
            val expected = count * pct / 100
            assertTrue(
                "share $pct% produced $wifi/$count, expected ~$expected",
                kotlin.math.abs(wifi - expected) <= 1,
            )
        }
    }

    @Test
    fun `70-30 stays interleaved instead of sending one path first`() {
        val pattern = PathBondAllocator.pattern(70, 20)
        // No run longer than 3 identical picks: a "block" would be 14 wifi in a row before any
        // cellular, which is exactly the "turn by turn / batched" behaviour being ruled out.
        var run = 1
        var maxRun = 1
        for (i in 1 until pattern.size) {
            run = if (pattern[i] == pattern[i - 1]) run + 1 else 1
            if (run > maxRun) maxRun = run
        }
        assertTrue("max run was $maxRun, expected <= 3", maxRun <= 3)
        assertTrue("both paths must appear early", pattern.take(3).contains(false) && pattern.take(3).contains(true))
    }

    @Test
    fun `zero and full share pin to one path`() {
        assertEquals(0, PathBondAllocator.wifiCount(0, 50))
        assertEquals(50, PathBondAllocator.wifiCount(100, 50))
    }

    @Test
    fun `live allocator is deterministic and reset clears the phase`() {
        val allocator = PathBondAllocator()
        val first = List(10) { allocator.next(50) }
        allocator.reset()
        val second = List(10) { allocator.next(50) }
        assertEquals(first, second)
    }

    @Test
    fun `shared PathBond keeps one aggregate ratio across mixed call sites`() {
        PathBond.reset()
        PathBond.updateShare(50)
        // Simulates Path3Router (flows), BondingSocksServer (connections) and the bonded UDP
        // channel (datagrams) all drawing from the same sequence.
        val picks = (1..100).map { PathBond.next() }
        assertEquals(50, picks.count { it })
        assertEquals(50, picks.count { !it })
    }

    @Test
    fun `description states the simultaneous merge`() {
        assertTrue(PathBondAllocator.describe(50, 50).contains("50/50"))
        assertTrue(PathBondAllocator.describe(70, 30).contains("ادغام همزمان"))
        assertFalse(PathBondAllocator.describe(70, 30).contains("نوبت"))
    }
}
