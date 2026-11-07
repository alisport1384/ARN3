package com.bigrocket.egress

/**
 * Simultaneous, weight-exact path interleaving for the "verified static-IP proxy" mode.
 *
 * The rules this implements (from the product requirement):
 *
 *  1. When a static-IP proxy is configured AND verified (its exit IP was confirmed before
 *     connecting), the proxy is the ENDPOINT. Path selection by score*, path "identity"
 *     stickiness and latency/quality tie-breaks must not decide which single path is used -
 *     there is nothing to decide, both pipes lead to the same exit.
 *  2. Equal scores -> the two paths are merged **simultaneously and bit-for-bit**, i.e. strictly
 *     alternating units (A,B,A,B,...), never "first all of one, then the other" and never a
 *     per-connection/latency-based pick that silently favours one side.
 *  3. Unequal scores -> the split follows the score ratio exactly: the higher-scored path gets
 *     that much larger a share of the merged stream (e.g. 3 vs 1 -> 70/30 today), and the
 *     sequence is still interleaved as evenly as the ratio allows, not blocked.
 *
 * "Unit" is whatever the caller can safely carry on either path: one relayed chunk/packet, one
 * new connection (a TCP flow cannot be split mid-stream - its 4-tuple is fixed), or one UDP
 * datagram. The allocator is what makes all of those collectively *simultaneous*: at any point
 * both links are in flight, in exactly the requested proportions.
 *
 * Implementation: an error-diffusion accumulator (Bresenham). It is deterministic - same input
 * share, same sequence - which is what makes it testable and reproducible, unlike a random
 * weighted pick (which only converges to the ratio over many draws and, over a short window,
 * silently pins 100% on one side).
 */
class PathBondAllocator {

    /** Leftover credit, in percent-points, carried between calls. */
    private var accumulator = 0.0

    /**
     * @param wifiPercent the Wi-Fi share, 0..100. The cellular share is the remainder.
     * @return true when this unit should go over Wi-Fi, false when over Cellular.
     */
    @Synchronized
    fun next(wifiPercent: Int): Boolean {
        val pct = wifiPercent.coerceIn(0, 100)
        if (pct == 0) return false
        if (pct == 100) return true
        accumulator += pct
        return if (accumulator >= 100.0) {
            accumulator -= 100.0
            true
        } else {
            false
        }
    }

    @Synchronized
    fun reset() {
        accumulator = 0.0
    }

    companion object {
        /** The sequence [count] decisions would produce, without touching live state. */
        fun pattern(wifiPercent: Int, count: Int): List<Boolean> {
            val allocator = PathBondAllocator()
            return List(count) { allocator.next(wifiPercent) }
        }

        /** Number of Wi-Fi picks in the first [count] decisions (for tests/diagnostics). */
        fun wifiCount(wifiPercent: Int, count: Int): Int = pattern(wifiPercent, count).count { it }

        /** Human-readable split, e.g. "50/50 (ادغام همزمان)". */
        fun describe(wifiPercent: Int, cellularPercent: Int): String =
            "$wifiPercent/$cellularPercent (ادغام همزمان بیت‌به‌بیت)"
    }
}

/**
 * Process-wide allocator used by every point that has to choose a physical path while a
 * verified static-IP proxy is the endpoint: [com.bigrocket.service.Path3Router] (per flow /
 * packet), [com.bigrocket.service.BondingSocksServer] (per relayed connection) and
 * [VirtualEgress]'s bonded UDP channel (per datagram).
 *
 * One shared instance on purpose: the shares are a property of the whole data plane (the
 * user's scores), so a single sequence keeps the aggregate split exactly on ratio even when
 * several call sites interleave their decisions.
 */
object PathBond {
    private val allocator = PathBondAllocator()

    /**
     * Live Wi-Fi share (0..100), published by the service's weight loop. Kept here so the egress
     * layer can consult the share without depending on the service package.
     */
    @Volatile
    var wifiPercent: Int = 50
        private set

    fun updateShare(wifiPercent: Int) {
        this.wifiPercent = wifiPercent.coerceIn(0, 100)
    }

    /** true = Wi-Fi, false = Cellular. */
    fun next(wifiPercent: Int): Boolean = allocator.next(wifiPercent)

    /** Next decision at the currently published share. */
    fun next(): Boolean = allocator.next(wifiPercent)

    fun reset() {
        allocator.reset()
    }
}
