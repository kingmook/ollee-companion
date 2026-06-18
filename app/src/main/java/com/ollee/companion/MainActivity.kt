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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ollee.companion.ble.ConnectionState
import com.ollee.companion.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val permissions: Array<String>
        get() = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OlleeScreen(vm: MainViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(ui.message) {
        ui.message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }

    // When the whole app returns to the foreground, verify the link is still
    // live and re-sync, blocking the UI until it's done. ProcessLifecycleOwner
    // is process-scoped, so this fires reliably even when the notification tap
    // spins up a fresh Activity — and ON_START won't fire on cold launch (the
    // observer is added after the process has already started).
    DisposableEffect(Unit) {
        val owner = ProcessLifecycleOwner.get()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) vm.verifyConnection()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    Box(Modifier.fillMaxSize()) {
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
            // Keep the watch screen up during a brief auto-reconnect rather than
            // flashing the connect panel; show a small banner instead.
            if (ui.connection == ConnectionState.READY || ui.reconnecting) {
                if (ui.connection != ConnectionState.READY) ReconnectingBanner()
                WatchSummary(ui, vm)
                StepsCard(ui)
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
        if (ui.verifying) ReconnectingOverlay(ui.connection)
    }
}

/** Full-screen blocking scrim shown while re-verifying/syncing on resume. */
@Composable
private fun ReconnectingOverlay(connection: ConnectionState) {
    val text = if (connection == ConnectionState.READY) "Syncing with your watch…"
    else "Reconnecting to your watch…"
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f))
            // Swallow all input so nothing underneath is interactive.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 6.dp) {
            Column(
                Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircularProgressIndicator()
                Text(text, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** Slim inline banner shown while the link is briefly re-establishing. */
@Composable
private fun ReconnectingBanner() {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("Reconnecting to your watch…", style = MaterialTheme.typography.bodySmall)
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
                Text(
                    "Searching for your watch… (up to 1 min)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                IconButton(onClick = { vm.disconnect() }) {
                    Icon(
                        Icons.Filled.BluetoothDisabled,
                        contentDescription = "Disconnect",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun StepsCard(ui: UiState) {
    val goal = ui.stepGoal ?: 0
    val today = ui.todaySteps
    val fraction = if (goal > 0) (today.toFloat() / goal).coerceIn(0f, 1f) else 0f
    val percent = if (goal > 0) (fraction * 100).toInt() else 0
    ElevatedCard {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.DirectionsWalk, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Steps today", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Text("%,d / %,d".format(today, goal), style = MaterialTheme.typography.titleMedium)
            }
            LinearProgressIndicator(
                progress = fraction,
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)),
            )
            Text(
                "$percent% of daily goal · synced total. Tap “Sync health records” to update.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AlarmCard(vm: MainViewModel) {
    var hour by remember { mutableIntStateOf(6) }
    var minute by remember { mutableIntStateOf(30) }
    // index 0=Sun .. 6=Sat; default Mon-Fri.
    val days = remember { mutableStateListOf(false, true, true, true, true, true, false) }
    val dayLabels = listOf("S", "M", "T", "W", "T", "F", "S")

    FeatureCard("Alarm", Icons.Filled.Alarm) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "%02d:%02d".format(hour, minute),
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(Modifier.weight(1f))
            Stepper("Hr") { hour = (hour + it + 24) % 24 }
            Spacer(Modifier.width(8.dp))
            Stepper("Min") { minute = (minute + it + 60) % 60 }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            dayLabels.forEachIndexed { i, label ->
                DayToggle(
                    label = label,
                    selected = days[i],
                    modifier = Modifier.weight(1f),
                    onToggle = { days[i] = !days[i] },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                var mask = 0
                days.forEachIndexed { i, on -> if (on) mask = mask or (1 shl i) }
                vm.setAlarm(hour, minute, mask)
            }) { Text("Set alarm") }
            OutlinedButton(onClick = { vm.clearAlarm() }) { Text("Clear") }
        }
    }
}

@Composable
private fun Stepper(label: String, onStep: (Int) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onStep(-1) }) {
                Icon(Icons.Filled.Remove, contentDescription = "−")
            }
            IconButton(onClick = { onStep(1) }) {
                Icon(Icons.Filled.Add, contentDescription = "+")
            }
        }
    }
}

@Composable
private fun DayToggle(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
) {
    Surface(
        onClick = onToggle,
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.aspectRatio(1f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun SunCard(ui: UiState, vm: MainViewModel, modifier: Modifier = Modifier) {
    val fmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    FeatureCard("Rise / Set", Icons.Filled.WbSunny, modifier) {
        ui.sun?.let { s ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                StatTile("Sunrise", s.sunriseEpoch?.let { fmt.format(Date(it)) } ?: "n/a")
                StatTile("Sunset", s.sunsetEpoch?.let { fmt.format(Date(it)) } ?: "n/a")
            }
        }
        FilledTonalButton(onClick = { vm.computeSun() }) { Text("Locate") }
    }
}

@Composable
private fun FeatureGrid(ui: UiState, vm: MainViewModel) {
    HealthRecordsCard(ui, vm)
    AlarmCard(vm)
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SunCard(ui, vm, Modifier.weight(1f).fillMaxHeight())
        TimeSyncCard(vm, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun TimeSyncCard(vm: MainViewModel, modifier: Modifier = Modifier) {
    FeatureCard("Time Sync", Icons.Filled.Sync, modifier) {
        FilledTonalButton(onClick = { vm.syncTimeNow() }) { Text("Sync now") }
    }
}

@Composable
private fun HealthRecordsCard(ui: UiState, vm: MainViewModel) {
    FeatureCard("Health records", Icons.Filled.Favorite) {
        RecordSection("Steps", Icons.Filled.DirectionsWalk) {
            if (ui.stepDaily.isEmpty()) {
                MutedText("No data yet.")
            } else {
                ui.stepDaily.forEach { d -> LogRow(d.label, "%,d steps".format(d.steps)) }
            }
        }
        RecordSection("Temperature", Icons.Filled.Thermostat) {
            if (ui.tempDaily.isEmpty()) {
                MutedText("No data yet.")
            } else {
                ui.tempDaily.forEach { d ->
                    LogRow(d.label, "%.1f–%.1f °C".format(d.minC, d.maxC))
                }
            }
        }
        RecordSection("Heart rate", Icons.Filled.Favorite) {
            if (ui.hrDaily.isEmpty()) {
                MutedText("No samples yet.")
            } else {
                ui.hrDaily.forEach { d ->
                    LogRow(d.label, "${d.min}–${d.max} bpm · avg ${d.avg}")
                }
            }
        }
        MutedText("Last 30 days · tap to refresh")
        SyncButton(ui, vm)
    }
}

@Composable
private fun RecordSection(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        content()
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
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    OutlinedCard(modifier) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
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
