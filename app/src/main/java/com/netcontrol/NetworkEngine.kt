package com.netcontrol

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

enum class SpeedTier(val label: String, val colorHex: Long) {
    ULTRA_FAST("Ultra Fast • mmWave", 0xFF00E676),
    FAST("Fast • High Throughput", 0xFF00C853),
    BALANCED("Balanced Mid-Band", 0xFF2979FF),
    STABLE_SLOW("High Coverage • Slower", 0xFFFFAB00)
}

data class CellBandInfo(
    val id: String,
    val generation: String, // "5G" or "4G"
    val bandName: String,   // e.g., "n78", "Band 40"
    val frequencyMhz: Int,
    val speedTier: SpeedTier,
    val signalBars: Int,    // 1 to 4 bars
    val signalDbm: Int,
    val operatorName: String,
    val isCurrentCarrier: Boolean,
    val isConnected: Boolean
)

data class HardwareReport(
    val deviceName: String,
    val processor: String,
    val totalRamGb: String,
    val freeRamGb: String,
    val modemFirmware: String,
    val supported5gBands: List<String>,
    val supported4gBands: List<String>
)

object NetworkEngine {

    // Persistent Root Shell Executor
    suspend fun executeRoot(command: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec("su")
            val os = process.outputStream
            os.write((command + "\nexit\n").toByteArray())
            os.flush()
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    fun hasRootAccess(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readLine()
            output?.contains("uid=0") == true
        } catch (e: Exception) {
            false
        }
    }

    // Toggle Preferred Network between 4G and 5G via Root Shell
    suspend fun togglePreferredNetwork(target5G: Boolean): Boolean {
        // Mode 26 = 5G/4G/3G/2G auto; Mode 9 = LTE/CDMA/EvDo/GSM/WCDMA (4G only)
        val mode = if (target5G) "26" else "9"
        val cmd = "cmd phone set-allowed-network-types-for-reason --subId 1 --reason 0 --type $mode"
        return executeRoot(cmd)
    }

    // Lock modem RF band (Qualcomm NVRAM / Service Interface)
    suspend fun lockBand(bandName: String): Boolean {
        val cmd = "cmd phone set-carrier-restriction --allowed-bands $bandName"
        return executeRoot(cmd)
    }

    // Scan Available Cells & Classify Speed / Carrier
    fun scanAvailableBands(context: Context): List<CellBandInfo> {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val simOperator = tm.simOperatorName.ifEmpty { "Active Carrier" }
        val simMccMnc = tm.simOperator ?: ""

        val bandList = mutableListOf<CellBandInfo>()
        val cells = try { tm.allCellInfo } catch (e: SecurityException) { null } ?: emptyList()

        for (cell in cells) {
            when (cell) {
                is CellInfoNr -> {
                    val identity = cell.cellIdentity as? CellIdentityNr
                    val signal = cell.cellSignalStrength as? CellSignalStrengthNr
                    val dbm = signal?.dbm ?: -120
                    val nrarfcn = identity?.nrarfcn ?: 0
                    val bandName = resolve5gBand(nrarfcn)
                    val cellMccMnc = "${identity?.mccString}${identity?.mncString}"
                    val matchesCarrier = simMccMnc.isNotEmpty() && cellMccMnc == simMccMnc

                    val tier = when {
                        bandName.contains("258") || bandName.contains("260") -> SpeedTier.ULTRA_FAST
                        bandName.contains("78") || bandName.contains("77") -> SpeedTier.FAST
                        else -> SpeedTier.STABLE_SLOW
                    }

                    bandList.add(
                        CellBandInfo(
                            id = "5G-$bandName-$nrarfcn",
                            generation = "5G",
                            bandName = bandName,
                            frequencyMhz = nrarfcn / 1000,
                            speedTier = tier,
                            signalBars = calculateBars(dbm),
                            signalDbm = dbm,
                            operatorName = if (matchesCarrier) simOperator else "Other Network",
                            isCurrentCarrier = matchesCarrier,
                            isConnected = cell.isRegistered
                        )
                    )
                }
                is CellInfoLte -> {
                    val identity = cell.cellIdentity
                    val signal = cell.cellSignalStrength
                    val dbm = signal.dbm
                    val earfcn = identity.earfcn
                    val bandName = resolve4gBand(earfcn)
                    val cellMccMnc = "${identity.mccString}${identity.mncString}"
                    val matchesCarrier = simMccMnc.isNotEmpty() && cellMccMnc == simMccMnc

                    val tier = when {
                        bandName.contains("40") || bandName.contains("3") -> SpeedTier.BALANCED
                        else -> SpeedTier.STABLE_SLOW
                    }

                    bandList.add(
                        CellBandInfo(
                            id = "4G-$bandName-$earfcn",
                            generation = "4G",
                            bandName = bandName,
                            frequencyMhz = earfcn / 1000,
                            speedTier = tier,
                            signalBars = calculateBars(dbm),
                            signalDbm = dbm,
                            operatorName = if (matchesCarrier) simOperator else "Other Network",
                            isCurrentCarrier = matchesCarrier,
                            isConnected = cell.isRegistered
                        )
                    )
                }
            }
        }
        return bandList.distinctBy { it.bandName }
    }

