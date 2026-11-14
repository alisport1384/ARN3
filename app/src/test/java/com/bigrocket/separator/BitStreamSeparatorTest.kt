package com.bigrocket.separator

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/**
 * Bit-exactness of the separator itself, pinned down.
 *
 * The package's whole claim is "one stream, separated bit-by-bit onto two radios, reassembled
 * without losing a single bit". Every test here asserts that claim literally: same bytes back,
 * same order, exact bit counts per path, and a loud failure when a single bit is flipped.
 */
class BitStreamSeparatorTest {

    private fun payload(bytes: Int, seed: Long = 0xC0FFEEL): ByteArray {
        val rng = Random(seed)
        return ByteArray(bytes) { rng.nextInt(256).toByte() }
    }

    // --- the core round trip -------------------------------------------------------------

    @Test
    fun `50 50 round trip is bit exact and splits bits evenly`() {
        val data = payload(4096)
        val pair = BitStreamSeparator.separateChunk(streamId = 7, chunkSeq = 0, data = data, wifiPercent = 50)

        assertEquals(4096 * 8, pair.totalBits)
        assertEquals(4096 * 4, pair.wifiBits)
        assertEquals(4096 * 4, pair.cellularBits)
        assertArrayEquals(data, BitStreamSeparator.mergePair(pair.wifiSlice, pair.cellularSlice))
    }

    @Test
    fun `50 50 carries only half the bytes on each physical path`() {
        val data = payload(8192)
        val pair = BitStreamSeparator.separateChunk(1, 0, data, wifiPercent = 50)
        // Each radio physically transmits (bits + 7) / 8 bytes - half the payload each.
        assertEquals(4096, pair.wifiSlice.packedBits.size)
        assertEquals(4096, pair.cellularSlice.packedBits.size)
        assertEquals(BitStreamSeparator.HEADER_BYTES, 30)
    }

    @Test
    fun `every share ratio from 1 to 99 round trips`() {
        val data = payload(1024)
        for (pct in 1..99) {
            val pair = BitStreamSeparator.separateChunk(streamId = 11, chunkSeq = pct.toLong(), data = data, wifiPercent = pct)
            assertEquals("ratio $pct must keep every bit", data.size * 8, pair.totalBits)
            assertArrayEquals("ratio $pct", data, BitStreamSeparator.mergePair(pair.wifiSlice, pair.cellularSlice))
        }
    }

    @Test
    fun `70 30 puts exactly 70 percent of the bits on wi-fi`() {
        val data = payload(2000)
        val pair = BitStreamSeparator.separateChunk(3, 0, data, wifiPercent = 70)
        val total = 2000 * 8
        assertEquals((total * 70L / 100L).toInt(), pair.wifiBits)
        assertEquals(total - (total * 70L / 100L).toInt(), pair.cellularBits)
        assertArrayEquals(data, BitStreamSeparator.mergePair(pair.wifiSlice, pair.cellularSlice))
    }

    @Test
    fun `1 99 still moves real bits on both radios`() {
        val data = payload(1000)
        val pair = BitStreamSeparator.separateChunk(5, 0, data, wifiPercent = 1)
        assertTrue("wi-fi must still carry bits at 1%", pair.wifiBits > 0)
        assertEquals(1000 * 8 - pair.wifiBits, pair.cellularBits)
        assertArrayEquals(data, BitStreamSeparator.mergePair(pair.wifiSlice, pair.cellularSlice))
    }

    @Test
    fun `100 0 and 0 100 degrade to single path without losing bytes`() {
        val data = payload(777)
        val allWifi = BitStreamSeparator.separateChunk(9, 0, data, wifiPercent = 100)
        assertEquals(0, allWifi.cellularBits)
        assertEquals(data.size, allWifi.wifiSlice.packedBits.size)
        assertArrayEquals(data, BitStreamSeparator.mergePair(allWifi.wifiSlice, allWifi.cellularSlice))

        val allCell = BitStreamSeparator.separateChunk(9, 1, data, wifiPercent = 0)
        assertEquals(0, allCell.wifiBits)
        assertEquals(data.size, allCell.cellularSlice.packedBits.size)
        assertArrayEquals(data, BitStreamSeparator.mergePair(allCell.wifiSlice, allCell.cellularSlice))
    }

