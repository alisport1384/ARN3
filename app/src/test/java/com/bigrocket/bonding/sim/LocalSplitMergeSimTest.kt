package com.bigrocket.bonding.sim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The point of these tests is the claim that made two engines worth building: ONE byte stream,
 * split across two modelled paths, reassembled in order and byte-for-byte identical - including
 * when a path loses frames (NACK + resend) and when frames arrive out of order (reorder buffer).
 */
class LocalSplitMergeSimTest {

    private fun cfg(
        loss: Int = 0,
        wifiPercent: Int = 50,
        wifiKbps: Int = 40_000,
        cellKbps: Int = 15_000,
        bytes: Int = 300_000,
        wifiLoss: Int = loss,
        cellLoss: Int = loss,
        hardSkipMs: Long = 1_500,
    ) = SimConfig(
        bytes = bytes,
        wifiPercent = wifiPercent,
        hardSkipMs = hardSkipMs,
        wifi = PathProfile("wifi", wifiKbps, 20, 5, wifiLoss),
        cellular = PathProfile("cellular", cellKbps, 45, 10, cellLoss),
    )

    @Test
    fun `clean paths reassemble byte-exact`() {
        val r = LocalDuplexSimulation.run(cfg())
        assertTrue(r.summary(), r.byteExact && r.completed)
        assertEquals(0, r.skippedGaps)
        assertEquals(300_000, r.deliveredBytes)
    }

    @Test
    fun `equal score splits strictly alternately`() {
        val r = LocalDuplexSimulation.run(cfg())
        // Same allocator semantics as the production PathBondAllocator: the first unit at 50/50
        // goes to the secondary path, then the two strictly alternate - never two equal picks
        // in a row, never a block of one path.
        assertEquals("C,W,C,W,C,W,C,W,C,W,C,W,C,W,C,W,C,W,C,W", r.interleave)
        // Byte shares follow the frame shares (modulo MTU rounding).
        assertTrue("wifi=${r.wifiBytes} cell=${r.cellularBytes}", kotlin.math.abs(r.wifiBytes - r.cellularBytes) <= 1200)
    }

    @Test
    fun `unequal score follows the score percentage`() {
        val r = LocalDuplexSimulation.run(cfg(wifiPercent = 75))
        val pct = r.wifiBytes * 100.0 / (r.wifiBytes + r.cellularBytes)
        assertTrue("wifi share was $pct%", kotlin.math.abs(pct - 75.0) < 2.0)
        assertTrue(r.byteExact)
    }

    @Test
    fun `lossy paths still deliver byte-exact thanks to resends`() {
        val r = LocalDuplexSimulation.run(cfg(wifiLoss = 8, cellLoss = 8))
        assertTrue(r.summary(), r.byteExact && r.completed)
        assertTrue("expected retransmits, got ${r.retransmits}", r.retransmits > 0)
        assertEquals(0, r.skippedGaps)
    }

    @Test
    fun `reordering is absorbed by the buffer`() {
        // Heavy jitter on both paths => frames overtake each other constantly.
        val r = LocalDuplexSimulation.run(
            SimConfig(
                bytes = 200_000,
                wifi = PathProfile("wifi", 20_000, 30, 120, 0),
                cellular = PathProfile("cellular", 20_000, 40, 150, 0),
                wifiPercent = 50,
            ),
        )
        assertTrue(r.summary(), r.byteExact)
        assertTrue("max depth was ${r.maxReorderDepth}", r.maxReorderDepth > 1)
    }

    @Test
    fun `bonded goodput beats either single path`() {
        val bonded = LocalDuplexSimulation.run(cfg(bytes = 2 * 1024 * 1024))
        val wifiOnly = LocalDuplexSimulation.runSinglePath(cfg(bytes = 2 * 1024 * 1024), true)
        val cellOnly = LocalDuplexSimulation.runSinglePath(cfg(bytes = 2 * 1024 * 1024), false)
        assertTrue(
            "bonded=${bonded.goodputKbps} wifi=${wifiOnly.goodputKbps} cell=${cellOnly.goodputKbps}",
            bonded.goodputKbps > wifiOnly.goodputKbps && bonded.goodputKbps > cellOnly.goodputKbps,
        )
    }

    @Test
    fun `dead path is detected and the gap is skipped instead of hanging`() {
        // Cellular is 100% loss and RTO gives up quickly: the joiner must not wait forever.
        val r = LocalDuplexSimulation.run(
            SimConfig(
                bytes = 100_000,
                hardSkipMs = 300,
                wifi = PathProfile("wifi", 40_000, 20, 0, 0),
                cellular = PathProfile("cellular", 15_000, 45, 0, 100),
                wifiPercent = 50,
            ),
        )
        assertTrue("simulation hung", r.completed)
        assertTrue("expected skipped gaps, got ${r.skippedGaps}", r.skippedGaps > 0)
        assertFalse("data cannot be exact when a path is dead", r.byteExact)
    }

    @Test
    fun `same seed reproduces the same run`() {
        val a = LocalDuplexSimulation.run(cfg(wifiLoss = 5, cellLoss = 5))
        val b = LocalDuplexSimulation.run(cfg(wifiLoss = 5, cellLoss = 5))
        assertEquals(a.goodputKbps, b.goodputKbps)
        assertEquals(a.retransmits, b.retransmits)
        assertEquals(a.interleave, b.interleave)
    }
}
