package com.bigrocket.service

import android.content.Context
import studio.cluvex.aether.core.ExitRegions
import java.io.File

/**
 * The optional "Aether -> Psiphon" hop.
 *
 * Every chain that contains Aether (Direct+Aether, Direct+Aether+Xray,
 * Direct+Xray+Aether) can switch this on: the engine then runs its own Psiphon
 * stage INSIDE the tunnel, so the exit is a Psiphon exit while the network only
 * ever sees the tunnel. This mirrors the upstream Aether app's `Aether -> Psiphon`
 * mode, which we had removed from this client in an earlier refactor (the engine
 * support was never removed - libpsiphon.so and the server-entry list still ship
 * inside every APK, and the engine still accepts `--psiphon`, `--psiphon-region`
 * and the whole AETHER_PSIPHON_* family).
 *
 * DEFAULTS AND SEMANTICS (as requested):
 *  - OFF by default. Nothing about the chain changes while it is off.
 *  - When ON, the exit country starts as AUTOMATIC = "let Psiphon pick the best",
 *    which is expressed by simply NOT passing `--psiphon-region`: the engine then
 *    writes no `EgressRegion` into the Psiphon config at all. Psiphon's own
 *    EgressRegion is a HARD filter, so announcing a country we cannot reach would
 *    give no tunnel at all rather than a worse one - which is why "automatic" must
 *    stay the default even when the hop is on.
 *  - A country may then be chosen from [ExitRegions]; only a valid two-letter code
 *    ever reaches the engine (see [sanitizedRegion]).
 *
 * The engine reads all of this through `ConnectionProfile.toArgs()` (the two
 * switches) and through the environment (`AETHER_PSIPHON_BIN`,
 * `AETHER_PSIPHON_SERVER_ENTRIES`, `AETHER_PSIPHON_DIR`, ...) - see [env].
 */
object PsiphonHop {

    /** What the user chose. [region] is "" for automatic/best, otherwise an ISO alpha-2 code. */
    data class Settings(val enabled: Boolean = false, val region: String = "") {
        /** Stable identity of a launch, so a live engine can tell it was configured differently. */
        val signature: String get() = if (!enabled) "off" else "on:${region.trim().uppercase()}"

        companion object {
            val OFF = Settings(false, "")
        }
    }

    private const val PREFS = "bigrocket_psiphon_hop"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_REGION = "region"

    /**
     * How long the engine may wait for Psiphon to produce a tunnel before it gives
     * up on this launch. Psiphon's own default is 180 s; over an Iranian mobile
     * network with a bonded upstream the establishment legitimately takes a while,
     * and a stage that gives up early looks exactly like "Aether will not connect".
     */
    const val READY_SECS = 300

    /**
     * Where the engine serves the Psiphon stage (`--psiphon-bind`). Its dials are
     * carried by the tunnel, so its exit is a Psiphon exit - see [AetherEntry] for why
     * the rest of the app must dial THIS port, not [studio.cluvex.aether.core.TunnelConfig.SOCKS_PORT].
     */
    const val BIND_PORT = 1821

    /** Loopback, like every other listener in this app. */
    const val BIND_HOST = "127.0.0.1"

    /** Extra time on top of the protocol's own budget while the Psiphon stage is armed. */
    const val LAUNCH_SLACK_MS = 60_000L

    /**
     * Verification budget while the Psiphon stage is armed: real traffic only flows
     * once Psiphon has a tunnel, which is minutes, not the 30 s a plain chain needs.
     */
    const val VERIFY_BUDGET_MS = 180_000L

    fun read(context: Context): Settings {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Settings(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            region = sanitizedRegion(prefs.getString(KEY_REGION, "") ?: ""),
        )
    }