    @Test
    fun `empty chunk round trips as empty`() {
        val pair = BitStreamSeparator.separateChunk(1, 0, ByteArray(0), wifiPercent = 50)
        assertEquals(0, pair.totalBits)
        assertArrayEquals(ByteArray(0), BitStreamSeparator.mergePair(pair.wifiSlice, pair.cellularSlice))
    }

    @Test
    fun `countSeparatedBits matches the real split at every ratio`() {
        for (pct in 0..100) {
            val (wifi, cell) = BitStreamSeparator.countSeparatedBits(1234, pct)
            val pair = BitStreamSeparator.separateChunk(1, 0, payload(1234), wifiPercent = pct)
            assertEquals("wifi bits at $pct", wifi, pair.wifiBits.toLong())
            assertEquals("cellular bits at $pct", cell, pair.cellularBits.toLong())
            assertEquals(1234L * 8L, wifi + cell)
        }
    }

    @Test
    fun `bit pattern preview is exactly what the scheduler emits`() {
        assertEquals("C,W,C,W,C,W,C,W", BitStreamSeparator.previewBitPattern(50, 8))
        // 70%: acc starts at 0 and gains 70 per bit, so the FIRST bit still goes to cellular
        // and every crossing of 100 goes to wi-fi - the exact Bresenham sequence.
        assertEquals("C,W,W,C,W,W,C,W", BitStreamSeparator.previewBitPattern(70, 8))
        // 0% and 100% are pure single-path patterns.
        assertEquals("C,C,C,C", BitStreamSeparator.previewBitPattern(0, 4))
        assertEquals("W,W,W,W", BitStreamSeparator.previewBitPattern(100, 4))
    }

    // --- the BSB1 wire format ------------------------------------------------------------

    @Test
    fun `BSB1 encode and decode preserve every header field`() {
        val data = payload(300)
        val pair = BitStreamSeparator.separateChunk(streamId = 0x425253, chunkSeq = 42L, data = data, wifiPercent = 65)
        val wire = pair.wifiSlice.encode()
        assertEquals(BitStreamSeparator.HEADER_BYTES + pair.wifiSlice.packedBits.size, wire.size)

        val decoded = BitStreamSeparator.BitSlicePacket.decode(wire)
        assertEquals(0x425253, decoded.header.streamId)
        assertEquals(42L, decoded.header.chunkSeq)
        assertEquals(300, decoded.header.originalByteCount)
        assertEquals(65, decoded.header.wifiPercent)
        assertEquals(BitStreamSeparator.PATH_WIFI, decoded.header.pathId)
        assertEquals(pair.wifiSlice.header.bitCount, decoded.header.bitCount)
        assertEquals(pair.wifiSlice.header.crc32, decoded.header.crc32)
        assertArrayEquals(pair.wifiSlice.packedBits, decoded.packedBits)
    }

    @Test
    fun `BSB1 frames survive the wire round trip for both paths`() {
        val data = payload(1500)
        val pair = BitStreamSeparator.separateChunk(2, 3, data, wifiPercent = 40)
        val wifi = BitStreamSeparator.BitSlicePacket.decode(pair.wifiSlice.encode())
        val cell = BitStreamSeparator.BitSlicePacket.decode(pair.cellularSlice.encode())
        assertArrayEquals(data, BitStreamSeparator.mergePair(wifi, cell))
    }

    @Test
    fun `a frame with the wrong magic is rejected`() {
        val pair = BitStreamSeparator.separateChunk(1, 0, payload(64), wifiPercent = 50)
        val wire = pair.wifiSlice.encode()
        wire[0] = 0x00
        try {
            BitStreamSeparator.BitSlicePacket.decode(wire)
            fail("expected BitIntegrityException for bad magic")
        } catch (expected: BitStreamSeparator.BitIntegrityException) {
            assertTrue(expected.message!!.contains("magic"))
        }
    }

    @Test
    fun `a truncated frame is rejected`() {
        val pair = BitStreamSeparator.separateChunk(1, 0, payload(64), wifiPercent = 50)
        val wire = pair.wifiSlice.encode()
        try {
            BitStreamSeparator.BitSlicePacket.decode(wire.copyOf(wire.size - 1))
            fail("expected BitIntegrityException for truncated frame")
        } catch (expected: BitStreamSeparator.BitIntegrityException) {
            // Either "Truncated" or "length mismatch" is a correct rejection.
            assertNotNull(expected.message)
        }
    }

    // --- integrity: one flipped bit must never be delivered ------------------------------

