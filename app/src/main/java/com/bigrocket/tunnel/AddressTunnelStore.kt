package com.bigrocket.tunnel

import android.content.Context
import com.bigrocket.service.AppLogger
import com.bigrocket.service.SecureTextCipher

/**
 * Persists the address the user typed, encrypted with the same Keystore-held key the proxy and
 * Xray stores use (an address can be a private bridge hostname; it is not a secret like a proxy
 * password, but one storage policy is easier to keep auditable than two).
 *
 * Synchronous on purpose, matching [com.bigrocket.egress.StaticProxyStore]: the Service reads it
 * in `onCreate` before the tunnel carries a packet, so there must be no async window in which a
 * stale default could be applied to live traffic.
 */
object AddressTunnelStore {

    private const val PREFS = "bigrocket_address_tunnel"
    private const val KEY_SPEC = "spec_enc"

    /** @return the stored address form, or null when the feature is off / unreadable. */
    fun readRaw(context: Context): String? {
        val encrypted = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SPEC, null) ?: return null
        return SecureTextCipher.decrypt(encrypted)
    }

    /** Parses the stored value back into a spec (null when it is a proxy or unreadable). */
    fun read(context: Context, proxyParser: (String) -> Boolean): TunnelSpec? {
        val raw = readRaw(context) ?: return null
        return runCatching { TunnelSpecParser.parse(raw, proxyParser) }
            .getOrElse { e ->
                AppLogger.log("Tunnel", "stored address is unparsable (${e.message}) - ignored")
                null
            }
    }

    /** @param spec null clears the stored address (feature off). */
    fun save(context: Context, spec: TunnelSpec?) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (spec == null) {
            editor.remove(KEY_SPEC)
        } else {
            editor.putString(KEY_SPEC, SecureTextCipher.encrypt(spec.canonical()))
        }
        editor.apply()
    }
}
