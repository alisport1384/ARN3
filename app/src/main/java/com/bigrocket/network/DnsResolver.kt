package com.bigrocket.network

import android.content.Context
import java.io.IOException
import java.net.InetAddress
import kotlinx.coroutines.withTimeout

/**
 * Resolves a user-supplied [DnsConfig] to a concrete IPv4 address that can be handed
 * to [android.net.VpnService.Builder.addDnsServer].
 *
 * - [DnsConfig.PlainIp] – the address is validated and returned as-is.
 * - [DnsConfig.DohUrl]  – the DoH endpoint is queried (RFC 8484) and the first A
 *                         record is returned as the IPv4 address.
 * - [DnsConfig.None]    – null, meaning "use the engine default".
 */
object DnsResolver {

    /**
     * Resolves [config] to an IPv4 address string, or null if no user DNS is configured.
     *
     * @param context used for SharedPreferences-free resolution (no async DB access).
     * @param config  the user's DNS configuration.
     * @return the resolved IPv4 address, or null to fall back to defaults.
     * @throws IOException if a DoH query fails (callers should catch and fall back).
     */
    @Throws(IOException::class)
    fun resolveToIp(context: Context, config: DnsConfig): String? = when (config) {
        is DnsConfig.PlainIp -> validateIp(config.ip)
        is DnsConfig.DohUrl -> DohClient.resolveFirstIp(config.url)
        DnsConfig.None -> null
    }

    /** Returns [ip] if it is a valid literal IPv4 address, otherwise null. */
    private fun validateIp(ip: String): String? {
        return runCatching {
            InetAddress.getByName(ip).also {
                require(it.hostAddress.contains(".")) { "not an IPv4 address: $ip" }
                require(it.isSiteLocalAddress.not()) { "private IPv4 address: $ip" }
            }.hostAddress
        }.getOrNull()
    }

    /**
     * Resolves [config] with a hard timeout so VPN startup is never blocked by a
     * slow or dead DoH endpoint. Falls back to null (defaults) on any failure.
     *
     * Must be called from a coroutine (suspend context).
     */
    suspend fun resolveToIpSafe(context: Context, config: DnsConfig, timeoutMs: Long = 6_000L): String? {
        return runCatching {
            withTimeout(timeoutMs) {
                resolveToIp(context, config)
            }
        }.getOrNull()
    }
}