    @Test
    fun `a single flipped payload bit is caught by CRC32`() {
        val data = payload(1024)
        val pair = BitStreamSeparator.separateChunk(1, 0, data, wifiPercent = 50)
        // Flip the lowest bit of the first packed wi-fi byte: one bit out of 8192.
        val corrupted = BitStreamSeparator.BitSlicePacket(
            pair.wifiSlice.header,
            pair.wifiSlice.packedBits.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() },
        )
        try {
            BitStreamSeparator.mergePair(corrupted, pair.cellularSlice)
            fail("a flipped bit must be rejected, not delivered")
        } catch (expected: BitStreamSeparator.BitIntegrityException) {
            assertTrue(expected.message!!.contains("CRC32"))
        }
    }

    @Test
    fun `mismatched partners are rejected instead of splicing wrong bytes`() {
        val a = BitStreamSeparator.separateChunk(1, 0, payload(200, seed = 1), wifiPercent = 50)
        val b = BitStreamSeparator.separateChunk(1, 1, payload(200, seed = 2), wifiPercent = 50)
        try {
            BitStreamSeparator.mergePair(a.wifiSlice, b.cellularSlice)
            fail("chunks with different seq must not merge")
        } catch (expected: BitStreamSeparator.BitIntegrityException) {
            assertNotNull(expected.message)
        }
    }

    @Test
    fun `different stream ids are rejected`() {
        val a = BitStreamSeparator.separateChunk(1, 0, payload(120, seed = 3), wifiPercent = 50)
        val b = BitStreamSeparator.separateChunk(2, 0, payload(120, seed = 3), wifiPercent = 50)
        try {
            BitStreamSeparator.mergePair(a.wifiSlice, b.cellularSlice)
            fail("different stream ids must not merge")
        } catch (expected: BitStreamSeparator.BitIntegrityException) {
            assertNotNull(expected.message)
        }
    }

    // --- whole stream --------------------------------------------------------------------

    @Test
    fun `separateStream chunks the payload and every chunk round trips`() {
        val data = payload(50_000, seed = 99)
        val chunks = BitStreamSeparator.separateStream(5, data, wifiPercent = 60, chunkBytes = 4096)
        assertEquals(13, chunks.size)
        val merged = java.io.ByteArrayOutputStream()
        chunks.forEach { merged.write(BitStreamSeparator.mergePair(it.wifiSlice, it.cellularSlice)) }
        assertArrayEquals(data, merged.toByteArray())
        assertEquals(0L, chunks.first().wifiSlice.header.chunkSeq)
        assertEquals(12L, chunks.last().wifiSlice.header.chunkSeq)
    }

    // --- the reassembler ----------------------------------------------------------------

    @Test
    fun `merger delivers out-of-order arrivals strictly in sequence`() {
        val data = payload(8 * 100, seed = 4)
        val chunks = BitStreamSeparator.separateStream(3, data, wifiPercent = 50, chunkBytes = 100)
        val merger = BitStreamSeparator.WindowedBitStreamMerger(streamId = 3)

        // Push in a shuffled order: all wifi halves first (reversed), then cellular halves.
        val delivered = ArrayList<ByteArray>()
        chunks.reversed().forEach { delivered += merger.push(it.wifiSlice) }
        chunks.forEach { delivered += merger.push(it.cellularSlice) }

        assertArrayEquals(data, delivered.fold(java.io.ByteArrayOutputStream()) { acc, bytes ->
            acc.write(bytes); acc
        }.toByteArray())
        assertEquals(8L, merger.mergedChunks)
        assertEquals(8L, merger.expectedSeq)
        assertEquals(800L, merger.mergedBytes)
    }

    @Test
    fun `merger drops duplicates and late slices without corrupting output`() {
        val data = payload(300, seed = 5)
        val chunks = BitStreamSeparator.separateStream(4, data, wifiPercent = 50, chunkBytes = 100)
        val merger = BitStreamSeparator.WindowedBitStreamMerger(4)
        val out = java.io.ByteArrayOutputStream()

        chunks.forEach { pair ->
            merger.push(pair.wifiSlice).forEach { out.write(it) }
            merger.push(pair.cellularSlice).forEach { out.write(it) }
            // Replay both halves: must be counted and dropped, never re-emitted.
            merger.push(pair.wifiSlice).forEach { out.write(it) }
            merger.push(pair.cellularSlice).forEach { out.write(it) }
        }
        assertArrayEquals(data, out.toByteArray())
        assertEquals(6L, merger.droppedDuplicateOrLate)
        assertEquals(3L, merger.mergedChunks)
    }

    @Test
    fun `merger refuses slices beyond its window instead of growing without bound`() {
        val chunks = BitStreamSeparator.separateStream(6, payload(8 * 100, seed = 6), 50, chunkBytes = 100)
        val merger = BitStreamSeparator.WindowedBitStreamMerger(6, maxWindowChunks = 4)
        assertEquals(0, merger.push(chunks[0].wifiSlice).size)
        // Chunk 9 is far beyond expected(0) + window(4).
        assertEquals(0, merger.push(chunks[7].cellularSlice).size)
        assertTrue(merger.droppedBeyondWindow > 0)
        assertEquals(1L, merger.droppedBeyondWindow)
    }

    @Test
    fun `merger fires the missing-partner callback after the soft timeout`() {
        val chunks = BitStreamSeparator.separateStream(8, payload(200, seed = 7), 50, chunkBytes = 100)
        val missing = ArrayList<Pair<Long, Byte>>()
        val merger = BitStreamSeparator.WindowedBitStreamMerger(
            streamId = 8,
            softTimeoutMs = 100L,
            onMissingPartner = { seq, path -> missing.add(seq to path) },
        )
        // Only the wi-fi half arrives.
        merger.push(chunks[0].wifiSlice, nowMs = 1_000L)
        merger.pollTimeouts(nowMs = 1_050L)
        assertTrue("must not NACK before the timeout", missing.isEmpty())
        merger.pollTimeouts(nowMs = 1_101L)
        assertEquals(listOf(0L to BitStreamSeparator.PATH_CELLULAR), missing)
        // A second poll must not spam the same NACK.
        merger.pollTimeouts(nowMs = 1_500L)
        assertEquals(1, missing.size)
    }

    @Test
    fun `merger accepts the single-path 100 percent halves on their own`() {
        val data = payload(500, seed = 8)
        val chunks = BitStreamSeparator.separateStream(9, data, wifiPercent = 100, chunkBytes = 250)
        val merger = BitStreamSeparator.WindowedBitStreamMerger(9)
        val out = java.io.ByteArrayOutputStream()
        chunks.forEach { merger.push(it.wifiSlice).forEach { b -> out.write(b) } }
        assertArrayEquals(data, out.toByteArray())
        assertEquals(0L, merger.cellularBitsMerged)
        assertEquals(500L * 8L, merger.wifiBitsMerged)
    }

    @Test
    fun `a merger bound to another stream rejects foreign slices`() {
        val chunks = BitStreamSeparator.separateStream(10, payload(100, seed = 9), 50, chunkBytes = 100)
        val merger = BitStreamSeparator.WindowedBitStreamMerger(11)
        try {
            merger.push(chunks[0].wifiSlice)
            fail("expected BitIntegrityException for a foreign stream id")
        } catch (expected: BitStreamSeparator.BitIntegrityException) {
            assertTrue(expected.message!!.contains("streamId"))
        }
    }

    // --- random heavy stress --------------------------------------------------------------

    @Test
    fun `heavy random out-of-order traffic across both paths reassembles perfectly`() {
        val rng = Random(0x5E9A2026L)
        val data = payload(64 * 1024, seed = 10)
        val chunks = BitStreamSeparator.separateStream(12, data, wifiPercent = 63, chunkBytes = 2048)
        assertEquals(32, chunks.size)

        // Build one big arrival list, shuffle it, then deliver - simulating two radios
        // racing each other with completely arbitrary interleaving.
        val arrivals = ArrayList<BitStreamSeparator.BitSlicePacket>(chunks.size * 2)
        chunks.forEach {
            arrivals.add(it.wifiSlice)
            arrivals.add(it.cellularSlice)
        }
        val merger = BitStreamSeparator.WindowedBitStreamMerger(12)
        val out = java.io.ByteArrayOutputStream()
        arrivals.shuffled(rng).forEach { slice ->
            merger.push(slice, nowMs = 1_000L).forEach { out.write(it) }
        }
        assertArrayEquals(data, out.toByteArray())
        assertEquals(32L, merger.mergedChunks)
        assertTrue(merger.maxReorderDepth > 1)
        assertNull(null)
    }
}
