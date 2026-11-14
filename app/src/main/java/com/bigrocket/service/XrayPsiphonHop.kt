package com.bigrocket.service

import android.content.Context
import kotlinx.coroutines.runBlocking
import studio.cluvex.aether.core.PortProbe
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The `direct + xray` Psiphon hop: Xray -> Psiphon -> internet.
 *
 * ## Why this is a separate launcher and not part of [PsiphonHop]
 *
 * [PsiphonHop] only *configures* a hop that the **Aether engine** runs internally
 * (`aether --psiphon`, served on `127.0.0.1:1821`). That works because Aether speaks
 * psiphon-tunnel-core's protocol. Xray does not: there is no `--psiphon` flag in Xray and
 * no stage inside it, so in the `direct + xray` chain the client has to be run by us.
 *
 * What makes that possible is a property of psiphon-tunnel-core that this launcher leans
 * on deliberately: its `UpstreamProxyURL` config field makes the client dial its own
 * tunnel through a SOCKS proxy. Point it at Xray's local SOCKS port and the chain becomes
 *
 *   TUN -> hev/JVM router -> Xray (socks-in :1839) -> [proxy outbound = the user's config]
 *       -> dialerProxy -> [chain-out socks :BondingSocksServer.PORT] -> bonding -> network
 *
 *   ... and, with this stage armed:
 *
 *   TUN -> Xray (:1839) -> Psiphon client (socks-in :1821, its own dials proxied)
 *       -> [upstream socks :1839 = Xray] -> user's outbound -> bonding -> network
 *
 * so the exit the internet sees is a **Psiphon exit**, while the network in front of the
 * phone only ever sees Xray's traffic. That is the same end state as the Aether chain's
 * hop, reached a different way.
 *
 * ## Ports
 *
 * The client listens on [SOCKS_PORT] (1821) - deliberately the same number [PsiphonHop]
 * uses for the engine's own stage. The two are never live together: the hop belongs to
 * one chain at a time, and switching chains stops the previous chain's stages first. Using
 * one number means the rest of the app ([AetherEntry.tcpPort]) needs no second table.
 *
 * ## Lifetime
 *
 * The client must outlive the launch that started it (it survives for as long as the
 * chain is up) and it must not survive the chain going down - a leftover client holds the
 * Psiphon datastore lock, which makes the NEXT start time out. [stop] is therefore called
 * from every place that stops Xray, and [killStaleClients] sweeps orphans on startup.
 */
object XrayPsiphonHop {

    /** Local SOCKS port the Psiphon client serves the chain on (same number as the Aether hop). */
    const val SOCKS_PORT = PsiphonHop.BIND_PORT

    /** Loopback, like every other listener in this app. */
    const val SOCKS_HOST = PsiphonHop.BIND_HOST

    /** How long the client may take to open its listener before we call the launch failed. */
    const val READY_SECS = PsiphonHop.READY_SECS

    /** Set while a client started by [start] is believed to be alive. */
    @Volatile private var running = false

    @Volatile private var process: Process? = null

    // -------------------------------------------------------------------------------------
    // Which chain this applies to
    // -------------------------------------------------------------------------------------

    /**
     * The hop is offered for `direct + xray` and for NO other chain.
     *
     * Xray is the outermost stage in `AETHER_XRAY` too, so it is tempting to add it there -
     * but every chain that CONTAINS Aether already has a Psiphon mechanism: the engine runs
     * its own stage inside the tunnel ([PsiphonHop], `aether --psiphon`). Offering this one
     * as well would put a Psiphon client in front of a chain that is already exiting through
     * Psiphon - a silent double hop that halves throughput and makes the exit region
     * impossible to reason about. `XrayPsiphonHopTest` asserts the two mechanisms never
     * claim the same chain, which is what pins this down.
     *
     * `direct + xray` is the only chain with a proxy stage that has no Psiphon of its own.
     */
    fun appliesTo(choice: EmbeddedAetherRuntime.UpstreamChoice): Boolean =
        choice == EmbeddedAetherRuntime.UpstreamChoice.XRAY

    /** The settings that actually apply to [choice] right now (OFF unless this chain owns the hop). */
    fun effective(context: Context, choice: EmbeddedAetherRuntime.UpstreamChoice): PsiphonHop.Settings =
        if (appliesTo(choice)) PsiphonHop.read(context) else PsiphonHop.Settings.OFF

    /**
     * True when the hop is armed for the live chain AND its client is not running yet, or is
     * running with a different region - i.e. the caller must (re)start it.
     */
    fun needsRestart(context: Context): Boolean {
        val signature = currentSignature(context)
        if (signature == PsiphonHop.Settings.OFF.signature) return false
        if (!running || process?.isAlive != true) return true
        return activeSignature != signature
    }

    /** Identity of the launch, so a change of region restarts the client. */
    @Volatile private var activeSignature: String = ""

    private fun currentSignature(context: Context): String =
        effective(context, EmbeddedAetherRuntime.readUpstreamChoice(context)).signature

    fun isRunning(): Boolean = running && process?.isAlive == true

    // -------------------------------------------------------------------------------------
    // Start / stop
    // -------------------------------------------------------------------------------------

    /**
     * Starts the Psiphon client for the `direct + xray` chain.
     *
     * @param upstreamPort the local SOCKS port the client's OWN dials must go through - the
     *                     Xray listener, so its tunnel is carried by the chain (and therefore
     *                     by the bonding boundary beneath it).
     */
    fun start(context: Context, upstreamPort: Int = EmbeddedXrayRuntime.SOCKS_PORT) {
        val app = context.applicationContext
        val choice = EmbeddedAetherRuntime.readUpstreamChoice(app)
        val settings = effective(app, choice)
        if (!settings.enabled) {
            AppLogger.log(TAG, "hop not armed for $choice - nothing to start")
            return
        }
        if (isRunning() && activeSignature == settings.signature) {
            AppLogger.log(TAG, "client already running (${settings.signature})")
            return
        }
        stop("restarting for ${settings.signature}")

        val libDir = app.applicationInfo.nativeLibraryDir
        val bin = File(libDir, PSIPHON_BIN)
        if (!bin.isFile) {
            // Same diagnosis the Aether chain gives; the binary ships in every APK, so this
            // only ever means a broken install.
            AppLogger.logError(TAG, "psiphon client missing", IllegalStateException("no $PSIPHON_BIN in $libDir"))
            return
        }

        // Fail in seconds, readably, when the client cannot even initialise - instead of
        // leaving the chain to report "no traffic" minutes later. Reuses the Aether path's
        // check, so both chains diagnose a Go/TLS ABI panic identically.
        val check = PsiphonHop.preflight(libDir)
        if (!check.ok) {
            AppLogger.logError(TAG, "psiphon client unusable", IllegalStateException(check.reason))
            return
        }

        val killed = PsiphonHop.killStaleClients()
        if (killed > 0) AppLogger.log(TAG, "killed $killed stale psiphon client(s)")

        val launchId = PsiphonHop.launchId()
        val dir = PsiphonHop.stateDir(app, launchId)
        val config = File(dir, "psiphon.config")
        runCatching {
            config.writeText(
                buildConfig(
                    dataRoot = dir.absolutePath,
                    socksPort = SOCKS_PORT,
                    upstreamProxy = "socks5://$SOCKS_HOST:$upstreamPort",
                    region = settings.region,
                    serverEntries = PsiphonHop.serverEntries(app)?.absolutePath,
                ),
            )
        }.onFailure {
            AppLogger.logError(TAG, "could not write the psiphon config", it)
            return
        }
        // A config file holds no secrets (a port, a path, a region), but it does hold a
        // proxy address; keep the directory private like the datastore next to it.
        runCatching { config.setReadable(false, false) }

        AppLogger.log(
            TAG,
            "starting psiphon stage: exit=${PsiphonHop.describe(settings)} " +
                "listen=$SOCKS_HOST:$SOCKS_PORT upstream=socks5://$SOCKS_HOST:$upstreamPort",
        )
        val command = ArrayList<String>(4)
        command += bin.absolutePath
        command += "-config"
        command += config.absolutePath
        // Hand over the server entries the APK ships. Without them the client depends on
        // downloading a remote list, which on an Iranian mobile network is routinely
        // blocked - it then sits forever on "no servers" and looks like a broken chain.
        val entries = PsiphonHop.serverEntries(app)
        if (entries != null) {
            command += "-serverList"
            command += entries.absolutePath
        }
        val proc = runCatching {
            ProcessBuilder(command).redirectErrorStream(true).start()
        }.onFailure { AppLogger.logError(TAG, "could not exec the psiphon client", it) }
            .getOrNull() ?: return

        process = proc
        running = true
        activeSignature = settings.signature
        // Drain the client's log into ours, so a failed establishment is explainable from
        // the app's own log instead of a silent "still connecting".
        Thread({
            runCatching {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    AppLogger.log(TAG, line)
                }
            }
            running = false
        }, "psiphon-client-log").apply { isDaemon = true }.start()
        Thread({
            val code = runCatching { proc.waitFor() }.getOrDefault(-1)
            running = false
            AppLogger.log(TAG, "psiphon client exited (code=$code)")
        }, "psiphon-client-wait").apply { isDaemon = true }.start()

        // Wait for the listener: "started" must mean "actually serving the chain".
        val ready = runBlocking {
            PortProbe.awaitOpen(
                SOCKS_HOST,
                SOCKS_PORT,
                READY_SECS * 1_000L,
                isEngineAlive = { proc.isAlive },
            )
        }
        if (ready) {
            AppLogger.log(TAG, "psiphon stage live on $SOCKS_HOST:$SOCKS_PORT -> $upstreamPort")
            PsiphonHop.pruneStateDirs(app)
        } else {
            AppLogger.logError(
                TAG,
                "psiphon listener did not open on $SOCKS_HOST:$SOCKS_PORT within ${READY_SECS}s " +
                    "(clientAlive=${proc.isAlive})",
                IllegalStateException("psiphon stage not ready"),
            )
            stop("listener never opened")
        }
    }

    /** Stops the client this object started. Safe to call when nothing is running. */
    fun stop(reason: String = "") {
        val proc = process
        running = false
        activeSignature = ""
        process = null
        if (proc == null) return
        AppLogger.log(TAG, "stopping psiphon client${if (reason.isEmpty()) "" else " ($reason)"}")
        runCatching { proc.destroy() }
        // The client only ever exits on SIGKILL in practice (it is a Go program with its own
        // signal handling and a tunnel to tear down); a graceful wait is a courtesy, not a plan.
        if (!runCatching { proc.waitFor(2, TimeUnit.SECONDS) }.getOrDefault(false)) {
            runCatching { proc.destroyForcibly() }
        }
        PsiphonHop.killStaleClients()
    }

    // -------------------------------------------------------------------------------------
    // Config
    // -------------------------------------------------------------------------------------

    /**
     * Builds the psiphon-tunnel-core client config for the Xray chain.
     *
     * Pure function so it can be tested off-device: the two things that can silently break
     * the chain - the upstream proxy pointing somewhere else, and a region being emitted as
     * an empty string (which Psiphon treats as a hard filter, yielding no tunnel at all) -
     * are asserted in `XrayPsiphonHopTest`.
     *
     * The keys are exactly the ones the client accepts; anything unknown is ignored by it,
     * so this deliberately stays minimal rather than mirroring Aether's whole config.
     */
    fun buildConfig(
        dataRoot: String,
        socksPort: Int,
        upstreamProxy: String,
        region: String,
        serverEntries: String?,
    ): String {
        val json = StringBuilder()
        json.append("{\n")
        json.append("  \"DataRootDirectory\": ").append(quote(dataRoot)).append(",\n")
        json.append("  \"LocalSocksProxyPort\": ").append(socksPort).append(",\n")
        json.append("  \"LocalHttpProxyPort\": 0,\n")
        json.append("  \"DisableLocalHTTPProxy\": true,\n")
        // The client dials its OWN tunnel through this proxy. This one line is the whole
        // chain: without it the client would go straight out and the exit would not be
        // reached through Xray (nor through the bonding boundary under it).
        json.append("  \"UpstreamProxyURL\": ").append(quote(upstreamProxy)).append(",\n")
        json.append("  \"EmitDiagnosticNotices\": true,\n")
        // ONE flag, and only when a real region was chosen. An empty EgressRegion is a
        // hard filter in Psiphon: it does not mean "automatic", it means "nowhere".
        val cc = sanitizeRegion(region)
        if (cc != null) json.append("  \"EgressRegion\": ").append(quote(cc)).append(",\n")
        json.append("  \"ConnectionWorkerPoolSize\": 4,\n")
        json.append("  \"EstablishTunnelTimeoutSeconds\": ").append(READY_SECS).append(",\n")
        // The shipped server-entry list is the client's own asset format, not a config
        // field: it is handed over on the command line (`-serverList`) below, exactly like
        // the Aether chain does. `serverEntries` is therefore only logged here.
        // Trailing comma cleanup: JSON parsers in Go are strict about it.
        var text = json.toString().trimEnd()
        if (text.endsWith(",")) text = text.dropLast(1)
        text += "\n}\n"
        return text
    }

    /** The region as the client accepts it (ISO alpha-2, uppercase), or null for automatic. */
    fun sanitizeRegion(raw: String): String? {
        val cc = raw.trim().uppercase()
        return cc.takeIf { it.length == 2 && it.all { c -> c in 'A'..'Z' } }
    }

    private fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** The client binary, shipped in every APK next to the other engine libraries. */
    const val PSIPHON_BIN = "libpsiphon.so"

    private const val TAG = "XrayPsiphon"
}
