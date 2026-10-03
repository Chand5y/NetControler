package com.netcontrol

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CellBandInfo(
    val id: String, val generation: String, val bandName: String,
    val speedTier: String, val signalDbm: String, val bars: Int,
    val operatorName: String, val isConnected: Boolean, val isAccessible: Boolean
)

object NetworkEngine {

    suspend fun executeRoot(command: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            p.waitFor() == 0
        } catch (e: Exception) { false }
    }

    suspend fun setProp(prop: String, value: String) = executeRoot("setprop $prop $value")
    
    suspend fun lockBand(bandName: String) = executeRoot("cmd phone set-carrier-restriction --allowed-bands $bandName")

    @SuppressLint("MissingPermission")
    fun getActiveConnectionName(context: Context): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val capabilities = cm.getNetworkCapabilities(cm.activeNetwork)
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                return "Connected via Wi-Fi"
            }
            val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            sm.activeSubscriptionInfoList?.firstOrNull()?.carrierName?.toString() ?: "Cellular Data"
        } catch (e: Exception) {
            "Unknown Network"
        }
    }

    @SuppressLint("MissingPermission")
    fun scanAvailableBands(context: Context): List<CellBandInfo> {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val simMccMnc = try { tm.simOperator ?: "" } catch (e: Exception) { "" }
        val bandList = mutableListOf<CellBandInfo>()
        val cells = try { tm.allCellInfo } catch (e: Exception) { null } ?: emptyList()

        for (cell in cells) {
            try {
                val isReg = cell.isRegistered
                when (cell) {
                    is CellInfoNr -> {
                        val id = cell.cellIdentity as? CellIdentityNr
                        val dbmRaw = (cell.cellSignalStrength as? CellSignalStrengthNr)?.dbm ?: CellInfo.UNAVAILABLE
                        val dbm = if (dbmRaw == CellInfo.UNAVAILABLE || dbmRaw > 0) "N/A" else dbmRaw.toString()
                        val band = resolve5gBand(id?.nrarfcn ?: 0)
                        val match = simMccMnc.isNotEmpty() && "${id?.mccString}${id?.mncString}" == simMccMnc
                        
                        bandList.add(CellBandInfo("5G-${id?.nrarfcn}", "5G", band, "Ultra/Fast", dbm, calcBars(dbmRaw), if(match) "Your SIM" else "Other Network", isReg, match))
                    }
                    is CellInfoLte -> {
                        val id = cell.cellIdentity as? CellIdentityLte
                        val dbmRaw = (cell.cellSignalStrength as? CellSignalStrengthLte)?.dbm ?: CellInfo.UNAVAILABLE
                        val dbm = if (dbmRaw == CellInfo.UNAVAILABLE || dbmRaw > 0) "N/A" else dbmRaw.toString()
                        val band = resolve4gBand(id?.earfcn ?: 0)
                        val match = simMccMnc.isNotEmpty() && "${id?.mccString}${id?.mncString}" == simMccMnc

                        bandList.add(CellBandInfo("4G-${id?.earfcn}", "4G", band, "Stable/Mid", dbm, calcBars(dbmRaw), if(match) "Your SIM" else "Other Network", isReg, match))
                    }
                }
            } catch (e: Exception) {
                // Prevent a single broken cell tower return from crashing the app
            }
        }
        return bandList.distinctBy { it.bandName }.sortedByDescending { it.isConnected }
    }

    private fun calcBars(dbm: Int): Int = when { dbm >= -85 -> 4; dbm >= -98 -> 3; dbm >= -110 -> 2; dbm >= -120 -> 1; else -> 0 }
    
    private fun resolve5gBand(arfcn: Int): String = when(arfcn) { in 620000..653333 -> "n78"; in 151600..160600 -> "n28"; in 499200..537999 -> "n41"; else -> "NR-$arfcn" }
    
    private fun resolve4gBand(arfcn: Int): String = when(arfcn) { in 38650..39649 -> "Band 40"; in 1200..1949 -> "Band 3"; in 0..599 -> "Band 1"; in 2400..2649 -> "Band 5"; else -> "LTE-$arfcn" }
}
