package com.netcontrol

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NetworkTileService : TileService() {
    
    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        tile.label = "Band Locker"
        tile.subtitle = "Tap to open"
        tile.state = Tile.STATE_ACTIVE
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        NetworkEngine.logEvent("QUICK_TILE", "Quick Tile clicked. Launching native RadioInfo.")
        
        CoroutineScope(Dispatchers.IO).launch {
            // Closes the notification shade and instantly opens the hidden menu
            NetworkEngine.openNativeBandLocker(applicationContext)
        }
    }
}
