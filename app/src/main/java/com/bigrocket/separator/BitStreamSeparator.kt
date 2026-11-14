package com.bigrocket.separator

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.TreeMap
import java.util.zip.CRC32

/**
 * Virtual Bit-by-Bit Separator & Merger Engine ("موتور جداکننده مجازی بیت‌به‌بیت").
 *
 * Implements genuine bit-level inverse multiplexing (IMUX) and bit-exact reassembly:
 *  - Every byte of an input chunk (MSB-first, bits 7..0) is walked bit-by-bit through a
 *    deterministic Bresenham error-diffusion accumulator driven by [wifiPercent] (0..100).
 *  - At 50/50, bit 0 goes to Path 2 (Cellular), bit 1 goes to Path 1 (Wi-Fi), bit 2 to Cellular,
 *    bit 3 to Wi-Fi ("C,W,C,W,...") — matching [com.bigrocket.egress.PathBondAllocator] down to
 *    the individual bit.
 *  - Each path physically carries ONLY its own packed bits (`(bitCount + 7) / 8` bytes), stamped
 *    with a 30-byte `BSB1` wire header including [wifiPercent] (so dynamic mid-stream score
 *    changes never desynchronize bit alignment) and the original chunk's [CRC32].
 *  - [mergePair] and [WindowedBitStreamMerger] recombine the two bit-streams bit-by-bit in exact
 *    inverse order and verify CRC32 integrity before delivering bytes upstream.
 */
object BitStreamSeparator {

    const val MAGIC_BSB1: Int = 0x42534231 // "BSB1"
    const val HEADER_BYTES: Int = 30
    const val PATH_WIFI: Byte = 0
    const val PATH_CELLULAR: Byte = 1
    const val DEFAULT_CHUNK_BYTES: Int = 16 * 1024

    class BitIntegrityException(message: String) : IllegalStateException(message)

    data class BitSliceHeader(
        val streamId: Int,
        val chunkSeq: Long,
        val originalByteCount: Int,
        val wifiPercent: Int,
        val pathId: Byte,
        val bitCount: Int,
        val crc32: Int,
    ) {
        init {
            require(originalByteCount >= 0) { "Negative originalByteCount: $originalByteCount" }
            require(wifiPercent in 0..100) { "wifiPercent out of 0..100: $wifiPercent" }
            require(pathId == PATH_WIFI || pathId == PATH_CELLULAR) { "Invalid pathId: $pathId" }
            require(bitCount >= 0) { "Negative bitCount: $bitCount" }
        }
    }

    data class BitSlicePacket(
        val header: BitSliceHeader,
        val packedBits: ByteArray,
    ) {
        fun encode(): ByteArray {
            val expectedPackedBytes = (header.bitCount + 7) ushr 3
            require(packedBits.size == expectedPackedBytes) {
                "packedBits.size=${packedBits.size} != expected=$expectedPackedBytes for bitCount=${header.bitCount}"
            }
            val out = ByteArray(HEADER_BYTES + packedBits.size)
            val buf = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN)
            buf.putInt(MAGIC_BSB1)
            buf.putInt(header.streamId)
            buf.putLong(header.chunkSeq)
            buf.putInt(header.originalByteCount)
            buf.put(header.wifiPercent.toByte())
            buf.put(header.pathId)
            buf.putInt(header.bitCount)
            buf.putInt(header.crc32)
            System.arraycopy(packedBits, 0, out, HEADER_BYTES, packedBits.size)
            return out
        }

