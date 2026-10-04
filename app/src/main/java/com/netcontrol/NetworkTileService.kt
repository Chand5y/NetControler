package com.netcontrol

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader

class NetworkTileService : TileService() {
    
    // 0 = Auto, 1 = 5G (NR Only), 2 = 4G (LTE Only)
    private var currentState = 0 

    override fun onStartListening() {
        super.onStartListening()
        CoroutineScope(Dispatchers.IO).launch {
            val currentMask = getActiveBitmask()
            currentState = when {
                currentMask.contains("524288") && !currentMask.contains("4096") -> 1 // 5G Only
                currentMask.contains("4096") && !currentMask.contains("524288") -> 2 // 4G Only
                else -> 0 // Auto Mode
            }
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        currentState = (currentState + 1) % 3
        
        val targetMode = when (currentState) {
            1 -> "NR_ONLY"
            2 -> "LTE_ONLY"
            else -> "AUTO"
        }
        
        CoroutineScope(Dispatchers.IO).launch {
            NetworkEngine.applyNetworkMode(targetMode)
            updateTile()
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        when (currentState) {
            1 -> {
                tile.label = "5G Only"
                tile.subtitle = "NR Only"
                tile.state = Tile.STATE_ACTIVE
            }
            2 -> {
                tile.label = "4G Only"
                tile.subtitle = "LTE Only"
                tile.state = Tile.STATE_ACTIVE
            }
            else -> {
                tile.label = "Auto Mode"
                tile.subtitle = "Default"
                tile.state = Tile.STATE_INACTIVE
            }
        }
        tile.updateTile()
    }

    private fun getActiveBitmask(): String {
        return try {
            // First check the default subscription
            var p = Runtime.getRuntime().exec(arrayOf("su", "-c", "cmd phone get-allowed-network-types-for-users"))
            var output = BufferedReader(InputStreamReader(p.inputStream)).readLine()?.trim() ?: ""
            
            // If empty, try polling standard subIds until we hit the active SIM
            var subId = 1
            while (output.isEmpty() && subId <= 10) {
                p = Runtime.getRuntime().exec(arrayOf("su", "-c", "cmd phone get-allowed-network-types-for-users -s $subId"))
                output = BufferedReader(InputStreamReader(p.inputStream)).readLine()?.trim() ?: ""
                subId++
            }
            output
        } catch (e: Exception) { "" }
    }
}
