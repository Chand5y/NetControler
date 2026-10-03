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
            NetworkEngine.togglePreferredNetwork(is5GActive)
        }
        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        tile.state = if (is5GActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (is5GActive) "Forced 5G" else "4G LTE Only"
        tile.updateTile()
    }
}