    fun write(context: Context, settings: Settings) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putString(KEY_REGION, sanitizedRegion(settings.region))
            .apply()
    }

    /**
     * The hop only exists where Aether exists. A chain without Aether must never
     * show the switch, and - more importantly - a chain without Aether must never
     * have a saved "on" leak into it if it is ever started without Aether.
     */
    fun appliesTo(choice: EmbeddedAetherRuntime.UpstreamChoice): Boolean = when (choice) {
        EmbeddedAetherRuntime.UpstreamChoice.AETHER,
        EmbeddedAetherRuntime.UpstreamChoice.AETHER_XRAY,
        EmbeddedAetherRuntime.UpstreamChoice.XRAY_AETHER -> true
        EmbeddedAetherRuntime.UpstreamChoice.NONE,
        EmbeddedAetherRuntime.UpstreamChoice.XRAY -> false
    }

    /** The settings that actually apply to [choice] right now (OFF when Aether is not in the chain). */
    fun effective(context: Context, choice: EmbeddedAetherRuntime.UpstreamChoice): Settings =
        if (appliesTo(choice)) read(context) else Settings.OFF

    /** "" (automatic) unless the value is a real two-letter region the engine will accept. */
    fun sanitizedRegion(raw: String): String = ExitRegions.normalize(raw)

    /** Human-readable summary for logs and the status line. */
    fun describe(settings: Settings): String = when {
        !settings.enabled -> "off"
        settings.region.isEmpty() -> "on, exit=automatic (best)"
        else -> "on, exit=${settings.region}"
    }

    /**
     * Environment for the engine process while the stage is armed.
     *
     * Everything here is a path or a duration, never a secret, so the environment
     * (rather than argv) is used for consistency with the rest of the launch. The
     * one non-obvious entry is `RUST_LOG`: the engine logs its Psiphon stage through
     * its own `aether::psiphon` module, which the default `warn` level silences
     * completely - the stage then produces neither its ready line nor any error
     * line, which is how "Psiphon never became ready" used to be reported with no
     * reason attached.
     */
    fun env(
        context: Context,
        launchId: String = launchId(),
        baseLogLevel: String = "warn",
    ): Map<String, String> = buildMap {
        val app = context.applicationContext
        val bin = File(app.applicationInfo.nativeLibraryDir, "libpsiphon.so")
        put("AETHER_PSIPHON_BIN", bin.absolutePath)
        // A FRESH datastore per launch - see stateDir's doc: Psiphon's boltdb is
        // exclusively locked, so reusing one directory across restarts is what made
        // "tryDatastoreOpenDB: timeout" kill every second start.
        put("AETHER_PSIPHON_DIR", stateDir(app, launchId).absolutePath)
        put("AETHER_PSIPHON_BIND", "$BIND_HOST:$BIND_PORT")
        serverEntries(app)?.let { put("AETHER_PSIPHON_SERVER_ENTRIES", it.absolutePath) }
        put("AETHER_PSIPHON_READY_SECS", READY_SECS.toString())
        // The user's own level still decides everything else; only this one module is
        // raised, and only while the stage is armed.
        put("RUST_LOG", "${baseLogLevel.trim().ifEmpty { "warn" }},aether::psiphon=info")
    }

    /** Identity of one launch, used for its own datastore directory. */
    fun launchId(): String = System.currentTimeMillis().toString(36) +
        "-" + Integer.toHexString((Math.random() * 0xFFFF).toInt())

    /**
     * The Psiphon datastore for one launch: `files/psiphon/<launchId>`.
     *
     * WHY PER-LAUNCH (field log, .12): psiphon-tunnel-core opens its state as a
     * boltdb **with an exclusive file lock** and gives up after ~3 s with
     * "tryDatastoreOpenDB failed: ... timeout", which the engine reports as
     * "psiphon stopped before it was ready". The lock is held by the previous
     * client, and the previous client does NOT always die with the engine: the
     * engine is killed with SIGKILL (see AetherProcess.stop - it must be, a graceful
     * kill takes seconds), and a SIGKILLed parent never runs its cleanup, so the
     * psiphon child outlives it holding the lock. Every restart inside one session
     * then failed while the first start of the session usually succeeded - exactly
     * the pattern in the log (BE timed out, then "automatic" worked, then switching
     * to GB timed out again).
     *
     * A directory nobody else can be holding removes the contention by
     * construction, and costs only the cached server list (the app ships its own
     * server entries anyway, and the handshake took 2-8 s in that log either way).
     */
    fun stateDir(app: Context, launchId: String): File =
        File(File(app.applicationContext.filesDir, "psiphon"), launchId).apply { mkdirs() }

    /**
     * Deletes datastores of past launches, keeping [keep] of them.
     *
     * Only ever called right after a launch has settled, so the live directory is
     * never a candidate: the newest by name is skipped as well as the ones in use.
     */
    fun pruneStateDirs(app: Context, keep: Int = 2) {
        val root = File(app.applicationContext.filesDir, "psiphon")
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: return
        dirs.sortedByDescending { it.name }.drop(keep).forEach { stale ->
            runCatching { stale.deleteRecursively() }
        }
    }

    /**
     * Kills Psiphon clients left behind by an earlier engine.
     *
     * The companion to [stateDir]: even with a per-launch datastore, an orphaned
     * client is a live process holding the network and, on the very first launch
     * after an update, the old shared directory. Only OUR OWN children are ever
     * touched (`/proc/<pid>/cmdline` names the Psiphon binary we ship, and Android
     * only exposes processes we are allowed to see).
     */
    fun killStaleClients(excludePid: Int = 0): Int {
        val myPid = android.os.Process.myPid()
        var killed = 0
        val entries = File("/proc").listFiles() ?: return 0
        for (dir in entries) {
            val pid = dir.name.toIntOrNull() ?: continue
            if (pid == myPid || pid == excludePid) continue
            val cmdline = runCatching {
                File(dir, "cmdline").readBytes()
            }.getOrNull() ?: continue
            if (cmdline.isEmpty()) continue
            val text = String(cmdline, Charsets.ISO_8859_1).replace('\u0000', ' ')
            if (!text.contains("libpsiphon.so")) continue
            runCatching { android.os.Process.sendSignal(pid, 9) }
            killed++
        }
        return killed
    }

    /** Outcome of the launch-time sanity check on the client binary. */
    data class Preflight(val ok: Boolean, val reason: String = "")

    /**
     * Runs the Psiphon client once, for a moment, before the engine ever starts.
     *
     * WHY THIS EXISTS (the .10 field bug, see docs/PsiphonClientGoFix-fa.md): the client
     * we shipped was built with a Go
     * toolchain whose `crypto/tls.ConnectionState` has one field more than
     * `Psiphon-Labs/psiphon-tls`'s fork expects. That fork compares both structs in
     * its `init()`, so the binary panic'ed on EVERY start with
     * "struct field count mismatch: 18 vs 17" - before it ever read a config. The
     * engine only saw "psiphon stopped before it was ready" minutes later, which
     * reads exactly like a network problem and sends everyone hunting in the wrong
     * place. The panic happens in `init()`, so merely executing the binary with any
     * argument surfaces it in milliseconds: that is what this check does.
     *
     * Never throws; a client that simply exits (unknown flag, no config) is fine.
     */
    fun preflight(nativeLibDir: String, timeoutMs: Long = 4_000L): Preflight {
        val bin = File(nativeLibDir, "libpsiphon.so")
        if (!bin.isFile) {
            return Preflight(
                false,
                "کلاینت Psiphon (libpsiphon.so) در این APK نیست - برنامه را دوباره نصب کنید",
            )
        }
        val proc = runCatching {
            ProcessBuilder(bin.absolutePath, "-version").redirectErrorStream(true).start()
        }.getOrNull() ?: return Preflight(false, "اجرای کلاینت Psiphon ممکن نشد")

        val output = StringBuilder()
        val drain = Thread {
            runCatching {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(output) {
                        if (output.length < 8_000) output.append(line).append('\n')
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        runCatching {
            if (!proc.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                proc.destroyForcibly()
            }
            drain.join(500)
        }
        runCatching { proc.destroyForcibly() }

        val text = synchronized(output) { output.toString() }
        val broken = diagnose(text)
        return if (broken != null) Preflight(false, broken) else Preflight(true)
    }

    /**
     * Pure: recognizes the fatal startup patterns of a broken client and says what
     * they mean. Null when the output shows nothing fatal.
     */
    fun diagnose(output: String): String? {
        val text = output.trim()
        if (text.isEmpty()) return null
        if (text.contains("struct field count mismatch") ||
            text.contains("ConnectionState is not equal to tls.ConnectionState")
        ) {
            return "کلاینت Psiphon با موتور سازگار نیست (ناسازگاری ساختار TLS در Go) - " +
                "نسخه‌ی درست باید همراه APK باشد؛ برنامه را دوباره نصب کنید"
        }
        if (text.contains("panic:")) {
            val line = text.lineSequence().firstOrNull { it.contains("panic:") }
                ?.substringAfter("panic:")?.trim()?.take(120).orEmpty()
            return "کلاینت Psiphon هنگام شروع کرش کرد: $line"
        }
        return null
    }

    /**
     * The Psiphon server-entry list the app ships as `assets/server_entries.txt`.
     *
     * Upstream starts the console client with no server entries at all and relies on
     * the remote server-list download, which on Android is routinely blocked - the
     * engine then sits on "no servers, no tunnel" forever. The asset is copied to the
     * app's private files directory once (it lives inside the APK, so it cannot be
     * handed to the engine in place) and handed over as `-serverList`.
     */
    fun serverEntries(context: Context): File? {
        val app = context.applicationContext
        // Deliberately in the SHARED root, not inside a per-launch directory: the
        // client only reads it, so there is no lock to collide over, and extracting
        // 1.1 MB again for every launch would be pure waste.
        val target = File(File(app.filesDir, "psiphon").apply { mkdirs() }, "server_entries.txt")
        if (target.length() > 0L) return target
        return runCatching {
            val temp = File(target.parentFile, "server_entries.txt.part")
            app.assets.open("server_entries.txt").use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
            if (temp.length() <= 0L) {
                temp.delete()
                return null
            }
            if (!temp.renameTo(target)) {
                temp.delete()
                return null
            }
            target
        }.getOrNull()
    }
}
