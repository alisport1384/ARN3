package com.bigrocket.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import studio.cluvex.aether.data.ProfileStore
import studio.cluvex.aether.model.ConnectionProfile
import com.bigrocket.config.ConfigInputParser
import com.bigrocket.egress.EgressIpChecker
import com.bigrocket.egress.StaticProxyParseException
import com.bigrocket.egress.StaticProxyParser
import com.bigrocket.egress.StaticProxyRuntime
import com.bigrocket.egress.StaticProxyStore
import com.bigrocket.tunnel.AddressTunnelRuntime
import com.bigrocket.tunnel.AddressTunnelStore
import com.bigrocket.tunnel.TunnelSpec
import com.bigrocket.tunnel.TunnelSpecParseException
import com.bigrocket.tunnel.TunnelSpecParser
import com.bigrocket.config.ConfigParseException
import com.bigrocket.config.SubscriptionFetcher
import com.bigrocket.network.DnsConfig
import com.bigrocket.network.DnsConfigStore
import com.bigrocket.service.EmbeddedAetherRuntime
import com.bigrocket.service.PsiphonHop
import com.bigrocket.service.XrayPsiphonHop
import com.bigrocket.service.UpstreamHealth
import com.bigrocket.service.UpstreamLevel
import com.bigrocket.service.XrayConfigBuilder
import com.bigrocket.service.XrayConfigStore
import com.bigrocket.service.DynamicWeightCalculator
import com.bigrocket.service.NetworkPreferenceStore
import com.bigrocket.service.AppLogger
import com.bigrocket.R
import com.bigrocket.service.BigRocketVpnService
import com.bigrocket.service.BondingMode
import com.bigrocket.service.BondingStatus
import com.bigrocket.separator.VirtualSeparatorProbe
import com.bigrocket.separator.VirtualSeparatorRuntime
import com.bigrocket.separator.VirtualSeparatorStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The two modes the app can run in - exactly one embedded upstream is ever active.
 *  Owned by EmbeddedAetherRuntime now (the Service needs it independent of any Activity);
 *  kept as a typealias here so every existing UpstreamChoice.NONE/.AETHER call site below
 *  keeps working unchanged. */
typealias UpstreamChoice = EmbeddedAetherRuntime.UpstreamChoice

class MainActivity : AppCompatActivity() {

    private lateinit var tvWifiStatus: TextView
    private lateinit var tvCellularStatus: TextView
    private lateinit var tvPath3Status: TextView
    private lateinit var spinnerWifiScore: Spinner
    private lateinit var spinnerCellularScore: Spinner
    private lateinit var tvAiStatus: TextView
    private lateinit var tvAiLog: TextView
    private lateinit var tvMode: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvVpnStatus: TextView
    private lateinit var tvUpstreamStatus: TextView
    private lateinit var tvBarLegend: TextView
    private lateinit var barWifi: View
    private lateinit var barCellular: View
    private lateinit var chartThroughput: SimpleLineChartView
    private lateinit var btnToggleVpn: Button
    private lateinit var tvAetherMode: TextView
    private lateinit var spinnerAetherMode: Spinner
    private lateinit var aetherEmbeddedPanel: ComposeView
    private lateinit var xrayConfigSection: LinearLayout
    private lateinit var psiphonHopSection: LinearLayout
    private lateinit var switchPsiphonHop: androidx.appcompat.widget.SwitchCompat
    private lateinit var tvPsiphonHopStatus: TextView
    private lateinit var psiphonRegionRow: LinearLayout
    private lateinit var spinnerPsiphonRegion: Spinner
    // Last hop state written by the UI. Guards the phantom SwitchCompat/Spinner
    // callbacks (both fire once when the layout restores them) from restarting a
    // healthy engine or overwriting a saved choice - the same trap the mode spinner
    // fell into; see setUpstreamChoice's doc.
    private var psiphonHop = PsiphonHop.Settings.OFF
    private lateinit var etXrayConfig: android.widget.EditText
    private lateinit var btnSaveXrayConfig: Button
    private lateinit var btnImportXrayFile: Button
    private lateinit var toolbar: Toolbar
    // Static-IP proxy card (see egress/StaticProxyConfig.kt for the accepted input forms).
    private lateinit var etStaticProxy: android.widget.EditText
    private lateinit var btnSaveStaticProxy: Button
    private lateinit var btnClearStaticProxy: Button
    private lateinit var btnCheckEgressIp: Button
    private lateinit var tvStaticProxyStatus: TextView
    // Address tunnel card (engine 2 in the phone): see tunnel/TunnelSpec.kt for accepted forms.
    private lateinit var etTunnelAddress: android.widget.EditText
    private lateinit var btnSaveTunnelAddress: Button
    private lateinit var btnClearTunnelAddress: Button
    private lateinit var tvTunnelStatus: TextView
    // DNS address: replaces hardcoded static DNS servers.
    private lateinit var etDnsAddress: android.widget.EditText
    private lateinit var btnSaveDnsAddress: Button
    private lateinit var tvEgressIp: TextView
    // Virtual separator engine card (see com.bigrocket.separator): bit-by-bit split of a
    // SINGLE TCP stream across both radios, reassembled inside the phone - no server needed.
    private lateinit var switchVirtualSeparator: androidx.appcompat.widget.SwitchCompat
    private lateinit var tvVirtualSeparatorStatus: TextView
    private lateinit var btnVirtualSeparatorTest: Button
    private var upstreamChoice = UpstreamChoice.NONE
    // Backed by Compose state (not a plain var): AetherEmbeddedPanel reads this inside
    // aetherEmbeddedPanel.setContent{}, so a plain var mutation here was invisible to Compose -
    // onProfileChange updated the value but nothing recomposed, making every Advanced-panel
    // control (protocol, scan mode, etc.) look locked/unresponsive until some unrelated state
    // (e.g. EmbeddedAetherRuntime.enabled) happened to force a recompose.
    private var aetherProfile by mutableStateOf(ConnectionProfile())
    private lateinit var aetherProfileStore: ProfileStore

