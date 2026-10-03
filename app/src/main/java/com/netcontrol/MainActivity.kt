package com.netcontrol

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(modifier = Modifier.fillMaxSize()) { NetControlApp() } } }
    }
}

@Composable
fun NetControlApp() {
    val context = LocalContext.current
    val view = LocalView.current
    val coroutineScope = rememberCoroutineScope()
    
    var bands by remember { mutableStateOf<List<CellBandInfo>>(emptyList()) }
    var activeNetwork by remember { mutableStateOf("Scanning...") }
    var hasPermissions by remember { mutableStateOf(false) }
    
    var caEnabled by remember { mutableStateOf(true) }
    var volteEnabled by remember { mutableStateOf(true) }
    var vonrEnabled by remember { mutableStateOf(true) }

    val triggerHaptic = { view.isHapticFeedbackEnabled = true; view.performHapticFeedback(HapticFeedbackConstants.CONFIRM) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { try { Runtime.getRuntime().exec("su -c id") } catch (e: Exception) {} }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
        hasPermissions = perms.values.all { it }
        if (hasPermissions) {
            activeNetwork = NetworkEngine.getActiveConnectionName(context)
            bands = NetworkEngine.scanAvailableBands(context)
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
            bands = NetworkEngine.scanAvailableBands(context)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("NetControler", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        Text("Status: $activeNetwork", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 16.dp))

        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), shape = RoundedCornerShape(16.dp)) {
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

        Text("Available Scanned Bands", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))

        if (!hasPermissions) {
            Text("Permissions required to scan towers.", color = MaterialTheme.colorScheme.error)
        } else if (bands.isEmpty()) {
            Text("No bands detected. Try turning off Wi-Fi or moving near a window.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(bands) { band ->
                    Card(modifier = Modifier.fillMaxWidth().alpha(if (band.isAccessible) 1f else 0.5f), shape = RoundedCornerShape(16.dp)) {
                        Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text("${band.generation} • ${band.bandName}", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                Text("${band.operatorName} | ${band.speedTier}", fontSize = 13.sp)
                                Text("Signal: ${band.signalDbm} dBm | Bars: ${band.bars}/4", fontSize = 12.sp, color = Color.Gray)
                                if (band.isConnected) Text("Active Connection", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            Button(onClick = { triggerHaptic(); coroutineScope.launch { NetworkEngine.lockBand(band.bandName) } }) { Text("Lock") }
                        }
                    }
                }
            }
        }
    }
}
