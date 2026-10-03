package com.netcontrol

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NetworkTileService : TileService() {
    private var is5GActive = true

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        is5GActive = !is5GActive
        
        CoroutineScope(Dispatchers.IO).launch {
            val mode = if (is5GActive) "26" else "9"
            NetworkEngine.executeRoot("settings put global preferred_network_mode $mode")
            NetworkEngine.executeRoot("settings put global preferred_network_mode1 $mode")
            NetworkEngine.executeRoot("settings put global preferred_network_mode2 $mode")
            NetworkEngine.executeRoot("cmd phone radio power false")
            Thread.sleep(1500)
            NetworkEngine.executeRoot("cmd phone radio power true")
        }
        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        tile.state = if (is5GActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (is5GActive) "5G Mode" else "4G Mode"
        tile.updateTile()
    }
}
