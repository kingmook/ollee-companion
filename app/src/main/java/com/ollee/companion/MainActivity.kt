package com.ollee.companion

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ollee.companion.ble.ConnectionState
import com.ollee.companion.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val permissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        permissionLauncher.launch(permissions)
        setContent {
            OlleeTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    OlleeScreen()
                }
            }
        }
    }
}

private enum class Status { OK, PENDING }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OlleeScreen(vm: MainViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(ui.message) {
        ui.message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Watch, null, Modifier.size(22.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Ollee Companion")
                    }
                },
                actions = {
                    ConnectionPill(ui.connection)
                    Spacer(Modifier.width(8.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .padding(horizontal = 16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(2.dp))
            if (ui.connection == ConnectionState.READY) {
                WatchSummary(ui, vm)
                StepsCard(ui)
                SunCard(ui, vm)
                Text(
                    "Features",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                )
                FeatureGrid(ui, vm)
            } else {
                ConnectPanel(ui, vm)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ConnectionPill(state: ConnectionState) {
    val (label, dot) = when (state) {
        ConnectionState.READY -> "Connected" to MaterialTheme.colorScheme.primary
        ConnectionState.CONNECTING -> "Connecting" to MaterialTheme.colorScheme.tertiary
        ConnectionState.DISCONNECTED -> "Offline" to MaterialTheme.colorScheme.outline
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectPanel(ui: UiState, vm: MainViewModel) {
    ElevatedCard {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Filled.Bluetooth, null,
                Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text("Connect your Ollee watch", style = MaterialTheme.typography.titleMedium)
            Text(
                "Make sure the watch is nearby and not connected to the official app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (ui.connection == ConnectionState.CONNECTING) {
                CircularProgressIndicator(Modifier.size(28.dp))
            } else {
                Button(
                    onClick = { vm.connect(vm.defaultAddress) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Connect watch") }
                OutlinedButton(
                    onClick = { if (ui.scanning) vm.stopScan() else vm.startScan() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (ui.scanning) "Stop scan" else "Scan for devices") }
            }
            if (ui.devices.isNotEmpty()) {
                Divider(Modifier.padding(vertical = 4.dp))
                ui.devices.forEach { d ->
                    ListItem(
                        headlineContent = { Text(d.name) },
                        supportingContent = { Text(d.address) },
                        leadingContent = { Icon(Icons.Filled.Watch, null) },
                        trailingContent = {
                            TextButton(onClick = { vm.connect(d.address) }) { Text("Connect") }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun WatchSummary(ui: UiState, vm: MainViewModel) {
    ElevatedCard {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Watch, null,
                    Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        ui.name?.takeIf { it.isNotBlank() } ?: "Ollee watch",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        ui.firmware ?: "—",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { vm.refresh() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                }
            }
        }
    }
}

@Composable
private fun StepsCard(ui: UiState) {
    val goal = ui.stepGoal ?: 0
    val live = ui.liveValue ?: 0
    val fraction = if (goal > 0) (live.toFloat() / goal).coerceIn(0f, 1f) else 0f
    ElevatedCard {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.DirectionsWalk, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Steps", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Text("$live / $goal", style = MaterialTheme.typography.titleMedium)
            }
            LinearProgressIndicator(
                progress = fraction,
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)),
            )
            Text(
                "Live value vs daily goal. (Live-value meaning still being confirmed.)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SunCard(ui: UiState, vm: MainViewModel) {
    val fmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    FeatureCard("Sunrise / Sunset", Icons.Filled.WbSunny, Status.OK) {
        ui.sun?.let { s ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                StatTile("Sunrise", s.sunriseEpoch?.let { fmt.format(Date(it)) } ?: "n/a")
                StatTile("Sunset", s.sunsetEpoch?.let { fmt.format(Date(it)) } ?: "n/a")
            }
        }
        FilledTonalButton(onClick = { vm.computeSun() }) { Text("Compute for my location") }
    }
}

@Composable
private fun FeatureGrid(ui: UiState, vm: MainViewModel) {
    FeatureCard("Automatic time sync", Icons.Filled.Sync, Status.OK) {
        Text(
            "Runs automatically on connect.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilledTonalButton(onClick = { vm.syncTimeNow() }) { Text("Sync now") }
    }
    StepHistoryCard(ui, vm)
    TemperatureCard(ui, vm)
    HeartRateCard(ui, vm)
    FeatureCard("Alarm", Icons.Filled.Alarm, Status.PENDING) {
        Text(
            "Days, chime, snooze & hourly beep — pending protocol capture.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StepHistoryCard(ui: UiState, vm: MainViewModel) {
    FeatureCard("Step history", Icons.Filled.DirectionsWalk, Status.OK) {
        if (ui.stepDaily.isEmpty()) {
            MutedText("No data yet — sync to pull step history.")
        } else {
            Text(
                "Latest day: %,d steps".format(ui.stepDaily.first().steps),
                style = MaterialTheme.typography.titleMedium,
            )
            MutedText("${ui.stepDaily.size} days · last 30 days")
            Spacer(Modifier.height(2.dp))
            ui.stepDaily.forEach { d ->
                LogRow(d.label, "%,d steps".format(d.steps))
            }
        }
        SyncButton(ui, vm)
    }
}

@Composable
private fun TemperatureCard(ui: UiState, vm: MainViewModel) {
    FeatureCard("Temperature", Icons.Filled.Thermostat, Status.OK) {
        if (ui.tempDaily.isEmpty()) {
            MutedText("No data yet — sync to pull the hourly log.")
        } else {
            Text(
                "Latest: %.1f °C".format(ui.temperatureLog.first().celsius),
                style = MaterialTheme.typography.titleMedium,
            )
            MutedText("${ui.temperatureLog.size} readings · last 30 days")
            Spacer(Modifier.height(2.dp))
            ui.tempDaily.forEach { d ->
                LogRow(d.label, "%.1f–%.1f °C".format(d.minC, d.maxC))
            }
        }
        SyncButton(ui, vm)
    }
}

@Composable
private fun HeartRateCard(ui: UiState, vm: MainViewModel) {
    FeatureCard("Heart rate", Icons.Filled.Favorite, Status.OK) {
        if (ui.hrDaily.isEmpty()) {
            MutedText("No samples yet — sync to pull HR history.")
        } else {
            Text(
                "Latest: ${ui.hrLog.first().bpm} bpm",
                style = MaterialTheme.typography.titleMedium,
            )
            MutedText("${ui.hrLog.size} samples · last 30 days")
            Spacer(Modifier.height(2.dp))
            ui.hrDaily.forEach { d ->
                LogRow(d.label, "${d.min}–${d.max} bpm · avg ${d.avg}")
            }
        }
        SyncButton(ui, vm)
    }
}

@Composable
private fun SyncButton(ui: UiState, vm: MainViewModel) {
    if (ui.syncing) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("Syncing…", style = MaterialTheme.typography.bodySmall)
        }
    } else {
        FilledTonalButton(onClick = { vm.syncRecords() }) { Text("Sync health records") }
    }
}

@Composable
private fun LogRow(left: String, right: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(left, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(right, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun MutedText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun FeatureCard(
    title: String,
    icon: ImageVector,
    status: Status,
    content: @Composable ColumnScope.() -> Unit,
) {
    OutlinedCard {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                StatusChip(status)
            }
            content()
        }
    }
}

@Composable
private fun StatusChip(status: Status) {
    val (text, bg, fg) = when (status) {
        Status.OK -> Triple("Working", StatusOkContainer, StatusOkText)
        Status.PENDING -> Triple("Needs capture", StatusPendingContainer, StatusPendingText)
    }
    Surface(shape = RoundedCornerShape(50), color = bg, contentColor = fg) {
        Text(
            text,
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun StatTile(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
