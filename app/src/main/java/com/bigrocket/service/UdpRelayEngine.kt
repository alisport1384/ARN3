package com.bigrocket.service

import android.net.Network
import com.bigrocket.egress.EgressDatagram
import com.bigrocket.egress.EgressUdpChannel
import com.bigrocket.egress.VirtualEgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

// A UDP "session" here is just a local NAT-style mapping so replies can find their way back.
// Real NAT gateways time these out after a period of inactivity (commonly ~30s) - without doing
// the same, every DNS lookup (etc.) leaks one socket forever, eventually exhausting the app's
// file descriptor limit and breaking all relaying until the VPN is manually restarted.
private const val UDP_IDLE_TIMEOUT_MS = 30_000L

// Same reasoning as TcpRelayEngine's cap: bounds worst-case file-descriptor usage under a
// sudden burst of new sessions, independent of the idle timeout above.
private const val MAX_CONCURRENT_UDP_SESSIONS = 200

/**
 * One UDP flow's outbound transport.
 *
 * This used to be either a bound [java.net.DatagramSocket] (Direct mode) or an
 * [AetherUpstream.UdpAssociation] (chained modes), decided at each call site. Both are now
 * expressed as an [EgressUdpChannel], which is also where the static-IP proxy plugs in:
 * in Direct mode the channel is a SOCKS5 UDP ASSOCIATE (or a plain socket when the proxy is
 * off / HTTP-only), so the relay logic below stays single-path and unchanged.
 */
private class UdpSession(
    val channel: EgressUdpChannel,
    val aetherAssociation: AetherUpstream.UdpAssociation?,
    @Volatile var lastActivityMs: Long
)

