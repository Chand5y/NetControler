package com.netcontrol

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.List
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(modifier = Modifier.fillMaxSize()) { NetControlApp() } } }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NetControlApp() {
    val context = LocalContext.current
    val view = LocalView.current
    val coroutineScope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { 3 })
    
    var activeNetwork by remember { mutableStateOf("Scanning...") }
    var hasPermissions by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { 
            NetworkEngine.logEvent("APP_START", "Requesting root access...")
            try { Runtime.getRuntime().exec("su -c id") } catch (e: Exception) {} 
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
        hasPermissions = perms.values.all { it }
        if (hasPermissions) { 
            NetworkEngine.startFallbackMonitor(context)
            activeNetwork = NetworkEngine.getActiveConnectionName(context) 
        }
    }

    LaunchedEffect(Unit) {
        val locGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val phoneGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        if (!locGranted || !phoneGranted) {
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_PHONE_STATE))
        } else {
            hasPermissions = true
            NetworkEngine.startFallbackMonitor(context)
            activeNetwork = NetworkEngine.getActiveConnectionName(context)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("NetControler", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
            Text("Status: $activeNetwork", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
        }

        TabRow(
            selectedTabIndex = pagerState.currentPage,
            containerColor = Color.Transparent,
            indicator = { tabPositions -> TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(tabPositions[pagerState.currentPage]), color = MaterialTheme.colorScheme.primary) }
        ) {
            listOf("Telemetry", "Hardware", "Logs").forEachIndexed { index, title ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = {
                        view.isHapticFeedbackEnabled = true
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        coroutineScope.launch { pagerState.animateScrollToPage(index) }
                    },
                    text = { Text(title, fontWeight = if (pagerState.currentPage == index) FontWeight.Bold else FontWeight.Normal) }
                )
            }
        }

        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
            when (page) {
                0 -> NetworkBandsScreen(context, hasPermissions)
                1 -> HardwareInfoScreen(context)
                2 -> DiagnosticLogScreen(context)
            }
        }
    }
}

