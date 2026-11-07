package com.bigrocket.service

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Real multi-flow aggregation - no simulation, no server.
 *
 * The data plane hands every NEW flow (one TCP connection, one UDP association) to exactly one
 * physical path, and a flow keeps that path for its lifetime (a 4-tuple cannot move mid-flight).
 * Aggregation is therefore won or lost at the moment a flow is created: if new flows keep
 * landing on the same radio, that radio saturates and the other stays idle.
 *
 * This class decides that moment from two things only:
 *
 *  - the user's share of the two scores (the base split, unchanged from the bonding rules);
 *  - the MEASURED throughput of each radio right now ([PathTrafficStats]).
 *
 * The second part is the part that makes aggregation real in practice: scores are a preference,
 * but a 40 Mbit Wi-Fi and a 5 Mbit LTE link only aggregate well if new flows are opened roughly
 * 8:1. When a path is measurably saturated its share is capped so new flows go to the other
 * radio instead of queueing behind a full buffer.
 *
 * Deliberately pure Kotlin (no android imports) so the decision maths is unit-tested.
 */
object FlowBalancer {

    /** A path below this measured speed is treated as "not carrying data yet". */
    private const val MIN_MEASURED_KBPS = 32

    /**
     * Effective share of new flows for Wi-Fi, 0..100.
     *
     * @param scorePercent the user-score share (PathBond / DynamicWeightCalculator).
     * @param wifiKbps measured Wi-Fi throughput (EWMA), 0 when unknown.
     * @param cellKbps measured cellular throughput (EWMA), 0 when unknown.
     */
    fun effectiveWifiPercent(scorePercent: Int, wifiKbps: Int, cellKbps: Int): Int {
        val score = scorePercent.coerceIn(0, 100)
        if (score == 0 || score == 100) return score
        val wifiUnknown = wifiKbps < MIN_MEASURED_KBPS
        val cellUnknown = cellKbps < MIN_MEASURED_KBPS
        // Nothing measured yet (or only one side): the scores are the only information there is.
        if (wifiUnknown && cellUnknown) return score
        if (wifiUnknown) {
            // Wi-Fi has shown no throughput while cellular has: open most flows on cellular, but
            // never abandon a path entirely - a parked radio would never be measured again.
            return minOf(score, 15)
        }
        if (cellUnknown) return maxOf(score, 85)
        // Both measured: split new flows by measured capacity, bounded so a momentary dip cannot
        // starve a path (the 60/40 user preference still dominates the floor/ceiling).
        val capacity = wifiKbps.toDouble() / (wifiKbps.toDouble() + cellKbps.toDouble())
        val capacityPercent = (capacity * 100).toInt()
        val blended = (capacityPercent * 0.7 + score * 0.3).toInt()
        // Measured capacity may pull the split up to 25 points away from the user's scores -
        // far enough to actually aggregate a 40 Mbit + 5 Mbit pair - but never closer than that:
        // a momentary measurement dip must not override a deliberate score choice.
        return blended.coerceIn(max(5, score - 25), min(95, score + 25))
    }

    /**
     * Whether the *next* new flow should go over Wi-Fi, given how many flows are already in
     * flight on each side. Keeps the aggregate share honest even when the allocator's long-run
     * ratio is right but short bursts would pile up on one side.
     */
    fun shouldUseWifi(
        wifiPercent: Int,
        activeWifiFlows: Int,
        activeCellFlows: Int,
        allocatorSaysWifi: Boolean,
    ): Boolean {
        val pct = wifiPercent.coerceIn(0, 100)
        if (pct == 0) return false
        if (pct == 100) return true
        val totalAfter = activeWifiFlows + activeCellFlows + 1
        val targetWifi = totalAfter * pct / 100.0
        // The allocator's answer is authoritative as long as it does not push the live flow
        // count more than one flow past its target; otherwise the other side takes this flow.
        val wifiAfter = activeWifiFlows + if (allocatorSaysWifi) 1 else 0
        val cellAfter = activeCellFlows + if (allocatorSaysWifi) 0 else 1
        val wifiError = abs(wifiAfter - targetWifi)
        val flipError = abs((if (allocatorSaysWifi) activeWifiFlows else activeWifiFlows + 1) - targetWifi)
        val cellTarget = totalAfter - targetWifi
        val cellError = abs(cellAfter - cellTarget)
        val flipCellError = abs((if (allocatorSaysWifi) activeCellFlows + 1 else activeCellFlows) - cellTarget)
        return if (wifiError + cellError <= flipError + flipCellError) allocatorSaysWifi else !allocatorSaysWifi
    }
}

/**
 * Real per-path traffic counters, fed by the data plane itself (TcpRelayEngine/UdpRelayEngine
 * call [recordBytes] for every payload chunk that actually crossed a radio).
 *
 * Everything here is measured; nothing is inferred or simulated. The screen that displays it can
 * therefore show which radio is actually carrying the bytes at any moment.
 */
object PathTrafficStats {

    data class Sample(
        val label: String,
        val activeFlows: Int,
        val totalBytes: Long,
        val kbpsNow: Int,
    )

    private class PathState {
        var activeFlows: Int = 0
        var totalBytes: Long = 0
        var windowBytes: Long = 0
        var ewmaKbps: Double = 0.0
        var lastSampleMs: Long = 0
    }

    private val lock = Any()
    private val states = HashMap<String, PathState>()

    private fun state(label: String): PathState = states.getOrPut(label) { PathState() }

    fun flowOpened(label: String) {
        synchronized(lock) { state(label).activeFlows++ }
    }

    fun flowClosed(label: String) {
        synchronized(lock) {
            val s = state(label)
            if (s.activeFlows > 0) s.activeFlows--
        }
    }

    /** Called for every payload chunk that actually crossed this path. */
    fun recordBytes(label: String, bytes: Int, nowMs: Long = System.currentTimeMillis()) {
        if (bytes <= 0) return
        synchronized(lock) {
            val s = state(label)
            s.totalBytes += bytes
            s.windowBytes += bytes
            if (s.lastSampleMs == 0L) s.lastSampleMs = nowMs
        }
    }

    /** Roll the measurement window (called once a second by the weight loop / UI poll). */
    fun tick(nowMs: Long = System.currentTimeMillis()): List<Sample> = synchronized(lock) {
        states.map { (label, s) ->
            val elapsed = if (s.lastSampleMs == 0L) 0L else (nowMs - s.lastSampleMs).coerceAtLeast(1L)
            if (elapsed >= 250L) {
                val kbps = (s.windowBytes * 8.0 / elapsed).toInt()
                // 0.4 weight on the new sample: responsive to a real speed change, immune to a
                // single 1-second dip.
                s.ewmaKbps = if (s.ewmaKbps == 0.0) kbps.toDouble() else s.ewmaKbps * 0.6 + kbps * 0.4
                s.windowBytes = 0
                s.lastSampleMs = nowMs
            }
            Sample(label, s.activeFlows, s.totalBytes, s.ewmaKbps.toInt())
        }
    }

    fun reset() {
        synchronized(lock) { states.clear() }
    }

    fun totalFlows(): Int = synchronized(lock) { states.values.sumOf { it.activeFlows } }
}
