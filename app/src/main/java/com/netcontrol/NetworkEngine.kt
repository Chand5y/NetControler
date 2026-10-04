package com.netcontrol

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CellBandInfo(
    val id: String, val generation: String, val bandName: String,
    val speedTier: String, val signalDbm: String, val bars: Int,
    val operatorName: String, val isConnected: Boolean, val isAccessible: Boolean
)

data class HardwareReport(
    val deviceName: String, val processor: String,
    val totalRamGb: String, val freeRamGb: String,
    val modemFirmware: String,
    val supported5gBands: List<String>, val supported4gBands: List<String>
)

object NetworkEngine {
    var currentLockedBand: String? = null

    // --- LOGGER SYSTEM ---
    private val _appLogs = MutableStateFlow<List<String>>(emptyList())
    val appLogs = _appLogs.asStateFlow()

    fun logEvent(tag: String, message: String) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val logEntry = "[$time] [$tag] $message"
        // Keep last 500 logs, insert at top
        _appLogs.value = listOf(logEntry) + _appLogs.value.take(499)
    }

    fun clearLogs() {
        _appLogs.value = emptyList()
        logEvent("SYSTEM", "Logs cleared.")
    }
    // ---------------------

    suspend fun executeRootWithLog(command: String): String = withContext(Dispatchers.IO) {
        logEvent("ROOT_CMD", "Executing: $command")
        try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("$command\n")
            os.writeBytes("exit\n")
            os.flush()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))
            
            val output = reader.readText().trim()
            val error = errorReader.readText().trim()
            
            process.waitFor()
            
            if (error.isNotEmpty()) {
                logEvent("ROOT_ERR", error)
                "ERROR: $error"
            } else if (output.isNotEmpty()) {
                logEvent("ROOT_OUT", output)
                "SUCCESS: $output"
            } else {
                logEvent("ROOT_OUT", "[No Output / Success]")
                "OK"
            }
        } catch (e: Exception) {
            logEvent("ROOT_CRASH", e.message ?: "Unknown crash")
            "CRASH: ${e.message}"
        }
    }

    suspend fun setProp(prop: String, value: String): String {
        logEvent("MODEM_FEATURE", "Setting $prop to $value")
        return executeRootWithLog("setprop $prop $value")
    }
    
    suspend fun applyNetworkMode(mode: String): String {
        logEvent("NETWORK_MODE", "Attempting to apply mode: $mode")
        val legacyMode = when (mode) {
            "NR_ONLY" -> "33"
            "LTE_ONLY" -> "11"
            else -> "26"
        }

        logEvent("NETWORK_MODE", "Updating Global Database to mode $legacyMode")
        executeRootWithLog("settings put global preferred_network_mode $legacyMode")
        executeRootWithLog("settings put global preferred_network_mode1 $legacyMode")
        executeRootWithLog("settings put global preferred_network_mode2 $legacyMode")
        
        logEvent("NETWORK_MODE", "Injecting legacy preferred-network-type ($legacyMode) to SIMs")
        executeRootWithLog("cmd phone set-preferred-network-type $legacyMode")
        executeRootWithLog("cmd phone set-preferred-network-type 0 $legacyMode")
        executeRootWithLog("cmd phone set-preferred-network-type 1 $legacyMode")
        
        logEvent("NETWORK_MODE", "Killing Telephony Daemon to force reload...")
        val killLog = executeRootWithLog("pkill -f com.android.phone")
        
        return "Applied Mode $legacyMode. Kill Status: $killLog"
    }

    suspend fun lockBand(bandName: String, generation: String): String {
        logEvent("UI_ACTION", "Lock Button Clicked for $bandName ($generation)")
        currentLockedBand = bandName
        val mode = if (generation == "5G") "NR_ONLY" else "LTE_ONLY"
        return applyNetworkMode(mode)
    }

    suspend fun unlockBands(): String {
        logEvent("UI_ACTION", "Unlock Button Clicked")
        currentLockedBand = null
        return applyNetworkMode("AUTO")
    }

    @SuppressLint("MissingPermission")
    fun getActiveConnectionName(context: Context): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(net)
            
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                logEvent("CONN_STATE", "Active Connection is Wi-Fi")
                return "Wi-Fi Network"
            }
            
            val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val carrier = sm.activeSubscriptionInfoList?.firstOrNull()?.carrierName?.toString() ?: "Cellular Data"
            logEvent("CONN_STATE", "Active Connection is Cellular: $carrier")
            carrier
        } catch (e: Exception) { 
            logEvent("CONN_STATE_ERR", "Failed to read active connection")
            "Unknown Network" 
        }
    }

    @SuppressLint("MissingPermission")
    fun scanAvailableBands(context: Context): List<CellBandInfo> {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
        
        val activeSim = try { sm.activeSubscriptionInfoList?.firstOrNull() } catch(e:Exception){ null }
        val simMccMnc = if (activeSim != null) "${activeSim.mccString}${activeSim.mncString}" else ""
        val carrierName = activeSim?.carrierName?.toString() ?: "Your SIM"

        val bandList = mutableListOf<CellBandInfo>()
        val cells = try { tm.allCellInfo } catch (e: Exception) { null } ?: emptyList()

        var connectedBandLog = "No active cellular band detected"

        for (cell in cells) {
            try {
                val isReg = cell.isRegistered
                when (cell) {
                    is CellInfoNr -> {
                        val id = cell.cellIdentity as? CellIdentityNr
                        val dbmRaw = (cell.cellSignalStrength as? CellSignalStrengthNr)?.dbm ?: CellInfo.UNAVAILABLE
                        val dbm = if (dbmRaw == CellInfo.UNAVAILABLE || dbmRaw > 0) "N/A" else dbmRaw.toString()
                        val band = resolve5gBand(id?.nrarfcn ?: 0)
                        val speed = if (band == "n78" || band.contains("258")) "Ultra Fast / High Band" else "Stable / Wide Coverage"
                        
                        val cellMccMnc = "${id?.mccString}${id?.mncString}"
                        val match = isReg || simMccMnc.isEmpty() || cellMccMnc == simMccMnc || cellMccMnc == "nullnull" || cellMccMnc.startsWith(simMccMnc.take(5))
                        
                        if (isReg) connectedBandLog = "Connected to 5G $band | Signal: $dbm dBm"
                        bandList.add(CellBandInfo("5G-${id?.nrarfcn}", "5G", band, speed, dbm, calcBars(dbmRaw), if(match) carrierName else "Other Network", isReg, match))
                    }
                    is CellInfoLte -> {
                        val id = cell.cellIdentity as? CellIdentityLte
                        val dbmRaw = (cell.cellSignalStrength as? CellSignalStrengthLte)?.dbm ?: CellInfo.UNAVAILABLE
                        val dbm = if (dbmRaw == CellInfo.UNAVAILABLE || dbmRaw > 0) "N/A" else dbmRaw.toString()
                        val band = resolve4gBand(id?.earfcn ?: 0)
                        val speed = if (band == "Band 40" || band == "Band 3") "Balanced Mid-Band" else "Standard Coverage"

                        val cellMccMnc = "${id?.mccString}${id?.mncString}"
                        val match = isReg || simMccMnc.isEmpty() || cellMccMnc == simMccMnc || cellMccMnc == "nullnull" || cellMccMnc.startsWith(simMccMnc.take(5))

                        if (isReg) connectedBandLog = "Connected to 4G $band | Signal: $dbm dBm"
                        bandList.add(CellBandInfo("4G-${id?.earfcn}", "4G", band, speed, dbm, calcBars(dbmRaw), if(match) carrierName else "Other Network", isReg, match))
                    }
                }
            } catch (e: Exception) {}
        }
        
        // Log the active connection during a scan
        if (cells.isNotEmpty()) {
            logEvent("SCANNER", connectedBandLog)
        }
        
        return bandList.distinctBy { it.bandName }.sortedByDescending { it.isConnected }
    }

    fun getHardwareReport(context: Context): HardwareReport {
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager.getMemoryInfo(memInfo)
        
        val totalRam = String.format("%.1f", memInfo.totalMem / (1024.0 * 1024.0 * 1024.0))
        val freeRam = String.format("%.1f", memInfo.availMem / (1024.0 * 1024.0 * 1024.0))
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE

        return HardwareReport(
            deviceName = Build.MODEL,
            processor = if (soc.isNotBlank() && soc != "unknown") soc else "Snapdragon 870",
            totalRamGb = totalRam, freeRamGb = freeRam,
            modemFirmware = Build.getRadioVersion() ?: "Unknown",
            supported5gBands = listOf("n1", "n3", "n5", "n7", "n8", "n20", "n28", "n38", "n40", "n41", "n77", "n78"),
            supported4gBands = listOf("B1", "B2", "B3", "B4", "B5", "B7", "B8", "B20", "B28", "B38", "B40", "B41")
        )
    }

    private fun calcBars(dbm: Int): Int = when { dbm >= -85 -> 4; dbm >= -98 -> 3; dbm >= -110 -> 2; dbm >= -120 -> 1; else -> 0 }
    private fun resolve5gBand(arfcn: Int): String = when(arfcn) { in 620000..653333 -> "n78"; in 151600..160600 -> "n28"; in 499200..537999 -> "n41"; else -> "NR-$arfcn" }
    private fun resolve4gBand(arfcn: Int): String = when(arfcn) { in 38650..39649 -> "Band 40"; in 1200..1949 -> "Band 3"; in 0..599 -> "Band 1"; in 2400..2649 -> "Band 5"; else -> "LTE-$arfcn" }
}
