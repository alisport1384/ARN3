package com.bigrocket.network

import android.content.Context

/**
 * Persists the user's DNS address choice in SharedPreferences (plain text, synchronous).
 * The Service reads this on its own, independent of any Activity, so the VPN tunnel
 * always uses the user's configured DNS without waiting for the UI.
 */
object DnsConfigStore {
    private const val PREFS = "bigrocket_dns"
    private const val KEY_DNS_ADDRESS = "dns_address"

    fun read(context: Context): DnsConfig {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return DnsConfig.parse(prefs.getString(KEY_DNS_ADDRESS, null) ?: "")
    }

    fun save(context: Context, config: DnsConfig) {
        val address = when (config) {
            is DnsConfig.PlainIp -> config.ip
            is DnsConfig.DohUrl -> config.url
            DnsConfig.None -> ""
        }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_DNS_ADDRESS, address)
            .apply()
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_DNS_ADDRESS)
            .apply()
    }
}