        companion object {
            fun decode(wire: ByteArray, offset: Int = 0, length: Int = wire.size - offset): BitSlicePacket {
                if (length < HEADER_BYTES) {
                    throw BitIntegrityException("Truncated BSB1 frame: length=$length < $HEADER_BYTES")
                }
                val buf = ByteBuffer.wrap(wire, offset, length).order(ByteOrder.BIG_ENDIAN)
                val magic = buf.int
                if (magic != MAGIC_BSB1) {
                    throw BitIntegrityException("Invalid BSB1 magic: 0x${Integer.toHexString(magic)}")
                }
                val streamId = buf.int
                val chunkSeq = buf.long
                val originalByteCount = buf.int
                val wifiPercent = buf.get().toInt() and 0xFF
                val pathId = buf.get()
                val bitCount = buf.int
                val crc32 = buf.int
                if (originalByteCount < 0 || wifiPercent > 100 || bitCount < 0) {
                    throw BitIntegrityException("Malformed BSB1 header fields")
                }
                if (pathId != PATH_WIFI && pathId != PATH_CELLULAR) {
                    throw BitIntegrityException("Unknown BSB1 pathId=$pathId")
                }
                val packedLen = (bitCount + 7) ushr 3
                if (length != HEADER_BYTES + packedLen) {
                    throw BitIntegrityException(
                        "BSB1 payload length mismatch: wire=$length expected=${HEADER_BYTES + packedLen}"
                    )
                }
                val payload = wire.copyOfRange(offset + HEADER_BYTES, offset + length)
                return BitSlicePacket(
                    header = BitSliceHeader(
                        streamId = streamId,
                        chunkSeq = chunkSeq,
                        originalByteCount = originalByteCount,
                        wifiPercent = wifiPercent,
                        pathId = pathId,
                        bitCount = bitCount,
                        crc32 = crc32,
                    ),
                    packedBits = payload,
                )
            }
        }
    }

    data class SeparatedBitPair(
        val wifiSlice: BitSlicePacket,
        val cellularSlice: BitSlicePacket,
    ) {
        val totalBits: Int get() = wifiSlice.header.bitCount + cellularSlice.header.bitCount
        val wifiBits: Int get() = wifiSlice.header.bitCount
        val cellularBits: Int get() = cellularSlice.header.bitCount
    }

    /**
     * Returns the exact bit-routing preview string (e.g. `"C,W,C,W,C,W,..."` at 50/50)
     * for the first [sampleBits] bits under [wifiPercent].
     */
    fun previewBitPattern(wifiPercent: Int, sampleBits: Int = 16): String {
        val share = wifiPercent.coerceIn(0, 100)
        var acc = 0
        val picks = ArrayList<String>(sampleBits)
        repeat(sampleBits.coerceAtLeast(0)) {
            val isWifi = when {
                share <= 0 -> false
                share >= 100 -> true
                else -> {
                    acc += share
                    if (acc >= 100) {
                        acc -= 100
                        true
                    } else {
                        false
                    }
                }
            }
            picks.add(if (isWifi) "W" else "C")
        }
        return picks.joinToString(",")
    }

    /**
     * Computes exact `(wifiBitCount, cellularBitCount)` for [byteCount] bytes at [wifiPercent]
     * without allocating bit arrays (useful for fast accounting on zero-copy slices).
     */
    fun countSeparatedBits(byteCount: Int, wifiPercent: Int): Pair<Long, Long> {
        val totalBits = byteCount.coerceAtLeast(0).toLong() * 8L
        val share = wifiPercent.coerceIn(0, 100)
        if (share <= 0) return 0L to totalBits
        if (share >= 100) return totalBits to 0L
        // Exact arithmetic matching Bresenham acc += share; if (acc >= 100) acc -= 100:
        // After T steps starting at acc=0, the number of times acc crossed 100 is floor((T * share) / 100).
        val wifiBits = (totalBits * share.toLong()) / 100L
        return wifiBits to (totalBits - wifiBits)
    }

    /**
     * Separates [data] (`[offset, offset + length)`) bit-by-bit into a Path 1 (Wi-Fi) packed
     * bit-slice and a Path 2 (Cellular) packed bit-slice.
     */
    fun separateChunk(
        streamId: Int,
        chunkSeq: Long,
        data: ByteArray,
        wifiPercent: Int,
        offset: Int = 0,
        length: Int = data.size - offset,
    ): SeparatedBitPair {
        require(offset >= 0 && length >= 0 && offset + length <= data.size) {
            "Invalid slice bounds offset=$offset length=$length size=${data.size}"
        }
        val share = wifiPercent.coerceIn(0, 100)
        val crc = computeCrc32(data, offset, length)
        val totalBits = length * 8

        if (share == 100) {
            val copy = data.copyOfRange(offset, offset + length)
            return SeparatedBitPair(
                wifiSlice = BitSlicePacket(
                    BitSliceHeader(streamId, chunkSeq, length, 100, PATH_WIFI, totalBits, crc),
                    copy,
                ),
                cellularSlice = BitSlicePacket(
                    BitSliceHeader(streamId, chunkSeq, length, 100, PATH_CELLULAR, 0, crc),
                    ByteArray(0),
                ),
            )
        }
        if (share == 0) {
            val copy = data.copyOfRange(offset, offset + length)
            return SeparatedBitPair(
                wifiSlice = BitSlicePacket(
                    BitSliceHeader(streamId, chunkSeq, length, 0, PATH_WIFI, 0, crc),
                    ByteArray(0),
                ),
                cellularSlice = BitSlicePacket(
                    BitSliceHeader(streamId, chunkSeq, length, 0, PATH_CELLULAR, totalBits, crc),
                    copy,
                ),
            )
        }

        val expectedWifiBits = ((totalBits.toLong() * share.toLong()) / 100L).toInt()
        val expectedCellBits = totalBits - expectedWifiBits
        val wifiBytes = ByteArray((expectedWifiBits + 7) ushr 3)
        val cellBytes = ByteArray((expectedCellBits + 7) ushr 3)

        var acc = 0
        var wBitIdx = 0
        var cBitIdx = 0

        for (i in 0 until length) {
            val byteVal = data[offset + i].toInt() and 0xFF
            for (bitPos in 7 downTo 0) {
                val bit = (byteVal ushr bitPos) and 1
                acc += share
                if (acc >= 100) {
                    acc -= 100
                    if (bit != 0) {
                        wifiBytes[wBitIdx ushr 3] =
                            (wifiBytes[wBitIdx ushr 3].toInt() or (1 shl (7 - (wBitIdx and 7)))).toByte()
                    }
                    wBitIdx++
                } else {
                    if (bit != 0) {
                        cellBytes[cBitIdx ushr 3] =
                            (cellBytes[cBitIdx ushr 3].toInt() or (1 shl (7 - (cBitIdx and 7)))).toByte()
                    }
                    cBitIdx++
                }
            }
        }

        check(wBitIdx == expectedWifiBits && cBitIdx == expectedCellBits) {
            "Bit separation invariant violated: w=$wBitIdx/$expectedWifiBits c=$cBitIdx/$expectedCellBits"
        }

        return SeparatedBitPair(
            wifiSlice = BitSlicePacket(
                BitSliceHeader(streamId, chunkSeq, length, share, PATH_WIFI, wBitIdx, crc),
                wifiBytes,
            ),
            cellularSlice = BitSlicePacket(
                BitSliceHeader(streamId, chunkSeq, length, share, PATH_CELLULAR, cBitIdx, crc),
                cellBytes,
            ),
        )
    }

    /**
     * Recombines [wifiSlice] and [cellularSlice] bit-by-bit in exact inverse Bresenham order
     * and validates the reconstructed chunk's CRC32 checksum.
     */
    fun mergePair(wifiSlice: BitSlicePacket, cellularSlice: BitSlicePacket): ByteArray {
        val wh = wifiSlice.header
        val ch = cellularSlice.header
        if (wh.pathId != PATH_WIFI || ch.pathId != PATH_CELLULAR) {
            throw BitIntegrityException("mergePair requires path0=WIFI and path1=CELLULAR")
        }
        if (wh.streamId != ch.streamId || wh.chunkSeq != ch.chunkSeq) {
            throw BitIntegrityException(
                "Mismatched slice identity: (${wh.streamId},${wh.chunkSeq}) vs (${ch.streamId},${ch.chunkSeq})"
            )
        }
        if (wh.originalByteCount != ch.originalByteCount ||
            wh.wifiPercent != ch.wifiPercent ||
            wh.crc32 != ch.crc32
        ) {
            throw BitIntegrityException("Mismatched slice metadata at seq=${wh.chunkSeq}")
        }
        val length = wh.originalByteCount
        val totalBits = length * 8
        if (wh.bitCount + ch.bitCount != totalBits) {
            throw BitIntegrityException(
                "Bit count mismatch at seq=${wh.chunkSeq}: ${wh.bitCount}+${ch.bitCount} != $totalBits"
            )
        }

        val share = wh.wifiPercent
        val out = when (share) {
            100 -> wifiSlice.packedBits.copyOf(length)
            0 -> cellularSlice.packedBits.copyOf(length)
            else -> {
                val merged = ByteArray(length)
                val wBuf = wifiSlice.packedBits
                val cBuf = cellularSlice.packedBits
                var acc = 0
                var wBitIdx = 0
                var cBitIdx = 0
                for (i in 0 until length) {
                    var byteVal = 0
                    for (bitPos in 7 downTo 0) {
                        acc += share
                        val bit = if (acc >= 100) {
                            acc -= 100
                            val b = (wBuf[wBitIdx ushr 3].toInt() ushr (7 - (wBitIdx and 7))) and 1
                            wBitIdx++
                            b
                        } else {
                            val b = (cBuf[cBitIdx ushr 3].toInt() ushr (7 - (cBitIdx and 7))) and 1
                            cBitIdx++
                            b
                        }
                        byteVal = byteVal or (bit shl bitPos)
                    }
                    merged[i] = byteVal.toByte()
                }
                merged
            }
        }

        val actualCrc = computeCrc32(out, 0, out.size)
        if (actualCrc != wh.crc32) {
            throw BitIntegrityException(
                "CRC32 bit-integrity failure at seq=${wh.chunkSeq}: " +
                    "expected=0x${Integer.toHexString(wh.crc32)} actual=0x${Integer.toHexString(actualCrc)}"
            )
        }
        return out
    }

    /**
     * Splits an entire [payload] into [chunkBytes]-sized chunks and separates each chunk bit-by-bit.
     */
    fun separateStream(
        streamId: Int,
        payload: ByteArray,
        wifiPercent: Int,
        chunkBytes: Int = DEFAULT_CHUNK_BYTES,
    ): List<SeparatedBitPair> {
        require(chunkBytes > 0) { "chunkBytes must be > 0" }
        if (payload.isEmpty()) return emptyList()
        val out = ArrayList<SeparatedBitPair>((payload.size + chunkBytes - 1) / chunkBytes)
        var offset = 0
        var seq = 0L
        while (offset < payload.size) {
            val len = minOf(chunkBytes, payload.size - offset)
            out.add(separateChunk(streamId, seq++, payload, wifiPercent, offset, len))
            offset += len
        }
        return out
    }

    fun computeCrc32(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        val crc = CRC32()
        crc.update(data, offset, length)
        return crc.value.toInt()
    }

    /**
     * Bounded sliding-window bit-stream reassembler:
     *  - Accepts [BitSlicePacket]s arriving asynchronously and out-of-order from Wi-Fi and Cellular.
     *  - Reconstructs each chunk bit-by-bit as soon as both its Wi-Fi and Cellular bit-slices have
     *    arrived (or immediately when one path carries 100% of bits for that chunk).
     *  - Delivers chunks strictly in ascending `chunkSeq` order (`0, 1, 2, ...`).
     *  - Fires [onMissingPartner] when one half of the head chunk waits longer than [softTimeoutMs],
     *    allowing the surviving path to re-send or steal the missing half-slice.
     */
    class WindowedBitStreamMerger(
        private val streamId: Int,
        private val maxWindowChunks: Int = 256,
        private val softTimeoutMs: Long = 250L,
        private val onMissingPartner: (chunkSeq: Long, missingPathId: Byte) -> Unit = { _, _ -> },
    ) {
        private class PartialPair(
            var wifi: BitSlicePacket? = null,
            var cellular: BitSlicePacket? = null,
            val firstSeenMs: Long,
            var nackSent: Boolean = false,
        )

        private val lock = Any()
        private val pending = TreeMap<Long, PartialPair>()
        private val completedAhead = TreeMap<Long, ByteArray>()

        @Volatile var expectedSeq: Long = 0L
            private set

        @Volatile var maxReorderDepth: Int = 0
            private set

        @Volatile var mergedChunks: Long = 0L
            private set

        @Volatile var mergedBytes: Long = 0L
            private set

        @Volatile var wifiBitsMerged: Long = 0L
            private set

        @Volatile var cellularBitsMerged: Long = 0L
            private set

        @Volatile var droppedDuplicateOrLate: Long = 0L
            private set

        @Volatile var droppedBeyondWindow: Long = 0L
            private set

        /**
         * Inserts one incoming [slice] and returns all contiguous byte chunks that became
         * deliverable as a result (may be empty if still waiting on earlier or partner bits).
         */
        fun push(slice: BitSlicePacket, nowMs: Long = System.currentTimeMillis()): List<ByteArray> =
            synchronized(lock) {
                val h = slice.header
                if (h.streamId != streamId) {
                    throw BitIntegrityException("Wrong streamId=${h.streamId}, expected=$streamId")
                }
                val seq = h.chunkSeq
                if (seq < expectedSeq || completedAhead.containsKey(seq)) {
                    droppedDuplicateOrLate++
                    return emptyList()
                }
                if (seq >= expectedSeq + maxWindowChunks) {
                    droppedBeyondWindow++
                    return emptyList()
                }

                val pair = pending.getOrPut(seq) { PartialPair(firstSeenMs = nowMs) }
                if (h.pathId == PATH_WIFI) {
                    if (pair.wifi == null) pair.wifi = slice else droppedDuplicateOrLate++
                    if (h.wifiPercent == 100 && pair.cellular == null) {
                        pair.cellular = BitSlicePacket(
                            h.copy(pathId = PATH_CELLULAR, bitCount = 0),
                            ByteArray(0),
                        )
                    }
                } else {
                    if (pair.cellular == null) pair.cellular = slice else droppedDuplicateOrLate++
                    if (h.wifiPercent == 0 && pair.wifi == null) {
                        pair.wifi = BitSlicePacket(
                            h.copy(pathId = PATH_WIFI, bitCount = 0),
                            ByteArray(0),
                        )
                    }
                }

                val w = pair.wifi
                val c = pair.cellular
                if (w != null && c != null) {
                    pending.remove(seq)
                    val merged = mergePair(w, c)
                    wifiBitsMerged += w.header.bitCount.toLong()
                    cellularBitsMerged += c.header.bitCount.toLong()
                    completedAhead[seq] = merged
                }

                val depth = pending.size + completedAhead.size
                if (depth > maxReorderDepth) maxReorderDepth = depth

                checkTimeoutsLocked(nowMs)
                drainReadyLocked()
            }

        /** Polls timeouts even when traffic on one path went completely silent. */
        fun pollTimeouts(nowMs: Long = System.currentTimeMillis()): List<ByteArray> =
            synchronized(lock) {
                checkTimeoutsLocked(nowMs)
                drainReadyLocked()
            }

        private fun checkTimeoutsLocked(nowMs: Long) {
            for ((seq, pair) in pending) {
                if (!pair.nackSent && nowMs - pair.firstSeenMs >= softTimeoutMs) {
                    val missing = when {
                        pair.wifi == null -> PATH_WIFI
                        pair.cellular == null -> PATH_CELLULAR
                        else -> null
                    }
                    if (missing != null) {
                        pair.nackSent = true
                        onMissingPartner(seq, missing)
                    }
                }
            }
        }

        private fun drainReadyLocked(): List<ByteArray> {
            if (completedAhead.isEmpty()) return emptyList()
            val ready = ArrayList<ByteArray>()
            while (true) {
                val head = completedAhead.remove(expectedSeq) ?: break
                ready.add(head)
                mergedChunks++
                mergedBytes += head.size.toLong()
                expectedSeq++
            }
            return ready
        }
    }
}
