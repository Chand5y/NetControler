package com.netcontrol

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NetworkTileService : TileService() {
    // 0 = Auto, 1 = 5G (NR Only), 2 = 4G (LTE Only)
    private var currentState = 0 

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        currentState = (currentState + 1) % 3
        
        val targetMode = when (currentState) {
            1 -> "NR_ONLY"
            2 -> "LTE_ONLY"
            else -> "AUTO"
        }
        
        NetworkEngine.logEvent("QUICK_TILE", "Tile Clicked. Routing mode to $targetMode")
        
        CoroutineScope(Dispatchers.IO).launch {
            NetworkEngine.applyNetworkMode(applicationContext, targetMode)
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
}