@Composable
fun DiagnosticLogScreen(context: Context) {
    val logs by NetworkEngine.appLogs.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        val logString = NetworkEngine.appLogs.value.joinToString("\n")
                        output.write(logString.toByteArray())
                    }
                    withContext(Dispatchers.Main) { Toast.makeText(context, "Logs saved successfully.", Toast.LENGTH_SHORT).show() }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { Toast.makeText(context, "Failed to save file.", Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("System & Root Console", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Row {
                IconButton(onClick = { exportLauncher.launch("NetControl_Logs.txt") }) {
                    Icon(Icons.Rounded.List, contentDescription = "Save Logs as TXT", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = { NetworkEngine.clearLogs() }) {
                    Icon(Icons.Rounded.Delete, contentDescription = "Clear Logs", tint = Color.Gray)
                }
            }
        }

        Surface(modifier = Modifier.fillMaxSize(), shape = RoundedCornerShape(12.dp), color = Color(0xFF1E1E1E)) {
            if (logs.isEmpty()) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Text("No logs yet...", color = Color.Gray)
                }
            } else {
                LazyColumn(modifier = Modifier.padding(12.dp), reverseLayout = true) {
                    items(logs) { logMsg ->
                        val color = when {
                            logMsg.contains("[ERROR") || logMsg.contains("[ROOT_ERR") || logMsg.contains("[CRASH") -> Color(0xFFFF5252)
                            logMsg.contains("[SUCCESS") || logMsg.contains("[ROOT_OUT") -> Color(0xFF69F0AE)
                            logMsg.contains("[UI_ACTION") || logMsg.contains("[API_MODE") -> Color(0xFF40C4FF)
                            logMsg.contains("[TELEMETRY") || logMsg.contains("[WATCHDOG") -> Color(0xFFFFD740)
                            else -> Color.White
                        }
                        Text(text = logMsg, color = color, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(bottom = 6.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun NetworkBandsScreen(context: android.content.Context, hasPermissions: Boolean) {
    val coroutineScope = rememberCoroutineScope()
    val view = LocalView.current
    var bands by remember { mutableStateOf<List<CellBandInfo>>(emptyList()) }
    var lockedBandId by remember { mutableStateOf<String?>(null) }
    
    var caEnabled by remember { mutableStateOf(true) }
    var volteEnabled by remember { mutableStateOf(true) }
    var vonrEnabled by remember { mutableStateOf(true) }

    val triggerHaptic = { view.isHapticFeedbackEnabled = true; view.performHapticFeedback(HapticFeedbackConstants.CONFIRM) }

    LaunchedEffect(hasPermissions) {
        if (hasPermissions) { bands = NetworkEngine.scanAvailableBands(context) }
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxSize()) {
        
        item {
            Button(
                onClick = { 
                    triggerHaptic()
                    coroutineScope.launch { NetworkEngine.openNativeBandLocker(context) }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
            ) {
                Text("Open Native Radio Info (Fallback)", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }

        item {
            Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("IMS & Modem Overrides", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("Carrier Aggregation (CA)")
                        Switch(checked = caEnabled, onCheckedChange = { 
                            caEnabled = it; triggerHaptic()
                            coroutineScope.launch { NetworkEngine.setProp("persist.radio.lte_ca_enabled", if(it) "1" else "0") } 
                        })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("VoLTE Override")
                        Switch(checked = volteEnabled, onCheckedChange = { 
                            volteEnabled = it; triggerHaptic()
                            coroutineScope.launch { NetworkEngine.setProp("persist.dbg.volte_avail_ovr", if(it) "1" else "0") } 
                        })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("VoNR (5G Calling)")
                        Switch(checked = vonrEnabled, onCheckedChange = { 
                            vonrEnabled = it; triggerHaptic()
                            coroutineScope.launch { NetworkEngine.setProp("persist.radio.vonr_enabled", if(it) "true" else "false") } 
                        })
                    }
                }
            }
        }

        item { 
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Live Cell Telemetry", fontWeight = FontWeight.Bold)
                Button(onClick = { 
                    triggerHaptic()
                    bands = NetworkEngine.scanAvailableBands(context) 
                }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                    Text("Refresh", fontSize = 12.sp)
                }
            }
        }

        if (!hasPermissions) {
            item { Text("Permissions required to scan towers.", color = MaterialTheme.colorScheme.error) }
        } else {
            items(bands) { band ->
                Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().alpha(if (band.isAccessible) 1f else 0.4f)) {
                    Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${band.generation} • ${band.bandName}", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            if (band.isAccessible) {
                                Button(
                                    onClick = { 
                                        triggerHaptic()
                                        if (lockedBandId == band.id) {
                                            lockedBandId = null
                                            coroutineScope.launch { 
                                                NetworkEngine.unlockBands(context) 
                                                withContext(Dispatchers.Main) { Toast.makeText(context, "Restoring Auto Mode...", Toast.LENGTH_SHORT).show() }
                                            }
                                        } else {
                                            lockedBandId = band.id
                                            coroutineScope.launch { 
                                                NetworkEngine.lockBand(context, band.bandName, band.generation) 
                                                withContext(Dispatchers.Main) { Toast.makeText(context, "Locking to ${band.generation}...", Toast.LENGTH_SHORT).show() }
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = if (lockedBandId == band.id) Color(0xFF00C853) else MaterialTheme.colorScheme.primary),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                    modifier = Modifier.height(32.dp)
                                ) { 
                                    Text(if (lockedBandId == band.id) "Unlock" else "Lock", fontSize = 12.sp) 
                                }
                            }
                        }
                        Text("${band.operatorName} | ${band.speedTier}", fontSize = 13.sp, color = Color.Gray, modifier = Modifier.padding(bottom = 6.dp))
                        
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("RSRP (Pwr): ${band.signalDbm} dBm", fontSize = 12.sp)
                            Text("SINR (Noise): ${band.sinr} dB", fontSize = 12.sp)
                        }
                        // FIX: Removed duplicated modifier that broke compilation
                        Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("RSRQ (Qual): ${band.rsrq} dB", fontSize = 12.sp)
                            Text("PCI (Cell ID): ${band.pci}", fontSize = 12.sp, color = Color.Gray)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HardwareInfoScreen(context: android.content.Context) {
    val report = remember { NetworkEngine.getHardwareReport(context) }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxSize()) {
        item {
            Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Device Specifications", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Model", fontSize = 12.sp, color = Color.Gray)
                            Text(report.deviceName, fontWeight = FontWeight.SemiBold)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Memory (RAM)", fontSize = 12.sp, color = Color.Gray)
                            Text("${report.freeRamGb} / ${report.totalRamGb} GB", fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Column {
                        Text("Processor", fontSize = 12.sp, color = Color.Gray)
                        Text(report.processor, fontWeight = FontWeight.SemiBold)
                    }
                    Column {
                        Text("Modem Baseband", fontSize = 12.sp, color = Color.Gray)
                        Text(report.modemFirmware, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
