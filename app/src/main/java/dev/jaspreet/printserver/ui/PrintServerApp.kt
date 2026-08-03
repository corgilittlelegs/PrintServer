package dev.jaspreet.printserver.ui

import android.content.Context
import android.os.PowerManager
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.jaspreet.printserver.R
import dev.jaspreet.printserver.activity.ActivityEntry
import dev.jaspreet.printserver.activity.ActivityStatus
import dev.jaspreet.printserver.access.ClientAccessMode
import dev.jaspreet.printserver.access.ClientAccessSettings
import dev.jaspreet.printserver.jobs.JobState
import dev.jaspreet.printserver.jobs.QueueEntry
import dev.jaspreet.printserver.jobs.QueueState
import dev.jaspreet.printserver.scan.ScanProgressPhase
import dev.jaspreet.printserver.scan.ScanTone
import dev.jaspreet.printserver.scan.ScanToneSettings
import dev.jaspreet.printserver.scan.SupplyCartridge
import dev.jaspreet.printserver.service.ScanState
import dev.jaspreet.printserver.service.ServerStatus
import dev.jaspreet.printserver.ui.components.UsbConnectionIllustration
import dev.jaspreet.printserver.ui.components.WirelessSharingIllustration
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintServerApp(
    status: ServerStatus,
    scanToneSettings: ScanToneSettings,
    clientAccessSettings: ClientAccessSettings,
    activityEntries: List<ActivityEntry>,
    queueEntries: List<QueueEntry>,
    onStartServerClick: () -> Unit,
    onStopServerClick: () -> Unit,
    onBatteryExemptionClick: () -> Unit,
    onCancelJob: (Int) -> Unit,
    onRetryJob: (Int) -> Unit,
    onScanToneSettingsChange: (brightness: Int, contrast: Int) -> Unit,
    onClientAccessSave: (restricted: Boolean, rules: String) -> String?,
) {
    var showMenu by remember { mutableStateOf(false) }
    val settingsRotationAngle by animateFloatAsState(
        targetValue = if (showMenu) 360f else 0f,
        animationSpec = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
        label = "settings_rotation"
    )
    val context = LocalContext.current
    val appName = stringResource(id = R.string.app_name)
    val versionName = remember(context) {
        try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "0.1.0"
        } catch (e: Exception) {
            "0.1.0"
        }
    }

    var showLicensesDialog by remember { mutableStateOf(false) }
    var showClientAccessDialog by remember { mutableStateOf(false) }
    var licensesText by remember { mutableStateOf("") }

    LaunchedEffect(showLicensesDialog) {
        if (showLicensesDialog && licensesText.isEmpty()) {
            licensesText = try {
                context.assets.open("licenses/NOTICE.md")
                    .bufferedReader()
                    .use { it.readText() }
            } catch (e: Exception) {
                "Error loading licenses notice."
            }
        }
    }

    // Monitor Battery Optimization state
    var isBatteryOptimizationIgnored by remember { mutableStateOf(true) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                isBatteryOptimizationIgnored = pm.isIgnoringBatteryOptimizations(context.packageName)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = appName,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.SansSerif,
                            color = MaterialTheme.colorScheme.onBackground,
                            fontSize = 24.sp
                        )
                        Text(
                            text = "Version $versionName",
                            fontWeight = FontWeight.Normal,
                            fontFamily = FontFamily.SansSerif,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                            fontSize = 12.sp
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showMenu = !showMenu }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.rotate(settingsRotationAngle)
                        )
                    }
                    MaterialTheme(
                        shapes = MaterialTheme.shapes.copy(
                            extraSmall = RoundedCornerShape(16.dp)
                        )
                    ) {
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            modifier = Modifier
                                .border(
                                    BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                                    shape = RoundedCornerShape(16.dp)
                                )
                        ) {
                            DropdownMenuItem(
                                text = { Text("Restricted Access", fontWeight = FontWeight.Medium) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    showClientAccessDialog = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Disable Battery Optimization", fontWeight = FontWeight.Medium) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    onBatteryExemptionClick()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Third-Party Licenses", fontWeight = FontWeight.Medium) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    showLicensesDialog = true
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Battery Optimization Warning Banner
            AnimatedVisibility(
                visible = !isBatteryOptimizationIgnored,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Warning",
                                tint = MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = "Battery Optimization Warning",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        Text(
                            text = "Android may terminate background server processes when battery optimization is enabled. Disable it to ensure continuous print server availability.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f)
                        )
                        Button(
                            onClick = onBatteryExemptionClick,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("Disable Optimization")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Smooth state transition between Setup and Active screen
            AnimatedContent(
                targetState = status.running,
                transitionSpec = {
                    (fadeIn(animationSpec = tween(400)) + slideInVertically(animationSpec = tween(400), initialOffsetY = { it / 8 }))
                        .togetherWith(fadeOut(animationSpec = tween(300)))
                },
                label = "state_transition"
            ) { isRunning ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (!isRunning) {
                        // SETUP STATE VIEW
                        
                        // Card 1: Connect Printer via USB
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "Connect Printer via USB",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                
                                Spacer(modifier = Modifier.height(16.dp))
                                
                                UsbConnectionIllustration(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(140.dp)
                                )
                                
                                Spacer(modifier = Modifier.height(20.dp))
                                
                                Button(
                                    onClick = onStartServerClick,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp),
                                    shape = RoundedCornerShape(24.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                ) {
                                    Text(
                                        text = "Start Sharing over Wi-Fi",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }

                        // Card 2: Network / Status Info
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            ) {
                                Text(
                                    text = "Status",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                    fontWeight = FontWeight.Bold
                                )
                                
                                Spacer(modifier = Modifier.height(12.dp))
                                
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .background(
                                                MaterialTheme.colorScheme.surfaceVariant,
                                                RoundedCornerShape(8.dp)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Wifi,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    
                                    Spacer(modifier = Modifier.width(16.dp))
                                    
                                    Column {
                                        Text(
                                            text = "Network",
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 15.sp,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = status.message,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                            fontSize = 13.sp
                                        )
                                        if (status.unsupportedDevice &&
                                            (status.manufacturer != null || status.model != null)
                                        ) {
                                            Text(
                                                text = "Detected: ${status.manufacturer ?: "Unknown"} ${status.model ?: ""}".trim(),
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                                fontSize = 12.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        // ACTIVE SHARING STATE VIEW

                        // Status Banner Badge
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(Color(0xFF4CAF50), RoundedCornerShape(4.dp))
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Printer Shared: Active",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                        }

                        // Card 1: Active Printer & Wireless Illustration
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .background(
                                                MaterialTheme.colorScheme.surfaceVariant,
                                                RoundedCornerShape(8.dp)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Print,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    
                                    Spacer(modifier = Modifier.width(12.dp))
                                    
                                    val dashboardPrinterName = when (status.tier) {
                                        2 -> status.profileName
                                            ?: listOfNotNull(status.manufacturer, status.model).joinToString(" ").ifBlank { null }
                                            ?: status.printerName
                                            ?: "Unknown Printer"
                                        else -> status.printerName ?: "Unknown Printer"
                                    }

                                    Column {
                                        Text(
                                            text = "Printer: $dashboardPrinterName",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "Processing Mode: " + when (status.tier) {
                                                1 -> "Direct IPP-USB Passthrough"
                                                2 -> "On-Device Rendering Active (Host-based)"
                                                else -> "Active"
                                            },
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                            fontSize = 12.sp,
                                            lineHeight = 16.sp
                                        )
                                    }
                                }
                                
                                Spacer(modifier = Modifier.height(16.dp))
                                
                                WirelessSharingIllustration(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(130.dp)
                                )
                                
                                Spacer(modifier = Modifier.height(20.dp))
                                
                                Button(
                                    onClick = onStopServerClick,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp),
                                    shape = RoundedCornerShape(24.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error,
                                        contentColor = MaterialTheme.colorScheme.onError
                                    )
                                ) {
                                    Text(
                                        text = "Stop Sharing Printer",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }

                        ScanStatusCard(
                            status = status,
                            scanToneSettings = scanToneSettings,
                            onScanToneSettingsChange = onScanToneSettingsChange,
                        )

                        SuppliesCard(status = status)

                        // Card 2: Discovery Information
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp)
                            ) {
                                Text(
                                    text = "Discovery Information for Other Devices",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                
                                Spacer(modifier = Modifier.height(12.dp))
                                
                                Text(
                                    text = "1. Ensure other devices (Mac, Windows, iOS) are connected to the same local network.",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    lineHeight = 18.sp
                                )
                                
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                val addressInfo = if (status.ip != null) " at http://${status.ip}:${status.port}" else ""
                                Text(
                                    text = "2. Search for printers; '${status.printerName ?: "Printer"}' will appear automatically (Zero-Conf/AirPrint compatible)$addressInfo.",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    lineHeight = 18.sp
                                )
                            }
                        }

                        // Card 3: Detailed Specifications (Expandable/Details)
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp)
                            ) {
                                Text(
                                    text = "Connection Specifications",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                
                                Spacer(modifier = Modifier.height(12.dp))

                                val details = listOf(
                                    "Tier" to when (status.tier) {
                                        1 -> "Tier 1 (IPP-USB Passthrough)"
                                        2 -> "Tier 2 (On-Device Rendering)"
                                        else -> "N/A"
                                    },
                                    "Network Name" to (status.printerName ?: "N/A"),
                                    "Network Access" to if (clientAccessSettings.mode == ClientAccessMode.RESTRICTED) {
                                        "Restricted (${clientAccessSettings.rules.size} rule${if (clientAccessSettings.rules.size == 1) "" else "s"})"
                                    } else {
                                        "Open"
                                    },
                                    "Manufacturer" to (status.manufacturer ?: "N/A"),
                                    "Model" to (status.model ?: "N/A"),
                                    "Verified Profile" to (status.profileName ?: "N/A"),
                                    "Serial" to (status.serialNumber ?: "N/A"),
                                    "VID:PID" to (status.vidPid ?: "N/A"),
                                    "PDLs" to if (status.pdls.isNotEmpty()) status.pdls.joinToString(", ") else "N/A",
                                    "Supplies" to suppliesSummaryLabel(status),
                                    "Scanner" to scanStatusLabel(status),
                                    "Scan Capabilities" to scanCapabilitiesLabel(status),
                                    "Connected At" to (status.connectedAt?.let { DateFormat.getTimeInstance().format(Date(it)) } ?: "N/A")
                                )

                                details.forEach { (label, value) ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = label,
                                            fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            text = value,
                                            fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                        }

                        // Live Print Queue
                        QueueCard(entries = queueEntries, onCancel = onCancelJob)

                        // Activity Log Feed
                        ActivityCard(entries = activityEntries, onRetry = onRetryJob)
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    if (showLicensesDialog) {
        AlertDialog(
            onDismissRequest = { showLicensesDialog = false },
            title = {
                Text(
                    text = "Third-Party Licenses",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = licensesText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showLicensesDialog = false }) {
                    Text("OK", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            },
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    if (showClientAccessDialog) {
        RestrictedAccessDialog(
            settings = clientAccessSettings,
            onDismiss = { showClientAccessDialog = false },
            onSave = { restricted, rules ->
                onClientAccessSave(restricted, rules).also { error ->
                    if (error == null) showClientAccessDialog = false
                }
            },
        )
    }
}

@Composable
private fun RestrictedAccessDialog(
    settings: ClientAccessSettings,
    onDismiss: () -> Unit,
    onSave: (Boolean, String) -> String?,
) {
    var restricted by remember(settings) {
        mutableStateOf(settings.mode == ClientAccessMode.RESTRICTED)
    }
    var rules by remember(settings) { mutableStateOf(settings.rules.joinToString("\n")) }
    var error by remember(settings) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restricted Access", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "When enabled, only the listed IPv4 devices or ranges can print or scan. " +
                        "The printer remains visible to other devices, but their connections are blocked.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Enable restricted access", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (restricted) "Guest list is active" else "All Wi-Fi clients are allowed",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        )
                    }
                    Switch(checked = restricted, onCheckedChange = { restricted = it; error = null })
                }
                OutlinedTextField(
                    value = rules,
                    onValueChange = { rules = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Allowed addresses") },
                    supportingText = {
                        Text("One per line, for example 192.168.0.100 or 192.168.0.0/28")
                    },
                    minLines = 4,
                    maxLines = 8,
                    isError = error != null,
                    enabled = restricted,
                )
                if (restricted && rules.isBlank()) {
                    Text(
                        "Warning: saving an empty guest list blocks every network client.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "This is a Wi-Fi guest list, not password encryption. Device addresses can change; " +
                        "a router DHCP reservation gives the most reliable exact-address rule.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { error = onSave(restricted, rules) }) {
                Text("Save", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

@Composable
private fun ScanStatusCard(
    status: ServerStatus,
    scanToneSettings: ScanToneSettings,
    onScanToneSettingsChange: (brightness: Int, contrast: Int) -> Unit,
) {
    val progress = status.scanProgress
    var nowMs by remember(progress?.startedAtMs, progress?.phase) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(progress?.startedAtMs, progress?.phase, status.scanState) {
        while (progress != null && status.scanState == ScanState.SCANNING) {
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
    }
    val (label, detail, color) = when (status.scanState) {
        ScanState.READY -> Triple(
            if (progress?.phase == ScanProgressPhase.READY) "Scan Ready" else "Scanner Ready",
            if (progress?.phase == ScanProgressPhase.READY) {
                buildString {
                    append("Result prepared")
                    progress.outputBytes?.let { append(" · ${formatBytes(it)}") }
                    append(" · eSCL ready for the client")
                }
            } else {
                "eSCL available at http://${status.ip}:${status.scanPort}/eSCL"
            },
            Color(0xFF4CAF50),
        )
        ScanState.SCANNING -> Triple(
            scanProgressTitle(progress?.phase),
            scanProgressDetail(progress, nowMs),
            MaterialTheme.colorScheme.primary,
        )
        ScanState.FAILED -> Triple(
            "Scanner Failed",
            status.scanFailureReason ?: "The scan server hit an unknown error.",
            MaterialTheme.colorScheme.error,
        )
        ScanState.STARTING -> Triple(
            "Scanner Starting",
            "Checking scanner capabilities over USB.",
            MaterialTheme.colorScheme.primary,
        )
        ScanState.UNAVAILABLE -> Triple(
            "Scanner Unavailable",
            status.scanFailureReason ?: "No compatible scan interface was detected.",
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(color.copy(alpha = 0.12f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (status.scanState == ScanState.FAILED) Icons.Default.Warning else Icons.Default.Info,
                        contentDescription = null,
                        tint = color,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = label,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = detail,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
            status.scanCapabilities?.let { caps ->
                Text(
                    text = "Resolutions: ${caps.supportedResolutions.joinToString(", ")} dpi · Modes: ${caps.supportedColorModes.joinToString(", ") { it.name.lowercase().replaceFirstChar(Char::uppercase) }}",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                )
            }
            if (status.scanCapabilities != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                if (status.scanState == ScanState.SCANNING && progress != null) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = color,
                        trackColor = color.copy(alpha = 0.14f),
                    )
                    Text(
                        text = "Phase-based progress from HP LEDM state; no fake percentage.",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                    )
                }
                ScanToneSlider(
                    label = "Brightness",
                    value = scanToneSettings.brightness,
                    onValueChange = {
                        onScanToneSettingsChange(it, scanToneSettings.contrast)
                    },
                )
                ScanToneSlider(
                    label = "Contrast",
                    value = scanToneSettings.contrast,
                    onValueChange = {
                        onScanToneSettingsChange(scanToneSettings.brightness, it)
                    },
                )
            }
        }
    }
}

@Composable
private fun ScanToneSlider(label: String, value: Int, onValueChange: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = value.toString(),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = ScanTone.MIN.toFloat()..ScanTone.MAX.toFloat(),
            steps = 39,
        )
    }
}

@Composable
private fun SuppliesCard(status: ServerStatus) {
    val supplyStatus = status.supplyStatus
    val hasSupplySignal = supplyStatus != null || status.supplyFailureReason != null
    if (!status.running || !hasSupplySignal) return

    val color = when {
        supplyStatus == null -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        supplyStatus.cartridges.any { (it.levelPercent ?: 100) <= 15 } -> MaterialTheme.colorScheme.error
        else -> Color(0xFF4CAF50)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(color.copy(alpha = 0.12f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (supplyStatus == null) Icons.Default.Warning else Icons.Default.Info,
                        contentDescription = null,
                        tint = color,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (supplyStatus == null) "Ink Status Unavailable" else "Ink / Supplies",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = supplyStatus?.let { "Read from HP LEDM ${it.sourcePath}" }
                            ?: (status.supplyFailureReason ?: "The printer did not expose supply details."),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                }
            }

            supplyStatus?.cartridges?.forEachIndexed { index, cartridge ->
                SupplyRow(cartridge = cartridge, color = colorForSupply(cartridge, color))
                if (index != supplyStatus.cartridges.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                }
            }
        }
    }
}

@Composable
private fun SupplyRow(cartridge: SupplyCartridge, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = cartridge.name,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                val detail = listOfNotNull(cartridge.color, cartridge.type, cartridge.state, cartridge.message)
                    .distinct()
                    .joinToString(" · ")
                if (detail.isNotBlank()) {
                    Text(
                        text = detail,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        lineHeight = 14.sp,
                    )
                }
            }
            Text(
                text = cartridge.levelPercent?.let { "$it%" } ?: "Level unknown",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
        }
        cartridge.levelPercent?.let { percent ->
            LinearProgressIndicator(
                progress = { percent / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = color,
                trackColor = color.copy(alpha = 0.14f),
            )
        }
    }
}

@Composable
private fun QueueCard(entries: List<QueueEntry>, onCancel: (Int) -> Unit) {
    if (entries.isEmpty()) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            Text(
                text = "Print Queue",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(12.dp))
            entries.forEachIndexed { index, entry ->
                QueueRow(entry = entry, onCancel = { onCancel(entry.id) })
                if (index != entries.lastIndex) {
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun QueueRow(entry: QueueEntry, onCancel: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            val statusLabel = when (entry.state) {
                JobState.PROCESSING -> "Printing…"
                else -> "Queued" + (entry.position?.let { " · #$it" } ?: "")
            }
            Text(
                text = "$statusLabel · ${elapsedSince(entry.submittedAtMs)} · ${formatBytes(entry.sizeBytes)}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        if (entry.state == JobState.PENDING) {
            TextButton(onClick = onCancel) {
                Text("Cancel", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun elapsedSince(startMs: Long): String {
    val minutes = (System.currentTimeMillis() - startMs) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m"
        else -> "${minutes / 60}h"
    }
}

@Composable
private fun ActivityCard(entries: List<ActivityEntry>, onRetry: (Int) -> Unit) {
    var expandedId by remember { mutableStateOf<Int?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            Text(
                text = "Recent Activity",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (entries.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Print,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "No print jobs yet this session.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(entries, key = { it.id }) { entry ->
                        ActivityRow(
                            entry = entry,
                            expanded = expandedId == entry.id,
                            onClick = { expandedId = if (expandedId == entry.id) null else entry.id },
                            onRetry = { entry.jobId?.let(onRetry) },
                            canRetry = entry.jobId?.let { QueueState.isRetryable(it) } ?: false,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityRow(entry: ActivityEntry, expanded: Boolean, onClick: () -> Unit, onRetry: () -> Unit, canRetry: Boolean) {
    val (dotColor, label) = when (entry.status) {
        ActivityStatus.PRINTED -> Color(0xFF4CAF50) to "Printed"
        ActivityStatus.PRINTING -> MaterialTheme.colorScheme.primary to "Printing…"
        ActivityStatus.FAILED -> MaterialTheme.colorScheme.error to
            ("Failed" + (entry.failureReason?.let { " · $it" } ?: ""))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                        RoundedCornerShape(8.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Print,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    Box(modifier = Modifier.size(8.dp).background(dotColor, RoundedCornerShape(4.dp)))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = label,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
            Text(
                text = relativeTime(entry),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
        if (canRetry) {
            TextButton(onClick = onRetry, modifier = Modifier.align(Alignment.End)) {
                Text("Retry")
            }
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .padding(start = 48.dp, top = 8.dp)
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                entry.clientAddress?.let { DetailLine("Client", it) }
                entry.sizeBytes?.let { DetailLine("Size", formatBytes(it)) }
                entry.completedAt?.let { DetailLine("Duration", "%.1fs".format((it - entry.startedAt) / 1000.0)) }
                entry.format?.let { DetailLine("Format", it) }
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            fontWeight = FontWeight.Medium
        )
        Text(
            text = value,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private fun relativeTime(entry: ActivityEntry): String {
    val elapsedMs = System.currentTimeMillis() - entry.startedAt
    val minutes = elapsedMs / 60_000
    return when {
        entry.status == ActivityStatus.PRINTING && entry.completedAt == null && minutes < 1 -> "now"
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        else -> "${minutes / 60}h ago"
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}

private fun formatElapsed(elapsedMs: Long): String {
    val totalSeconds = (elapsedMs.coerceAtLeast(0)) / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

private fun scanProgressTitle(phase: ScanProgressPhase?): String = when (phase) {
    ScanProgressPhase.STARTING -> "Starting scan"
    ScanProgressPhase.SCANNER_WORKING -> "Scanner is working"
    ScanProgressPhase.RECEIVING_IMAGE -> "Receiving image"
    ScanProgressPhase.READY -> "Scan ready"
    ScanProgressPhase.FAILED -> "Scan failed"
    null -> "Scanner is working"
}

private fun scanProgressDetail(
    progress: dev.jaspreet.printserver.service.ScanProgress?,
    nowMs: Long,
): String {
    if (progress == null) return "A scan job is currently running."
    val elapsed = formatElapsed(nowMs - progress.startedAtMs)
    val mode = progress.colorMode.name.lowercase().replaceFirstChar(Char::uppercase)
    val base = "${progress.resolution} dpi · $mode · elapsed $elapsed"
    return when (progress.phase) {
        ScanProgressPhase.STARTING -> "Checking scanner and creating job · $base"
        ScanProgressPhase.SCANNER_WORKING -> "HP reports the job is processing; image is not ready yet · $base"
        ScanProgressPhase.RECEIVING_IMAGE -> "HP returned the image URL; receiving JPEG bytes · $base"
        ScanProgressPhase.READY -> "JPEG is ready${progress.outputBytes?.let { " · ${formatBytes(it)}" } ?: ""} · $base"
        ScanProgressPhase.FAILED -> "Scan failed · $base"
    }
}

private fun scanStatusLabel(status: ServerStatus): String = when (status.scanState) {
    ScanState.READY -> "Ready on port ${status.scanPort ?: "N/A"}"
    ScanState.SCANNING -> scanProgressTitle(status.scanProgress?.phase)
    ScanState.FAILED -> "Failed${status.scanFailureReason?.let { ": $it" } ?: ""}"
    ScanState.STARTING -> "Starting"
    ScanState.UNAVAILABLE -> "Unavailable${status.scanFailureReason?.let { ": $it" } ?: ""}"
}

private fun scanCapabilitiesLabel(status: ServerStatus): String {
    val caps = status.scanCapabilities ?: return "N/A"
    val resolutions = caps.supportedResolutions.joinToString(", ") { "${it} dpi" }
    val modes = caps.supportedColorModes.joinToString(", ") { it.name.lowercase().replaceFirstChar(Char::uppercase) }
    return "$resolutions; $modes"
}

private fun suppliesSummaryLabel(status: ServerStatus): String {
    val supplyStatus = status.supplyStatus
        ?: return status.supplyFailureReason?.let { "Unavailable: $it" } ?: "N/A"
    return supplyStatus.cartridges.joinToString(", ") { cartridge ->
        cartridge.levelPercent?.let { "${cartridge.name} $it%" } ?: cartridge.name
    }
}

private fun colorForSupply(cartridge: SupplyCartridge, fallback: Color): Color = when {
    cartridge.levelPercent != null && cartridge.levelPercent <= 15 -> Color(0xFFE53935)
    cartridge.levelPercent != null && cartridge.levelPercent <= 30 -> Color(0xFFFF9800)
    cartridge.color?.contains("black", ignoreCase = true) == true -> Color(0xFF424242)
    cartridge.color?.contains("cyan", ignoreCase = true) == true -> Color(0xFF00ACC1)
    cartridge.color?.contains("magenta", ignoreCase = true) == true -> Color(0xFFD81B60)
    cartridge.color?.contains("yellow", ignoreCase = true) == true -> Color(0xFFFDD835)
    cartridge.name.contains("black", ignoreCase = true) -> Color(0xFF424242)
    cartridge.name.contains("color", ignoreCase = true) -> Color(0xFF7E57C2)
    else -> fallback
}
