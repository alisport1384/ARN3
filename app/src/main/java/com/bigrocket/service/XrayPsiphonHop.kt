package com.bigrocket.service

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
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
 * ## Why the config mirrors the Aether engine field for field
 *
 * psiphon-tunnel-core's `Config.Commit()` refuses to start without a distribution
 * identity: `PropagationChannelId` and `SponsorId` are mandatory, and a
 * `RemoteServerListURLs` without `RemoteServerListSignaturePublicKey` is rejected too.
 * An earlier version of this launcher wrote none of them, so the console client exited
 * (`propagation channel ID is missing from the configuration file`) before it ever
 * opened its listener and the hop could never become ready. The field set below is the
 * one the working Rust launcher writes for the same chained shape
 * (`native/aether/aether/src/psiphon.rs::build_config` with an upstream proxy), and
 * `XrayPsiphonHopTest` pins the mandatory fields so they cannot silently drop out again.
 *
 * ## Readiness means a tunnel, not a socket
 *
 * The client opens its SOCKS listener while it is still establishing. Sending chain
 * traffic at a listener with no tunnel behind it just blackholes the flows, so this
 * launcher only calls the stage ready once the client reports a live tunnel (the
 * `Tunnels` notice with count > 0) AND the listener accepts a connection. Readiness is
 * published on [ready]; establishment routinely takes minutes on a censored network,
 * so the wait runs on this object's own thread - [start] returns as soon as the client
 * is spawned and never blocks the VPN service's control path (which lives under a
 * 15 s watchdog).
 */
object XrayPsiphonHop {

    /** Local SOCKS port the Psiphon client serves the chain on (same number as the Aether hop). */
    const val SOCKS_PORT = PsiphonHop.BIND_PORT

    /** Loopback, like every other listener in this app. */
    const val SOCKS_HOST = PsiphonHop.BIND_HOST

    /** How long establishment may take before the launch is called failed. */
    const val READY_SECS = PsiphonHop.READY_SECS

    // -------------------------------------------------------------------------------------
    // The distribution identity psiphon-tunnel-core refuses to start without.
    // Mirrored from psiphon.rs; the peer-to-peer network keys on these values, so they
    // must match the engine's exactly or the two launchers would join different crowds.
    // -------------------------------------------------------------------------------------

    const val PROPAGATION_CHANNEL_ID = "FFFFFFFFFFFFFFFF"
    const val SPONSOR_ID = "FFFFFFFFFFFFFFFF"
    const val CLIENT_PLATFORM = "Android_aether"
    const val CLIENT_VERSION = "1"

    const val SERVER_LIST_URL =
        "https://s3.amazonaws.com//psiphon/web/mjr4-p23r-puwl/server_list_compressed"

    /** Public key the remote server list is verified against (psiphon.rs, verbatim). */
    const val SERVER_LIST_SIGNATURE_KEY =
        "MIICIDANBgkqhkiG9w0BAQEFAAOCAg0AMIICCAKCAgEAt7Ls+/39r+T6zNW7GiVpJfzq/xvL9SBH" +
            "5rIFnk0RXYEYavax3WS6HOD35eTAqn8AniOwiH+DOkvgSKF2caqk/y1dfq47Pdymtwzp9ikpB1C5" +
            "OfAysXzBiwVJlCdajBKvBZDerV1cMvRzCKvKwRmvDmHgphQQ7WfXIGbRbmmk6opMBh3roE42Kcot" +
            "LFtqp0RRwLtcBRNtCdsrVsjiI1Lqz/lH+T61sGjSjQ3CHMuZYSQJZo/KrvzgQXpkaCTdbObxHqb6" +
            "/+i1qaVOfEsvjoiyzTxJADvSytVtcTjijhPEV6XskJVHE1Zgl+7rATr/pDQkw6DPCNBS1+Y6fy7G" +
            "stZALQXwEDN/qhQI9kWkHijT8ns+i1vGg00Mk/6J75arLhqcodWsdeG/M/moWgqQAnlZAGVtJI1O" +
            "geF5fsPpXu4kctOfuZlGjVZXQNW34aOzm8r8S0eVZitPlbhcPiR4gT/aSMz/wd8lZlzZYsje/Jr8" +
            "u/YtlwjjreZrGRmG8KMOzukV3lLmMppXFMvl4bxv6YFEmIuTsOhbLTwFgh7KYNjodLj/LsqRVfwz" +
            "31PgWQFTEPICV7GCvgVlPRxnofqKSjgTWI4mxDhBpVcATvaoBl1L/6WLbFvBsoAUBItWwctO2xal" +
            "KxF5szhGm8lccoc5MZr8kfE0uxMgsxz4er68iCID+rsCAQM="

