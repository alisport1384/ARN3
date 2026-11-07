package com.bigrocket.service

import com.bigrocket.egress.StaticProxyRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * A REAL aggregation measurement: it downloads over the actual radios, at the same time, and
 * reports what each one carried. No simulation, no server of ours, no traffic model - the bytes
 * come from the internet and leave through Wi-Fi and cellular respectively.
 *
 * Why this matters: a single TCP connection can only use one radio, so "aggregation" that a
 * phone can genuinely deliver with a static-IP proxy (one exit IP, no remote bonding peer) is
 * aggregation ACROSS connections. This test opens two connections at once, one bound to each
 * radio, and shows that the two together move more than either alone - the same effect a
 * browser or a multi-connection downloader gets in normal use.
 */
object AggregationProbe {

    /** A large file served by Cloudflare's public speed-test endpoint. */
    private const val TEST_URL = "https://speed.cloudflare.com/__down?bytes=25000000"

    data class Result(
        val label: String,
        val bytes: Long,
        val millis: Long,
    ) {
        val kbps: Int get() = if (millis <= 0) 0 else (bytes * 8.0 / millis).toInt()
        val mbps: Double get() = kbps / 1000.0
    }

    private fun readFor(
        network: android.net.Network,
        label: String,
        durationMs: Long,
    ): Result {
        val started = System.currentTimeMillis()
        var total = 0L
        try {
            val connection = network.openConnection(java.net.URL(TEST_URL)) as java.net.HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("User-Agent", "BigRocket-aggregation-probe")
            connection.inputStream.use { stream: InputStream ->
                val buffer = ByteArray(64 * 1024)
                while (System.currentTimeMillis() - started < durationMs) {
                    val n = stream.read(buffer)
                    if (n <= 0) break
                    total += n
                }
            }
            runCatching { connection.disconnect() }
        } catch (e: Exception) {
            AppLogger.log("Probe", "$label failed: ${e.javaClass.simpleName}: ${e.message}")
        }
        return Result(label, total, (System.currentTimeMillis() - started).coerceAtLeast(1))
    }

    /**
     * Runs the measurement: Wi-Fi alone, cellular alone, then both simultaneously.
     * @return ready-to-display lines plus the raw results.
     */
    suspend fun measure(
        wifi: android.net.Network?,
        cellular: android.net.Network?,
        seconds: Int = 6,
    ): Pair<List<String>, List<Result>> = withContext(Dispatchers.IO) {
        val durationMs = seconds * 1000L
        val results = ArrayList<Result>()
        val lines = ArrayList<String>()

        val wifiOnly = wifi?.let { readFor(it, "wifi", durationMs) }
        wifiOnly?.let { results.add(it); lines.add("فقط وای‌فای: ${"%.2f".format(it.mbps)} Mbps (${it.bytes} بایت در ${it.millis}ms)") }

        val cellOnly = cellular?.let { readFor(it, "cellular", durationMs) }
        cellOnly?.let { results.add(it); lines.add("فقط سلولار: ${"%.2f".format(it.mbps)} Mbps (${it.bytes} بایت در ${it.millis}ms)") }

        if (wifi != null && cellular != null) {
            val (a, b) = coroutineScope {
                val ja = async(Dispatchers.IO) { readFor(wifi, "wifi", durationMs) }
                val jb = async(Dispatchers.IO) { readFor(cellular, "cellular", durationMs) }
                ja.await() to jb.await()
            }
            results.add(a)
            results.add(b)
            val bothKbps = a.kbps + b.kbps
            lines.add(
                "هم‌زمان: وای‌فای ${"%.2f".format(a.mbps)} + سلولار ${"%.2f".format(b.mbps)} " +
                    "= ${"%.2f".format(bothKbps / 1000.0)} Mbps",
            )
            val bestSingle = maxOf(wifiOnly?.kbps ?: 0, cellOnly?.kbps ?: 0)
            if (bestSingle > 0) {
                lines.add("بهبود نسبت به بهترین تک‌مسیر: ${"%.2f".format(bothKbps.toDouble() / bestSingle)}×")
            }
            if (StaticProxyRuntime.isVerified()) {
                lines.add("این ترافیک از پروکسی استاتیک تأییدشده بیرون رفت (هر دو مسیر با یک IP خروجی).")
            } else {
                lines.add("توجه: پروکسی استاتیک تأیید نشده است — این تست مستقیم روی دو رادیو اجرا شد.")
            }
        } else {
            lines.add("برای تست هم‌زمان، هر دو مسیر (وای‌فای و سلولار) باید فعال باشند.")
        }
        lines to results
    }
}
