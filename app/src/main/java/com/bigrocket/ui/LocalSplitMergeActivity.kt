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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.bigrocket.bonding.sim.LocalDuplexSimulation
import com.bigrocket.bonding.sim.PathProfile
import com.bigrocket.bonding.sim.SimConfig
import com.bigrocket.bonding.sim.SimReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Both bonding engines run inside the app here: the merge/split engine (A) and the separation
 * engine (B) are connected by two modelled links (bandwidth, latency, jitter, loss). No VPN and
 * no server are needed, so this screen works even before Connect.
 *
 * It exists to answer one question on a real device: does one TCP stream survive being cut into
 * frames across two paths and reassembled in order? The counters below are the answer.
 */
class LocalSplitMergeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) { LocalSplitMergeScreen() }
            }
        }
    }
}

@Composable
fun LocalSplitMergeScreen() {
    val scope = rememberCoroutineScope()
    var mbText by remember { mutableStateOf("2") }
    var wifiKbps by remember { mutableStateOf("40000") }
    var cellKbps by remember { mutableStateOf("15000") }
    var wifiLoss by remember { mutableStateOf("0") }
    var cellLoss by remember { mutableStateOf("0") }
    var wifiPercent by remember { mutableStateOf("50") }
    var running by remember { mutableStateOf(false) }
    var bonded by remember { mutableStateOf<SimReport?>(null) }
    var single by remember { mutableStateOf<SimReport?>(null) }
    var status by remember { mutableStateOf("آماده — هر دو موتور داخل همین اپ اجرا می‌شوند") }

    fun buildConfig(percent: Int): SimConfig = SimConfig(
        bytes = ((mbText.toDoubleOrNull() ?: 2.0) * 1024 * 1024).toInt(),
        wifiPercent = percent,
        wifi = PathProfile(
            "wifi",
            wifiKbps.toIntOrNull() ?: 40000,
            20,
            5,
            wifiLoss.toIntOrNull() ?: 0,
        ),
        cellular = PathProfile(
            "cellular",
            cellKbps.toIntOrNull() ?: 15000,
            45,
            10,
            cellLoss.toIntOrNull() ?: 0,
        ),
    )

    fun runBonded() {
        running = true
        status = "در حال اجرای ادغام/جداسازی..."
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                LocalDuplexSimulation.run(buildConfig(wifiPercent.toIntOrNull() ?: 50))
            }
            bonded = result
            status = if (result.byteExact && result.completed) "ادغام موفق — خروجی بایت‌به‌بایت درست" else "ادغام ناموفق"
            running = false
        }
    }

    fun runSingle(overWifi: Boolean) {
        running = true
        status = "در حال اجرای تک‌مسیر (${if (overWifi) "وای‌فای" else "سلولار"})..."
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                LocalDuplexSimulation.runSinglePath(buildConfig(if (overWifi) 100 else 0), overWifi)
            }
            single = result
            status = "تک‌مسیر ${if (overWifi) "وای‌فای" else "سلولار"}: ${result.goodputKbps} kbps"
            running = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("شبیه‌سازی ادغام و جداسازی (هر دو موتور داخل اپ)", style = MaterialTheme.typography.titleMedium)
        Text(
            "یک استریم TCP ساخته می‌شود، موتور ادغام آن را به فریم می‌شکند و روی دو مسیر مدل‌شده می‌فرستد، " +
                "موتور جداسازی فریم‌ها را بازچینی می‌کند و خروجی را بایت‌به‌بایت با ورودی مقایسه می‌کند.",
            style = MaterialTheme.typography.bodySmall,
        )

        OutlinedTextField(mbText, { mbText = it }, label = { Text("حجم استریم (MB)") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(wifiKbps, { wifiKbps = it }, label = { Text("وای‌فای kbps") }, modifier = Modifier.weight(1f))
            OutlinedTextField(cellKbps, { cellKbps = it }, label = { Text("سلولار kbps") }, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(wifiLoss, { wifiLoss = it }, label = { Text("افت وای‌فای %") }, modifier = Modifier.weight(1f))
            OutlinedTextField(cellLoss, { cellLoss = it }, label = { Text("افت سلولار %") }, modifier = Modifier.weight(1f))
        }
        OutlinedTextField(
            wifiPercent,
            { wifiPercent = it },
            label = { Text("سهم وای‌فای % (نسبت امتیاز)") },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { runBonded() }, enabled = !running, modifier = Modifier.weight(1f)) {
                Text("ادغام دو مسیر")
            }
            Button(onClick = { runSingle(true) }, enabled = !running, modifier = Modifier.weight(1f)) {
                Text("فقط وای‌فای")
            }
            Button(onClick = { runSingle(false) }, enabled = !running, modifier = Modifier.weight(1f)) {
                Text("فقط سلولار")
            }
        }

        Text(status, style = MaterialTheme.typography.bodyMedium)

        bonded?.let { ReportCard("نتیجه ادغام", it) }
        single?.let { ReportCard("نتیجه تک‌مسیر", it) }

        if (bonded != null && single != null) {
            val gain = bonded!!.goodputKbps.toDouble() / single!!.goodputKbps.coerceAtLeast(1)
            Card(colors = CardDefaults.cardColors()) {
                Column(Modifier.padding(12.dp)) {
                    Text("مقایسه", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "ادغام ${bonded!!.goodputKbps} kbps در مقابل تک‌مسیر ${single!!.goodputKbps} kbps " +
                            "→ ${String.format("%.2f", gain)}×",
                    )
                }
            }
        }

        Text(
            "توجه: هر دو موتور داخل همین گوشی اجرا می‌شوند، پس این یک شبیه‌سازی است — مسیرها مدل‌شده‌اند " +
                "و پهنای باند رادیوهای واقعی جمع نمی‌شود. برای جمع‌شدن واقعی، موتور جداسازی باید روی یک " +
                "آدرس عمومی (سرور) اجرا شود.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ReportCard(title: String, report: SimReport) {
    Card {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                report.summary(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
