package com.netcontrol

import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NetControlApp()
        }
    }
}

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NetControlApp() {
    val context = LocalContext.current
    val view = LocalView.current
    val isDark = isSystemInDarkTheme()
    val coroutineScope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { 2 })

    // Wake up Root on Launch
    LaunchedEffect(Unit) {
        kotlinx.coroutines.Dispatchers.IO.invoke {
            Runtime.getRuntime().exec("su -c id") // Wakes up Magisk/KernelSU prompt
        }
    }

    val triggerHaptic: (Int) -> Unit = { feedbackType ->
        view.isHapticFeedbackEnabled = true
        view.performHapticFeedback(feedbackType)
    }

    val backgroundBrush = if (isDark) {
        Brush.verticalGradient(listOf(Color(0xFF0C0C0E), Color(0xFF000000)))
    } else {
        Brush.verticalGradient(listOf(Color(0xFFE8F5E9), Color(0xFFE1F5FE), Color(0xFFF8F9FA)))
    }

    Box(modifier = Modifier.fillMaxSize().background(backgroundBrush)) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("NetControler", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = if (isDark) Color.White else Color(0xFF1F1F1F))
                    Text("HyperOS Radio Subsystem", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Rounded.CellTower, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
            }

            TabRow(
                selectedTabIndex = pagerState.currentPage,
                containerColor = Color.Transparent,
                divider = {},
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[pagerState.currentPage]),
                        height = 3.dp, color = MaterialTheme.colorScheme.primary
                    )
                },
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                listOf("Network Bands", "Hardware Specs").forEachIndexed { index, title ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = {
                            triggerHaptic(HapticFeedbackConstants.CLOCK_TICK)
                            coroutineScope.launch { pagerState.animateScrollToPage(index) }
                        },
                        text = { Text(title, fontWeight = if (pagerState.currentPage == index) FontWeight.Bold else FontWeight.Normal, fontSize = 15.sp) }
                    )
                }
            }

            HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
                when (page) {
                    0 -> NetworkBandsScreen(context, triggerHaptic)
                    1 -> HardwareInfoScreen(context, triggerHaptic)
                }
            }
        }
    }
}

@Composable
fun NetworkBandsScreen(context: android.content.Context, triggerHaptic: (Int) -> Unit) {
    val coroutineScope = rememberCoroutineScope()
    var bands by remember { mutableStateOf<List<CellBandInfo>>(emptyList()) }
    var lockedBandId by remember { mutableStateOf<String?>(null) }
    var hasPermissions by remember { mutableStateOf(false) }

    // Permission Launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasPermissions = permissions.values.all { it }
        if (hasPermissions) {
            bands = NetworkEngine.scanAvailableBands(context)
        }
    }

    // Check permissions on screen load
    LaunchedEffect(Unit) {
        val locGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val phoneGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        
        if (!locGranted || !phoneGranted) {
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_PHONE_STATE))
        } else {
            hasPermissions = true
            bands = NetworkEngine.scanAvailableBands(context)
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            ActiveCarrierBanner(bands.firstOrNull { it.isConnected }?.operatorName ?: "SIM 1 Network")
        }

        if (!hasPermissions) {
            item {
                Text("Location and Phone permissions are required to scan cell towers.", color = MaterialTheme.colorScheme.error)
            }
        }

        items(bands, key = { it.id }) { band ->
            BandCard(
                band = band,
                isLocked = lockedBandId == band.id,
                onLockClick = {
                    triggerHaptic(HapticFeedbackConstants.CONFIRM)
                    lockedBandId = if (lockedBandId == band.id) null else band.id
                    coroutineScope.launch { NetworkEngine.lockBand(band.bandName) }
                }
            )
        }
    }
}




@Composable
fun ActiveCarrierBanner(carrierName: String) {
    val isDark = isSystemInDarkTheme()
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (isDark) Color(0xFF1E1E22) else Color.White,
        border = BorderStroke(1.dp, if (isDark) Color(0xFF2E2E34) else Color(0xFFE0E0E0)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.SimCard, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text("Active Subscription", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(carrierName, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }
}

@Composable
fun BandCard(band: CellBandInfo, isLocked: Boolean, onLockClick: () -> Unit) {
    val isDark = isSystemInDarkTheme()
    val accessible = band.isCurrentCarrier
    val cardAlpha = if (accessible) 1f else 0.35f // Grayed-out dull aesthetic for inaccessible foreign towers

    val borderColor by animateColorAsState(
        targetValue = if (isLocked) MaterialTheme.colorScheme.primary else (if (isDark) Color(0xFF28282D) else Color(0xFFE5E5EA)),
        animationSpec = tween(300)
    )

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = if (isDark) Color(0xFF161618) else Color.White,
        border = BorderStroke(if (isLocked) 2.dp else 1.dp, borderColor),
        modifier = Modifier.fillMaxWidth().alpha(cardAlpha)
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${band.generation} • ${band.bandName}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    SignalBarsGraphic(band.signalBars)
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = band.speedTier.label,
                    fontSize = 12.sp,
                    color = Color(band.speedTier.colorHex),
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${band.operatorName} | ${band.signalDbm} dBm",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (accessible) {
                Button(
                    onClick = onLockClick,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isLocked) MaterialTheme.colorScheme.primary else (if (isDark) Color(0xFF2A2A2E) else Color(0xFFEEEEF2)),
                        contentColor = if (isLocked) Color.White else MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Text(if (isLocked) "Locked" else "Lock Band", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun SignalBarsGraphic(bars: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        for (i in 1..4) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height((i * 3.5).dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (i <= bars) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.3f))
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HardwareInfoScreen(context: android.content.Context, triggerHaptic: (Int) -> Unit) {
    val report = remember { NetworkEngine.getHardwareReport(context) }
    val isDark = isSystemInDarkTheme()

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = if (isDark) Color(0xFF161618) else Color.White,
                border = BorderStroke(1.dp, if (isDark) Color(0xFF28282D) else Color(0xFFE5E5EA)),
                modifier = Modifier.fillMaxWidth().clickable { triggerHaptic(HapticFeedbackConstants.CLOCK_TICK) }
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Device Specifications", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Model", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(report.deviceName, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Memory (RAM)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${report.freeRamGb} / ${report.totalRamGb}", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        }
                    }
                    Column {
                        Text("Processor", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(report.processor, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    }
                    Column {
                        Text("Modem Baseband", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(report.modemFirmware, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    }
                }
            }
        }

        item {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = if (isDark) Color(0xFF161618) else Color.White,
                border = BorderStroke(1.dp, if (isDark) Color(0xFF28282D) else Color(0xFFE5E5EA)),
                modifier = Modifier.fillMaxWidth().clickable { triggerHaptic(HapticFeedbackConstants.CLOCK_TICK) }
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Modem Supported Bands", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(modifier = Modifier.height(14.dp))
                    
                    Text("5G NR Channels", fontSize = 13.sp, color = Color(0xFF00C853), fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        report.supported5gBands.forEach { band ->
                            Surface(shape = RoundedCornerShape(8.dp), color = if (isDark) Color(0xFF252528) else Color(0xFFF0F0F3)) {
                                Text(band, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), fontSize = 13.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text("4G LTE Channels", fontSize = 13.sp, color = Color(0xFF2979FF), fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        report.supported4gBands.forEach { band ->
                            Surface(shape = RoundedCornerShape(8.dp), color = if (isDark) Color(0xFF252528) else Color(0xFFF0F0F3)) {
                                Text(band, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
