package com.bigrocket.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.bigrocket.egress.StaticProxyRuntime
import com.bigrocket.service.AggregationProbe
import com.bigrocket.service.BigRocketVpnService
import com.bigrocket.service.PathLabels
import com.bigrocket.service.PathTrafficStats
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Live view of the REAL two-path aggregation (no simulation):
 *  - per-radio byte counters and flow counts, measured by the data plane itself;
 *  - a real-traffic test that downloads over Wi-Fi, over cellular, then over both at once.
 */
class LiveAggregationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme { Surface(modifier = Modifier.fillMaxSize()) { LiveAggregationScreen() } }
        }
    }
}

@Composable
fun LiveAggregationScreen() {
    val scope = rememberCoroutineScope()
    var wifiKbps by remember { mutableStateOf(0) }
    var cellKbps by remember { mutableStateOf(0) }
    var wifiFlowCount by remember { mutableStateOf(0) }
    var cellFlowCount by remember { mutableStateOf(0) }
    var wifiTotal by remember { mutableStateOf(0L) }
    var cellTotal by remember { mutableStateOf(0L) }
    var sharePercent by remember { mutableStateOf(50) }
    var testing by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(Unit) {
        while (true) {
            val samples = PathTrafficStats.tick()
            samples.firstOrNull { it.label == PathLabels.WIFI }?.let {
                wifiKbps = it.kbpsNow; wifiFlowCount = it.activeFlows; wifiTotal = it.totalBytes
            }
            samples.firstOrNull { it.label == PathLabels.CELLULAR }?.let {
                cellKbps = it.kbpsNow; cellFlowCount = it.activeFlows; cellTotal = it.totalBytes
            }
            sharePercent = BigRocketVpnService.runningInstance?.currentFlowSharePercent() ?: 50
            delay(1000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("تجمیع واقعی دو مسیر (بدون شبیه‌سازی)", style = MaterialTheme.typography.titleMedium)
        Text(
            "این اعداد از ترافیک واقعیِ خود گوشی اندازه‌گیری می‌شوند: هر بایت که از وای‌فای یا سلولار " +
                "خارج شود اینجا شمرده می‌شود. اتصال‌های هم‌زمان بین دو رادیو پخش می‌شوند و همه از یک " +
                "IP خروجی (پروکسی استاتیک) بیرون می‌روند.",
            style = MaterialTheme.typography.bodySmall,
        )

        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("وضعیت زنده", style = MaterialTheme.typography.titleSmall)
                Text("وای‌فای: ${"%.2f".format(wifiKbps / 1000.0)} Mbps — ${wifiFlowCount} اتصال فعال — مجموع ${fmt(wifiTotal)}", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { (wifiKbps / 100_000f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text("سلولار: ${"%.2f".format(cellKbps / 1000.0)} Mbps — ${cellFlowCount} اتصال فعال — مجموع ${fmt(cellTotal)}", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { (cellKbps / 100_000f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text("مجموع دو مسیر: ${"%.2f".format((wifiKbps + cellKbps) / 1000.0)} Mbps", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "سهم تخصیص اتصال‌های جدید: $sharePercent% وای‌فای / ${100 - sharePercent}% سلولار " +
                        "(نسبت امتیاز، اصلاح‌شده با سرعت واقعیِ اندازه‌گیری‌شده)",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Button(
            onClick = {
                val service = BigRocketVpnService.runningInstance
                testing = true
                report = listOf("در حال تست واقعی (حدود ۲۰ ثانیه)...")
                scope.launch {
                    val (lines, _) = AggregationProbe.measure(
                        wifi = service?.sandboxWifiNetwork(),
                        cellular = service?.sandboxCellularNetwork(),
                        seconds = 6,
                    )
                    report = lines
                    testing = false
                }
            },
            enabled = !testing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (testing) "در حال تست..." else "تست واقعی تجمیع (دانلود هم‌زمان از دو رادیو)")
        }

        if (report.isNotEmpty()) {
            Card {
                Column(Modifier.padding(12.dp)) {
                    Text("نتیجه تست", style = MaterialTheme.typography.titleSmall)
                    Text(
                        report.joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }

        val verified = StaticProxyRuntime.isVerified()
        Text(
            if (verified) {
                "پروکسی استاتیک تأیید شده است: هر دو مسیر با یک IP خروجی کار می‌کنند، پس تقسیم ترافیک بین " +
                    "دو رادیو برای سایت‌ها نامرئی است."
            } else {
                "پروکسی استاتیک تأیید نشده: تا وقتی «بررسی IP خروجی» موفق نشود، تخصیص اتصال‌ها بین دو " +
                    "مسیر انجام نمی‌شود (چون IP خروجی هر مسیر متفاوت است و سایت‌ها آن را می‌بینند)."
            },
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "چرا یک دانلود تنها سریع‌تر نمی‌شود: یک اتصال TCP یک 4-tuple دارد و فقط از یک رادیو می‌تواند " +
                "برود. تجمیع روی «اتصال‌های هم‌زمان» انجام می‌شود؛ مرورگر، استریم و دانلودر چند-اتصاله " +
                "همین‌طور کار می‌کنند. برای سریع‌شدن یک اتصال واحد، طرف مقابل (موتور جداسازی) لازم است.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { PathTrafficStats.reset() }, modifier = Modifier.weight(1f)) { Text("صفر کردن شمارنده‌ها") }
        }
    }
}

private fun fmt(n: Long): String = when {
    n >= 1024L * 1024 * 1024 -> String.format("%.2f GB", n / 1073741824.0)
    n >= 1024L * 1024 -> String.format("%.2f MB", n / 1048576.0)
    n >= 1024 -> String.format("%.1f KB", n / 1024.0)
    else -> "$n B"
}
