package com.bigrocket

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.bigrocket.service.AppLogger
import com.bigrocket.tunnel.AppLog
import studio.cluvex.aether.core.DiagnosticsLog
import java.io.File

class BigRocketApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagnosticsLog.init(File(filesDir, "diagnostics.log"))
        // The tunnel package is Android-free (so its tests and the desktop harness run it
        // unchanged), which means it logs through a seam. Point that seam at the app's own
        // logger once, here, so engine-2 messages land in the same diagnostics log as the rest.
        AppLog.useAppLogger { tag, message -> AppLogger.log(tag, message) }
        installAetherCrashHandler()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("aether_vpn", "Aether", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                }
            )
        }
    }

    private fun installAetherCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                DiagnosticsLog.e("crash", "FATAL on thread '${thread.name}': $throwable\n${Log.getStackTraceString(throwable)}")
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
