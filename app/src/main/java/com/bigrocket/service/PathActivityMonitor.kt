package com.bigrocket.service

import android.net.Network
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks the last time real payload bytes were actually relayed over each physical Network
 * (Direct/NONE mode sockets only - see call sites in TcpRelayEngine/UdpRelayEngine).
 *
 * The periodic latency probe in BigRocketVpnService shares the same physical link as real
 * traffic. When a path is saturated by a genuine bulk transfer (e.g. a download maxing out
 * Wi-Fi), the tiny latency probe can queue behind that traffic and miss its own timeout even
 * though the path is demonstrably alive and moving data. Without this signal, that probe
 * timeout is indistinguishable from a real path failure, so the hysteresis in
 * BigRocketVpnService eventually declares the path lost, evicts its live sessions
 * (TunPacketRouter.notifySoftFailure), and forces a mid-download failover - and once eviction
 * stops competing for the queue, the very next probe succeeds and the path is declared
 * "recovered", producing a flapping loop instead of one stable path.
 *
 * Recent real traffic is direct, stronger evidence of liveness than a synthetic probe, so it is
 * used to withhold judgement on an inconclusive probe failure rather than to declare success
 * outright - see the call site in BigRocketVpnService's weight-update loop.
 */
/**
 * Maps an android.net.Network to the user-facing path name used by the live aggregation
 * counters ("وای‌فای" / "سلولار"). Publishing the two Network objects here keeps the relay
 * engines free of any Wi-Fi/cellular policy: they just report bytes against whatever label the
 * service assigned.
 */
object PathLabels {
    @Volatile private var wifi: Network? = null
    @Volatile private var cellular: Network? = null

    const val WIFI = "wifi"
    const val CELLULAR = "cellular"
    const val OTHER = "other"

    fun updateNetworks(wifi: Network?, cellular: Network?) {
        this.wifi = wifi
        this.cellular = cellular
    }

    fun of(network: Network?): String = when {
        network == null -> OTHER
        network == wifi -> WIFI
        network == cellular -> CELLULAR
        else -> OTHER
    }

    fun isKnown(label: String): Boolean = label == WIFI || label == CELLULAR
}

object PathActivityMonitor {

    private val lastActivityMs = ConcurrentHashMap<Network, Long>()

    fun recordActivity(network: Network) {
        lastActivityMs[network] = System.currentTimeMillis()
    }

    fun hasRecentActivity(network: Network, withinMs: Long): Boolean {
        val ts = lastActivityMs[network] ?: return false
        return (System.currentTimeMillis() - ts) <= withinMs
    }

    fun clear() {
        lastActivityMs.clear()
    }
}
