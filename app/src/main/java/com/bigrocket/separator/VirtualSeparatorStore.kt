package com.bigrocket.separator

import android.content.Context

/**
 * Persists the user's Virtual Separator Engine ("موتور جداکننده مجازی") enable state across app
 * launches. Enabled (`true`) by default so dual-radio separation + real merging works immediately.
 */
object VirtualSeparatorStore {
    private const val PREFS = "bigrocket_virtual_separator"
    private const val KEY_ENABLED = "enabled"

    fun load(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

    fun save(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    fun hydrate(context: Context) {
        VirtualSeparatorRuntime.setEnabled(load(context))
    }
}
