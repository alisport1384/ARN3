package com.bigrocket.separator

import com.bigrocket.service.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide live state and telemetry for BigRocket's Virtual Bit-by-Bit Separator Engine
 * ("موتور جداکننده مجازی بیت‌به‌بیت").
 *
 * Read from the data plane ([com.bigrocket.egress.VirtualEgress], [com.bigrocket.service.Path3Router],
 * [VirtualSeparatorSocket]) via lock-free volatile/atomic reads, and observed by the UI via [status].
 */
object VirtualSeparatorRuntime {

    data class Status(
        val enabled: Boolean = true,
        val separatedStreams: Long = 0,
        val passthroughStreams: Long = 0,
        val wifiBits: Long = 0,
        val cellularBits: Long = 0,
        val mergedBytes: Long = 0,
        val verifiedChunks: Long = 0,
        val integrityErrors: Long = 0,
        val workSteals: Long = 0,
        val activeSplit: String = "50/50",
        val lastDetail: String = "",
    ) {
        val totalBits: Long get() = wifiBits + cellularBits

        fun summary(): String {
            if (!enabled) {
                return "موتور جداکننده مجازی: خاموش (هر اتصال فقط روی یک مسیر می‌ماند)"
            }
            val mergedMb = mergedBytes / (1024.0 * 1024.0)
            val wifiPct = if (totalBits > 0) (wifiBits * 100 / totalBits).toInt() else 50
            val cellPct = if (totalBits > 0) 100 - wifiPct else 50
            val integrityText = if (integrityErrors == 0L) "۱۰۰٪ بیت‌به‌بیت سالم" else "خطا=$integrityErrors"
            return buildString {
                append("موتور جداکننده مجازی: فعال (نسبت $activeSplit)")
                if (separatedStreams > 0 || verifiedChunks > 0) {
                    append(" — جریان‌های جداشده: $separatedStreams")
                    append(" · ادغام‌شده: ${"%.2f".format(mergedMb)} MB")
                    append(" · سهم واقعی بیت‌ها: Wi-Fi $wifiPct% / دیتا $cellPct%")
                    append(" · $integrityText")
                    if (workSteals > 0) append(" · جبران مسیر کند: $workSteals")
                } else {
                    append(" — آماده تفکیک بیت‌به‌بیت و ادغام هم‌زمان Wi-Fi + دیتا (بدون نیاز به سرور)")
                }
                if (lastDetail.isNotEmpty()) {
                    append("\nآخرین رویداد: $lastDetail")
                }
            }
        }
    }

    @Volatile
    private var enabledFlag: Boolean = true

    @Volatile
    private var wifiSharePercent: Int = 50

    @Volatile
    private var lastDetailText: String = ""

    private val separatedStreams = AtomicLong(0)
    private val passthroughStreams = AtomicLong(0)
    private val wifiBits = AtomicLong(0)
    private val cellularBits = AtomicLong(0)
    private val mergedBytes = AtomicLong(0)
    private val verifiedChunks = AtomicLong(0)
    private val integrityErrors = AtomicLong(0)
    private val workSteals = AtomicLong(0)

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    fun isEnabled(): Boolean = enabledFlag

    fun currentWifiPercent(): Int = wifiSharePercent

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        val changed = enabledFlag != enabled
        enabledFlag = enabled
        if (changed) {
            AppLogger.log(
                "Separator",
                if (enabled) "virtual bit-by-bit separator ENABLED"
                else "virtual bit-by-bit separator DISABLED",
            )
        }
        publish()
    }

    fun updateShare(wifiPercent: Int, cellularPercent: Int = 100 - wifiPercent) {
        val clamped = wifiPercent.coerceIn(0, 100)
        if (clamped != wifiSharePercent) {
            wifiSharePercent = clamped
            publish()
        }
    }

    fun noteSeparatedStream(detail: String = "") {
        separatedStreams.incrementAndGet()
        if (detail.isNotEmpty()) lastDetailText = detail
        publish()
    }

    fun notePassthroughStream() {
        passthroughStreams.incrementAndGet()
    }

    /**
     * Records a chunk carried by one radio inside a virtually-separated stream or bit-level
     * inverse-multiplexed session.
     */
    fun noteRadioBits(isWifi: Boolean, bits: Long, bytesMerged: Long = 0L, chunkVerified: Boolean = false) {
        if (bits > 0) {
            if (isWifi) wifiBits.addAndGet(bits) else cellularBits.addAndGet(bits)
        }
        if (bytesMerged > 0) {
            mergedBytes.addAndGet(bytesMerged)
        }
        if (chunkVerified) {
            verifiedChunks.incrementAndGet()
        }
        // Throttle UI state emissions on tight inner loops while keeping exact atomic counters:
        // publish on every verified chunk or every ~256 KB boundary.
        if (chunkVerified || (mergedBytes.get() and 0x3FFFFL) < bytesMerged) {
            publish()
        }
    }

    fun noteBitPairMerged(wifiBitCount: Long, cellularBitCount: Long, reassembledBytes: Int) {
        if (wifiBitCount > 0) wifiBits.addAndGet(wifiBitCount)
        if (cellularBitCount > 0) cellularBits.addAndGet(cellularBitCount)
        if (reassembledBytes > 0) mergedBytes.addAndGet(reassembledBytes.toLong())
        verifiedChunks.incrementAndGet()
        publish()
    }

    fun noteIntegrityError(reason: String) {
        integrityErrors.incrementAndGet()
        lastDetailText = reason
        AppLogger.log("Separator", "integrity error: $reason")
        publish()
    }

    fun noteWorkSteal() {
        workSteals.incrementAndGet()
        publish()
    }

    fun noteDetail(detail: String) {
        lastDetailText = detail
        publish()
    }

    @Synchronized
    fun resetCounters() {
        separatedStreams.set(0)
        passthroughStreams.set(0)
        wifiBits.set(0)
        cellularBits.set(0)
        mergedBytes.set(0)
        verifiedChunks.set(0)
        integrityErrors.set(0)
        workSteals.set(0)
        lastDetailText = ""
        publish()
    }

    fun forcePublish() = publish()

    private fun publish() {
        val w = wifiSharePercent
        _status.value = Status(
            enabled = enabledFlag,
            separatedStreams = separatedStreams.get(),
            passthroughStreams = passthroughStreams.get(),
            wifiBits = wifiBits.get(),
            cellularBits = cellularBits.get(),
            mergedBytes = mergedBytes.get(),
            verifiedChunks = verifiedChunks.get(),
            integrityErrors = integrityErrors.get(),
            workSteals = workSteals.get(),
            activeSplit = "$w/${100 - w}",
            lastDetail = lastDetailText,
        )
    }
}
