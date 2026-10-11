package io.ahara.biopticon.probe

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var auth: Authentication
    private var message by mutableStateOf("")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        auth = Authentication(this)
        DeliveryWorker.scheduleRecovery(this)
        DeliveryWorker.enqueue(this)
        receiveAuth(intent)
        setContent { MaterialTheme { ProbeScreen() } }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); receiveAuth(intent) }
    private fun receiveAuth(intent: Intent) {
        if (intent.action == "AUTH_RESULT") lifecycleScope.launch {
            message = if (auth.complete(intent)) "Signed in for status delivery" else "Sign-in did not complete"
            DeliveryWorker.enqueue(this@MainActivity)
        }
    }
    override fun onDestroy() { auth.service.dispose(); super.onDestroy() }

    @Composable private fun ProbeScreen() {
        var activity by remember { mutableStateOf(false) }
        var alpha by remember { mutableStateOf(false) }
        var api by remember { mutableStateOf(auth.api) }
        var oauth by remember { mutableStateOf(auth.oauth) }
        var client by remember { mutableStateOf(auth.client) }
        var rows by remember { mutableStateOf(emptyList<ProbeDatabase.EventRow>()) }
        var selectedTarget by remember { mutableStateOf("hume") }
        var endpoint by remember { mutableStateOf("") }
        var query by remember { mutableStateOf("") }
        var chosen by remember { mutableStateOf<Assay?>(null) }
        var attribute by remember { mutableStateOf("") }
        var units by remember { mutableStateOf("") }
        var stage by remember { mutableStateOf("SOURCE_DISPLAY") }
        var outcome by remember { mutableStateOf("INCONCLUSIVE") }
        var nearby by remember { mutableStateOf(emptyList<NearbyDevice>()) }
        var bleConsent by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        val log = remember { EventLog(this) }
        val guided = remember { GuidedProbes(this, log) }
        val bluetooth = remember { BluetoothProbes(this, log) }
        fun perform(task: suspend () -> Unit) {
            if (busy) return
            busy = true
            lifecycleScope.launch {
                try { withContext(Dispatchers.IO) { task() }; message = "Observation saved locally" }
                catch (error: Exception) { message = "Probe stopped: ${error.javaClass.simpleName}" }
                finally { busy = false; DeliveryWorker.enqueue(this@MainActivity) }
            }
        }
        DisposableEffect(Unit) { onDispose { log.db.close() } }
        LaunchedEffect(Unit) { while (true) { rows = withContext(Dispatchers.IO) { log.records.recent() }; delay(1500) } }
        val permissions = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
            message = "Granted ${it.size} selected Health Connect permissions"
        }
        fun grant(selected: Set<String>) {
            runCatching { permissions.launch(selected) }.onFailure { message = "Health Connect permission screen unavailable: ${it.javaClass.simpleName}" }
        }
        val blePermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            message = "Bluetooth permissions updated; tap Scan to begin"
        }
        val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) lifecycleScope.launch(Dispatchers.IO) {
                runCatching {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    ArtifactProbes(this@MainActivity, log).inspect(uri)
                }.onFailure { withContext(Dispatchers.Main) { message = "File inspection failed: ${it.javaClass.simpleName}" } }
                DeliveryWorker.enqueue(this@MainActivity)
            }
        }
        LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp).statusBarsPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("Biopticon Probe", style = MaterialTheme.typography.headlineMedium); Text("Capability discovery · campaign discovery-1") }
            item { Row { Checkbox(activity, { activity = it }); Text("Include activity, heart rate and sleep reads") } }
            item { Row { Checkbox(alpha, { alpha = it }); Text("Include Alpha investigation") } }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { grant(HealthProbes.permissions(activity, alpha)) }) { Text("Grant selected reads") }
                Button(onClick = { CampaignWorker.enqueue(this@MainActivity, activity, alpha); message = "Campaign queued" }) { Text("Run probes") }
            } }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { grant(setOf("android.permission.health.READ_HEALTH_DATA_HISTORY")) }) { Text("History permission") }
                OutlinedButton(onClick = { grant(setOf("android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND")) }) { Text("Background permission") }
            }; Text("For a background-read test, start a campaign and close the UI. The observation records whether the UI was still visible.") }
            item { Text("Bluetooth investigation", style = MaterialTheme.typography.titleLarge)
                Row { Checkbox(bleConsent, { bleConsent = it }); Text("My scale; Hume disconnected. Allow scan and selected read-only service discovery.") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { blePermissions.launch(if (Build.VERSION.SDK_INT >= 31) arrayOf("android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT") else arrayOf("android.permission.ACCESS_FINE_LOCATION")) }) { Text("Permission") }
                    Button(enabled = bleConsent && !busy, onClick = { perform { val devices = bluetooth.scan(); withContext(Dispatchers.Main) { nearby = devices } } }) { Text("Scan 12 seconds") }
                }
            }
            items(nearby, key = { it.id }) { device ->
                OutlinedButton(enabled = bleConsent && !busy, onClick = { perform { bluetooth.enumerate(device) } }) { Text("Inspect services: ${device.label}") }
            }
            item { Text("Selected protocol interface", style = MaterialTheme.typography.titleLarge)
                Choice("Target", selectedTarget, listOf("hume", "garmin", "alpha")) { selectedTarget = it }
                OutlinedTextField(endpoint, { endpoint = it }, label = { Text("Known HTTPS vendor endpoint") }, modifier = Modifier.fillMaxWidth())
                Text("Sends GET, HEAD and OPTIONS without credentials. Observe challenges and response codes; record interactive login stages below.")
                Button(enabled = !busy && endpoint.isNotBlank(), onClick = { perform { ProtocolProbes(log).inspect(selectedTarget, endpoint) } }) { Text("Probe interface") }
            }
            item { Text("Guided questions", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(query, { query = it }, label = { Text("Find assay ID or question") }, modifier = Modifier.fillMaxWidth())
                Text("Select a question, perform its requested action in the target app, then record the exact attribute and observed stage. Account writes require separate approval and are never automatic.")
            }
            items(guided.assays.filter { (!it.optional || alpha) && (it.id.contains(query, true) || it.question.contains(query, true)) }.take(12), key = { "question-${it.id}" }) { assay ->
                Card(Modifier.fillMaxWidth().clickable { chosen = assay }) { Column(Modifier.padding(10.dp)) {
                    Text("${assay.id} · ${assay.mode} · ${assay.route}")
                    Text(assay.question)
                    Text("${assay.implementation}: ${assay.prerequisite}", style = MaterialTheme.typography.bodySmall)
                } }
            }
            chosen?.let { assay -> item {
                Text("Record ${assay.id}", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(attribute, { attribute = it }, label = { Text("Attribute / protocol stage name; no values or secrets") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(units, { units = it }, label = { Text("Unit name, when relevant") })
                Choice("Stage", stage, listOf("SOURCE_DISPLAY", "EXPORT", "AUTHENTICATION", "IMPORT_ACCEPTANCE", "STORAGE", "DISPLAY", "READBACK", "DEVICE_RECEIPT")) { stage = it }
                Choice("Result", outcome, listOf("PASS", "FAIL", "BLOCKED_AUTH", "BLOCKED_PERMISSION", "NO_DATA", "UNSUPPORTED", "INCONCLUSIVE", "SKIPPED")) { outcome = it }
                if (assay.writes) Text("An actual vendor write requires separate approval. Saving this observation does not execute a write.")
                Button(enabled = !busy && attribute.isNotBlank(), onClick = { perform { guided.observe(assay, attribute, stage, outcome, units) } }) { Text("Save observation") }
            } }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { WorkManager.getInstance(this@MainActivity).cancelUniqueWork("campaign") }) { Text("Stop") }
                OutlinedButton(onClick = { file.launch(arrayOf("text/*", "application/octet-stream", "application/vnd.ant.fit")) }) { Text("Inspect CSV / FIT") }
            } }
            item { Text("Status delivery", style = MaterialTheme.typography.titleLarge) }
            item { OutlinedTextField(api, { api = it }, label = { Text("HTTPS API URL") }, modifier = Modifier.fillMaxWidth()) }
            item { OutlinedTextField(oauth, { oauth = it }, label = { Text("Cognito hosted UI URL") }, modifier = Modifier.fillMaxWidth()) }
            item { OutlinedTextField(client, { client = it }, label = { Text("Public OAuth client ID") }, modifier = Modifier.fillMaxWidth()) }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { runCatching { auth.api = api; auth.oauth = oauth; auth.client = client; auth.login() }.onFailure { message = "Check the delivery configuration" } }) { Text("Sign in") }
                OutlinedButton(onClick = { auth.logout(); message = "Status delivery signed out" }) { Text("Sign out") }
                OutlinedButton(onClick = { lifecycleScope.launch(Dispatchers.IO) { log.records.retryErrors(); DeliveryWorker.enqueue(this@MainActivity) } }) { Text("Retry") }
            } }
            item { Text(message); Text(getSharedPreferences("delivery", MODE_PRIVATE).getString("status", "Status delivery awaiting configuration") ?: ""); Text("Observations (${rows.size} recent)", style = MaterialTheme.typography.titleLarge) }
            items(rows, key = { it.id }) { row ->
                var expanded by remember(row.id) { mutableStateOf(false) }
                Card(Modifier.fillMaxWidth().clickable { expanded = !expanded }) { Column(Modifier.padding(10.dp)) {
                    Text(row.summary)
                    Text(when (row.delivery) { 1 -> "Stored in S3"; 2 -> "Delivery error: ${row.error}"; else -> "Queued for S3" }, style = MaterialTheme.typography.bodySmall)
                    if (expanded) Text(row.json, style = MaterialTheme.typography.bodySmall)
                } }
            }
        }
    }

    @Composable private fun Choice(label: String, selected: String, values: List<String>, choose: (String) -> Unit) {
        var open by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { open = true }) { Text("$label: $selected") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                values.forEach { value -> DropdownMenuItem(text = { Text(value) }, onClick = { choose(value); open = false }) }
            }
        }
    }
}