    /**
     * The protocols offered when the client dials through the SOCKS upstream
     * (psiphon.rs `CHAINED_PROTOCOLS`): TCP only. QUIC and INPROXY variants need UDP,
     * which cannot cross a SOCKS5 hop - offering them only burns connection workers on
     * dials that can never succeed.
     */
    private val CHAINED_PROTOCOLS = listOf(
        "SSH",
        "OSSH",
        "TLS-OSSH",
        "UNFRONTED-MEEK-OSSH",
        "UNFRONTED-MEEK-HTTPS-OSSH",
        "UNFRONTED-MEEK-SESSION-TICKET-OSSH",
        "SHADOWSOCKS-OSSH",
        "FRONTED-MEEK-OSSH",
        "FRONTED-MEEK-CDN-OSSH",
        "FRONTED-MEEK-HTTP-OSSH",
        "FRONTED-MEEK-CDN-HTTP-OSSH",
    )

    // -------------------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------------------

    /** Set while a client started by [start] is believed to be alive. */
    @Volatile private var running = false

    @Volatile private var process: Process? = null

    /** True from spawn until the stage is ready, failed, or stopped. */
    @Volatile private var establishing = false

    /** Last tunnel count the client reported (`Tunnels` notice); 0 = no tunnel yet. */
    @Volatile private var tunnelCount = 0

    private val _ready = MutableStateFlow(false)

    /**
     * True only once the stage has a real tunnel and its listener accepts connections.
     * The VPN service collects this to move the chain entry onto [SOCKS_PORT] - and back
     * off it the moment the stage goes away.
     */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /** The stage has a live tunnel right now. */
    fun isTunnelReady(): Boolean = _ready.value && isRunning()