class UdpRelayEngine(
    private val vpnService: BigRocketVpnService
) {

    // SupervisorJob: see the identical comment in TcpRelayEngine.kt - one UDP session's
    // uncaught exception must not cancel every other session sharing this scope.
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeSessions = ConcurrentHashMap<SessionKey, UdpSession>()
    private val socketJobs = ConcurrentHashMap<SessionKey, Job>()
    @Volatile private var upstreamMode = UpstreamMode.NONE

    fun setUpstreamMode(mode: UpstreamMode) {
        if (upstreamMode == mode) return
        upstreamMode = mode
        clear()
    }

    fun forwardUdpPacket(
        packet: ParsedIpPacket,
        buffer: ByteBuffer,
        length: Int,
        targetNetwork: Network,
        tunOutputStream: FileOutputStream
    ) {
        val key = SessionKey(
            protocol = packet.protocol,
            sourceIp = packet.sourceIp,
            sourcePort = packet.sourcePort,
            destinationIp = packet.destinationIp,
            destinationPort = packet.destinationPort
        )

        try {
            var session = activeSessions[key]
            // A closed channel (path re-pinned, proxy tunnel dropped, bonded channel torn down)
            // must not keep swallowing datagrams for this flow: recreate the session on the
            // current path instead. Same idea as BR's dead-socket check, expressed through the
            // EgressUdpChannel abstraction.
            if (session != null && !session.channel.isOpen) {
                // Reclaim the dead session (and its slot) before building a new one.
                closeSession(key)
                session = null
            }
            if (session == null) {
                if (activeSessions.size >= MAX_CONCURRENT_UDP_SESSIONS) {
                    activeSessions.entries.minByOrNull { it.value.lastActivityMs }?.let {
                        closeSession(it.key)
                    }
                }

                // Chained modes keep their existing Aether/Xray association (those engines own
                // the transport); Direct mode goes through the single egress object, which is
                // where the static-IP proxy is applied.
                val aetherAssociation = when (upstreamMode) {
                    UpstreamMode.AETHER -> AetherUpstream.openUdp(vpnService)
                    UpstreamMode.XRAY -> AetherUpstream.openUdp(vpnService, proxyPort = EmbeddedXrayRuntime.SOCKS_PORT)
                    UpstreamMode.NONE -> null
                }
                val channel = if (aetherAssociation != null) {
                    AetherUdpChannel(aetherAssociation, targetNetwork)
                } else {
                    VirtualEgress.openUdpChannel(vpnService, targetNetwork, "direct-udp")
                }
                session = UdpSession(channel, aetherAssociation, System.currentTimeMillis())
                activeSessions[key] = session

                val job = scope.launch {
                    listenForResponses(key, session, tunOutputStream)
                }
                socketJobs[key] = job
            }

            session.lastActivityMs = System.currentTimeMillis()

            val payloadSize = packet.dataLength
            if (payloadSize > 0) {
                val payload = ByteArray(payloadSize)
                buffer.position(packet.dataOffset)
                buffer.get(payload, 0, payloadSize)

                if (session.aetherAssociation != null) {
                    session.aetherAssociation.send(packet.destinationIp, packet.destinationPort, payload)
                } else {
                    session.channel.send(packet.destinationIp, packet.destinationPort, payload, payloadSize)
                    PathTrafficStats.recordBytes(PathLabels.of(session.channel.network), payloadSize)
                    PathActivityMonitor.recordActivity(session.channel.network)
                }
                TrafficStats.recordBytes(payloadSize)
            }
        } catch (e: Exception) {
            AppLogger.logError(
                "Direct-UDP",
                "forward failed ${key.destinationIp}:${key.destinationPort}",
                e
            )
            closeSession(key)
        }
    }

    private fun listenForResponses(
        key: SessionKey,
        session: UdpSession,
        tunOutputStream: FileOutputStream
    ) {
        val rxBuffer = ByteArray(32767)
        while (true) {
            try {
                if (!session.channel.isOpen) break
                val payload = if (session.aetherAssociation != null) {
                    session.aetherAssociation.receive(rxBuffer)?.payload
                } else {
                    val datagram = session.channel.receive(rxBuffer, UDP_IDLE_TIMEOUT_MS.toInt())
                    if (datagram == null) {
                        // receive() returns null both on timeout and on a closed channel; the
                        // idle check below is what decides whether to keep waiting.
                        if (System.currentTimeMillis() - session.lastActivityMs >= UDP_IDLE_TIMEOUT_MS) break
                        continue
                    }
                    PathActivityMonitor.recordActivity(session.channel.network)
                    datagram.data.copyOf(datagram.length)
                }
                if (payload == null) {
                    if (System.currentTimeMillis() - session.lastActivityMs >= UDP_IDLE_TIMEOUT_MS) break
                    continue
                }
                session.lastActivityMs = System.currentTimeMillis()

                val replyPacketBytes = IpPacketBuilder.buildUdpResponsePacket(
                    srcIp = key.destinationIp,
                    srcPort = key.destinationPort,
                    dstIp = key.sourceIp,
                    dstPort = key.sourcePort,
                    data = payload,
                    dataLength = payload.size
                )

                synchronized(tunOutputStream) {
                    tunOutputStream.write(replyPacketBytes)
                    tunOutputStream.flush()
                }
                TrafficStats.recordBytes(payload.size)
            } catch (_: Exception) {
                break
            }
        }
        closeSession(key)
    }

    private fun closeSession(key: SessionKey) {
        socketJobs.remove(key)?.cancel()
        activeSessions.remove(key)?.let {
            runCatching { it.channel.close() }
            runCatching { it.aetherAssociation?.close() }
        }
    }

    /**
     * Called when a physical network genuinely goes away. UDP has no teardown handshake to
     * signal the client with, so this just frees the dead-network-bound socket immediately
     * (instead of leaving it to time out over the next 30s) - the client's own next retry
     * naturally opens a fresh session on the surviving network.
     */
    fun handleNetworkLost(deadNetwork: Network) {
        activeSessions.entries.toList().forEach { (key, session) ->
            if (session.channel.network == deadNetwork) {
                closeSession(key)
            }
        }
    }

    fun clear() {
        socketJobs.values.forEach { it.cancel() }
        socketJobs.clear()
        activeSessions.values.forEach {
            runCatching { it.channel.close() }
            runCatching { it.aetherAssociation?.close() }
        }
        activeSessions.clear()
    }
}

/** Adapts an Aether/Xray UDP association to the same [EgressUdpChannel] contract. */
private class AetherUdpChannel(
    private val association: AetherUpstream.UdpAssociation,
    override val network: Network,
) : EgressUdpChannel {

    override val label: String get() = "aether-association"

    override fun send(host: String, port: Int, payload: ByteArray, length: Int): Boolean =
        runCatching { association.send(host, port, if (length == payload.size) payload else payload.copyOf(length)) }
            .isSuccess

    override fun receive(buffer: ByteArray, timeoutMs: Int): EgressDatagram? {
        // The association owns its own socket timeout (see AetherUpstream.openUdp); a null
        // result means "nothing yet", which the caller treats like a timeout.
        val received = association.receive(buffer) ?: return null
        val payload = received.payload
        System.arraycopy(payload, 0, buffer, 0, payload.size)
        return EgressDatagram(host = "", port = 0, data = buffer, length = payload.size)
    }

    override val isOpen: Boolean get() = true

    override fun close() = association.close()
}
