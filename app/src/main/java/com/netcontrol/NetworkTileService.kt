package com.netcontrol

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import java.io.DataOutputStream

class NetworkTileService : TileService() {
    
    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        tile.label = "Radio Menu"
        tile.subtitle = "Band Locker"
        tile.state = Tile.STATE_ACTIVE
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        
        // 1. Collapse the notification shade immediately
        val closeIntent = Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
        applicationContext.sendBroadcast(closeIntent)

        // 2. Launch the hidden Native Radio Info menu via Root
        Thread {
            try {
                val process = Runtime.getRuntime().exec("su")
                val os = DataOutputStream(process.outputStream)
                os.writeBytes("am start -n com.android.phone/.settings.RadioInfo\n")
                os.writeBytes("exit\n")
                os.flush()
                process.waitFor()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }
}