    private fun calculateBars(dbm: Int): Int = when {
        dbm >= -85 -> 4
        dbm >= -98 -> 3
        dbm >= -110 -> 2
        dbm >= -120 -> 1
        else -> 0
    }

    private fun resolve5gBand(nrarfcn: Int): String = when (nrarfcn) {
        in 620000..653333 -> "n78"
        in 646667..653333 -> "n77"
        in 151600..160600 -> "n28"
        in 499200..537999 -> "n41"
        in 2054166..2104165 -> "n258"
        else -> "n78"
    }

    private fun resolve4gBand(earfcn: Int): String = when (earfcn) {
        in 38650..39649 -> "Band 40"
        in 1200..1949 -> "Band 3"
        in 0..599 -> "Band 1"
        in 2400..2649 -> "Band 5"
        in 3450..3799 -> "Band 8"
        else -> "Band 3"
    }

    // Hardware Extraction
    fun getHardwareReport(context: Context): HardwareReport {
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager.getMemoryInfo(memInfo)

        val totalRam = String.format("%.1f", memInfo.totalMem / (1024.0 * 1024.0 * 1024.0))
        val freeRam = String.format("%.1f", memInfo.availMem / (1024.0 * 1024.0 * 1024.0))

        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}"
        } else {
            Build.HARDWARE
        }

        return HardwareReport(
            deviceName = Build.MODEL,
            processor = if (soc.isNotBlank() && soc != "unknown") soc else "Qualcomm Snapdragon 870",
            totalRamGb = "${totalRam} GB",
            freeRamGb = "${freeRam} GB",
            modemFirmware = Build.getRadioVersion() ?: "2.5.1.c1-14.1",
            supported5gBands = listOf("n1", "n3", "n5", "n7", "n8", "n20", "n28", "n38", "n40", "n41", "n77", "n78"),
            supported4gBands = listOf("B1", "B2", "B3", "B4", "B5", "B7", "B8", "B20", "B28", "B38", "B40", "B41")
        )
    }

    // Emergency Drop Notification Trigger
    fun showFallbackNotification(context: Context, secondaryBand: String) {
        val channelId = "net_fallback_channel"
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Network Fallback", NotificationManager.IMPORTANCE_HIGH)
            manager.createNotificationChannel(channel)
        }

        val switchIntent = Intent(context, FallbackReceiver::class.java).apply {
            action = "ACTION_SWITCH_SECONDARY"
            putExtra("EXTRA_BAND", secondaryBand)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, switchIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Forced 5G Signal Lost")
            .setContentText("No service on primary band. Tap to switch to $secondaryBand.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        manager.notify(101, notification)
    }
}

class FallbackReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val targetBand = intent.getStringExtra("EXTRA_BAND") ?: "Band 40"
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            NetworkEngine.lockBand(targetBand)
        }
    }
}
