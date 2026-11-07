package com.bigrocket.network

/**
 * User-supplied DNS address that replaces the hardcoded static DNS servers.
 *
 * [PlainIp]  – a literal IPv4 address (e.g. `188.114.99.0`). Used as-is.
 * [DohUrl]   – a DNS-over-HTTPS endpoint (e.g. `https://8.8.8.8/dns-query`).
 *              The endpoint is queried via RFC 8484 and the first A record
 *              is resolved to an IPv4 address.
 * [None]     – no user DNS configured; the engine default is used.
 */
sealed class DnsConfig {
    data class PlainIp(val ip: String) : DnsConfig()
    data class DohUrl(val url: String) : DnsConfig()
    object None : DnsConfig()

    companion object {
        /** Parses a raw user string into a [DnsConfig]. */
        fun parse(raw: String): DnsConfig {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return None
            if (trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true)) {
                return DohUrl(trimmed)
            }
            return PlainIp(trimmed)
        }
    }
}