    /** A launch is in flight (spawned, not yet ready). Callers must not start a second one. */
    fun isStarting(): Boolean = establishing && process?.isAlive == true

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
     * Returns as soon as the client process is spawned; watch [ready] for the stage
     * actually carrying traffic. Establishment takes seconds-to-minutes and must not
     * block the caller.
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
        if (isStarting()) {
            AppLogger.log(TAG, "client already starting (${settings.signature})")
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
        // Owner read/write only (0600), like the engine's own config write. An earlier
        // version made the file readable by NOBODY - setReadable(false, false) - which
        // includes the client itself: the console client runs as this app's UID, so it
        // could not open its own config and died before doing anything at all.
        runCatching {
            config.setReadable(false, false)
            config.setWritable(false, false)
            config.setReadable(true, true)
            config.setWritable(true, true)
        }

        AppLogger.log(
            TAG,
            "starting psiphon stage: exit=${PsiphonHop.describe(settings)} " +
                "listen=$SOCKS_HOST:$SOCKS_PORT upstream=socks5://$SOCKS_HOST:$upstreamPort",
        )
        // Same argument order as the engine: config, data root, then the entries file.
        val command = ArrayList<String>(6)
        command += bin.absolutePath
        command += "-config"
        command += config.absolutePath
        command += "-dataRootDirectory"
        command += dir.absolutePath
        // Hand over the server entries the APK ships. Without them the client depends on
        // downloading a remote list, which on an Iranian mobile network is routinely
        // blocked - it then sits forever on "no servers" and looks like a broken chain.
        val entries = PsiphonHop.serverEntries(app)
        if (entries != null) {
            command += "-serverList"
            command += entries.absolutePath
        }
        val builder = ProcessBuilder(command).redirectErrorStream(true)
        // The static Go client ships no CA store and Android has no /etc/ssl it can find:
        // without this every TLS fetch (remote server list, tactics) dies with
        // "x509: certificate signed by unknown authority". Mirrors psiphon.rs.
        val env = builder.environment()
        val preset = env.containsKey("SSL_CERT_FILE") || env.containsKey("SSL_CERT_DIR")
        caStoreEnv(
            preset = preset,
            prefix = System.getenv("PREFIX"),
            fileExists = { File(it).isFile },
            dirExists = { File(it).isDirectory },
        )?.let { (key, value) -> env[key] = value }

        val proc = runCatching { builder.start() }
            .onFailure { AppLogger.logError(TAG, "could not exec the psiphon client", it) }
            .getOrNull() ?: return

        process = proc
        running = true
        establishing = true
        tunnelCount = 0
        _ready.value = false
        activeSignature = settings.signature
        // Drain the client's log into ours, so a failed establishment is explainable from
        // the app's own log instead of a silent "still connecting". The notices in that
        // stream are also where readiness comes from, so they are parsed on the way past.
        Thread({
            runCatching {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    noticeTunnelCount(line)?.let { tunnelCount = it }
                    AppLogger.log(TAG, line)
                }
            }
            running = false
        }, "psiphon-client-log").apply { isDaemon = true }.start()
        Thread({
            val code = runCatching { proc.waitFor() }.getOrDefault(-1)
            running = false
            establishing = false
            _ready.value = false
            AppLogger.log(TAG, "psiphon client exited (code=$code)")
        }, "psiphon-client-wait").apply { isDaemon = true }.start()
        // Establishment wait on its own thread: see the class doc. [ready] is the result.
        Thread({
            awaitTunnel(app, proc, upstreamPort)
        }, "psiphon-client-ready").apply { isDaemon = true }.start()
    }

    /**
     * Waits (off the caller's thread) until the client reports a live tunnel and its
     * listener accepts a connection, then publishes [ready]. Gives up at [READY_SECS]:
     * a stage that never tunnels must not hold the chain entry hostage, so it is stopped
     * and the chain keeps running on Xray alone.
     */
    private fun awaitTunnel(app: Context, proc: Process, upstreamPort: Int) {
        val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(READY_SECS.toLong())
        while (System.nanoTime() < deadlineNanos) {
            if (!proc.isAlive) return // the wait thread has already logged the exit
            if (tunnelCount > 0 && socketOpen(SOCKS_HOST, SOCKS_PORT)) {
                establishing = false
                _ready.value = true
                AppLogger.log(TAG, "psiphon stage live on $SOCKS_HOST:$SOCKS_PORT -> $upstreamPort")
                PsiphonHop.pruneStateDirs(app)
                return
            }
            runCatching { Thread.sleep(250L) }
        }
        if (process === proc) {
            AppLogger.logError(
                TAG,
                "psiphon tunnel did not establish within ${READY_SECS}s " +
                    "(clientAlive=${proc.isAlive} tunnels=$tunnelCount)",
                IllegalStateException("psiphon stage not ready"),
            )
            stop("tunnel never established")
        }
    }

    /** One-shot TCP connect check: is something accepting on host:port right now? */
    private fun socketOpen(host: String, port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 300)
            true
        }
    }.getOrDefault(false)

    /** Stops the client this object started. Safe to call when nothing is running. */
    fun stop(reason: String = "") {
        val proc = process
        running = false
        establishing = false
        tunnelCount = 0
        _ready.value = false
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
    // Client output (notices)
    // -------------------------------------------------------------------------------------

    private val COUNT_REGEX = Regex(""""count"\s*:\s*(\d+)""")
    private val PORT_REGEX = Regex(""""port"\s*:\s*(\d+)""")

    /**
     * The tunnel count from a `Tunnels` notice line, or null when [line] is not one.
     * The client emits one JSON notice per line on stdout; count > 0 is the only honest
     * "the stage can carry traffic" signal it offers.
     */
    fun noticeTunnelCount(line: String): Int? {
        if (!line.contains("\"Tunnels\"")) return null
        return COUNT_REGEX.find(line)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** The port from a `ListeningSocksProxyPort` notice line, or null when not one. */
    fun noticeSocksPort(line: String): Int? {
        if (!line.contains("\"ListeningSocksProxyPort\"")) return null
        return PORT_REGEX.find(line)?.groupValues?.get(1)?.toIntOrNull()
    }

    // -------------------------------------------------------------------------------------
    // CA store (mirrors psiphon.rs::ca_store_env)
    // -------------------------------------------------------------------------------------

    /**
     * The CA-store environment entry for the client process, or null to inherit.
     *
     * Pure and injectable so the selection order - preset wins, then the first existing
     * cert file, then the platform cacerts directories - is unit-tested off-device.
     */
    fun caStoreEnv(
        preset: Boolean,
        prefix: String?,
        fileExists: (String) -> Boolean,
        dirExists: (String) -> Boolean,
    ): Pair<String, String>? {
        if (preset) return null
        val files = ArrayList<String>(5)
        if (!prefix.isNullOrEmpty()) files += "$prefix/etc/tls/cert.pem"
        files += "/data/data/com.termux/files/usr/etc/tls/cert.pem"
        files += "/etc/ssl/certs/ca-certificates.crt"
        files += "/etc/pki/tls/certs/ca-bundle.crt"
        files += "/etc/ssl/cert.pem"
        files.firstOrNull(fileExists)?.let { return "SSL_CERT_FILE" to it }
        val dirs = listOf(
            "/system/etc/security/cacerts",
            "/apex/com.android.conscrypt/cacerts",
        ).filter(dirExists)
        return if (dirs.isEmpty()) null else "SSL_CERT_DIR" to dirs.joinToString(":")
    }

    // -------------------------------------------------------------------------------------
    // Config
    // -------------------------------------------------------------------------------------

    /**
     * Builds the psiphon-tunnel-core client config for the Xray chain.
     *
     * Pure function so it can be tested off-device. The field set mirrors the Aether
     * engine's chained config (`psiphon.rs::build_config` with an upstream proxy): the
     * distribution identity and the signed remote server list are mandatory
     * (`Config.Commit()` rejects the file without them), and only TCP protocols are
     * offered because the client sits behind a SOCKS5 hop. `XrayPsiphonHopTest` pins
     * all of it.
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
        // Mandatory distribution identity (see the class doc).
        json.append("  \"PropagationChannelId\": ").append(quote(PROPAGATION_CHANNEL_ID)).append(",\n")
        json.append("  \"SponsorId\": ").append(quote(SPONSOR_ID)).append(",\n")
        json.append("  \"ClientPlatform\": ").append(quote(CLIENT_PLATFORM)).append(",\n")
        json.append("  \"ClientVersion\": ").append(quote(CLIENT_VERSION)).append(",\n")
        // Remote server list: the URL travels base64-encoded, and Commit() requires the
        // signature key next to it. The shipped `-serverList` entries cover the first
        // connection; this list is what refreshes them afterwards.
        val listUrlB64 = java.util.Base64.getEncoder()
            .encodeToString(SERVER_LIST_URL.toByteArray(Charsets.UTF_8))
        json.append("  \"RemoteServerListURLs\": [{\"URL\": ").append(quote(listUrlB64)).append("}],\n")
        json.append("  \"RemoteServerListSignaturePublicKey\": ")
            .append(quote(SERVER_LIST_SIGNATURE_KEY)).append(",\n")
        // Never let the client pick an in-proxy (UDP) protocol through this hop.
        json.append("  \"InproxyTunnelProtocolPreferProbability\": 0.0,\n")
        json.append("  \"InproxyTunnelProtocolSelectionProbability\": 0.0,\n")
        // Chained shape: TCP-only protocols, no tactics (the proxy in front decides).
        json.append("  \"LimitTunnelProtocols\": [")
            .append(CHAINED_PROTOCOLS.joinToString(", ") { quote(it) }).append("],\n")
        json.append("  \"DisableTactics\": true,\n")
        json.append("  \"DataRootDirectory\": ").append(quote(dataRoot)).append(",\n")
        json.append("  \"LocalSocksProxyPort\": ").append(socksPort).append(",\n")
        json.append("  \"LocalHttpProxyPort\": 0,\n")
        json.append("  \"DisableLocalHTTPProxy\": true,\n")
        // The client dials its OWN tunnel through this proxy. This one line is the whole
        // chain: without it the client would go straight out and the exit would not be
        // reached through Xray (nor through the bonding boundary under it).
        json.append("  \"UpstreamProxyURL\": ").append(quote(upstreamProxy)).append(",\n")
        json.append("  \"EmitDiagnosticNotices\": true,\n")
        json.append("  \"EmitDiagnosticNetworkParameters\": true,\n")
        json.append("  \"EmitServerAlerts\": true,\n")
        json.append("  \"EmitBytesTransferred\": false,\n")
        // ONE flag, and only when a real region was chosen. An empty EgressRegion is a
        // hard filter in Psiphon: it does not mean "automatic", it means "nowhere".
        val cc = sanitizeRegion(region)
        if (cc != null) json.append("  \"EgressRegion\": ").append(quote(cc)).append(",\n")
        json.append("  \"ConnectionWorkerPoolSize\": 4,\n")
        json.append("  \"EstablishTunnelTimeoutSeconds\": ").append(READY_SECS).append(",\n")
        // The shipped server-entry list is the client's own asset format, not a config
        // field: it is handed over on the command line (`-serverList`), exactly like
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
