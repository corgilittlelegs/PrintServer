package dev.jaspreet.printserver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jaspreet.printserver.service.ServerStatus
import dev.jaspreet.printserver.ui.components.UsbConnectionIllustration
import dev.jaspreet.printserver.ui.components.WirelessSharingIllustration
import dev.jaspreet.printserver.ui.theme.Charcoal
import dev.jaspreet.printserver.ui.theme.DarkNavy
import dev.jaspreet.printserver.R
import dev.jaspreet.printserver.ui.theme.LightSlate
import dev.jaspreet.printserver.ui.theme.MediumGray
import dev.jaspreet.printserver.ui.theme.PureWhite
import dev.jaspreet.printserver.ui.theme.SlateBlue
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintServerApp(
    status: ServerStatus,
    onStartServerClick: () -> Unit,
    onStopServerClick: () -> Unit,
    onBatteryExemptionClick: () -> Unit,
    onLicensesClick: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val appName = androidx.compose.ui.res.stringResource(id = R.string.app_name)
    val versionName = remember(context) {
        try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "0.1.0"
        } catch (e: Exception) {
            "0.1.0"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = appName,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.SansSerif,
                            color = PureWhite,
                            fontSize = 20.sp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "v$versionName",
                            fontWeight = FontWeight.Normal,
                            fontFamily = FontFamily.SansSerif,
                            color = PureWhite.copy(alpha = 0.7f),
                            fontSize = 14.sp
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showMenu = !showMenu }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = PureWhite
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        modifier = Modifier.background(PureWhite)
                    ) {
                        DropdownMenuItem(
                            text = { Text("Disable Battery Optimization") },
                            onClick = {
                                showMenu = false
                                onBatteryExemptionClick()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Third-Party Licenses") },
                            onClick = {
                                showMenu = false
                                onLicensesClick()
                            }
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkNavy,
                    titleContentColor = PureWhite,
                    actionIconContentColor = PureWhite
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
        ) {
            // Header Area (Dark Navy)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
                    .background(DarkNavy)
                    .padding(horizontal = 24.dp, vertical = 24.dp)
            ) {
                Text(
                    text = if (status.running) "Sharing Status" else "Home/Setup",
                    style = MaterialTheme.typography.headlineMedium,
                    color = PureWhite
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Main Content Body (Off-White background)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (!status.running) {
                    // SETUP STATE VIEW
                    
                    // Card 1: Connect Printer via USB
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = PureWhite),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
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
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onBackground
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
                                    containerColor = SlateBlue,
                                    contentColor = PureWhite
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
                        colors = CardDefaults.cardColors(containerColor = PureWhite),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Text(
                                text = "Status",
                                style = MaterialTheme.typography.labelMedium,
                                color = MediumGray,
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
                                            LightSlate.copy(alpha = 0.4f),
                                            RoundedCornerShape(8.dp)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Wifi,
                                        contentDescription = null,
                                        tint = SlateBlue
                                    )
                                }
                                
                                Spacer(modifier = Modifier.width(16.dp))
                                
                                Column {
                                    Text(
                                        text = "Network",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 15.sp,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                    Text(
                                        text = status.message,
                                        color = MediumGray,
                                        fontSize = 13.sp
                                    )
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
                            .background(SlateBlue.copy(alpha = 0.15f))
                            .border(1.dp, SlateBlue.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
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
                                color = DarkNavy,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                    }

                    // Card 1: Active Printer & Wireless Illustration
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = PureWhite),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
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
                                            LightSlate.copy(alpha = 0.4f),
                                            RoundedCornerShape(8.dp)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Print,
                                        contentDescription = null,
                                        tint = SlateBlue
                                    )
                                }
                                
                                Spacer(modifier = Modifier.width(12.dp))
                                
                                Column {
                                    Text(
                                        text = "Printer: ${status.printerName ?: "Unknown Printer"}",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                    Text(
                                        text = "Processing Mode: " + when (status.tier) {
                                            1 -> "Direct IPP-USB Passthrough"
                                            2 -> "On-Device Rendering Active (Host-based)"
                                            else -> "Active"
                                        },
                                        color = MediumGray,
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
                        }
                    }

                    // Card 2: Discovery Information
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = PureWhite),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
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
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            Text(
                                text = "1. Ensure other devices (Mac, Windows, iOS) are connected to the same local network.",
                                fontSize = 13.sp,
                                color = MediumGray,
                                lineHeight = 18.sp
                            )
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            val addressInfo = if (status.ip != null) " at http://${status.ip}:${status.port}" else ""
                            Text(
                                text = "2. Search for printers; '${status.printerName ?: "Printer"}' will appear automatically (Zero-Conf/AirPrint compatible)$addressInfo.",
                                fontSize = 13.sp,
                                color = MediumGray,
                                lineHeight = 18.sp
                            )
                        }
                    }

                    // Card 3: Detailed Specifications (Expandable/Details)
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = PureWhite),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
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
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            
                            Spacer(modifier = Modifier.height(12.dp))

                            val details = listOf(
                                "Tier" to when (status.tier) {
                                    1 -> "Tier 1 (IPP-USB Passthrough)"
                                    2 -> "Tier 2 (On-Device Rendering)"
                                    else -> "N/A"
                                },
                                "Manufacturer" to (status.manufacturer ?: "N/A"),
                                "Model" to (status.model ?: "N/A"),
                                "Serial" to (status.serialNumber ?: "N/A"),
                                "VID:PID" to (status.vidPid ?: "N/A"),
                                "PDLs" to if (status.pdls.isNotEmpty()) status.pdls.joinToString(", ") else "N/A",
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
                                        color = MediumGray,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = value,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onBackground,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }

                    // Bottom Stop Sharing Button
                    Button(
                        onClick = onStopServerClick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LightSlate,
                            contentColor = Charcoal
                        )
                    ) {
                        Text(
                            text = "Stop Sharing",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}