    private var isVpnRunning = false

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnService()
        } else {
            tvVpnStatus.text = "وضعیت اتصال: اجازه داده نشد"
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Whether granted or not, we still proceed - the VPN/relay itself works either way,
        // only the persistent status notification would stay hidden if denied.
        proceedToVpnPermissionCheck()
    }

    private val importXrayFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult // user cancelled the picker
        lifecycleScope.launch {
            try {
                val text = withContext(Dispatchers.IO) { readXrayConfigFile(uri) }
                etXrayConfig.setText(text)
                Toast.makeText(this@MainActivity, "فایل بارگذاری شد؛ برای اعتبارسنجی ذخیره را بزنید", Toast.LENGTH_SHORT).show()
            } catch (e: ConfigParseException) {
                Toast.makeText(this@MainActivity, e.message, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "خواندن فایل ناموفق بود: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private val saveLogLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult // user cancelled the picker
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(AppLogger.exportText().toByteArray())
            }
            Toast.makeText(this, "لاگ ذخیره شد", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "ذخیره لاگ ناموفق بود: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        AppLogger.init(this)
        // Restore the user's upstream choice BEFORE observeBondingStatus() can tick.
        // upstreamChoice used to be pure in-memory state that reset to NONE on every fresh
        // Activity instance (e.g. reopening from Recents), while the VPN service - now kept
        // alive by stopWithTask=false - was still genuinely running Aether. The per-tick
        // observer then saw "NONE but Aether is running" and called EmbeddedAetherRuntime.stop()
        // on a healthy engine, which is what produced the repeated disconnect/reconnect.
        // SharedPreferences (synchronous read) on purpose: an async DataStore read would
        // reintroduce a window where the default is still visible.
        upstreamChoice = loadUpstreamChoice()
        initViews()
        setSupportActionBar(toolbar)
        setupUpstreamIntegration()

        btnToggleVpn.setOnClickListener {
            if (isVpnRunning) {
                stopVpnService()
            } else {
                prepareAndStart()
            }
        }

        observeBondingStatus()
        requestBatteryOptimizationExemption()
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
        tvWifiStatus = findViewById(R.id.tvWifiStatus)
        tvCellularStatus = findViewById(R.id.tvCellularStatus)
        tvPath3Status = findViewById(R.id.tvPath3Status)
        spinnerWifiScore = findViewById(R.id.spinnerWifiScore)
        spinnerCellularScore = findViewById(R.id.spinnerCellularScore)
        setupNetworkScoreControls()
        tvAiStatus = findViewById(R.id.tvAiStatus)
        tvAiLog = findViewById(R.id.tvAiLog)
        tvMode = findViewById(R.id.tvMode)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvVpnStatus = findViewById(R.id.tvVpnStatus)
        tvUpstreamStatus = findViewById(R.id.tvUpstreamStatus)
        tvBarLegend = findViewById(R.id.tvBarLegend)
        barWifi = findViewById(R.id.barWifi)
        barCellular = findViewById(R.id.barCellular)
        chartThroughput = findViewById(R.id.chartThroughput)
        btnToggleVpn = findViewById(R.id.btnToggleVpn)
        tvAetherMode = findViewById(R.id.tvAetherMode)
        spinnerAetherMode = findViewById(R.id.spinnerAetherMode)
        aetherEmbeddedPanel = findViewById(R.id.aetherEmbeddedPanel)
        xrayConfigSection = findViewById(R.id.xrayConfigSection)
        psiphonHopSection = findViewById(R.id.psiphonHopSection)
        switchPsiphonHop = findViewById(R.id.switchPsiphonHop)
        tvPsiphonHopStatus = findViewById(R.id.tvPsiphonHopStatus)
        psiphonRegionRow = findViewById(R.id.psiphonRegionRow)
        spinnerPsiphonRegion = findViewById(R.id.spinnerPsiphonRegion)
        setupPsiphonHopControls()
        etXrayConfig = findViewById(R.id.etXrayConfig)
        btnSaveXrayConfig = findViewById(R.id.btnSaveXrayConfig)
        btnImportXrayFile = findViewById(R.id.btnImportXrayFile)
        setupXrayConfigControls()
        etStaticProxy = findViewById(R.id.etStaticProxy)
        btnSaveStaticProxy = findViewById(R.id.btnSaveStaticProxy)
        btnClearStaticProxy = findViewById(R.id.btnClearStaticProxy)
        btnCheckEgressIp = findViewById(R.id.btnCheckEgressIp)
        tvStaticProxyStatus = findViewById(R.id.tvStaticProxyStatus)
        tvEgressIp = findViewById(R.id.tvEgressIp)
        etDnsAddress = findViewById(R.id.etDnsAddress)
        btnSaveDnsAddress = findViewById(R.id.btnSaveDnsAddress)
        etTunnelAddress = findViewById(R.id.etTunnelAddress)
        btnSaveTunnelAddress = findViewById(R.id.btnSaveTunnelAddress)
        btnClearTunnelAddress = findViewById(R.id.btnClearTunnelAddress)
        tvTunnelStatus = findViewById(R.id.tvTunnelStatus)
        switchVirtualSeparator = findViewById(R.id.switchVirtualSeparator)
        tvVirtualSeparatorStatus = findViewById(R.id.tvVirtualSeparatorStatus)
        btnVirtualSeparatorTest = findViewById(R.id.btnVirtualSeparatorTest)
        setupStaticProxyControls()
        setupTunnelControls()
        setupDnsControls()
        setupVirtualSeparatorControls()
        observeVirtualSeparatorStatus()
    }

    /**
     * The Virtual Separator Engine card ("موتور جداکننده مجازی").
     *
     * The switch persists the operator's choice and publishes it to
     * [VirtualSeparatorRuntime], which every egress point reads per new flow - so turning it
     * off never tears down the tunnel, it only stops NEW streams from being split (already
     * separated streams finish on their own). The test button runs the real proof:
     * 256 KiB separated into 2,097,152 individual bits across two concurrent workers with
     * out-of-order arrivals, verified byte-for-byte, followed by a live single-stream
     * dual-radio download when both radios (and the VPN) are up.
     */
    private fun setupVirtualSeparatorControls() {
        VirtualSeparatorStore.hydrate(this)
        val enabled = VirtualSeparatorStore.load(this)
        switchVirtualSeparator.setOnCheckedChangeListener(null)
        switchVirtualSeparator.isChecked = enabled
        switchVirtualSeparator.setOnCheckedChangeListener { _, checked ->
            VirtualSeparatorStore.save(this, checked)
            VirtualSeparatorRuntime.setEnabled(checked)
            AppLogger.log("UI", "virtual separator enabled=$checked")
            Toast.makeText(
                this,
                if (checked) {
                    "موتور جداکننده مجازی روشن شد: پیلود یک اتصال بین Wi-Fi و دیتا تفکیک می‌شود"
                } else {
                    "موتور جداکننده مجازی خاموش شد: هر اتصال فقط از یک مسیر می‌رود"
                },
                Toast.LENGTH_SHORT,
            ).show()
        }

        btnVirtualSeparatorTest.setOnClickListener {
            btnVirtualSeparatorTest.isEnabled = false
            val service = BigRocketVpnService.runningInstance
            lifecycleScope.launch {
                try {
                    val report = VirtualSeparatorProbe.runLiveDualRadioTest(
                        vpnService = service,
                        wifi = service?.sandboxWifiNetwork(),
                        cellular = service?.sandboxCellularNetwork(),
                    )
                    AppLogger.log("UI", "virtual separator test: ${report.note}")
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("نتیجه تست جداکننده مجازی")
                        .setMessage(report.formatPersian())
                        .setPositiveButton("باشه", null)
                        .show()
                } catch (e: Exception) {
                    AppLogger.log("UI", "virtual separator test failed: ${e.javaClass.simpleName}: ${e.message}")
                    Toast.makeText(
                        this@MainActivity,
                        "تست ناموفق بود: ${e.message ?: e.javaClass.simpleName}",
                        Toast.LENGTH_LONG,
                    ).show()
                } finally {
                    btnVirtualSeparatorTest.isEnabled = true
                }
            }
        }
    }

    /** Mirrors [VirtualSeparatorRuntime.status] into the card on every publish. */
    private fun observeVirtualSeparatorStatus() {
        lifecycleScope.launch {
            VirtualSeparatorRuntime.status.collectLatest { status ->
                if (!::switchVirtualSeparator.isInitialized) return@collectLatest
                if (switchVirtualSeparator.isChecked != status.enabled) {
                    switchVirtualSeparator.setOnCheckedChangeListener(null)
                    switchVirtualSeparator.isChecked = status.enabled
                    switchVirtualSeparator.setOnCheckedChangeListener { _, checked ->
                        VirtualSeparatorStore.save(this@MainActivity, checked)
                        VirtualSeparatorRuntime.setEnabled(checked)
                    }
                }
                tvVirtualSeparatorStatus.text = status.summary()
            }
        }
    }

    /**
     * The static-IP proxy card.
     *
     * Saving here does two things: it persists the (encrypted) line via [StaticProxyStore],
     * and it publishes it to [StaticProxyRuntime], which is the single object every egress
     * point of the Service reads per new flow. That means the change is applied to the next
     * connection immediately - no disconnect/reconnect, and no Intent round-trip to the
     * Service - while already-established flows keep running on the old egress until they
     * close on their own (same policy as every other live setting in this app).
     */
    private fun setupStaticProxyControls() {
        val stored = StaticProxyStore.read(this)
        // The Service may have been started by the system before any Activity existed, so the
        // runtime and the store are reconciled here rather than assumed to be in sync.
        StaticProxyRuntime.install(stored)
        etStaticProxy.setText(stored?.canonical().orEmpty())

        btnSaveStaticProxy.setOnClickListener {
            val raw = etStaticProxy.text?.toString()?.trim().orEmpty()
            val parsed = try {
                StaticProxyParser.parse(raw)
            } catch (e: StaticProxyParseException) {
                Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            try {
                StaticProxyStore.save(this, parsed)
            } catch (e: Exception) {
                AppLogger.log("UI", "static proxy save failed: ${e.javaClass.simpleName}")
                Toast.makeText(this, "ذخیره امن پروکسی ناموفق بود", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            StaticProxyRuntime.install(parsed)
            if (parsed != null && parsed.autoPort) {
                // Bare IP: tell the user right away which port really answers, instead of
                // letting the first connection fail with a generic timeout.
                detectProxyPort(parsed)
                return@setOnClickListener
            }
            if (parsed == null) {
                etStaticProxy.setText("")
                Toast.makeText(this, "استاتیک آی‌پی خاموش شد؛ خروجی مستقیم است", Toast.LENGTH_SHORT).show()
            } else {
                etStaticProxy.setText(parsed.canonical())
                AppLogger.log("UI", "static proxy saved: ${parsed.describe()}")
                Toast.makeText(
                    this,
                    "ذخیره شد: ${parsed.describe()} — از اتصال بعدی اعمال می‌شود",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            if (BigRocketVpnService.runningInstance != null) {
                startService(
                    Intent(this, BigRocketVpnService::class.java)
                        .setAction(BigRocketVpnService.ACTION_PROXY_CHANGED)
                )
            }
        }

        btnClearStaticProxy.setOnClickListener {
            etStaticProxy.setText("")
            runCatching { StaticProxyStore.save(this, null) }
            StaticProxyRuntime.install(null)
            tvEgressIp.text = ""
            Toast.makeText(this, "استاتیک آی‌پی حذف شد", Toast.LENGTH_SHORT).show()
        }

        btnCheckEgressIp.setOnClickListener {
            btnCheckEgressIp.isEnabled = false
            tvEgressIp.text = "در حال بررسی..."
            lifecycleScope.launch {
                try {
                    val result = EgressIpChecker.check(this@MainActivity)
                    tvEgressIp.text = "IP خروجی: ${result.ip}"
                    Toast.makeText(
                        this@MainActivity,
                        "آی‌پی خروجی از دید ${result.endpoint}: ${result.ip}",
                        Toast.LENGTH_LONG,
                    ).show()
                } catch (e: Exception) {
                    tvEgressIp.text = "بررسی ناموفق"
                    Toast.makeText(
                        this@MainActivity,
                        e.message ?: "بررسی آی‌پی خروجی ناموفق بود",
                        Toast.LENGTH_LONG,
                    ).show()
                } finally {
                    btnCheckEgressIp.isEnabled = true
                }
            }
        }

        lifecycleScope.launch {
            StaticProxyRuntime.status.collectLatest { status ->
                tvStaticProxyStatus.text = status.summary()
                if (status.egressIp.isNotEmpty()) {
                    tvEgressIp.text = "IP خروجی: ${status.egressIp}"
                }
                if (status.detectedPort > 0 && etStaticProxy.text?.toString()?.contains(status.detectedPort.toString()) == false) {
                    // Reflect the discovered port in the box, so the value the user sees is
                    // the one actually being used.
                    val current = StaticProxyParser.parse(etStaticProxy.text?.toString()) ?: return@collectLatest
                    if (current.autoPort) {
                        etStaticProxy.setText(
                            current.copy(port = status.detectedPort, autoPort = false).canonical()
                        )
                    }
                }
            }
        }
    }

    /**
     * The ADDRESS TUNNEL card (engine 2 in the phone).
     *
     * Saving is deliberately "test then save": the user asked for "if the address exists, use
     * engine 2 after testing; if not, go straight to sandbox + engine 1". So the button runs the
     * real self-test (HELLO + PING over both radios) before anything is persisted or activated,
     * and a failure is reported with the exact reason instead of silently changing the egress.
     */
    private fun setupTunnelControls() {
        val stored = AddressTunnelStore.readRaw(this)
        etTunnelAddress.setText(stored.orEmpty())

        btnSaveTunnelAddress.setOnClickListener {
            val raw = etTunnelAddress.text?.toString()?.trim().orEmpty()
            if (raw.isEmpty()) {
                Toast.makeText(this, "آدرس را وارد کنید", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val spec = try {
                TunnelSpecParser.parse(raw) { text -> runCatching { StaticProxyParser.parse(text) }.isSuccess }
            } catch (e: TunnelSpecParseException) {
                Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (spec == null) {
                // Looks like a proxy, not an address: hand it to the static-proxy card instead of
                // quietly doing something else with it.
                etStaticProxy.setText(raw)
                Toast.makeText(
                    this,
                    "این یک پروکسی است، نه آدرس تونل؛ در کادر استاتیک آی‌پی ذخیره شد",
                    Toast.LENGTH_LONG,
                ).show()
                return@setOnClickListener
            }
            startTunnelTest(spec)
        }

        btnClearTunnelAddress.setOnClickListener {
            etTunnelAddress.setText("")
            runCatching { AddressTunnelStore.save(this, null) }
            AddressTunnelRuntime.install(this, null)
            notifyServiceOfTunnelChange()
            Toast.makeText(this, "تونل آدرسی حذف شد", Toast.LENGTH_SHORT).show()
        }

        lifecycleScope.launch {
            AddressTunnelRuntime.status.collectLatest { status ->
                tvTunnelStatus.text = status.summary()
            }
        }
    }

    /** Runs the self-test off the UI thread, then persists + activates only if it passed. */
    private fun startTunnelTest(spec: TunnelSpec) {
        btnSaveTunnelAddress.isEnabled = false
        btnSaveTunnelAddress.text = getString(R.string.address_tunnel_testing)
        tvTunnelStatus.text = getString(R.string.address_tunnel_testing)
        lifecycleScope.launch {
            // Install wakes the runtime up; onNetworksUpdated has already given it the radios by
            // now (the Service publishes them), and install() runs the same self-test the card
            // promises.
            val result = withContext(Dispatchers.IO) {
                AddressTunnelRuntime.install(this@MainActivity, spec, testNow = true)
                AddressTunnelRuntime.status.value
            }
            btnSaveTunnelAddress.isEnabled = true
            btnSaveTunnelAddress.text = getString(R.string.address_tunnel_save)
            if (result.ready) {
                runCatching { AddressTunnelStore.save(this@MainActivity, spec) }
                etTunnelAddress.setText(spec.canonical())
                toast(
                    "تونل تأیید شد: ${spec.endpointLabel()} (${result.pipes} مسیر، ${result.rttMs}ms) — موتور ۲ فعال است",
                )
            } else {
                // Not saved on purpose: an address that did not answer must not come back after a
                // restart and flip the egress to something dead.
                runCatching { AddressTunnelStore.save(this@MainActivity, null) }
                toast(
                    "آدرس پاسخ نداد: ${result.lastError.ifEmpty { result.detail }} — همان موتور ۱ / خروجی مستقیم می‌ماند",
                )
            }
            notifyServiceOfTunnelChange()
        }
    }

    private fun notifyServiceOfTunnelChange() {
        if (BigRocketVpnService.runningInstance != null) {
            startService(
                Intent(this, BigRocketVpnService::class.java)
                    .setAction(BigRocketVpnService.ACTION_TUNNEL_CHANGED)
            )
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    /**
     * Runs ProxyPortDetector for a portless proxy on a background thread and reports the
     * outcome - found port/protocol, or a message naming every port that was tried. This is
     * the difference between "the app rejects my IP" and "the app tells me what is wrong with
     * my IP".
     */
    private fun detectProxyPort(config: com.bigrocket.egress.StaticProxyConfig) {
        btnSaveStaticProxy.isEnabled = false
        btnSaveStaticProxy.text = "در حال تشخیص پورت..."
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                com.bigrocket.egress.ProxyPortDetector.detect(host = config.host)
            }
            btnSaveStaticProxy.isEnabled = true
            btnSaveStaticProxy.text = "ذخیره"
            val found = result.first
            if (found != null) {
                val resolved = config.copy(port = found.port, kind = found.kind, autoPort = false)
                runCatching { StaticProxyStore.save(this@MainActivity, resolved) }
                StaticProxyRuntime.install(resolved)
                StaticProxyRuntime.noteDetectedPort(found.port, found.kind)
                etStaticProxy.setText(resolved.canonical())
                AppLogger.log("UI", "auto port detection: ${config.host} -> ${found.port} (${found.detail})")
                Toast.makeText(
                    this@MainActivity,
                    "پورت پیدا شد: ${found.port} (${found.detail}) — ذخیره شد",
                    Toast.LENGTH_LONG,
                ).show()
                if (BigRocketVpnService.runningInstance != null) {
                    startService(
                        Intent(this@MainActivity, BigRocketVpnService::class.java)
                            .setAction(BigRocketVpnService.ACTION_PROXY_CHANGED)
                    )
                }
            } else {
                val message = com.bigrocket.egress.ProxyPortDetector.describeFailure(config.host, result.second)
                StaticProxyRuntime.noteTunnelFailed(message)
                AppLogger.log("UI", "auto port detection failed: $message")
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** DNS address card: replaces hardcoded static DNS servers. Plain IP or DoH URL. */
    private fun setupDnsControls() {
        val stored = DnsConfigStore.read(this)
        val text = when (stored) {
            is DnsConfig.PlainIp -> stored.ip
            is DnsConfig.DohUrl -> stored.url
            DnsConfig.None -> ""
        }
        etDnsAddress.setText(text)

        btnSaveDnsAddress.setOnClickListener {
            val raw = etDnsAddress.text?.toString()?.trim().orEmpty()
            val config = DnsConfig.parse(raw)
            try {
                DnsConfigStore.save(this, config)
            } catch (e: Exception) {
                AppLogger.log("UI", "dns save failed: ${e.javaClass.simpleName}")
                Toast.makeText(this, "ذخیره دی ان اس ناموفق بود", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val desc = when (config) {
                is DnsConfig.PlainIp -> "آی‌پی: ${config.ip}"
                is DnsConfig.DohUrl -> "دی‌اوچ‌ی: ${config.url}"
                DnsConfig.None -> "پیش‌فرض هسته"
            }
            AppLogger.log("UI", "dns saved: $desc")
            Toast.makeText(
                this,
                "ذخیره شد: $desc — از اتصال بعدی اعمال می‌شود",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun setupXrayConfigControls() {
        XrayConfigStore.read(this)?.let { etXrayConfig.setText(it) }
        btnImportXrayFile.setOnClickListener {
            importXrayFileLauncher.launch(arrayOf("*/*"))
        }
        btnSaveXrayConfig.setOnClickListener {
            val raw = etXrayConfig.text?.toString()?.trim().orEmpty()
            if (raw.isEmpty()) {
                Toast.makeText(this, "کانفیگ Xray خالی است", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (ConfigInputParser.isSubscriptionUrl(raw)) {
                // The Service reads the stored config synchronously, so a subscription URL
                // is downloaded here and its body (the resolved links) is what gets saved.
                btnSaveXrayConfig.isEnabled = false
                lifecycleScope.launch {
                    try {
                        val body = withContext(Dispatchers.IO) { SubscriptionFetcher.fetch(raw) }
                        etXrayConfig.setText(body.trim())
                        saveXrayConfig(body.trim())
                    } catch (e: ConfigParseException) {
                        Toast.makeText(this@MainActivity, e.message, Toast.LENGTH_LONG).show()
                    } finally {
                        btnSaveXrayConfig.isEnabled = true
                    }
                }
            } else {
                saveXrayConfig(raw)
            }
        }
    }

    /** Reads a config file the user picked; text only, size-capped, strict UTF-8. */
    private fun readXrayConfigFile(uri: Uri): String {
        val max = ConfigInputParser.MAX_INPUT_CHARS
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw ConfigParseException("فایل قابل خواندن نیست")
        if (bytes.size > max) throw ConfigParseException("فایل بیش از حد بزرگ است")
        if (bytes.size >= 4 && String(bytes, 0, 4, Charsets.US_ASCII) == "NPVS") {
            throw ConfigParseException(ConfigInputParser.NAPSTERNET_UNSUPPORTED)
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString().trim()
        } catch (_: java.nio.charset.CharacterCodingException) {
            throw ConfigParseException("فایل متنی نیست؛ فقط فایل‌های متنی یا JSON پشتیبانی می‌شوند")
        }
    }

    private fun saveXrayConfig(raw: String) {
        // Validate by actually building the runtime config (ports are irrelevant
        // for validation) so a broken paste is rejected here, not at connect time.
        val count = try {
            XrayConfigBuilder.build(raw, 1, 2)
            ConfigInputParser.parse(raw).size
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "کانفیگ Xray نامعتبر است", Toast.LENGTH_LONG).show()
            return
        }
        try {
            XrayConfigStore.save(this, raw)
        } catch (e: Exception) {
            AppLogger.log("UI", "xray config save failed: ${e.javaClass.simpleName}")
            Toast.makeText(this, "ذخیره امن کانفیگ ناموفق بود", Toast.LENGTH_LONG).show()
            return
        }
        AppLogger.log("UI", "xray config saved (${raw.length} chars, $count config(s))")
        Toast.makeText(
            this,
            if (count > 1) "کانفیگ Xray ذخیره شد ($count کانفیگ یافت شد؛ اولین مورد استفاده می‌شود)"
            else "کانفیگ Xray ذخیره شد",
            Toast.LENGTH_SHORT,
        ).show()
        // If an Xray chain is live, the Service re-checks and restarts the core
        // against the new config (see EmbeddedXrayRuntime.needsRestart).
        if (BigRocketVpnService.runningInstance != null &&
            (upstreamChoice == UpstreamChoice.XRAY ||
                upstreamChoice == UpstreamChoice.AETHER_XRAY ||
                upstreamChoice == UpstreamChoice.XRAY_AETHER)
        ) {
            startService(
                Intent(this, BigRocketVpnService::class.java)
                    .setAction(BigRocketVpnService.ACTION_UPSTREAM_CHANGED)
            )
        }
    }

    private fun setupNetworkScoreControls() {
        val values = arrayOf("1", "2", "3", "4", "5")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, values)

        spinnerWifiScore.adapter = adapter
        spinnerCellularScore.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            values
        )

        spinnerWifiScore.setSelection(NetworkPreferenceStore.wifiScore(this) - 1, false)
        spinnerCellularScore.setSelection(NetworkPreferenceStore.cellularScore(this) - 1, false)

        spinnerWifiScore.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit

            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                NetworkPreferenceStore.setWifiScore(this@MainActivity, position + 1)
                DynamicWeightCalculator.configureUserScores(
                    wifiScore = position + 1,
                    cellularScore = NetworkPreferenceStore.cellularScore(this@MainActivity)
                )
            }
        }

        spinnerCellularScore.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit

            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                NetworkPreferenceStore.setCellularScore(this@MainActivity, position + 1)
                DynamicWeightCalculator.configureUserScores(
                    wifiScore = NetworkPreferenceStore.wifiScore(this@MainActivity),
                    cellularScore = position + 1
                )
            }
        }

        DynamicWeightCalculator.configureUserScores(
            wifiScore = NetworkPreferenceStore.wifiScore(this),
            cellularScore = NetworkPreferenceStore.cellularScore(this)
        )
    }

    private fun setupUpstreamIntegration() {
        aetherProfileStore = ProfileStore(applicationContext)
        aetherEmbeddedPanel.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        aetherEmbeddedPanel.setContent {
            val aetherRuntimeEnabled = EmbeddedAetherRuntime.enabled.collectAsState().value
            // Was a bare MaterialTheme{} (Android's default M3 baseline colors, e.g.
            // stock purple) - completely disconnected both from BigRocket's own
            // window theme (Theme.AetherMobile / colors.xml: aether_background
            // #0A0E1A, aether_primary #4C8DFF, already the Aether navy palette) and
            // from Aether's own screens (HomeScreen etc., which use AetherTheme).
            // That's exactly why this embedded panel visually clashed with the rest
            // of the app. AetherTheme is the same theme Aether's own UI uses, so this
            // panel now matches it - and therefore matches BigRocket, since BigRocket
            // was already themed to Aether's own palette.
            studio.cluvex.aether.ui.theme.AetherTheme {
                Surface {
                    AetherEmbeddedPanel(
                        profile = aetherProfile,
                        onProfileChange = { profile ->
                            AppLogger.log(
                                "UI",
                                "aether profile changed: protocol=${profile.protocol} h2=${profile.masqueHttp2} " +
                                    "scan=${profile.scanMode} endpoint=${profile.endpointMode} noize=${profile.noize}",
                            )
                            aetherProfile = profile
                            lifecycleScope.launch { aetherProfileStore.save(profile) }
                        },
                        enabled = aetherRuntimeEnabled,
                        onEnabledChange = { selected ->
                            // Inside the combined chains this toggle only governs the Aether
                            // stage: off collapses Direct+Aether+Xray / Direct+Xray+Aether to
                            // Direct+Xray, it never silently drops the Xray stage the user
                            // explicitly selected.
                            val target = when {
                                selected && upstreamChoice == UpstreamChoice.AETHER_XRAY -> UpstreamChoice.AETHER_XRAY
                                selected && upstreamChoice == UpstreamChoice.XRAY_AETHER -> UpstreamChoice.XRAY_AETHER
                                selected -> UpstreamChoice.AETHER
                                upstreamChoice == UpstreamChoice.AETHER_XRAY ||
                                    upstreamChoice == UpstreamChoice.XRAY_AETHER -> UpstreamChoice.XRAY
                                else -> UpstreamChoice.NONE
                            }
                            setUpstreamChoice(target)
                        },
                    )
                }
            }
        }

        lifecycleScope.launch {
            aetherProfileStore.profile.collectLatest { aetherProfile = it.copy(proxyMode = true) }
        }



        val options = arrayOf("Direct", "Direct + Aether", "Direct + Xray", "Direct + Aether + Xray", "Direct + Xray + Aether")
        spinnerAetherMode.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
        // A Spinner ALWAYS fires onItemSelected once after layout with whatever position is
        // selected at that moment - it is not a user action. A fresh adapter defaults to
        // position 0 ("BigRocket only" = NONE), so without pre-selecting the restored choice
        // here that phantom callback called setUpstreamChoice(NONE): it overwrote the saved
        // choice, showed "Direct", and stop()ped a healthy running Aether engine the moment
        // the app was opened (connection alive until entering the app, dropped on entering).
        // Selecting the restored position BEFORE attaching the listener makes that phantom
        // callback a no-op (setUpstreamChoice returns early when the choice is unchanged).
        spinnerAetherMode.setSelection(spinnerPositionFor(upstreamChoice), false)
        spinnerAetherMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val selected = when (position) {
                    1 -> UpstreamChoice.AETHER
                    2 -> UpstreamChoice.XRAY
                    3 -> UpstreamChoice.AETHER_XRAY
                    4 -> UpstreamChoice.XRAY_AETHER
                    else -> UpstreamChoice.NONE
                }
                setUpstreamChoice(selected)
            }
        }
        renderUpstreamChoice()
    }

    /**
     * Single place that switches the active upstream. Always stops whichever runtime is
     * NOT the new choice first - a stray "both active at once" state would race both
     * SOCKS upstreams for BigRocketVpnService.setUpstreamMode.
     */
    private fun setUpstreamChoice(choice: UpstreamChoice) {
        if (upstreamChoice == choice) return
        AppLogger.log("Upstream", "switching $upstreamChoice -> $choice")
        upstreamChoice = choice
        saveUpstreamChoice(choice)
        renderUpstreamChoice()
        // Keep the mode spinner consistent when the choice changes from somewhere else
        // (e.g. the Aether panel toggle). The phantom onItemSelected this triggers is a
        // no-op: setUpstreamChoice returns early on an unchanged choice.
        if (spinnerAetherMode.selectedItemPosition != spinnerPositionFor(choice)) {
            spinnerAetherMode.setSelection(spinnerPositionFor(choice), false)
        }

        // The Service owns the engine's actual start/stop now (see applyUpstreamChoice() in
        // BigRocketVpnService) - this only wakes it up to re-check the choice we just saved,
        // and only if it's actually alive; if it's not running yet, the choice will simply be
        // picked up the next time setupVpn() runs.
        if (BigRocketVpnService.runningInstance != null) {
            startService(Intent(this, BigRocketVpnService::class.java).setAction(BigRocketVpnService.ACTION_UPSTREAM_CHANGED))
        }
    }

    // Thin wrappers so existing call sites are unchanged - single source of truth is now
    // EmbeddedAetherRuntime, which the Service reads independently of this Activity.
    private fun loadUpstreamChoice(): UpstreamChoice = EmbeddedAetherRuntime.readUpstreamChoice(this)

    private fun saveUpstreamChoice(choice: UpstreamChoice) = EmbeddedAetherRuntime.saveUpstreamChoice(this, choice)

    private fun spinnerPositionFor(choice: UpstreamChoice): Int = when (choice) {
        UpstreamChoice.NONE -> 0
        UpstreamChoice.AETHER -> 1
        UpstreamChoice.XRAY -> 2
        UpstreamChoice.AETHER_XRAY -> 3
        UpstreamChoice.XRAY_AETHER -> 4
    }

    private fun renderUpstreamChoice() {
        tvAetherMode.text = when (upstreamChoice) {
            UpstreamChoice.AETHER -> "مسیر بعد از Bonding: Direct → Aether"
            UpstreamChoice.XRAY -> "مسیر بعد از Bonding: Direct → Xray"
            UpstreamChoice.AETHER_XRAY -> "مسیر بعد از Bonding: Direct → Aether → Xray"
            UpstreamChoice.XRAY_AETHER -> "مسیر بعد از Bonding: Direct → Xray → Aether"
            UpstreamChoice.NONE -> "مسیر بعد از Bonding: Direct"
        }
        // Aether's settings panel only makes sense while Aether is part of the active
        // chain - showing it unconditionally exposed knobs (protocol, scan mode, ...)
        // for an engine that wasn't even the active upstream.
        val usesAether = upstreamChoice == UpstreamChoice.AETHER ||
            upstreamChoice == UpstreamChoice.AETHER_XRAY ||
            upstreamChoice == UpstreamChoice.XRAY_AETHER
        aetherEmbeddedPanel.visibility = if (usesAether) View.VISIBLE else View.GONE
        // Same reasoning for the Xray config input: only visible for the Xray chains.
        val usesXray = upstreamChoice == UpstreamChoice.XRAY ||
            upstreamChoice == UpstreamChoice.AETHER_XRAY ||
            upstreamChoice == UpstreamChoice.XRAY_AETHER
        xrayConfigSection.visibility = if (usesXray) View.VISIBLE else View.GONE

        // The optional Psiphon hop exists in every chain that has a stage to hang it on:
        //   - Aether chains: the engine runs its own in-tunnel Psiphon stage (PsiphonHop);
        //   - Xray-outermost chains (Direct+Xray, Direct+Aether+Xray): this app runs the
        //     client itself and chains it in front of Xray (XrayPsiphonHop).
        // NONE has no proxy stage at all, so it must not show the switch.
        val hopSupported = PsiphonHop.appliesTo(upstreamChoice) ||
            XrayPsiphonHop.appliesTo(upstreamChoice)
        psiphonHopSection.visibility = if (hopSupported) View.VISIBLE else View.GONE
        // The saved switch is shared by both chains (one user intent, two mechanisms).
        // effective() reflects whether THIS chain can honour it.
        syncPsiphonHopControls(
            if (PsiphonHop.appliesTo(upstreamChoice)) {
                PsiphonHop.effective(this, upstreamChoice)
            } else {
                XrayPsiphonHop.effective(this, upstreamChoice)
            },
        )
    }

    /**
     * Ties the optional Psiphon hop card to persistence and to the Service.
     *
     * Everything here is deliberately tiny: the Service owns the engine (see
     * EmbeddedAetherRuntime's class doc), so the UI only (1) stores the choice and
     * (2) nudges the Service with ACTION_UPSTREAM_CHANGED, which makes
     * applyUpstreamChoice() notice - via hopNeedsRestart() - that the live engine was
     * launched with a different hop and restart it. Nothing is restarted from here.
     */
    private fun setupPsiphonHopControls() {
        val labels = studio.cluvex.aether.core.ExitRegions.values.map {
            studio.cluvex.aether.core.ExitRegions.label(it)
        }
        spinnerPsiphonRegion.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        spinnerPsiphonRegion.setSelection(0, false)
        spinnerPsiphonRegion.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val region = studio.cluvex.aether.core.ExitRegions.values
                    .getOrElse(position) { "" }
                if (region == psiphonHop.region) return
                applyPsiphonHop(psiphonHop.copy(region = region))
            }
        }
        switchPsiphonHop.setOnCheckedChangeListener { _, checked ->
            if (checked == psiphonHop.enabled) return@setOnCheckedChangeListener
            applyPsiphonHop(psiphonHop.copy(enabled = checked))
        }
        syncPsiphonHopControls(PsiphonHop.effective(this, loadUpstreamChoice()))
    }

    /** Mirrors [settings] into the controls without re-entering the change listeners. */
    private fun syncPsiphonHopControls(settings: PsiphonHop.Settings) {
        val normalized = PsiphonHop.Settings(
            enabled = settings.enabled,
            region = PsiphonHop.sanitizedRegion(settings.region),
        )
        if (switchPsiphonHop.isChecked != normalized.enabled) {
            switchPsiphonHop.setOnCheckedChangeListener(null)
            switchPsiphonHop.isChecked = normalized.enabled
            switchPsiphonHop.setOnCheckedChangeListener { _, checked ->
                if (checked != psiphonHop.enabled) applyPsiphonHop(psiphonHop.copy(enabled = checked))
            }
        }
        val rowPosition = studio.cluvex.aether.core.ExitRegions.values
            .indexOf(normalized.region)
            .coerceAtLeast(0)
        if (spinnerPsiphonRegion.selectedItemPosition != rowPosition) {
            spinnerPsiphonRegion.setSelection(rowPosition, false)
        }
        psiphonHop = normalized
        psiphonRegionRow.visibility = if (normalized.enabled) View.VISIBLE else View.GONE
        val outerIsXray = XrayPsiphonHop.appliesTo(loadUpstreamChoice())
        tvPsiphonHopStatus.text = when {
            !normalized.enabled && outerIsXray -> "خاموش — ترافیک فقط از تونل Xray بیرون می‌رود"
            !normalized.enabled -> "خاموش — ترافیک فقط از تونل Aether بیرون می‌رود"
            normalized.region.isEmpty() ->
                "روشن — خروجی: خودکار (بهترین انتخاب Psiphon)"
            else ->
                "روشن — خروجی: " + studio.cluvex.aether.core.ExitRegions.name(normalized.region) +
                    " (فیلتر قطعی)"
        }
    }

    /** Persists a hop change and asks the Service to converge onto it. */
    private fun applyPsiphonHop(settings: PsiphonHop.Settings) {
        val normalized = PsiphonHop.Settings(
            enabled = settings.enabled,
            region = PsiphonHop.sanitizedRegion(settings.region),
        )
        if (normalized == psiphonHop) return
        psiphonHop = normalized
        PsiphonHop.write(this, normalized)
        syncPsiphonHopControls(normalized)
        Toast.makeText(
            this,
            if (normalized.enabled) "مرحله Psiphon روشن شد" else "مرحله Psiphon خاموش شد",
            Toast.LENGTH_SHORT,
        ).show()
        if (BigRocketVpnService.runningInstance != null) {
            startService(
                Intent(this, BigRocketVpnService::class.java)
                    .setAction(BigRocketVpnService.ACTION_UPSTREAM_CHANGED)
            )
        }
    }


    private fun observeBondingStatus() {
        lifecycleScope.launch {
            BondingStatus.state.collectLatest { state ->
                val wifiText = if (state.isWifiConnected) {
                    "Wi-Fi: متصل (${state.wifiLatencyMs}ms) - سهم: ${state.wifiWeight}%"
                } else {
                    "Wi-Fi: قطع است"
                }
                tvWifiStatus.text = wifiText

                val cellularText = if (state.isCellularConnected) {
                    "Cellular: متصل (${state.cellularLatencyMs}ms) - سهم: ${state.cellularWeight}%"
                } else {
                    "Cellular: قطع است"
                }
                tvCellularStatus.text = cellularText

                tvPath3Status.text = if (state.path3Connected) {
                    "مسیر ۳: متصل (${state.path3LatencyMs}ms)"
                } else {
                    "مسیر ۳: قطع"
                }

                tvAiStatus.text = "وضعیت AI: ${state.mode.name}"
                tvAiLog.text = when (state.mode) {
                    BondingMode.BONDING_ACTIVE ->
                        "ترکیب فعال: Wi-Fi (${state.wifiWeight}%) | Cellular (${state.cellularWeight}%)"
                    BondingMode.SINGLE_PATH ->
                        if (state.isWifiConnected) "مسیر منفرد: استفاده اختصاصی از Wi-Fi"
                        else "مسیر منفرد: استفاده اختصاصی از Cellular"
                    BondingMode.IDLE -> "عدم دسترسی به اینترنت"
                }

                tvMode.text = if (state.mode == BondingMode.BONDING_ACTIVE) {
                    "حالت فعلی: Multi-Path Bonding (ترکیبی)"
                } else {
                    "حالت فعلی: Single-Path (تک‌مسیره)"
                }

                // One decimal place, not rounded to a whole number (e.g. "5.1", "0.5",
                // "100.6") - a plain roundToInt() still collapsed most real-world
                // sub-1 Mbps readings down to a flat "0".
                val displaySpeed = String.format(java.util.Locale.US, "%.1f", state.bondedSpeedMbps)
                tvSpeed.text = "سرعت ترکیبی: $displaySpeed Mbps"

                isVpnRunning = state.isServiceActive
                btnToggleVpn.text = if (isVpnRunning) "قطع اتصال BigRocket" else "شروع اتصال BigRocket"
                tvVpnStatus.text = if (isVpnRunning) "وضعیت اتصال: VPN فعال" else "وضعیت اتصال: غیرفعال"
                tvBarLegend.text = "Wi-Fi ${state.wifiWeight}% · Cellular ${state.cellularWeight}%"

                // Cheap split-bar update: just change layout weights, no custom drawing.
                (barWifi.layoutParams as LinearLayout.LayoutParams).weight =
                    state.wifiWeight.coerceAtLeast(1).toFloat()
                (barCellular.layoutParams as LinearLayout.LayoutParams).weight =
                    state.cellularWeight.coerceAtLeast(1).toFloat()
                barWifi.requestLayout()
                barCellular.requestLayout()
            }
        }

        // The upstream chain (Aether / Xray) failing used to be invisible here: the user saw
        // a normal "VPN active" screen while traffic silently bypassed the tunnel. Shown
        // only when there is something to say (see UpstreamSupervisor.publishWorking).
        lifecycleScope.launch {
            UpstreamHealth.status.collectLatest { status ->
                if (status.level == UpstreamLevel.OK || status.message.isEmpty()) {
                    tvUpstreamStatus.visibility = android.view.View.GONE
                } else {
                    tvUpstreamStatus.text = status.message
                    tvUpstreamStatus.setTextColor(
                        getColor(
                            if (status.level == UpstreamLevel.WORKING) R.color.br_text_muted
                            else R.color.br_accent_error
                        )
                    )
                    tvUpstreamStatus.visibility = android.view.View.VISIBLE
                }
            }
        }

        lifecycleScope.launch {
            BondingStatus.throughputHistory.collectLatest { history ->
                chartThroughput.setData(history.map { it.toFloat() })
            }
        }
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val powerManager = getSystemService(PowerManager::class.java) ?: return
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) return
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (_: Exception) {
            // Some ROMs block the direct request; no functional dependency on this dialog.
        }
    }

    private fun prepareAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        proceedToVpnPermissionCheck()
    }

    private fun proceedToVpnPermissionCheck() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        AppLogger.log("VPN", "startVpnService() called, upstreamChoice=$upstreamChoice")
        val intent = Intent(this, BigRocketVpnService::class.java)
        ContextCompat.startForegroundService(this, intent)

        isVpnRunning = true
        btnToggleVpn.text = "قطع اتصال BigRocket"
        tvVpnStatus.text = "وضعیت اتصال: VPN فعال"
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        // Reflects AppLogger's true current state every time the menu opens, rather than
        // trusting whatever the item's checked state happened to be left at - matters after a
        // process restart, since the menu view itself doesn't survive that but the underlying
        // preference does.
        menu.findItem(R.id.action_toggle_logging)?.isChecked = AppLogger.isEnabled()
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_toggle_logging -> {
                val newValue = !item.isChecked
                item.isChecked = newValue
                AppLogger.setEnabled(this, newValue)
                Toast.makeText(
                    this,
                    if (newValue) "ثبت لاگ فعال شد" else "ثبت لاگ غیرفعال شد",
                    Toast.LENGTH_SHORT,
                ).show()
                true
            }
            R.id.action_save_log -> {
                val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                    .format(java.util.Date())
                saveLogLauncher.launch("bigrocket_log_$timestamp.txt")
                true
            }
            R.id.action_live_aggregation -> {
                startActivity(Intent(this, LiveAggregationActivity::class.java))
                true
            }
            R.id.action_local_split_merge -> {
                startActivity(Intent(this, LocalSplitMergeActivity::class.java))
                true
            }
            R.id.action_virtual_bonding_sandbox -> {
                startActivity(Intent(this, VirtualBondingSandboxActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun stopVpnService() {
        AppLogger.log("VPN", "stopVpnService() called")
        // teardownVpn() in BigRocketVpnService already stops the Aether engine as part of
        // full VPN teardown - no need (and no longer safe, per applyUpstreamChoice()'s
        // ownership rule) to also stop it here.
        val intent = Intent(this, BigRocketVpnService::class.java).apply {
            action = BigRocketVpnService.ACTION_STOP
        }
        ContextCompat.startForegroundService(this, intent)

        isVpnRunning = false
        btnToggleVpn.text = "شروع اتصال BigRocket"
        tvVpnStatus.text = "وضعیت اتصال: غیرفعال"
    }
}
