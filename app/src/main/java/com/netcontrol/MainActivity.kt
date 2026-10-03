package com.netcontrol

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.HapticFeedbackConstants
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
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
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
    val pagerState = rememberPagerState(pageCount = { 2 })
    
    var activeNetwork by remember { mutableStateOf("Scanning...") }
    var hasPermissions by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { try { Runtime.getRuntime().exec("su -c id") } catch (e: Exception) {} }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
        hasPermissions = perms.values.all { it }
        if (hasPermissions) { 
            activeNetwork = NetworkEngine.getActiveConnectionName(context) 
            NetworkEngine.startFallbackMonitor(context)
        }
    }

    LaunchedEffect(Unit) {
        val locGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val phoneGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        if (!locGranted || !phoneGranted) {
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_PHONE_STATE))
        } else {
            hasPermissions = true
            activeNetwork = NetworkEngine.getActiveConnectionName(context)
            NetworkEngine.startFallbackMonitor(context)
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
            listOf("Network Bands", "Hardware Specs").forEachIndexed { index, title ->
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
            if (page == 0) { NetworkBandsScreen(context, hasPermissions) } else { HardwareInfoScreen(context) }
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
            Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Modem Features", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("Carrier Aggregation (CA)")
                        Switch(checked = caEnabled, onCheckedChange = { caEnabled = it; triggerHaptic(); coroutineScope.launch { NetworkEngine.setProp("persist.radio.lte_ca_enabled", if(it) "1" else "0") } })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("VoLTE Override")
                        Switch(checked = volteEnabled, onCheckedChange = { volteEnabled = it; triggerHaptic(); coroutineScope.launch { NetworkEngine.setProp("persist.dbg.volte_avail_ovr", if(it) "1" else "0") } })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("VoNR (5G Calling)")
                        Switch(checked = vonrEnabled, onCheckedChange = { vonrEnabled = it; triggerHaptic(); coroutineScope.launch { NetworkEngine.setProp("persist.radio.vonr_enabled", if(it) "true" else "false") } })
                    }
                }
            }
        }

        item { Text("Available Scanned Bands", fontWeight = FontWeight.Bold) }

        if (!hasPermissions) {
            item { Text("Permissions required to scan towers.", color = MaterialTheme.colorScheme.error) }
        } else {
            items(bands) { band ->
                Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().alpha(if (band.isAccessible) 1f else 0.4f)) {
                    Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("${band.generation} • ${band.bandName}", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text("${band.operatorName} | ${band.speedTier}", fontSize = 13.sp, color = Color.Gray)
                            Text("Signal: ${band.signalDbm} dBm | Bars: ${band.bars}/4", fontSize = 13.sp)
                            if (band.isConnected) {
                                Text("Active Connection", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        if (band.isAccessible) {
                            Button(
                                onClick = { 
                                    triggerHaptic()
                                    lockedBandId = band.id
                                    coroutineScope.launch { NetworkEngine.lockBand(band.bandName) } 
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = if (lockedBandId == band.id) Color(0xFF00C853) else MaterialTheme.colorScheme.primary)
                            ) { 
                                Text(if (lockedBandId == band.id) "Locked" else "Lock") 
                            }
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

        item {
            Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Modem Supported Bands", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(bottom = 12.dp))
                    
                    Text("5G NR Channels", fontSize = 13.sp, color = Color(0xFF00C853), fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        report.supported5gBands.forEach { band ->
                            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                Text(band, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), fontSize = 13.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text("4G LTE Channels", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        report.supported4gBands.forEach { band ->
                            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                Text(band, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
