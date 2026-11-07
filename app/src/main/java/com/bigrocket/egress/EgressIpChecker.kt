package com.bigrocket.egress

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.bigrocket.service.AppLogger
import com.bigrocket.service.BigRocketVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * "Is my static IP really what the internet sees?" - the only way to answer that is to ask a
 * service on the far side who it thinks we are, *from the same code path real traffic takes*.
 *
 * So this does not just call an HTTP client: it dials the configured proxy exactly like
 * [VirtualEgress.openTcp] does (network-bound, `protect()`-ed, SOCKS5/HTTP CONNECT), and then
 * performs a plain `GET /` to a "what is my IP" endpoint through that tunnel. The body is the
 * address the site saw - i.e. the proxy's exit IP when everything is wired correctly.
 *
 * Deliberately plain HTTP to port 80: an HTTP proxy can only forward a request it can read
 * (CONNECT to :80 is frequently refused), while a tunnelled plain HTTP request works
 * identically through SOCKS5 and HTTP CONNECT - one code path, no TLS trust questions.
 */
object EgressIpChecker {

    private const val TIMEOUT_MS = 10_000
    private val endpoints = listOf(
        "api.ipify.org" to "/",
        "icanhazip.com" to "/",
        "ifconfig.me" to "/ip",
    )

    data class Result(val ip: String, val endpoint: String)

    /**
     * @return the public IP as seen from the far side, or throws with a user-showable message.
     */
    suspend fun check(context: Context): Result = withContext(Dispatchers.IO) {
        val network = pickNetwork(context)
            ?: throw IllegalStateException("هیچ شبکه فعالی برای آزمایش پیدا نشد")
        val proxy = StaticProxyRuntime.activeOrNull()
        val vpnService = BigRocketVpnService.runningInstance

        var lastError: Exception? = null
        for ((host, path) in endpoints) {
            try {
                val socket = openPath(network, vpnService, proxy, host, 80)
                socket.use {
                    it.soTimeout = TIMEOUT_MS
                    val body = if (proxy != null && proxy.kind == StaticProxyKind.HTTP && !proxy.proxyOverTls) {
                        // Let the proxy itself perform the request (absolute-form GET).
                        HttpProxyProtocol.getAbsolute(it, proxy, "http://$host$path", host)
                    } else {
                        HttpProxyProtocol.getThroughTunnel(it, host, path)
                    }
                    val ip = extractIp(body)
                        ?: throw IllegalStateException("پاسخ «$host» قابل تفسیر نبود")
                    AppLogger.log("Egress", "egress IP check via $host -> $ip (${proxy?.describe() ?: "direct"})")
                    StaticProxyRuntime.noteEgressIp(ip)
                    // Survive service/app restarts: the split rules stay bypassed afterwards.
                    StaticProxyStore.saveVerifiedIp(context, ip)
                    return@withContext Result(ip, host)
                }
            } catch (e: Exception) {
                lastError = e
                AppLogger.log("Egress", "egress IP check via $host failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        val message = lastError?.message ?: "بررسی IP خروجی ناموفق بود"
        // The proxy just failed the one check that matters: it must not keep the "verified
        // endpoint" privileges (score/quality path rules off) it may have had before.
        StaticProxyRuntime.clearVerified()
        StaticProxyStore.saveVerifiedIp(context, null)
        StaticProxyRuntime.noteError(message)
        throw IllegalStateException(message, lastError)
    }

    /**
     * Opens a socket to [host]:[port], either directly or tunnelled through the active proxy,
     * on [network]. Mirrors VirtualEgress but without a VpnService requirement, so the check
     * can also run while the VPN is stopped.
     */
    private fun openPath(
        network: Network,
        vpnService: BigRocketVpnService?,
        proxy: StaticProxyConfig?,
        host: String,
        port: Int,
    ): Socket {
        val targetHost: String
        val targetPort: Int
        val needsTunnel: Boolean
        if (proxy == null || (proxy.kind == StaticProxyKind.HTTP && !proxy.proxyOverTls)) {
            // direct, or plain HTTP proxy in absolute-form mode (no tunnel needed)
            targetHost = proxy?.host ?: host
            targetPort = proxy?.port ?: port
            needsTunnel = false
        } else {
            targetHost = proxy.host
            targetPort = proxy.port
            needsTunnel = true
        }

        val socket = network.socketFactory.createSocket()
        try {
            vpnService?.protect(socket)
            socket.tcpNoDelay = true
            val address = network.getAllByName(targetHost).firstOrNull()
                ?: InetAddress.getByName(targetHost)
            socket.connect(InetSocketAddress(address, targetPort), TIMEOUT_MS)
            if (needsTunnel) {
                val channel: Socket = if (proxy!!.proxyOverTls) {
                    val factory = javax.net.ssl.SSLContext.getDefault().socketFactory
                    (factory.createSocket(socket, proxy.host, proxy.port, true) as javax.net.ssl.SSLSocket)
                        .also { it.startHandshake() }
                } else {
                    socket
                }
                when (proxy.kind) {
                    StaticProxyKind.SOCKS5 -> Socks5Protocol.connect(channel, proxy, host, port)
                    StaticProxyKind.HTTP -> HttpProxyProtocol.connect(channel, proxy, host, port)
                }
                return channel
            }
            return socket
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
    }

    private fun pickNetwork(context: Context): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        cm.activeNetwork?.let { active ->
            val caps = cm.getNetworkCapabilities(active)
            if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) return active
        }
        @Suppress("DEPRECATION")
        return cm.allNetworks.firstOrNull { network ->
            cm.getNetworkCapabilities(network)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }
    }

    /** Pulls the first IPv4/IPv6 literal out of a "what is my IP" response body. */
    fun extractIp(response: String): String? {
        val body = HttpProxyProtocol.extractBody(response).trim()
        val candidate = body.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        val match = Regex("([0-9a-fA-F:.]+)").find(candidate)?.value ?: return null
        return match.trim().takeIf { it.contains('.') || it.contains(':') }
    }
}
