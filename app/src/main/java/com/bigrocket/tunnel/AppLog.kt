package com.bigrocket.tunnel

/**
 * Logging seam for the tunnel package.
 *
 * Everything in `com.bigrocket.tunnel` (except [AddressTunnelRuntime]) is compiled into the JVM
 * unit tests and into the desktop end-to-end client, so it may not touch `android.util.Log`.
 * The app installs its own logger once at startup ([useAppLogger]); without one the messages go
 * to stdout, which is exactly what the desktop e2e harness wants.
 */
object AppLog {

    fun interface Logger {
        fun log(tag: String, message: String)
    }

    @Volatile private var delegate: Logger = Logger { tag, message -> println("[$tag] $message") }

    fun useAppLogger(logger: Logger) {
        delegate = logger
    }

    fun info(tag: String, message: String) = delegate.log(tag, message)

    fun warn(tag: String, message: String) = delegate.log(tag, message)
}
