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
            val currentMode = getActiveModeFromDatabase()
            currentState = when (currentMode) {
                "33" -> 1 // 5G Only
                "11" -> 2 // 4G Only
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

    private fun getActiveModeFromDatabase(): String {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "settings get global preferred_network_mode"))
            val reader = BufferedReader(InputStreamReader(p.inputStream))
            reader.readLine()?.trim() ?: "26"
        } catch (e: Exception) { "26" }
    }
}
