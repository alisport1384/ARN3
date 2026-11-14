package com.bigrocket.egress

import com.bigrocket.service.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Live, process-wide view of the user's static-IP proxy - the thing the Service's egress
 * points read on every new flow, so saving the box in the UI takes effect on the next
 * connection without any reconnect (and without an Activity having to talk to the Service).
 *
 * Follows the app's existing status-publisher conventions (BondingStatus / UpstreamHealth):
 * a volatile value read from every relay coroutine, a StateFlow for the UI, and atomics for
 * the counters.
 */
object StaticProxyRuntime {

    data class Status(
        val enabled: Boolean = false,
        val description: String = "",
        val tunnelsOpened: Long = 0,
        val tunnelsFailed: Long = 0,
        val udpChannels: Long = 0,
        val bytesRelayed: Long = 0,
        val lastError: String = "",
        val egressIp: String = "",
        val egressCheckedAtMs: Long = 0,
        /** Port/protocol found by ProxyPortDetector, when the user gave only an IP. */
        val detectedPort: Int = 0,
        val detectedKind: String = "",
        /** True once the exit IP was confirmed through the proxy (before connecting). */
        val verified: Boolean = false,
        val verifiedEgressIp: String = "",
        /** Exact split the scheduler is using right now, e.g. "50/50". */
        val bondSplit: String = "",
    ) {
        /** One line for the UI card under the input box. */
        fun summary(): String = when {
            !enabled -> "استاتیک آی‌پی: خاموش (خروجی مستقیم است)"
            verified && bondSplit.isNotEmpty() ->
                "استاتیک آی‌پی: تاییدشده (${verifiedEgressIp.ifEmpty { "?" }}) — ادغام همزمان $bondSplit"
            verified -> "استاتیک آی‌پی: تاییدشده (${verifiedEgressIp.ifEmpty { "?" }}) — اندپوینت پروکسی"
            detectedPort > 0 -> "استاتیک آی‌پی: فعال روی $description — پورت پیدا شد: $detectedPort ($detectedKind)"
            lastError.isNotEmpty() -> "استاتیک آی‌پی: $description — خطا: $lastError"
            else -> "استاتیک آی‌پی: فعال روی $description — تونل‌ها: $tunnelsOpened"
        }
    }

    /** Volatile on purpose: read from every relay coroutine, written from the UI thread. */
    @Volatile
    var config: StaticProxyConfig? = null
        private set

    private val tunnelsOpened = AtomicLong(0)
    private val tunnelsFailed = AtomicLong(0)
    private val udpChannels = AtomicLong(0)
    private val bytesRelayed = AtomicLong(0)

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    @Volatile private var lastError: String = ""
    @Volatile private var egressIp: String = ""
    @Volatile private var egressCheckedAtMs: Long = 0L
    @Volatile private var detectedPort: Int = 0
    @Volatile private var detectedKind: String = ""
    // A verified static proxy IS the endpoint, so path-selection-by-score/quality no longer
    // applies: the shares below only describe how much of the merged stream each physical path
    // carries, never which one "owns" the connection.
    @Volatile private var verified: Boolean = false
    @Volatile private var verifiedEgressIp: String = ""
    @Volatile private var bondSplit: String = ""

    /** Installs (or clears, when [next] is null) the active proxy and republishes the status. */
    @Synchronized
    fun install(next: StaticProxyConfig?) {
        val previous = config
        config = next
        lastError = ""
        val same = previous?.canonical() == next?.canonical()
        if (!same) {
            tunnelsOpened.set(0)
            tunnelsFailed.set(0)
            udpChannels.set(0)
            bytesRelayed.set(0)
            egressIp = ""
            egressCheckedAtMs = 0L
            detectedPort = 0
            detectedKind = ""
            verified = false
            verifiedEgressIp = ""
            bondSplit = ""
            PathBond.reset()
            AppLogger.log(
                "Egress",
                if (next == null) "static proxy disabled - egress is direct again"
                else "static proxy active: ${next.describe()} (udp via proxy=${!next.udpBypassesProxy})",
            )
        }
        publish()
    }

    fun activeOrNull(): StaticProxyConfig? = config

    fun noteTunnelOpened() {
        tunnelsOpened.incrementAndGet()
        if (lastError.isNotEmpty()) lastError = ""
        publish()
    }

    fun noteTunnelFailed(reason: String) {
        tunnelsFailed.incrementAndGet()
        lastError = reason
        publish()
    }

    fun noteUdpChannel() {
        udpChannels.incrementAndGet()
        publish()
    }

    fun noteBytes(count: Int) {
        if (count <= 0) return
        bytesRelayed.addAndGet(count.toLong())
    }

    /** Records what ProxyPortDetector found for a portless ("auto") proxy config. */
    @Synchronized
    fun noteDetectedPort(port: Int, kind: StaticProxyKind) {
        detectedPort = port
        detectedKind = kind.displayName()
        AppLogger.log("Egress", "auto port detection: $port (${kind.displayName()})")
        publish()
    }

    fun detectedPort(): Int = detectedPort

    /**
     * The exit-IP check succeeded through the proxy: the proxy is confirmed as the endpoint, so
     * "verified" mode (score/quality path rules off, simultaneous weighted merging on) starts
     * applying from the next flow.
     */
    fun noteEgressIp(ip: String) {
        egressIp = ip
        egressCheckedAtMs = System.currentTimeMillis()
        if (config != null) {
            verified = true
            verifiedEgressIp = ip
        }
        publish()
    }

    /** Called when the proxy itself is found to be unusable: drops the verified state. */
    fun clearVerified() {
        verified = false
        verifiedEgressIp = ""
        publish()
    }

    /** True when a static proxy is configured AND its exit was confirmed. */
    fun isVerified(): Boolean = config != null && verified

    /** True when a static proxy is configured and has either been verified or carried live tunnels. */
    fun isActiveAndWorking(): Boolean = config != null && (verified || tunnelsOpened.get() > 0L)

    /** Called by the weight loop so the UI can show the split actually in use. */
    fun noteBondSplit(wifiPercent: Int, cellularPercent: Int) {
        val text = "$wifiPercent/$cellularPercent"
        if (text != bondSplit) {
            bondSplit = text
            publish()
        }
    }

    fun noteError(reason: String) {
        lastError = reason
        publish()
    }

    private fun publish() {
        val current = config
        _status.value = Status(
            enabled = current != null,
            description = current?.describe().orEmpty(),
            tunnelsOpened = tunnelsOpened.get(),
            tunnelsFailed = tunnelsFailed.get(),
            udpChannels = udpChannels.get(),
            bytesRelayed = bytesRelayed.get(),
            lastError = lastError,
            egressIp = egressIp,
            egressCheckedAtMs = egressCheckedAtMs,
            detectedPort = detectedPort,
            detectedKind = detectedKind,
            verified = verified,
            verifiedEgressIp = verifiedEgressIp,
            bondSplit = bondSplit,
        )
    }
}
