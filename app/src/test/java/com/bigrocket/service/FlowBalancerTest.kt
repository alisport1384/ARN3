package com.bigrocket.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The balancer is what turns "two radios" into real aggregation, so its decisions are pinned:
 * with no measurements the user's scores decide; with measurements the faster radio gets more
 * new flows; a radio that is not moving data is not abandoned entirely.
 */
class FlowBalancerTest {

    @Test
    fun `no measurements means the scores decide`() {
        assertEquals(50, FlowBalancer.effectiveWifiPercent(50, 0, 0))
        assertEquals(70, FlowBalancer.effectiveWifiPercent(70, 0, 0))
        assertEquals(30, FlowBalancer.effectiveWifiPercent(30, 0, 0))
    }

    @Test
    fun `pinned scores are never overridden`() {
        assertEquals(0, FlowBalancer.effectiveWifiPercent(0, 50_000, 10_000))
        assertEquals(100, FlowBalancer.effectiveWifiPercent(100, 1_000, 80_000))
    }

    @Test
    fun `faster radio gets more new flows`() {
        // Wi-Fi 40 Mbit vs LTE 5 Mbit, equal scores: the split must move towards capacity.
        val fast = FlowBalancer.effectiveWifiPercent(50, 40_000, 5_000)
        assertTrue("expected a Wi-Fi leaning split, got $fast", fast > 65)
        val slow = FlowBalancer.effectiveWifiPercent(50, 5_000, 40_000)
        assertTrue("expected a cellular leaning split, got $slow", slow < 35)
    }

    @Test
    fun `silent radio is not abandoned completely`() {
        // Wi-Fi measured ~0 (e.g. just came back): it keeps a small share so it gets measured.
        val pct = FlowBalancer.effectiveWifiPercent(50, 0, 20_000)
        assertEquals(15, pct)
        val other = FlowBalancer.effectiveWifiPercent(50, 20_000, 0)
        assertEquals(85, other)
    }

    @Test
    fun `share stays inside a sane bound`() {
        listOf(5, 30, 50, 70, 95).forEach { score ->
            val pct = FlowBalancer.effectiveWifiPercent(score, 100_000, 1_000)
            assertTrue("score $score -> $pct", pct in 5..95)
        }
    }

    @Test
    fun `in-flight correction keeps equal scores alternating`() {
        var wifiFlows = 0
        var cellFlows = 0
        val picks = ArrayList<Boolean>()
        val allocator = com.bigrocket.egress.PathBondAllocator()
        repeat(20) {
            val overWifi = FlowBalancer.shouldUseWifi(
                wifiPercent = 50,
                activeWifiFlows = wifiFlows,
                activeCellFlows = cellFlows,
                allocatorSaysWifi = allocator.next(50),
            )
            picks.add(overWifi)
            if (overWifi) wifiFlows++ else cellFlows++
        }
        assertEquals(10, wifiFlows)
        assertEquals(10, cellFlows)
        // strictly alternating, never two same-side picks in a row
        for (i in 1 until picks.size) {
            assertTrue("two consecutive picks on the same path: $picks", picks[i] != picks[i - 1])
        }
    }

    @Test
    fun `correction overrides the allocator when one side is already crowded`() {
        // Allocator says Wi-Fi, but Wi-Fi already holds every live flow: the new one must not
        // pile up behind it, otherwise real aggregation stalls at one saturated radio.
        val overWifi = FlowBalancer.shouldUseWifi(
            wifiPercent = 50,
            activeWifiFlows = 10,
            activeCellFlows = 0,
            allocatorSaysWifi = true,
        )
        assertFalse(overWifi)
    }

    @Test
    fun `traffic counters measure real bytes`() {
        PathTrafficStats.reset()
        PathTrafficStats.flowOpened(PathLabels.WIFI)
        PathTrafficStats.flowOpened(PathLabels.WIFI)
        PathTrafficStats.flowOpened(PathLabels.CELLULAR)
        val start = 1_000L
        PathTrafficStats.recordBytes(PathLabels.WIFI, 1_000_000, start)
        PathTrafficStats.recordBytes(PathLabels.CELLULAR, 250_000, start)
        val samples = PathTrafficStats.tick(start + 1_000)
        val wifi = samples.first { it.label == PathLabels.WIFI }
        val cell = samples.first { it.label == PathLabels.CELLULAR }
        assertEquals(2, wifi.activeFlows)
        assertEquals(1, cell.activeFlows)
        assertEquals(1_000_000L, wifi.totalBytes)
        assertEquals(8_000, wifi.kbpsNow)
        assertEquals(2_000, cell.kbpsNow)
        PathTrafficStats.flowClosed(PathLabels.WIFI)
        assertEquals(1, PathTrafficStats.tick(start + 2_000).first { it.label == PathLabels.WIFI }.activeFlows)
        PathTrafficStats.reset()
    }
}
