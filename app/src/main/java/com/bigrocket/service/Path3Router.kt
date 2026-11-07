package com.bigrocket.service

import android.net.Network
import com.bigrocket.egress.PathBond
import com.bigrocket.egress.PathBondAllocator
import com.bigrocket.egress.StaticProxyRuntime

/**
 * The single logical Path 3 output of BigRocket.
 *
 * Path 1/2 are physical transports; callers above this class only ask Path 3
 * for the physical Network to use for a new flow. Aether's SOCKS input and
 * BigRocket's direct router both use this same selector.
 */
class Path3Router : PathSelector {
    @Volatile private var wifiNetwork: Network? = null
    @Volatile private var cellularNetwork: Network? = null
    @Volatile private var wifiWeight = 50
    @Volatile private var cellularWeight = 50

    // selectNetwork() is called per-packet/per-flow (can be thousands of times a second) -
    // logging every call would flood the log and add real I/O cost. Logging only the flips
    // (which physical path a new selection actually lands on) gives the same verification
    // value - "is Path 3 actually alternating/favoring the path I expect" - at negligible cost.
    @Volatile private var lastSelectedWasWifi: Boolean? = null

    fun updateNetworks(wifi: Network?, cellular: Network?) {
        wifiNetwork = wifi
        cellularNetwork = cellular
        AppLogger.log("Path3", "updateNetworks wifi=$wifi cellular=$cellular")
    }

    /** True when [network] is the current Wi-Fi or Cellular reference (not stale/replaced/gone). */
    fun isCurrent(network: Network?): Boolean =
        network != null && (network == wifiNetwork || network == cellularNetwork)

    /** Read-only snapshot for external observers (e.g. the Virtual Bonding sandbox UI). */
    fun currentWifiNetwork(): Network? = wifiNetwork
    fun currentCellularNetwork(): Network? = cellularNetwork

    fun updateWeights(wifi: Int, cellular: Int) {
        wifiWeight = wifi.coerceIn(0, 100)
        cellularWeight = cellular.coerceIn(0, 100)
        AppLogger.log("Path3", "updateWeights wifi=$wifiWeight cellular=$cellularWeight")
    }

    override fun selectNetwork(slot: Int?): Network? {
        val wifi = wifiNetwork
        val cellular = cellularNetwork

        // --- VERIFIED STATIC PROXY = ENDPOINT -------------------------------------------------
        // Nothing here may "pick the better path": the exit IP is fixed by the proxy, so both
        // links are one bandwidth pool. Every unit is interleaved by score ratio - strictly
        // alternating at equal scores (simultaneous bit-for-bit merge), proportional when one
        // score is higher. The score/latency/identity rules below are skipped entirely: that is
        // what "no IP selection by score or by quality (on equal scores)" means in practice.
        if (wifi != null && cellular != null && StaticProxyRuntime.isVerified()) {
            // Real aggregation across flows (no server, no simulation): the exit IP is fixed by
            // the verified proxy, so every new flow may take either radio. The split starts from
            // the score ratio (strict alternation at equal scores) and is then corrected by the
            // MEASURED throughput of each radio and by how many flows are already in flight -
            // that is what keeps a saturated radio from collecting every new connection.
            val samples = PathTrafficStats.tick()
            val wifiKbps = samples.firstOrNull { it.label == PathLabels.WIFI }?.kbpsNow ?: 0
            val cellKbps = samples.firstOrNull { it.label == PathLabels.CELLULAR }?.kbpsNow ?: 0
            val effectivePercent = FlowBalancer.effectiveWifiPercent(wifiWeight, wifiKbps, cellKbps)
            val allocatorSaysWifi = PathBond.next(effectivePercent)
            val overWifi = FlowBalancer.shouldUseWifi(
                wifiPercent = effectivePercent,
                activeWifiFlows = samples.firstOrNull { it.label == PathLabels.WIFI }?.activeFlows ?: 0,
                activeCellFlows = samples.firstOrNull { it.label == PathLabels.CELLULAR }?.activeFlows ?: 0,
                allocatorSaysWifi = allocatorSaysWifi,
            )
            val bonded = if (overWifi) wifi else cellular
            flowBalancerLastPercent = effectivePercent
            logIfFlipped(bonded, wifi)
            return bonded
        }

        val selected = if (wifi != null && cellular != null) {
            if (slot != null) {
                val normalized = Math.floorMod(slot, 100)
                if (normalized < wifiWeight) wifi else cellular
            } else {
                when {
                    wifiWeight > cellularWeight -> wifi
                    cellularWeight > wifiWeight -> cellular
                    else -> wifi
                }
            }
        } else {
            wifi ?: cellular
        }
        logIfFlipped(selected, wifi)
        return selected
    }

    private fun logIfFlipped(selected: Network?, wifi: Network?) {
        val nowWifi = selected != null && selected == wifi
        if (selected != null && lastSelectedWasWifi != nowWifi) {
            lastSelectedWasWifi = nowWifi
            AppLogger.log("Path3", "selectNetwork flipped to ${if (nowWifi) "wifi" else "cellular"} (wifi=$wifiWeight cellular=$cellularWeight)")
        }
    }

    /**
     * Returns all available networks ordered by weight descending (best first).
     * Used for fallback when the primary network fails to connect - ensures
     * registration traffic (api.cloudflareclient.com) can still succeed via the
     * other path instead of failing outright.
     *
     * FIX for WireGuard/Gool SOCKS5 readiness: previously only pickBestNetwork()
     * was tried; if that network resolved api.cloudflareclient.com to IPv6 that
     * was unreachable, the whole registration failed and PortProbe timed out
     * with "socks5 listener did not become ready". Now we try all networks.
     */
    fun allNetworksSorted(): List<Network> {
        val wifi = wifiNetwork
        val cellular = cellularNetwork
        return when {
            wifi != null && cellular != null -> {
                if (wifiWeight >= cellularWeight) listOf(wifi, cellular)
                else listOf(cellular, wifi)
            }
            wifi != null -> listOf(wifi)
            cellular != null -> listOf(cellular)
            else -> emptyList()
        }
    }

    /** Share of new flows the balancer last settled on (0..100, Wi-Fi side). */
    @Volatile var flowBalancerLastPercent: Int = 50
        private set

    /** The split currently in force, for logs/UI ("50/50" while merged). */
    fun bondSplitDescription(): String =
        PathBondAllocator.describe(wifiWeight, cellularWeight)

    fun latencyMs(wifiLatencyMs: Long, cellularLatencyMs: Long): Long {
        val wifi = wifiNetwork != null && wifiWeight > 0
        val cellular = cellularNetwork != null && cellularWeight > 0
        return when {
            wifi && cellular -> {
                val total = wifiWeight + cellularWeight
                ((wifiLatencyMs.coerceAtLeast(0) * wifiWeight) +
                    (cellularLatencyMs.coerceAtLeast(0) * cellularWeight)) /
                    total.coerceAtLeast(1)
            }
            wifi -> wifiLatencyMs.coerceAtLeast(0)
            cellular -> cellularLatencyMs.coerceAtLeast(0)
            else -> 0L
        }
    }

    /** For diagnostics/logging only - identifies whether [network] is the current Wi-Fi or
     *  Cellular reference, or neither (e.g. already stale/replaced). */
    fun describeNetwork(network: Network?): String = when (network) {
        null -> "none"
        wifiNetwork -> "wifi"
        cellularNetwork -> "cellular"
        else -> "unknown(${network})"
    }
}
