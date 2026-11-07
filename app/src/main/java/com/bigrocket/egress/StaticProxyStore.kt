package com.bigrocket.egress

import android.content.Context
import com.bigrocket.service.AppLogger
import com.bigrocket.service.SecureTextCipher

/**
 * Persists the static-IP proxy (credentials included) encrypted at rest with the same
 * Keystore-held key XrayConfigStore uses - a proxy line usually carries a username and
 * password, which must not sit in plain SharedPreferences.
 *
 * Synchronous SharedPreferences on purpose, exactly like
 * [com.bigrocket.service.EmbeddedAetherRuntime.readUpstreamChoice]: the Service reads it in
 * onCreate with no async window where a stale default could be applied to live traffic.
 */
object StaticProxyStore {

    private const val PREFS = "bigrocket_static_proxy"
    private const val KEY_CONFIG = "config_enc"

    /**
     * Last confirmed exit IP for the saved proxy. Not a secret (it is the public address the
     * proxy presents), so it needs no encryption - but it must persist, because "verified" is
     * what turns the two physical paths into one bonded pool (no score/quality path rules).
     */
    private const val KEY_VERIFIED_IP = "verified_egress_ip"

    /** @return the stored config, or null when the feature is off / unreadable. */
    fun read(context: Context): StaticProxyConfig? {
        val encrypted = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CONFIG, null) ?: return null
        val plain = SecureTextCipher.decrypt(encrypted) ?: run {
            AppLogger.log("Egress", "stored static proxy could not be decrypted (keystore key lost) - ignored")
            return null
        }
        return runCatching { StaticProxyParser.parse(plain) }
            .getOrElse { e ->
                AppLogger.log("Egress", "stored static proxy is unparsable (${e.message}) - ignored")
                null
            }
    }

    /** The text to restore into the input box (canonical form, no password masking). */
    fun readRaw(context: Context): String? = read(context)?.canonical()

    /** Records the exit IP that was confirmed BEFORE connecting (empty/null clears it). */
    fun saveVerifiedIp(context: Context, ip: String?) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (ip.isNullOrBlank()) editor.remove(KEY_VERIFIED_IP) else editor.putString(KEY_VERIFIED_IP, ip)
        editor.apply()
    }

    fun readVerifiedIp(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_VERIFIED_IP, null)
            ?.takeIf { it.isNotBlank() }

    /** @param config null clears the stored proxy (feature off). */
    fun save(context: Context, config: StaticProxyConfig?) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (config == null) {
            // A different (or removed) proxy can never inherit the previous exit confirmation.
            editor.remove(KEY_CONFIG)
            editor.remove(KEY_VERIFIED_IP)
            editor.apply()
        } else {
            val encrypted = SecureTextCipher.encrypt(config.canonical())
            editor.putString(KEY_CONFIG, encrypted)
            // Changing the proxy invalidates the old verification (see saveVerifiedIp below for
            // where a fresh confirmation is stored).
            if (read(context)?.canonical() != config.canonical()) editor.remove(KEY_VERIFIED_IP)
            editor.apply()
        }
    }
}
